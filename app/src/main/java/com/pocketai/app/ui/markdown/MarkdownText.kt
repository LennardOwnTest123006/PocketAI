package com.pocketai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.data.repo.TableStyle
import com.pocketai.app.ui.theme.ChatStyle
import com.pocketai.app.ui.theme.LocalChatStyle

/**
 * Renders Markdown produced by the model.
 *
 * Parsing is memoised on the source string, so a streaming message only
 * re-parses when new text actually arrives, and each block is an independent
 * composable so a growing answer does not invalidate the whole message.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    baseSize: TextUnit? = null,
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {}
) {
    val style = LocalChatStyle.current
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val bodyColor = textColor ?: style.aiText
    val bodySize = baseSize ?: style.bodySize

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockSpacing(block)))
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, style, bodySize)
                is MdBlock.Paragraph -> ParagraphBlock(block.text, style, bodyColor, bodySize)
                is MdBlock.Bullets -> BulletsBlock(block, style, bodyColor, bodySize)
                is MdBlock.Numbered -> NumberedBlock(block, style, bodyColor, bodySize)
                is MdBlock.Checklist -> ChecklistBlock(block, style, bodyColor, bodySize)
                is MdBlock.Quote -> QuoteBlock(block.text, style, bodySize)
                is MdBlock.Code -> CodeBlockView(block, style, onCopy)
                is MdBlock.Table -> TableView(block, style, onCopy, onShare)
                MdBlock.Divider -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

private fun blockSpacing(block: MdBlock) = when (block) {
    is MdBlock.Heading -> 12.dp
    is MdBlock.Code, is MdBlock.Table -> 10.dp
    else -> 7.dp
}

@Composable
private fun inlineStyle(style: ChatStyle) = remember(style) {
    InlineStyle(
        codeColor = style.codeText,
        codeBackground = style.codeBackground.copy(alpha = 0.55f),
        linkColor = style.link,
        codeSize = style.codeSize
    )
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, style: ChatStyle, bodySize: TextUnit) {
    val inline = inlineStyle(style)
    val annotated = remember(block.text, inline) { InlineFormatter.format(block.text, inline) }
    val size: TextUnit
    val color: Color
    when (block.level) {
        1 -> { size = style.headingSize; color = style.heading }
        2 -> { size = (style.headingSize.value * 0.9f).sp; color = style.heading }
        3 -> { size = style.subheadingSize; color = style.subheading }
        else -> { size = (bodySize.value * 1.05f).sp; color = style.subheading }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = size,
        lineHeight = size * 1.3f,
        fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold
    )
}

@Composable
private fun ParagraphBlock(text: String, style: ChatStyle, color: Color, size: TextUnit) {
    val inline = inlineStyle(style)
    val annotated = remember(text, inline) { InlineFormatter.format(text, inline) }
    Text(text = annotated, color = color, fontSize = size, lineHeight = size * 1.42f)
}

@Composable
private fun BulletsBlock(block: MdBlock.Bullets, style: ChatStyle, color: Color, size: TextUnit) {
    val inline = inlineStyle(style)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            val annotated = remember(item.text, inline) { InlineFormatter.format(item.text, inline) }
            Row(modifier = Modifier.padding(start = (item.indent * 14).dp)) {
                Text(
                    text = if (item.indent == 0) "•" else "◦",
                    color = style.heading,
                    fontSize = size,
                    lineHeight = size * 1.42f,
                    modifier = Modifier.width(18.dp)
                )
                Text(text = annotated, color = color, fontSize = size, lineHeight = size * 1.42f)
            }
        }
    }
}

@Composable
private fun NumberedBlock(block: MdBlock.Numbered, style: ChatStyle, color: Color, size: TextUnit) {
    val inline = inlineStyle(style)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            val annotated = remember(item.text, inline) { InlineFormatter.format(item.text, inline) }
            Row(modifier = Modifier.padding(start = (item.indent * 14).dp)) {
                Text(
                    text = "${block.start + index}.",
                    color = style.heading,
                    fontSize = size,
                    lineHeight = size * 1.42f,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(26.dp)
                )
                Text(text = annotated, color = color, fontSize = size, lineHeight = size * 1.42f)
            }
        }
    }
}

@Composable
private fun ChecklistBlock(block: MdBlock.Checklist, style: ChatStyle, color: Color, size: TextUnit) {
    val inline = inlineStyle(style)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            val annotated = remember(item.text, inline) { InlineFormatter.format(item.text, inline) }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(start = (item.indent * 14).dp)
            ) {
                Icon(
                    imageVector = if (item.checked) Icons.Outlined.CheckBox
                    else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (item.checked) "Checked" else "Not checked",
                    tint = if (item.checked) style.heading else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .padding(end = 8.dp, top = 2.dp)
                        .size(size.value.dp)
                )
                Text(text = annotated, color = color, fontSize = size, lineHeight = size * 1.42f)
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String, style: ChatStyle, size: TextUnit) {
    val inline = inlineStyle(style)
    val annotated = remember(text, inline) { InlineFormatter.format(text, inline) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(style.heading)
        )
        Text(
            text = annotated,
            color = style.thinkingText,
            fontSize = size,
            lineHeight = size * 1.42f,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 10.dp)
        )
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code, style: ChatStyle, onCopy: (String) -> Unit) {
    val scroll = rememberScrollState()
    val highlighted = remember(block.code, block.language, style.codeTheme) {
        SyntaxHighlighter.highlight(
            code = block.code,
            language = block.language,
            base = style.codeText,
            keyword = style.codeKeyword,
            string = style.codeString,
            comment = style.codeComment,
            number = style.codeNumber
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(style.codeBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language?.lowercase() ?: "code",
                color = style.codeComment,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Outlined.ContentCopy, "Copy code", style.codeComment) { onCopy(block.code) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
        ) {
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = style.codeSize,
                lineHeight = style.codeSize * 1.45f,
                color = style.codeText,
                softWrap = false,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Tables scroll horizontally so a wide comparison stays readable on a phone
 * without ever stretching the chat layout.
 *
 * Column widths are measured from the actual text once and then fixed, which
 * keeps every row aligned even when a cell wraps onto a second line.
 */
