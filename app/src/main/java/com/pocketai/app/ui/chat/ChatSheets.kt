package com.pocketai.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.data.repo.ChatMessage
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.repo.TextSizes
import com.pocketai.app.export.ExportFormat
import com.pocketai.app.llm.SummaryMode

/** Actions available on a single message. */
@Composable
fun MessageActionSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSummarize: () -> Unit,
    onSelectText: () -> Unit,
    onChangeTextSize: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResend: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = if (message.role == ChatRole.USER) "Your message" else "PocketAI reply",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 6.dp)
            )
            if (message.role == ChatRole.ASSISTANT) {
                SheetAction(Icons.Outlined.ContentCopy, "Copy") { onCopy(); onDismiss() }
                SheetAction(Icons.Outlined.Share, "Share") { onShare(); onDismiss() }
                SheetAction(Icons.Outlined.Refresh, "Regenerate") { onRegenerate(); onDismiss() }
                SheetAction(Icons.Outlined.PlayArrow, "Continue") { onContinue(); onDismiss() }
                SheetAction(Icons.Outlined.Summarize, "Summarize") { onSummarize(); onDismiss() }
                SheetAction(Icons.Outlined.TextFields, "Select text") { onSelectText(); onDismiss() }
                SheetAction(Icons.Outlined.FormatSize, "Change text size") { onChangeTextSize(); onDismiss() }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SheetAction(Icons.Outlined.Delete, "Delete", destructive = true) { onDelete(); onDismiss() }
            } else {
                SheetAction(Icons.Outlined.Edit, "Edit") { onEdit(); onDismiss() }
                SheetAction(Icons.Outlined.ContentCopy, "Copy") { onCopy(); onDismiss() }
                SheetAction(Icons.Outlined.Send, "Resend") { onResend(); onDismiss() }
                SheetAction(Icons.Outlined.TextFields, "Select text") { onSelectText(); onDismiss() }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SheetAction(Icons.Outlined.Delete, "Delete", destructive = true) { onDelete(); onDismiss() }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = color, fontSize = 15.sp)
    }
}

/** Picks a summary style before running the local summarisation prompt. */
@Composable
fun SummaryModeSheet(onDismiss: () -> Unit, onPick: (SummaryMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Summarize with the local model",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp)
            )
            Text(
                text = "The summary is generated on this device.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
            )
            SummaryMode.entries.forEach { mode ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(mode); onDismiss() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(mode.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = mode.instruction.substringBefore('.') + ".",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Full-screen selectable copy of a message, for precise partial selection. */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit, onCopyAll: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select text") },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCopyAll(); onDismiss() }) { Text("Copy all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Quick access to the chat text size without leaving the conversation. */
@Composable
fun TextSizeSheet(
    sizes: TextSizes,
    onDismiss: () -> Unit,
    onChange: (TextSizes) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text("Text size", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.padding(top = 6.dp))
            SizeSlider("Message text", sizes.body) { onChange(sizes.copy(body = it)) }
            SizeSlider("Headings", sizes.heading) { onChange(sizes.copy(heading = it)) }
            SizeSlider("Subheadings", sizes.subheading) { onChange(sizes.copy(subheading = it)) }
            SizeSlider("Code", sizes.code) { onChange(sizes.copy(code = it)) }
            SizeSlider("Tables", sizes.table) { onChange(sizes.copy(table = it)) }
            SizeSlider("Thinking", sizes.thinking) { onChange(sizes.copy(thinking = it)) }
            Spacer(Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Small" to TextSizes(14f, 20f, 16f, 12f, 12f, 12f),
                    "Default" to TextSizes(),
                    "Large" to TextSizes(19f, 26f, 22f, 15f, 17f, 17f),
                    "Huge" to TextSizes(23f, 31f, 26f, 17f, 20f, 20f)
                ).forEach { (label, preset) ->
                    TextButton(onClick = { onChange(preset) }) { Text(label, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
fun SizeSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${value.toInt()} sp", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { onChange(it) },
            valueRange = TextSizes.MIN..TextSizes.MAX,
            steps = (TextSizes.MAX - TextSizes.MIN).toInt() - 1
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Title") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExportFormatSheet(onDismiss: () -> Unit, onPick: (ExportFormat) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Text(
                "Export this chat",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp)
            )
            ExportFormat.entries.forEach { format ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(format); onDismiss() }
                        .padding(horizontal = 22.dp, vertical = 13.dp)
                ) {
                    Text(format.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        ".${format.extension}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
