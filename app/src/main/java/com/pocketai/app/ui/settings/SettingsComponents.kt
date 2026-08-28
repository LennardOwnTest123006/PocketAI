package com.pocketai.app.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.ui.theme.ColorPresets

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        onClick = { if (enabled) onChange(!checked) }
    )
}

@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                text = format(value),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onChangeFinished
        )
    }
}

/** Single-choice list rendered as pills, used for enums throughout settings. */
@Composable
fun <T> OptionPills(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 15.sp)
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clickable { onSelect(option) }
                ) {
                    Text(
                        text = label(option),
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Text colour picker: named presets plus a full custom colour dialog.
 * `null` means "follow the theme".
 */
@Composable
fun ColorSettingRow(
    title: String,
    current: Int,
    unsetValue: Int,
    onPick: (Int) -> Unit
) {
    var showCustom by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPresets.presets.forEach { preset ->
                val value = preset.color?.let { colorToInt(it) } ?: unsetValue
                Swatch(
                    color = preset.color,
                    selected = current == value,
                    label = preset.label
                ) { onPick(value) }
            }
            Swatch(
                color = if (isCustom(current, unsetValue)) Color(current) else null,
                selected = isCustom(current, unsetValue),
                label = "Custom"
            ) { showCustom = true }
        }
    }
    if (showCustom) {
        CustomColorDialog(
            initial = if (current == unsetValue) 0xFF7B5CFF.toInt() else current,
            onDismiss = { showCustom = false },
            onConfirm = { onPick(it) }
        )
    }
}

private fun isCustom(current: Int, unsetValue: Int): Boolean =
    current != unsetValue && ColorPresets.presets.none { it.color?.let(::colorToInt) == current }

private fun colorToInt(color: Color): Int = color.toArgb()

@Composable
private fun Swatch(color: Color?, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(bottom = 10.dp)
            .width(58.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (color != null) contrastOn(color) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            } else if (color == null) {
                Text("A", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Picks a readable foreground for a swatch tick. */
private fun contrastOn(color: Color): Color {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return if (luminance > 0.55) Color.Black else Color.White
}

/** RGB sliders with a live preview and a contrast hint. */
@Composable
fun CustomColorDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val start = Color(initial)
    var red by remember { mutableFloatStateOf(start.red * 255f) }
    var green by remember { mutableFloatStateOf(start.green * 255f) }
    var blue by remember { mutableFloatStateOf(start.blue * 255f) }

    val picked = Color(red / 255f, green / 255f, blue / 255f, 1f)
    val onSurface = MaterialTheme.colorScheme.surface
    val contrast = contrastRatio(picked, onSurface)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom colour") },
        text = {
            Column {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Text("The quick brown fox", color = picked, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                ChannelSlider("Red", red, Color(0xFFE05252)) { red = it }
                ChannelSlider("Green", green, Color(0xFF52C97A)) { green = it }
                ChannelSlider("Blue", blue, Color(0xFF5B8DE0)) { blue = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "#%02X%02X%02X".format(red.toInt(), green.toInt(), blue.toInt()) +
                        "  ·  contrast %.1f:1".format(contrast),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (contrast < 4.5) {
                    Text(
                        text = "This may be hard to read against the chat background.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    android.graphics.Color.argb(255, red.toInt(), green.toInt(), blue.toInt())
                )
                onDismiss()
            }) { Text("Use colour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(46.dp), color = tint)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toInt().toString(),
            fontSize = 12.sp,
            modifier = Modifier.width(34.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** WCAG relative-luminance contrast ratio, used for the readability hint. */
private fun contrastRatio(a: Color, b: Color): Double {
    fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
    val la = luminance(a)
    val lb = luminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}
