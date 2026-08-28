package com.pocketai.app.ui.chat

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.doc.ExtractedDocument
import com.pocketai.app.doc.DocumentExtractor

/**
 * The message composer.
 *
 * Grows with the text up to a fixed ceiling, keeps its own IME padding so the
 * keyboard never covers it, and exposes attach / dictate / web-search inline.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    webSearchEnabled: Boolean,
    webSearchAllowed: Boolean,
    onToggleWebSearch: () -> Unit,
    attachment: ExtractedDocument?,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onVoiceResult: (String) -> Unit,
    isEditing: Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) onVoiceResult(spoken)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            AnimatedVisibility(visible = isEditing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                ) {
                    Text(
                        text = "Editing message",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Cancel",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onCancelEdit)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            AnimatedVisibility(visible = attachment != null) {
                if (attachment != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = attachment.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1
                                )
                                Text(
                                    text = "~${attachment.approxTokens} tokens" +
                                        if (attachment.truncated) " (shortened)" else "",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            IconButton(onClick = onClearAttachment, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove attachment",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 168.dp),
                    placeholder = {
                        Text(
                            if (isGenerating) "PocketAI is replying..." else "Message PocketAI",
                            fontSize = 15.sp
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Default
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(8.dp))
                SendButton(
                    isGenerating = isGenerating,
                    enabled = text.isNotBlank() || attachment != null,
                    onSend = {
                        focusManager.clearFocus()
                        onSend()
                    },
                    onStop = onStop
                )
            }

            Spacer(Modifier.padding(top = 2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ComposerChip(
                    icon = Icons.Outlined.AttachFile,
                    label = "Attach",
                    active = false,
                    onClick = onAttach
                )
                ComposerChip(
                    icon = Icons.Outlined.Mic,
                    label = if (speechAvailable) "Speak" else "Speech off",
                    active = false,
                    enabled = speechAvailable,
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        }
                        runCatching { speechLauncher.launch(intent) }
                    }
                )
                ComposerChip(
                    icon = Icons.Outlined.Language,
                    label = "Web Search",
                    active = webSearchEnabled,
                    enabled = webSearchAllowed,
                    onClick = onToggleWebSearch
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val background = when {
        isGenerating -> MaterialTheme.colorScheme.errorContainer
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val tint = when {
        isGenerating -> MaterialTheme.colorScheme.onErrorContainer
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = isGenerating || enabled) {
                if (isGenerating) onStop() else onSend()
            }
    ) {
        Icon(
            imageVector = if (isGenerating) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (isGenerating) "Stop generating" else "Send message",
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ComposerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text = label, fontSize = 12.sp, color = content, fontWeight = FontWeight.Medium)
        }
    }
}

/** MIME types the document picker offers, derived from what we can actually parse. */
val DOCUMENT_MIME_TYPES: Array<String> = arrayOf(
    "text/*",
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/octet-stream"
)

@Composable
fun rememberSupportedExtensionsLabel(): String =
    remember { DocumentExtractor.SUPPORTED_LABEL }
