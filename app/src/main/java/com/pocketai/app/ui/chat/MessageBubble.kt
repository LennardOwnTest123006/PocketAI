package com.pocketai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.data.repo.AnimationLevel
import com.pocketai.app.data.repo.ChatMessage
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.repo.WebSource
import com.pocketai.app.ui.markdown.MarkdownText
import com.pocketai.app.ui.theme.LocalChatStyle

/**
 * One chat message.
 *
 * User turns render as a tinted bubble; assistant turns render full width so
 * tables and code blocks get the room they need on a narrow phone screen.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    showThinking: Boolean,
    showStats: Boolean,
    showSources: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalChatStyle.current
    val isUser = message.role == ChatRole.USER

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(style.cornerRadius),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth(style.maxWidthFraction)
                    .wrapContentWidth(Alignment.End)
            ) {
                // Selection is handled by SelectionContainer, so the action menu
                // lives on its own button rather than competing for the long press.
                SelectionContainer {
                    MarkdownText(
                        text = message.content,
                        textColor = style.userText,
                        onCopy = onCopy,
                        onShare = onShare,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            if (message.attachmentName != null) {
                Text(
                    text = "Attached: ${message.attachmentName}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp)
                )
            }
            MessageActionBar(
                alignEnd = true,
                onCopy = { onCopy(message.content) },
                onOpenActions = onOpenActions
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (message.usedWebSearch) {
                    WebAssistedBadge()
                    Spacer(Modifier.height(6.dp))
                }
                if (!message.thinking.isNullOrBlank() && showThinking) {
                    ThinkingSection(
                        text = message.thinking,
                        streaming = false,
                        onCopy = onCopy
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (message.isError) {
                    ErrorContent(message.content)
                } else {
                    SelectionContainer {
                        MarkdownText(
                            text = message.content,
                            textColor = style.aiText,
                            onCopy = onCopy,
                            onShare = onShare,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (showSources && message.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SourceList(message.sources)
                }
                if (showStats && message.stats.hasData) {
                    Spacer(Modifier.height(6.dp))
                    StatsRow(
                        tokensPerSecond = message.stats.tokensPerSecond,
                        tokens = message.stats.generatedTokens,
                        firstTokenMs = message.stats.firstTokenMs,
                        totalMs = message.stats.totalMs,
                        modelName = message.modelName
                    )
                }
                MessageActionBar(
                    alignEnd = false,
                    onCopy = { onCopy(message.content) },
                    onOpenActions = onOpenActions
                )
            }
        }
    }
}

/** Quick copy plus the full action menu, as explicit controls. */
@Composable
private fun MessageActionBar(
    alignEnd: Boolean,
    onCopy: () -> Unit,
    onOpenActions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(onClick = onOpenActions, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = "Message actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** Live assistant output while the model is still generating. */
@Composable
fun StreamingBubble(
    state: StreamingState,
    showThinking: Boolean,
    showSources: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalChatStyle.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        if (state.usedWebSearch) {
            WebAssistedBadge()
            Spacer(Modifier.height(6.dp))
        }
        if (state.thinking.isNotBlank() && showThinking) {
            ThinkingSection(
                text = state.thinking,
                streaming = state.insideThinking,
                onCopy = onCopy,
                initiallyExpanded = true
            )
            Spacer(Modifier.height(8.dp))
        }
        if (state.answer.isNotBlank()) {
            MarkdownText(
                text = state.answer,
                textColor = style.aiText,
                onCopy = onCopy,
                onShare = onShare,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (!state.insideThinking) {
            TypingIndicator()
        }
        if (showSources && state.sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SourceList(state.sources)
        }
    }
}

/**
 * The collapsible reasoning panel.
 *
 * Only ever shown when the model actually emitted a reasoning block - PocketAI
 * never fabricates thinking text to make the UI look busier.
 */
@Composable
fun ThinkingSection(
    text: String,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    initiallyExpanded: Boolean = false
) {
    val style = LocalChatStyle.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(if (style.animations == AnimationLevel.NONE) 0 else 180),
        label = "thinkingChevron"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .semantics {
                        contentDescription =
                            if (expanded) "Hide the model's reasoning" else "Show the model's reasoning"
                    }
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = style.thinkingText,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (streaming) "Thinking..." else "Thinking",
                    color = style.thinkingText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = style.thinkingText,
                    modifier = Modifier
                        .size(19.dp)
                        .rotate(rotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SelectionContainer {
                    MarkdownText(
                        text = text,
                        textColor = style.thinkingText,
                        baseSize = style.thinkingSize,
                        onCopy = onCopy,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebAssistedBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Answered with web results",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SourceList(sources: List<WebSource>) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Sources",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEachIndexed { index, source ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clickable { runCatching { uriHandler.openUri(source.url) } }
                ) {
                    Text(
                        text = "[${index + 1}] ${source.host}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(
    tokensPerSecond: Double,
    tokens: Int,
    firstTokenMs: Long,
    totalMs: Long,
    modelName: String?
) {
    val parts = buildList {
        if (tokensPerSecond > 0) add(String.format("%.1f tok/s", tokensPerSecond))
        if (tokens > 0) add("$tokens tokens")
        if (firstTokenMs > 0) add("first token ${firstTokenMs} ms")
        if (totalMs > 0) add(String.format("%.1f s total", totalMs / 1000.0))
        modelName?.let { add(it) }
    }
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = parts.joinToString("  ·  "),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 14.sp
            )
        }
    }
}

/** Three dots that pulse while waiting for the first token. */
@Composable
private fun TypingIndicator() {
    val style = LocalChatStyle.current
    val still = style.animations == AnimationLevel.NONE
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = if (still) 0.7f else 0.25f,
                targetValue = if (still) 0.7f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(620, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(style.heading.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "PocketAI is thinking on this device",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
