package com.pocketai.app.data.model

import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketai.app.core.DeviceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadStatus { IDLE, CONNECTING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val catalogId: String? = null,
    val displayName: String = "",
    val fileName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val message: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.CONNECTING || status == DownloadStatus.RUNNING

    /** Seconds left at the current rate, or null while the rate is still unknown. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && totalBytes > downloadedBytes)
            (totalBytes - downloadedBytes) / bytesPerSecond else null
}

/**
 * Single-slot model downloader with resume, pause and cancel.
 *
 * Partial data lives in a `.part` file next to the target, so a paused or
 * interrupted download picks up exactly where it stopped via a Range request
 * instead of starting the gigabyte over again.
 */
class ModelDownloadManager(
    private val context: Context,
    private val repository: ModelRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    fun start(
        url: String,
        fileName: String,
        displayName: String,
        catalogId: String?,
        expectedBytes: Long = 0L
    ) {
        if (_state.value.isActive) return
        pauseRequested.set(false)
        cancelRequested.set(false)
        _state.value = DownloadState(
            status = DownloadStatus.CONNECTING,
            catalogId = catalogId,
            displayName = displayName,
            fileName = fileName,
            totalBytes = expectedBytes
        )
        startService()
        job = scope.launch { runDownload(url, fileName, displayName, catalogId) }
    }

    fun pause() {
        if (_state.value.isActive) pauseRequested.set(true)
    }

    fun resumeLast(url: String, fileName: String, displayName: String, catalogId: String?) {
        start(url, fileName, displayName, catalogId, _state.value.totalBytes)
    }

    fun cancel() {
        cancelRequested.set(true)
        scope.launch {
            job?.cancelAndJoin()
            partFileFor(_state.value.fileName)?.delete()
            _state.value = _state.value.copy(
                status = DownloadStatus.CANCELLED,
                bytesPerSecond = 0,
                message = "Download cancelled."
            )
            stopService()
        }
    }

    fun clearFinished() {
        val s = _state.value
        if (!s.isActive) _state.value = DownloadState()
    }

    private fun partFileFor(fileName: String): File? =
        if (fileName.isBlank()) null else File(repository.modelsDir, "$fileName.part")

    private suspend fun runDownload(
        url: String,
        fileName: String,
        displayName: String,
        catalogId: String?
    ) {
        val part = File(repository.modelsDir, "$fileName.part")
        try {
            var existing = if (part.exists()) part.length() else 0L

            val requestBuilder = Request.Builder().url(url)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "PocketAI/1.0 (Android)")
            if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

            val response = client.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 416) {           // range past EOF - restart clean
                        part.delete()
                        existing = 0
                    }
                    if (!resp.isSuccessful && resp.code != 416) {
                        fail(
                            when (resp.code) {
                                401, 403 -> "This model requires accepting its licence on the provider's website first."
                                404 -> "The model file was not found at the provider. It may have been moved."
                                else -> "Download failed (HTTP ${resp.code})."
                            }
                        )
                        return
                    }
                }
                val body = resp.body ?: run { fail("Empty response from the server."); return }

                val resumed = resp.code == 206
                if (!resumed) existing = 0
                val contentLength = body.contentLength()
                val total = if (contentLength > 0) contentLength + (if (resumed) existing else 0L)
                else _state.value.totalBytes

                // Re-check storage against the real size before writing anything.
                val free = DeviceCapabilities.read(context).availableStorageBytes
                if (total > 0 && total - existing > free - 128L * 1024 * 1024) {
                    fail(
                        "Not enough free storage. This model needs " +
                            "${ModelRepository.formatBytes(total)} and only " +
                            "${ModelRepository.formatBytes(free)} is available."
                    )
                    return
                }

                _state.value = _state.value.copy(
                    status = DownloadStatus.RUNNING,
                    downloadedBytes = existing,
                    totalBytes = total
                )

                val sink = java.io.RandomAccessFile(part, "rw")
                sink.use { out ->
                    out.seek(existing)
                    val buffer = ByteArray(1 shl 16)
                    var written = existing
                    var lastTick = System.nanoTime()
                    var bytesSinceTick = 0L
                    var speed = 0L

                    body.byteStream().use { input ->
                        while (true) {
                            if (cancelRequested.get()) throw CancelledException()
                            if (pauseRequested.get()) {
                                _state.value = _state.value.copy(
                                    status = DownloadStatus.PAUSED,
                                    bytesPerSecond = 0,
                                    message = "Paused. Tap resume to continue where you left off."
                                )
                                stopService()
                                return
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            written += read
                            bytesSinceTick += read

                            val now = System.nanoTime()
                            val elapsed = now - lastTick
                            if (elapsed > 400_000_000L) {   // update ~2.5x a second
                                val instantaneous = bytesSinceTick * 1_000_000_000L / elapsed
                                speed = if (speed == 0L) instantaneous
                                else (speed * 3 + instantaneous) / 4      // smooth the readout
                                lastTick = now
                                bytesSinceTick = 0
                                _state.value = _state.value.copy(
                                    downloadedBytes = written,
                                    bytesPerSecond = speed,
                                    status = DownloadStatus.RUNNING
                                )
                            }
                        }
                    }
                    _state.value = _state.value.copy(downloadedBytes = written)
                }
            }

            // Validate before promoting the .part file to a real model.
            if (GgufMetadata.read(part) == null) {
                part.delete()
                fail("The downloaded file is not a valid GGUF model and was discarded.")
                return
            }

            val target = repository.uniqueFile(fileName)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            val model = repository.registerDownloaded(target, catalogId)
            _state.value = _state.value.copy(
                status = DownloadStatus.COMPLETED,
                downloadedBytes = model.sizeBytes,
                totalBytes = model.sizeBytes,
                bytesPerSecond = 0,
                message = "Model ready."
            )
        } catch (_: CancelledException) {
            withContext(Dispatchers.IO) { part.delete() }
            _state.value = _state.value.copy(
                status = DownloadStatus.CANCELLED,
                bytesPerSecond = 0,
                message = "Download cancelled."
            )
        } catch (e: IOException) {
            fail("Network problem: ${e.message ?: "connection lost"}. Partial progress was kept - resume to continue.")
        } catch (e: Throwable) {
            fail(e.message ?: "Download failed.")
        } finally {
            stopService()
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            status = DownloadStatus.FAILED,
            bytesPerSecond = 0,
            message = message
        )
    }

    private fun startService() {
        runCatching {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopService() {
        runCatching { context.stopService(Intent(context, ModelDownloadService::class.java)) }
    }

    private class CancelledException : RuntimeException()
}