@Composable
private fun TableView(
    block: MdBlock.Table,
    style: ChatStyle,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit
) {
    val scroll = rememberScrollState()
    val inline = inlineStyle(style)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val stripe = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    val showBorders = style.tableStyle != TableStyle.MINIMAL

    val columnWidths: List<Dp> = remember(block, style.tableSize, density) {
        val measureStyle = TextStyle(fontSize = style.tableSize)
        block.header.indices.map { column ->
            val texts = buildList {
                add(block.header.getOrElse(column) { "" })
                block.rows.forEach { add(it.getOrElse(column) { "" }) }
            }
            val widestPx = texts.maxOf { cell ->
                if (cell.isEmpty()) 0
                else measurer.measure(AnnotatedString(cell), measureStyle).size.width
            }
            val widestDp = with(density) { widestPx.toDp() }
            // Clamp so one verbose cell cannot push the table absurdly wide.
            (widestDp + CELL_HORIZONTAL_PADDING * 2).coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${block.rows.size} " + if (block.rows.size == 1) "row" else "rows",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Outlined.ContentCopy, "Copy table") { onCopy(block.toMarkdown()) }
            IconAction(Icons.Outlined.Share, "Share table") { onShare(block.toMarkdown()) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (showBorders) Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    else Modifier
                )
                .horizontalScroll(scroll)
        ) {
            Column {
                TableRow(
                    cells = block.header,
                    widths = columnWidths,
                    aligns = block.aligns,
                    style = style,
                    inline = inline,
                    bold = true,
                    background = headerBackground,
                    borderColor = if (showBorders) borderColor else Color.Transparent
                )
                block.rows.forEachIndexed { index, row ->
                    if (showBorders) HorizontalDivider(color = borderColor.copy(alpha = 0.6f))
                    TableRow(
                        cells = row,
                        widths = columnWidths,
                        aligns = block.aligns,
                        style = style,
                        inline = inline,
                        bold = false,
                        background = if (style.tableStyle == TableStyle.STRIPED && index % 2 == 1)
                            stripe else Color.Transparent,
                        borderColor = if (showBorders) borderColor else Color.Transparent
                    )
                }
            }
        }
    }
}

