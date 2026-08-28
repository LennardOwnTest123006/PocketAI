package com.pocketai.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketai.app.data.repo.AnimationLevel
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.CodeTheme
import com.pocketai.app.data.repo.DarkModePreference
import com.pocketai.app.data.repo.TableStyle
import com.pocketai.app.data.repo.TextColors

/** Colours and metrics the chat renderer reads while drawing a message. */
data class ChatStyle(
    val aiText: Color,
    val userText: Color,
    val thinkingText: Color,
    val heading: Color,
    val subheading: Color,
    val link: Color,
    val codeText: Color,
    val tableText: Color,
    val bodySize: androidx.compose.ui.unit.TextUnit,
    val headingSize: androidx.compose.ui.unit.TextUnit,
    val subheadingSize: androidx.compose.ui.unit.TextUnit,
    val codeSize: androidx.compose.ui.unit.TextUnit,
    val tableSize: androidx.compose.ui.unit.TextUnit,
    val thinkingSize: androidx.compose.ui.unit.TextUnit,
    val messageSpacing: Dp,
    val cornerRadius: Dp,
    val maxWidthFraction: Float,
    val codeTheme: CodeTheme,
    val tableStyle: TableStyle,
    val animations: AnimationLevel,
    val codeBackground: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeComment: Color,
    val codeNumber: Color
)

val LocalChatStyle = staticCompositionLocalOf<ChatStyle> {
    error("ChatStyle requested outside of PocketTheme")
}

/** Named text colours offered in the appearance settings. */
object ColorPresets {
    data class Preset(val label: String, val color: Color?)

    val presets: List<Preset> = listOf(
        Preset("Default", null),
        Preset("Blue", Color(0xFF5B9CFF)),
        Preset("Purple", Color(0xFFB69BFF)),
        Preset("Green", Color(0xFF5BD991)),
        Preset("Cyan", Color(0xFF4FD9E8)),
        Preset("Orange", Color(0xFFFFA95C)),
        Preset("Pink", Color(0xFFFF8FC8)),
        Preset("Red", Color(0xFFFF7A73)),
        Preset("White", Color(0xFFF5F5FA))
    )
}

private val PocketTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun PocketTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val prefersDark = when (settings.darkMode) {
        DarkModePreference.SYSTEM -> systemDark
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }
    val dark = PocketThemes.resolveDark(settings.themeId, prefersDark)
    val context = LocalContext.current

    val scheme: ColorScheme = remember(settings.themeId, dark, settings.dynamicColor) {
        if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            PocketThemes.colorScheme(settings.themeId, dark)
        }
    }

    val chatStyle = remember(scheme, settings.textColors, settings.textSizes, settings.codeTheme,
        settings.tableStyle, settings.animationLevel, settings.messageSpacing,
        settings.messageCornerRadius, settings.messageMaxWidthPercent) {
        buildChatStyle(scheme, settings, dark)
    }

    CompositionLocalProvider(LocalChatStyle provides chatStyle) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PocketTypography,
            content = content
        )
    }
}

private fun resolve(value: Int, fallback: Color): Color =
    if (value == TextColors.UNSET) fallback else Color(value)

private fun buildChatStyle(scheme: ColorScheme, settings: AppSettings, dark: Boolean): ChatStyle {
    val colors = settings.textColors
    val sizes = settings.textSizes
    val code = codePalette(settings.codeTheme, dark, scheme)
    return ChatStyle(
        aiText = resolve(colors.aiText, scheme.onSurface),
        userText = resolve(colors.userText, scheme.onPrimaryContainer),
        thinkingText = resolve(colors.thinkingText, scheme.onSurfaceVariant),
        heading = resolve(colors.heading, scheme.primary),
        subheading = resolve(colors.subheading, scheme.secondary),
        link = resolve(colors.link, if (dark) Color(0xFF7FB4FF) else Color(0xFF1B57C4)),
        codeText = resolve(colors.codeText, code.text),
        tableText = resolve(colors.tableText, scheme.onSurface),
        bodySize = sizes.body.sp,
        headingSize = sizes.heading.sp,
        subheadingSize = sizes.subheading.sp,
        codeSize = sizes.code.sp,
        tableSize = sizes.table.sp,
        thinkingSize = sizes.thinking.sp,
        messageSpacing = settings.messageSpacing.dp,
        cornerRadius = settings.messageCornerRadius.dp,
        maxWidthFraction = settings.messageMaxWidthPercent,
        codeTheme = settings.codeTheme,
        tableStyle = settings.tableStyle,
        animations = settings.animationLevel,
        codeBackground = code.background,
        codeKeyword = code.keyword,
        codeString = code.string,
        codeComment = code.comment,
        codeNumber = code.number
    )
}

private data class CodePalette(
    val background: Color,
    val text: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color
)

private fun codePalette(theme: CodeTheme, dark: Boolean, scheme: ColorScheme): CodePalette =
    when (theme) {
        CodeTheme.POCKET_NIGHT -> CodePalette(
            background = Color(0xFF14142B),
            text = Color(0xFFE3E1F5),
            keyword = Color(0xFFB69BFF),
            string = Color(0xFF7FE0B0),
            comment = Color(0xFF7E7C9B),
            number = Color(0xFFFFB27A)
        )
        CodeTheme.MIDNIGHT_BLUE -> CodePalette(
            background = Color(0xFF0D1626),
            text = Color(0xFFD7E3F5),
            keyword = Color(0xFF6FA8FF),
            string = Color(0xFF7FD6C4),
            comment = Color(0xFF64748B),
            number = Color(0xFFFFC46B)
        )
        CodeTheme.SOLAR_LIGHT -> CodePalette(
            background = Color(0xFFFBF3E0),
            text = Color(0xFF3B3223),
            keyword = Color(0xFF9A4C00),
            string = Color(0xFF3B6E1F),
            comment = Color(0xFF9A917F),
            number = Color(0xFF9C2E7A)
        )
        CodeTheme.MONO_GREY -> CodePalette(
            background = if (dark) Color(0xFF1C1C20) else Color(0xFFF1F1F4),
            text = if (dark) Color(0xFFE2E2E7) else Color(0xFF232327),
            keyword = if (dark) Color(0xFFB9B9C4) else Color(0xFF4A4A55),
            string = if (dark) Color(0xFFCFCFD8) else Color(0xFF3A3A44),
            comment = if (dark) Color(0xFF77777F) else Color(0xFF8B8B95),
            number = if (dark) Color(0xFFD4D4DC) else Color(0xFF3F3F49)
        )
    }
