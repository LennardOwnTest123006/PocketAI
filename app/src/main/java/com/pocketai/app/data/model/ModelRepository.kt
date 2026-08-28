package com.pocketai.app.data.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketai.app.core.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Owns the on-disk model directory and the index that describes it.
 *
 * The index is rebuilt from the directory on every load, so deleting a file by
 * hand (or a failed download) can never leave a phantom entry behind.
 */
class ModelRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    private val indexFile: File get() = File(modelsDir, "index.json")

    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    suspend fun refresh(): List<InstalledModel> = withContext(Dispatchers.IO) {
        val stored = readIndex().associateBy { it.fileName }
        val onDisk = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            ?: emptyArray()

        val result = onDisk.map { file ->
            val existing = stored[file.name]
            if (existing != null && existing.sizeBytes == file.length()) {
                existing.copy(absolutePath = file.absolutePath)
            } else {
                describe(file, existing?.catalogId, existing?.source ?: ModelSource.IMPORT)
            }
        }.sortedBy { it.displayName.lowercase() }

        writeIndex(result)
        _models.value = result
        result
    }

    /** Reads the GGUF header so imported files get real metadata, not placeholders. */
    private fun describe(file: File, catalogId: String?, source: ModelSource): InstalledModel {
        val meta = GgufMetadata.read(file)
        val catalog = catalogId?.let { ModelCatalog.byId(it) }
        val pretty = catalog?.displayName
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension.replace('-', ' ').replace('_', ' ')
        return InstalledModel(
            id = catalogId ?: file.nameWithoutExtension,
            displayName = pretty,
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            catalogId = catalogId,
            architecture = meta?.architecture,
            quantization = meta?.quantization ?: catalog?.quantization,
            parameterCount = meta?.parameterCount ?: 0L,
            trainedContextLength = meta?.contextLength ?: catalog?.contextLength ?: 0,
            supportsThinking = catalog?.supportsThinking
                ?: (meta?.architecture?.contains("qwen3", true) == true),
            importedAtMillis = file.lastModified(),
            source = source
        )
    }

    /** Called once a download finishes so the entry keeps its catalogue identity. */
    suspend fun registerDownloaded(file: File, catalogId: String?): InstalledModel =
        withContext(Dispatchers.IO) {
            val model = describe(file, catalogId, ModelSource.DOWNLOAD)
            val updated = (readIndex().filterNot { it.fileName == file.name } + model)
                .sortedBy { it.displayName.lowercase() }
            writeIndex(updated)
            _models.value = updated
            model
        }

    /**
     * Copies a user-picked GGUF into app storage.
     *
     * Validation is deliberately strict: anything that is not a real GGUF
     * container is rejected before it can reach the inference engine.
     */
    suspend fun importFromUri(uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }): ImportResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "imported-${UUID.randomUUID().toString().take(8)}.gguf"
            var declaredSize = -1L
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
                    }
                }
            }
            if (!name.endsWith(".gguf", ignoreCase = true)) {
                return@withContext ImportResult.Failed("Only .gguf model files can be imported.")
            }
            val free = DeviceCapabilities.read(context).availableStorageBytes
            if (declaredSize > 0 && declaredSize > free - 128L * 1024 * 1024) {
                return@withContext ImportResult.Failed(
                    "Not enough free storage for this model (needs ${formatBytes(declaredSize)}, " +
                        "${formatBytes(free)} available)."
                )
            }

            val target = uniqueFile(name)
            try {
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, declaredSize)
                        }
                    }
                } ?: return@withContext ImportResult.Failed("The selected file could not be opened.")
            } catch (t: Throwable) {
                target.delete()
                return@withContext ImportResult.Failed(t.message ?: "Import failed.")
            }

            if (GgufMetadata.read(target) == null) {
                target.delete()
                return@withContext ImportResult.Failed(
                    "That file is not a valid GGUF model. PocketAI only loads GGUF files."
                )
            }
            val model = describe(target, null, ModelSource.IMPORT)
            val updated = (readIndex().filterNot { it.fileName == target.name } + model)
                .sortedBy { it.displayName.lowercase() }
            writeIndex(updated)
            _models.value = updated
            ImportResult.Success(model)
        }

    suspend fun delete(model: InstalledModel): Boolean = withContext(Dispatchers.IO) {
        val file = File(model.absolutePath)
        val removed = !file.exists() || file.delete()
        if (removed) {
            val updated = readIndex().filterNot { it.fileName == model.fileName }
            writeIndex(updated)
            _models.value = updated
        }
        removed
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        var count = 0
        modelsDir.listFiles()?.forEach { if (it.isFile && it.delete()) count++ }
        writeIndex(emptyList())
        _models.value = emptyList()
        count
    }

    fun totalBytesUsed(): Long =
        modelsDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun uniqueFile(fileName: String): File {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var candidate = File(modelsDir, safe)
        var i = 1
        while (candidate.exists()) {
            val base = safe.removeSuffix(".gguf")
            candidate = File(modelsDir, "$base($i).gguf")
            i++
        }
        return candidate
    }

    private fun readIndex(): List<InstalledModel> = try {
        if (!indexFile.exists()) emptyList()
        else json.decodeFromString<List<InstalledModel>>(indexFile.readText())
    } catch (_: Throwable) {
        emptyList()
    }

    private fun writeIndex(models: List<InstalledModel>) {
        runCatching { indexFile.writeText(json.encodeToString(models)) }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            return if (unit <= 1) "${value.toInt()} ${units[unit]}"
            else String.format("%.2f %s", value, units[unit])
        }
    }
}

sealed interface ImportResult {
    data class Success(val model: InstalledModel) : ImportResult
    data class Failed(val message: String) : ImportResult
}
