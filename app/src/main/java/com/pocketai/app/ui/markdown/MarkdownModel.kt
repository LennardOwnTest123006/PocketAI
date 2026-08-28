package com.pocketai.app.ui.markdown

enum class MdAlign { LEFT, CENTER, RIGHT }

data class MdListItem(val text: String, val indent: Int)

data class MdCheckItem(val text: String, val checked: Boolean, val indent: Int)

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<MdListItem>) : MdBlock
    data class Numbered(val start: Int, val items: List<MdListItem>) : MdBlock
    data class Checklist(val items: List<MdCheckItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String?, val code: String, val complete: Boolean) : MdBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val aligns: List<MdAlign>
    ) : MdBlock {
        /** Markdown source, used by the table's copy and share actions. */
        fun toMarkdown(): String = buildString {
            append("| ").append(header.joinToString(" | ")).appendLine(" |")
            append("|")
            aligns.forEachIndexed { i, a ->
                append(
                    when (a) {
                        MdAlign.LEFT -> " --- "
                        MdAlign.CENTER -> " :-: "
                        MdAlign.RIGHT -> " ---: "
                    }
                )
                append("|")
                if (i == aligns.lastIndex) appendLine()
            }
            rows.forEach { row ->
                append("| ").append(row.joinToString(" | ")).appendLine(" |")
            }
        }

        fun toPlainText(): String = buildString {
            appendLine(header.joinToString("\t"))
            rows.forEach { appendLine(it.joinToString("\t")) }
        }
    }

    data object Divider : MdBlock
}