private val MIN_COLUMN_WIDTH = 56.dp
private val MAX_COLUMN_WIDTH = 260.dp
private val CELL_HORIZONTAL_PADDING = 12.dp

@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    aligns: List<MdAlign>,
    style: ChatStyle,
    inline: InlineStyle,
    bold: Boolean,
    background: Color,
    borderColor: Color
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        widths.forEachIndexed { index, width ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(borderColor.copy(alpha = 0.6f))
                )
            }
            val cell = cells.getOrElse(index) { "" }
            val annotated = remember(cell, inline) { InlineFormatter.format(cell, inline) }
            Text(
                text = annotated,
                color = style.tableText,
                fontSize = style.tableSize,
                lineHeight = style.tableSize * 1.35f,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = when (aligns.getOrElse(index) { MdAlign.LEFT }) {
                    MdAlign.LEFT -> TextAlign.Start
                    MdAlign.CENTER -> TextAlign.Center
                    MdAlign.RIGHT -> TextAlign.End
                },
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .background(background)
                    .padding(horizontal = CELL_HORIZONTAL_PADDING, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** Lightweight, language-aware colouring for fenced code blocks. */
object SyntaxHighlighter {

    private val COMMON_KEYWORDS = setOf(
        "abstract", "as", "assert", "async", "await", "base", "bool", "boolean", "break",
        "byte", "case", "catch", "char", "class", "companion", "const", "constructor",
        "continue", "data", "def", "default", "delete", "do", "double", "elif",
        "else", "enum", "except", "extends", "external", "false", "final", "finally",
        "float", "fn", "for", "from", "fun", "func", "function", "get", "goto", "if",
        "impl", "implements", "import", "in", "init", "inline", "instanceof", "int",
        "interface", "internal", "is", "lambda", "let", "long", "match", "mod", "module",
        "mut", "namespace", "new", "nil", "none", "not", "null", "object", "open",
        "operator", "or", "override", "package", "pass", "private", "protected", "pub",
        "public", "raise", "readonly", "ref", "return", "sealed", "self", "set", "short",
        "sizeof", "static", "struct", "super", "suspend", "switch", "template",
        "this", "throw", "throws", "trait", "true", "try", "type", "typedef", "typeof",
        "union", "unsafe", "until", "use", "using", "val", "var", "virtual", "void",
        "when", "where", "while", "with", "yield"
    )

    fun highlight(
        code: String,
        language: String?,
        base: Color,
        keyword: Color,
        string: Color,
        comment: Color,
        number: Color
    ): AnnotatedString = buildAnnotatedString {
        val lineComment = when (language?.lowercase()) {
            "python", "py", "sh", "bash", "zsh", "yaml", "yml", "ruby", "rb", "r", "toml" -> "#"
            "sql", "lua", "haskell" -> "--"
            else -> "//"
        }
        var i = 0
        val n = code.length
        val token = StringBuilder()

        fun flushToken() {
            if (token.isEmpty()) return
            val word = token.toString()
            val isNumber = word.first().isDigit()
            val color = when {
                COMMON_KEYWORDS.contains(word) -> keyword
                isNumber -> number
                else -> base
            }
            if (color == base) append(word) else withStyle(SpanStyle(color = color)) { append(word) }
            token.setLength(0)
        }

        while (i < n) {
            val c = code[i]
            when {
                code.startsWith(lineComment, i) -> {
                    flushToken()
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    withStyle(SpanStyle(color = comment)) { append(code.substring(i, end)) }
                    i = end
                }
                code.startsWith("/*", i) -> {
                    flushToken()
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    withStyle(SpanStyle(color = comment)) { append(code.substring(i, end)) }
                    i = end
                }
                c == '"' || c == '\'' -> {
                    flushToken()
                    var end = i + 1
                    while (end < n && code[end] != c) {
                        if (code[end] == '\\') end++
                        end++
                    }
                    end = (end + 1).coerceAtMost(n)
                    withStyle(SpanStyle(color = string)) { append(code.substring(i, end)) }
                    i = end
                }
                c.isLetterOrDigit() || c == '_' -> {
                    token.append(c)
                    i++
                }
                else -> {
                    flushToken()
                    append(c)
                    i++
                }
            }
        }
        flushToken()
    }
}
