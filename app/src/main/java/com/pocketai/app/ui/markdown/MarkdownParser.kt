package com.pocketai.app.ui.markdown

/**
 * A small, allocation-light Markdown block parser.
 *
 * It is written for streaming: an unterminated code fence or a half-written
 * table renders as far as it goes instead of collapsing the layout while the
 * model is still typing.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val CHECK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s*(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val DIVIDER = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)+\\|?\\s*$")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>(16)
        var i = 0

        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                paragraph.setLength(0)
            }
        }

        while (i < lines.size) {
            val line = lines[i]

            // ---- fenced code -------------------------------------------------
            val fence = FENCE.find(line)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2].takeIf { it.isNotBlank() }
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val candidate = lines[i]
                    if (candidate.trimStart().startsWith(marker.take(3)) &&
                        candidate.trim().all { it == marker[0] }
                    ) {
                        closed = true
                        i++
                        break
                    }
                    code.appendLine(candidate)
                    i++
                }
                blocks.add(
                    MdBlock.Code(
                        language = language,
                        code = code.toString().trimEnd('\n'),
                        complete = closed
                    )
                )
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            if (DIVIDER.matches(line)) {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            val headingMatch = HEADING.find(line)
            if (headingMatch != null) {
                flushParagraph()
                blocks.add(
                    MdBlock.Heading(
                        level = headingMatch.groupValues[1].length,
                        text = headingMatch.groupValues[2].trim()
                    )
                )
                i++
                continue
            }

            // ---- table -------------------------------------------------------
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1])) {
                flushParagraph()
                val header = splitRow(line)
                val aligns = parseAligns(lines[i + 1], header.size)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    val cells = splitRow(lines[i])
                    // Pad or trim so every row matches the header width.
                    rows.add(
                        when {
                            cells.size < header.size -> cells + List(header.size - cells.size) { "" }
                            cells.size > header.size -> cells.take(header.size)
                            else -> cells
                        }
                    )
                    i++
                }
                blocks.add(MdBlock.Table(header, rows, aligns))
                continue
            }

            // ---- blockquote ---------------------------------------------------
            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote.appendLine(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
                continue
            }

            // ---- checklist ----------------------------------------------------
            if (CHECK.matches(line)) {
                flushParagraph()
                val items = ArrayList<MdCheckItem>()
                while (i < lines.size) {
                    val m = CHECK.find(lines[i]) ?: break
                    items.add(
                        MdCheckItem(
                            text = m.groupValues[3].trim(),
                            checked = !m.groupValues[2].equals(" ", ignoreCase = false),
                            indent = m.groupValues[1].length / 2
                        )
                    )
                    i++
                }
                blocks.add(MdBlock.Checklist(items))
                continue
            }

            // ---- bullets -------------------------------------------------------
            if (BULLET.matches(line)) {
                flushParagraph()
                val items = ArrayList<MdListItem>()
                while (i < lines.size) {
                    if (CHECK.matches(lines[i])) break
                    val m = BULLET.find(lines[i]) ?: break
                    items.add(MdListItem(m.groupValues[2].trim(), m.groupValues[1].length / 2))
                    i++
                }
                if (items.isEmpty()) { i++; continue }
                blocks.add(MdBlock.Bullets(items))
                continue
            }

            // ---- numbered list --------------------------------------------------
            if (NUMBERED.matches(line)) {
                flushParagraph()
                val items = ArrayList<MdListItem>()
                var start = 1
                var first = true
                while (i < lines.size) {
                    val m = NUMBERED.find(lines[i]) ?: break
                    if (first) {
                        start = m.groupValues[2].toIntOrNull() ?: 1
                        first = false
                    }
                    items.add(MdListItem(m.groupValues[3].trim(), m.groupValues[1].length / 2))
                    i++
                }
                blocks.add(MdBlock.Numbered(start, items))
                continue
            }

            // ---- plain paragraph -------------------------------------------------
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trim())
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun splitRow(line: String): List<String> {
        var trimmed = line.trim()
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.dropLast(1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in trimmed) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '|' -> { cells.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun parseAligns(separator: String, columns: Int): List<MdAlign> {
        val parts = splitRow(separator)
        return (0 until columns).map { index ->
            val spec = parts.getOrNull(index)?.trim().orEmpty()
            when {
                spec.startsWith(":") && spec.endsWith(":") -> MdAlign.CENTER
                spec.endsWith(":") -> MdAlign.RIGHT
                else -> MdAlign.LEFT
            }
        }
    }
}
