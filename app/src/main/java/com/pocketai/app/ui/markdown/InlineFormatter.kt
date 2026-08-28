package com.pocketai.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

data class InlineStyle(
    val codeColor: Color,
    val codeBackground: Color,
    val linkColor: Color,
    val codeSize: TextUnit
)

/**
 * Converts inline Markdown into an [AnnotatedString].
 *
 * Supports bold, italic, bold+italic, strikethrough, inline code, explicit
 * underline via `<u>`, Markdown links and bare URLs. Anything that does not
 * form a complete construct is left as literal text, which matters while a
 * response is still streaming in.
 */
object InlineFormatter {

    private val AUTOLINK_PREFIXES = listOf("https://", "http://", "www.")

    fun format(text: String, style: InlineStyle): AnnotatedString = buildAnnotatedString {
        render(this, text, style)
    }

    private fun render(builder: AnnotatedString.Builder, text: String, style: InlineStyle) {
        var i = 0
        val n = text.length
        val literal = StringBuilder()

        fun flush() {
            if (literal.isNotEmpty()) {
                builder.append(literal.toString())
                literal.setLength(0)
            }
        }

        while (i < n) {
            val c = text[i]

            // ---- inline code ------------------------------------------------
            if (c == '`') {
                var run = 0
                while (i + run < n && text[i + run] == '`') run++
                val fence = text.substring(i, i + run)
                val close = text.indexOf(fence, i + run)
                if (close >= 0) {
                    flush()
                    builder.withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = style.codeColor,
                            background = style.codeBackground,
                            fontSize = style.codeSize
                        )
                    ) { append(text.substring(i + run, close)) }
                    i = close + run
                    continue
                }
                literal.append(c); i++; continue
            }

            // ---- explicit underline ------------------------------------------
            if (c == '<' && text.startsWith("<u>", i, ignoreCase = true)) {
                val close = text.indexOf("</u>", i + 3, ignoreCase = true)
                if (close >= 0) {
                    flush()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        render(builder, text.substring(i + 3, close), style)
                    }
                    i = close + 4
                    continue
                }
            }

            // ---- strikethrough -------------------------------------------------
            if (c == '~' && i + 1 < n && text[i + 1] == '~') {
                val close = text.indexOf("~~", i + 2)
                if (close >= 0) {
                    flush()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        render(builder, text.substring(i + 2, close), style)
                    }
                    i = close + 2
                    continue
                }
            }

            // ---- bold / italic --------------------------------------------------
            if (c == '*' || c == '_') {
                // Underscores inside a word (snake_case, file_name) are not emphasis.
                val precededByWord = i > 0 && text[i - 1].isLetterOrDigit()
                if (!(c == '_' && precededByWord)) {
                    val doubled = i + 1 < n && text[i + 1] == c
                    val marker = if (doubled) "$c$c" else c.toString()
                    val searchFrom = i + marker.length
                    val close = findClosing(text, marker, searchFrom)
                    if (close >= 0 && close > searchFrom) {
                        flush()
                        val inner = text.substring(searchFrom, close)
                        val span = if (doubled) SpanStyle(fontWeight = FontWeight.Bold)
                        else SpanStyle(fontStyle = FontStyle.Italic)
                        builder.withStyle(span) { render(builder, inner, style) }
                        i = close + marker.length
                        continue
                    }
                }
                literal.append(c); i++; continue
            }

            // ---- markdown link ---------------------------------------------------
            if (c == '[') {
                val labelEnd = matchBracket(text, i, '[', ']')
                if (labelEnd > 0 && labelEnd + 1 < n && text[labelEnd + 1] == '(') {
                    val urlEnd = matchBracket(text, labelEnd + 1, '(', ')')
                    if (urlEnd > 0) {
                        val label = text.substring(i + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ')
                        if (url.isNotEmpty()) {
                            flush()
                            appendLink(builder, label.ifBlank { url }, url, style)
                            i = urlEnd + 1
                            continue
                        }
                    }
                }
                literal.append(c); i++; continue
            }

            // ---- bare URL ----------------------------------------------------------
            val prefix = AUTOLINK_PREFIXES.firstOrNull { text.startsWith(it, i, ignoreCase = true) }
            if (prefix != null && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                var end = i
                while (end < n && !text[end].isWhitespace()) end++
                // Do not swallow trailing sentence punctuation.
                while (end > i && text[end - 1] in ".,;:!?)]}") end--
                val raw = text.substring(i, end)
                if (raw.length > prefix.length) {
                    flush()
                    val href = if (raw.startsWith("www.", true)) "https://$raw" else raw
                    appendLink(builder, raw, href, style)
                    i = end
                    continue
                }
            }

            literal.append(c)
            i++
        }
        flush()
    }

    private fun appendLink(
        builder: AnnotatedString.Builder,
        label: String,
        url: String,
        style: InlineStyle
    ) {
        builder.withLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = style.linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
            )
        ) { append(label) }
    }

    /** Finds the closing emphasis marker, skipping a marker that opens a new run. */
    private fun findClosing(text: String, marker: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val found = text.indexOf(marker, index)
            if (found < 0) return -1
            // A marker directly after whitespace is more likely an opener than a closer.
            if (found > 0 && !text[found - 1].isWhitespace()) return found
            index = found + marker.length
        }
        return -1
    }

    private fun matchBracket(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
