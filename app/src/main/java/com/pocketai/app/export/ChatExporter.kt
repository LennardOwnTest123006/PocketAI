package com.pocketai.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.pocketai.app.data.repo.ChatMessage
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.repo.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val id: String, val label: String, val extension: String, val mime: String) {
    TXT("txt", "Plain text", "txt", "text/plain"),
    MARKDOWN("md", "Markdown", "md", "text/markdown"),
    JSON("json", "JSON", "json", "application/json"),
    PDF("pdf", "PDF", "pdf", "application/pdf")
}

@Serializable
data class ExportedMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
    val modelName: String? = null,
    val usedWebSearch: Boolean = false
)

@Serializable
data class ExportedConversation(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val messages: List<ExportedMessage> = emptyList()
)

@Serializable
data class ExportBundle(
    val app: String = "PocketAI",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val conversations: List<ExportedConversation>
)

/**
 * Turns conversations into shareable files.
 *
 * Files are written to the app's cache and handed out through a FileProvider,
 * so nothing is written to shared storage and no extra permission is needed.
 */
class ChatExporter(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    suspend fun export(
        conversation: Conversation,
        messages: List<ChatMessage>,
        format: ExportFormat
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = conversation.title
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(' ', '-')
            .ifBlank { "chat" }
            .take(40)
        val file = File(dir, "PocketAI-$safeTitle-${fileStampFormat.format(Date())}.${format.extension}")

        when (format) {
            ExportFormat.TXT -> file.writeText(asPlainText(conversation, messages))
            ExportFormat.MARKDOWN -> file.writeText(asMarkdown(conversation, messages))
            ExportFormat.JSON -> file.writeText(
                json.encodeToString(
                    ExportBundle(
                        exportedAt = System.currentTimeMillis(),
                        conversations = listOf(toExported(conversation, messages))
                    )
                )
            )
            ExportFormat.PDF -> writePdf(file, conversation, messages)
        }
        file
    }

    suspend fun exportAll(
        conversations: List<Pair<Conversation, List<ChatMessage>>>
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "PocketAI-all-chats-${fileStampFormat.format(Date())}.json")
        file.writeText(
            json.encodeToString(
                ExportBundle(
                    exportedAt = System.currentTimeMillis(),
                    conversations = conversations.map { (c, m) -> toExported(c, m) }
                )
            )
        )
        file
    }

    fun parseImport(text: String): Result<List<ExportedConversation>> = runCatching {
        val bundle = json.decodeFromString<ExportBundle>(text)
        require(bundle.conversations.isNotEmpty()) { "The file contains no conversations." }
        bundle.conversations
    }

    private fun toExported(conversation: Conversation, messages: List<ChatMessage>) =
        ExportedConversation(
            title = conversation.title,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            pinned = conversation.pinned,
            favorite = conversation.favorite,
            messages = messages.filter { it.role != ChatRole.SYSTEM }.map {
                ExportedMessage(
                    role = it.role.wire,
                    content = it.content,
                    thinking = it.thinking,
                    createdAt = it.createdAt,
                    modelName = it.modelName,
                    usedWebSearch = it.usedWebSearch
                )
            }
        )

    fun asPlainText(conversation: Conversation, messages: List<ChatMessage>): String = buildString {
        appendLine(conversation.title)
        appendLine("Exported from PocketAI on ${timestampFormat.format(Date())}")
        appendLine("=".repeat(48))
        appendLine()
        messages.filter { it.role != ChatRole.SYSTEM }.forEach { message ->
            val who = if (message.role == ChatRole.USER) "You" else "PocketAI"
            appendLine("$who - ${timestampFormat.format(Date(message.createdAt))}")
            appendLine(message.content.trim())
            if (message.sources.isNotEmpty()) {
                appendLine()
                appendLine("Sources:")
                message.sources.forEachIndexed { i, s -> appendLine("  [${i + 1}] ${s.title} - ${s.url}") }
            }
            appendLine()
            appendLine("-".repeat(48))
            appendLine()
        }
    }

    fun asMarkdown(conversation: Conversation, messages: List<ChatMessage>): String = buildString {
        appendLine("# ${conversation.title}")
        appendLine()
        appendLine("*Exported from PocketAI on ${timestampFormat.format(Date())}*")
        appendLine()
        messages.filter { it.role != ChatRole.SYSTEM }.forEach { message ->
            val who = if (message.role == ChatRole.USER) "You" else "PocketAI"
            appendLine("## $who")
            appendLine()
            if (message.thinking != null) {
                appendLine("<details><summary>Thinking</summary>")
                appendLine()
                appendLine(message.thinking.trim())
                appendLine()
                appendLine("</details>")
                appendLine()
            }
            appendLine(message.content.trim())
            appendLine()
            if (message.sources.isNotEmpty()) {
                appendLine("**Sources**")
                appendLine()
                message.sources.forEachIndexed { i, s -> appendLine("${i + 1}. [${s.title}](${s.url})") }
                appendLine()
            }
        }
    }

    /**
     * Renders the transcript to A4 pages with real pagination.
     * Long paragraphs are wrapped to the page width rather than clipped.
     */
    private fun writePdf(file: File, conversation: Conversation, messages: List<ChatMessage>) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44f
        val maxWidth = pageWidth - margin * 2

        val bodyPaint = Paint().apply {
            isAntiAlias = true
            textSize = 11f
            color = 0xFF1A1A1A.toInt()
        }
        val authorPaint = Paint(bodyPaint).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = 0xFF3B2FD9.toInt()
        }
        val titlePaint = Paint(bodyPaint).apply {
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = 0xFF12103A.toInt()
        }
        val metaPaint = Paint(bodyPaint).apply {
            textSize = 9f
            color = 0xFF6B6B7B.toInt()
        }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin
        }

        fun drawLines(text: String, paint: Paint, extraLeading: Float = 0f) {
            val lineHeight = paint.textSize * 1.42f + extraLeading
            text.split("\n").forEach { rawLine ->
                if (rawLine.isBlank()) {
                    y += lineHeight * 0.55f
                    if (y > pageHeight - margin) newPage()
                    return@forEach
                }
                var remaining = rawLine
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, maxWidth, null)
                    var take = count
                    if (take < remaining.length) {
                        // Prefer breaking on whitespace so words stay intact.
                        val lastSpace = remaining.lastIndexOf(' ', take - 1)
                        if (lastSpace > take / 2) take = lastSpace + 1
                    }
                    val chunk = remaining.substring(0, take)
                    if (y + lineHeight > pageHeight - margin) newPage()
                    canvas.drawText(chunk.trimEnd(), margin, y + paint.textSize, paint)
                    y += lineHeight
                    remaining = remaining.substring(take)
                }
            }
        }

        drawLines(conversation.title, titlePaint)
        y += 4f
        drawLines("Exported from PocketAI on ${timestampFormat.format(Date())}", metaPaint)
        y += 12f

        messages.filter { it.role != ChatRole.SYSTEM }.forEach { message ->
            val who = if (message.role == ChatRole.USER) "You" else "PocketAI"
            drawLines("$who  -  ${timestampFormat.format(Date(message.createdAt))}", authorPaint)
            y += 2f
            drawLines(message.content.trim(), bodyPaint)
            if (message.sources.isNotEmpty()) {
                y += 4f
                drawLines(
                    "Sources: " + message.sources.mapIndexed { i, s -> "[${i + 1}] ${s.url}" }
                        .joinToString("  "),
                    metaPaint
                )
            }
            y += 14f
        }

        document.finishPage(page)
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }

    /** Hands the file to the Android share sheet. */
    fun shareIntent(file: File, mime: String, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareText(text: String, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
}
