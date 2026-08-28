package com.pocketai.app.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class ExtractedDocument(
    val name: String,
    val text: String,
    val sizeBytes: Long,
    val truncated: Boolean
) {
    val approxTokens: Int get() = text.length / 4
}

sealed interface ExtractionResult {
    data class Success(val document: ExtractedDocument) : ExtractionResult
    data class Failed(val message: String) : ExtractionResult
}

/**
 * Pulls readable text out of files the user attaches.
 *
 * Everything happens on the device - a document is never uploaded anywhere,
 * and the extracted text only ever reaches the local model.
 */
class DocumentExtractor(private val context: Context) {

    suspend fun extract(uri: Uri, charLimit: Int = 60_000): ExtractionResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "document"
            var size = -1L
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            }

            val extension = name.substringAfterLast('.', "").lowercase()
            val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()

            if (!isSupported(extension, mime)) {
                return@withContext ExtractionResult.Failed(
                    "PocketAI can read text-based documents ($SUPPORTED_LABEL). " +
                        "\"$name\" is not one of them."
                )
            }
            if (size > MAX_BYTES) {
                return@withContext ExtractionResult.Failed(
                    "That document is too large to process on-device (limit ${MAX_BYTES / (1024 * 1024)} MB)."
                )
            }

            val bytes = try {
                resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext ExtractionResult.Failed("The file could not be opened.")
            } catch (t: Throwable) {
                return@withContext ExtractionResult.Failed(t.message ?: "The file could not be read.")
            }

            if (looksBinary(bytes)) {
                return@withContext ExtractionResult.Failed(
                    "\"$name\" appears to be a binary file, so there is no text to extract."
                )
            }

            val decoded = decode(bytes)
            val extracted = when {
                extension == "html" || extension == "htm" || mime.contains("html") ->
                    Jsoup.parse(decoded).let { doc ->
                        doc.select("script, style, noscript").remove()
                        doc.body()?.wholeText() ?: doc.text()
                    }
                else -> decoded
            }
            val text = extracted.replace(NBSP, ' ').trim()

            if (text.isBlank()) {
                return@withContext ExtractionResult.Failed("No readable text was found in \"$name\".")
            }

            val truncated = text.length > charLimit
            ExtractionResult.Success(
                ExtractedDocument(
                    name = name,
                    text = if (truncated) text.take(charLimit) else text,
                    sizeBytes = if (size >= 0) size else bytes.size.toLong(),
                    truncated = truncated
                )
            )
        }

    private fun decode(bytes: ByteArray): String {
        // Honour a UTF-8/UTF-16 BOM when present, otherwise assume UTF-8 and
        // fall back to Latin-1 so legacy files still produce readable text.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if (utf8.contains(REPLACEMENT_CHAR)) String(bytes, Charset.forName("ISO-8859-1")) else utf8
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        if (sample.isEmpty()) return false
        val controlChars = sample.count { b ->
            val v = b.toInt() and 0xFF
            v < 0x09 || (v in 0x0E..0x1F && v != 0x1B)
        }
        return controlChars * 100 / sample.size > 5
    }

    private fun isSupported(extension: String, mime: String): Boolean =
        extension in SUPPORTED_EXTENSIONS ||
            mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml", "application/x-yaml")

    companion object {
        private const val MAX_BYTES = 12L * 1024 * 1024
        private const val NBSP = '\u00A0'
        private const val REPLACEMENT_CHAR = '\uFFFD'

        val SUPPORTED_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml",
            "html", "htm", "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp",
            "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "sql", "toml", "ini",
            "cfg", "conf", "properties", "gradle", "srt", "vtt", "tex", "rst"
        )

        const val SUPPORTED_LABEL = "TXT, Markdown, JSON, CSV, HTML, XML, YAML, source code"
    }
}
