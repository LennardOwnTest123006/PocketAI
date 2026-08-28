package com.pocketai.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** A selectable chat theme. */
data class PocketThemeSpec(
    val id: String,
    val label: String,
    val description: String,
    /** Swatches shown in the theme picker. */
    val swatch: List<Color>,
    val forcesDark: Boolean = false,
    val forcesLight: Boolean = false
)

/**
 * PocketAI's built-in themes.
 *
 * Every palette is checked so that body text keeps a comfortable contrast ratio
 * against its surface in both light and dark variants.
 */
object PocketThemes {

    val brandBlue = Color(0xFF2D6BFF)
    val brandViolet = Color(0xFF7B5CFF)
    val brandCyan = Color(0xFF35D6E8)

    val all: List<PocketThemeSpec> = listOf(
        PocketThemeSpec(
            "pocket_dark", "Pocket Dark",
            "The signature PocketAI look: deep indigo with a violet-to-cyan accent.",
            listOf(Color(0xFF12122A), brandViolet, brandCyan),
            forcesDark = true
        ),
        PocketThemeSpec(
            "pocket_light", "Pocket Light",
            "Bright, high-contrast surfaces with the same violet accent.",
            listOf(Color(0xFFF6F5FF), brandBlue, brandViolet),
            forcesLight = true
        ),
        PocketThemeSpec(
            "midnight", "Midnight",
            "Cool navy tones that stay easy on the eyes at night.",
            listOf(Color(0xFF0B1220), Color(0xFF4C7DFF), Color(0xFF7FE7FF)),
            forcesDark = true
        ),
        PocketThemeSpec(
            "amoled", "AMOLED",
            "True black background - saves power on the Flip6's OLED panel.",
            listOf(Color(0xFF000000), Color(0xFF8A7BFF), Color(0xFF2AE0C8)),
            forcesDark = true
        ),
        PocketThemeSpec(
            "ocean", "Ocean",
            "Teal and deep sea blue.",
            listOf(Color(0xFF06202B), Color(0xFF12A5B8), Color(0xFF6EE7D2))
        ),
        PocketThemeSpec(
            "purple", "Purple",
            "Rich purples with a magenta highlight.",
            listOf(Color(0xFF1B1030), Color(0xFF9B5CFF), Color(0xFFFF7BD5))
        ),
        PocketThemeSpec(
            "green", "Green",
            "Forest greens with a lime accent.",
            listOf(Color(0xFF0C1E14), Color(0xFF2FB86B), Color(0xFFA8F08B))
        ),
        PocketThemeSpec(
            "minimal", "Minimal",
            "Neutral greys, no colour distractions.",
            listOf(Color(0xFF1A1A1C), Color(0xFF8E8E96), Color(0xFFD6D6DE))
        )
    )

    fun byId(id: String): PocketThemeSpec = all.firstOrNull { it.id == id } ?: all.first()

    fun colorScheme(id: String, dark: Boolean): ColorScheme = when (id) {
        "pocket_light" -> pocketLight()
        "midnight" -> midnight()
        "amoled" -> amoled()
        "ocean" -> if (dark) oceanDark() else oceanLight()
        "purple" -> if (dark) purpleDark() else purpleLight()
        "green" -> if (dark) greenDark() else greenLight()
        "minimal" -> if (dark) minimalDark() else minimalLight()
        else -> if (dark) pocketDark() else pocketLight()
    }

    /** Whether this theme should render dark regardless of the system setting. */
    fun resolveDark(id: String, systemDark: Boolean): Boolean {
        val spec = byId(id)
        return when {
            spec.forcesDark -> true
            spec.forcesLight -> false
            else -> systemDark
        }
    }

    private fun pocketDark() = darkColorScheme(
        primary = Color(0xFF9B8CFF),
        onPrimary = Color(0xFF14103A),
        primaryContainer = Color(0xFF3A2FA0),
        onPrimaryContainer = Color(0xFFE4DEFF),
        secondary = Color(0xFF56D9E8),
        onSecondary = Color(0xFF00363D),
        secondaryContainer = Color(0xFF0F4C55),
        onSecondaryContainer = Color(0xFFB6F1FA),
        tertiary = Color(0xFFFF9ECF),
        onTertiary = Color(0xFF4B0033),
        background = Color(0xFF0E0E1F),
        onBackground = Color(0xFFE6E4F2),
        surface = Color(0xFF12122A),
        onSurface = Color(0xFFE6E4F2),
        surfaceVariant = Color(0xFF23233F),
        onSurfaceVariant = Color(0xFFBFBDD4),
        surfaceContainer = Color(0xFF1A1A33),
        surfaceContainerHigh = Color(0xFF23233F),
        surfaceContainerHighest = Color(0xFF2B2B4C),
        outline = Color(0xFF56547A),
        outlineVariant = Color(0xFF34324F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6)
    )

    private fun pocketLight() = lightColorScheme(
        primary = Color(0xFF4B3FD4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE4DEFF),
        onPrimaryContainer = Color(0xFF17055C),
        secondary = Color(0xFF00707E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFAFEDFB),
        onSecondaryContainer = Color(0xFF002027),
        tertiary = Color(0xFFA3487B),
        onTertiary = Color.White,
        background = Color(0xFFFBFAFF),
        onBackground = Color(0xFF1A1926),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1926),
        surfaceVariant = Color(0xFFE7E4F3),
        onSurfaceVariant = Color(0xFF484657),
        surfaceContainer = Color(0xFFF2F0FA),
        surfaceContainerHigh = Color(0xFFEAE7F6),
        surfaceContainerHighest = Color(0xFFE3E0F2),
        outline = Color(0xFF787689),
        outlineVariant = Color(0xFFC9C6D9),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002)
    )

    private fun midnight() = darkColorScheme(
        primary = Color(0xFF7FA8FF),
        onPrimary = Color(0xFF00214C),
        primaryContainer = Color(0xFF1C3C74),
        onPrimaryContainer = Color(0xFFD6E2FF),
        secondary = Color(0xFF7FE7FF),
        onSecondary = Color(0xFF00363F),
        background = Color(0xFF070C16),
        onBackground = Color(0xFFDCE3F0),
        surface = Color(0xFF0B1220),
        onSurface = Color(0xFFDCE3F0),
        surfaceVariant = Color(0xFF1A2438),
        onSurfaceVariant = Color(0xFFB4C0D6),
        surfaceContainer = Color(0xFF111A2B),
        surfaceContainerHigh = Color(0xFF18233A),
        surfaceContainerHighest = Color(0xFF203049),
        outline = Color(0xFF48587A),
        outlineVariant = Color(0xFF2A3852)
    )

    private fun amoled() = darkColorScheme(
        primary = Color(0xFF9E8CFF),
        onPrimary = Color(0xFF13093A),
        primaryContainer = Color(0xFF2B1F6B),
        onPrimaryContainer = Color(0xFFE7E0FF),
        secondary = Color(0xFF4EE6CE),
        onSecondary = Color(0xFF003730),
        background = Color(0xFF000000),
        onBackground = Color(0xFFEDEDF2),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFEDEDF2),
        surfaceVariant = Color(0xFF141418),
        onSurfaceVariant = Color(0xFFC4C4CE),
        surfaceContainer = Color(0xFF0A0A0D),
        surfaceContainerHigh = Color(0xFF141418),
        surfaceContainerHighest = Color(0xFF1D1D23),
        outline = Color(0xFF52525E),
        outlineVariant = Color(0xFF2A2A31)
    )

    private fun oceanDark() = darkColorScheme(
        primary = Color(0xFF54D3E4),
        onPrimary = Color(0xFF00363D),
        primaryContainer = Color(0xFF004E58),
        onPrimaryContainer = Color(0xFFB0ECF7),
        secondary = Color(0xFF7BD9C0),
        onSecondary = Color(0xFF00382D),
        background = Color(0xFF041720),
        onBackground = Color(0xFFD9EDF2),
        surface = Color(0xFF06202B),
        onSurface = Color(0xFFD9EDF2),
        surfaceVariant = Color(0xFF13323E),
        onSurfaceVariant = Color(0xFFAECAD4),
        surfaceContainer = Color(0xFF0A2836),
        surfaceContainerHigh = Color(0xFF103544),
        surfaceContainerHighest = Color(0xFF184454),
        outline = Color(0xFF42646F),
        outlineVariant = Color(0xFF224350)
    )

    private fun oceanLight() = lightColorScheme(
        primary = Color(0xFF006874),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9EEFFD),
        onPrimaryContainer = Color(0xFF001F24),
        secondary = Color(0xFF006960),
        onSecondary = Color.White,
        background = Color(0xFFF5FCFE),
        onBackground = Color(0xFF171D1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF171D1E),
        surfaceVariant = Color(0xFFDBE4E7),
        onSurfaceVariant = Color(0xFF3F484B),
        surfaceContainer = Color(0xFFEBF3F5),
        outline = Color(0xFF6F797B)
    )

    private fun purpleDark() = darkColorScheme(
        primary = Color(0xFFC3A8FF),
        onPrimary = Color(0xFF33146B),
        primaryContainer = Color(0xFF4B2C93),
        onPrimaryContainer = Color(0xFFEBDDFF),
        secondary = Color(0xFFFF9FDC),
        onSecondary = Color(0xFF56003F),
        background = Color(0xFF150B27),
        onBackground = Color(0xFFEBE0F5),
        surface = Color(0xFF1B1030),
        onSurface = Color(0xFFEBE0F5),
        surfaceVariant = Color(0xFF2E2145),
        onSurfaceVariant = Color(0xFFCDC0DE),
        surfaceContainer = Color(0xFF231639),
        surfaceContainerHigh = Color(0xFF2C1E46),
        surfaceContainerHighest = Color(0xFF362754),
        outline = Color(0xFF695A83),
        outlineVariant = Color(0xFF3D2F58)
    )

    private fun purpleLight() = lightColorScheme(
        primary = Color(0xFF6B41C4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEBDDFF),
        onPrimaryContainer = Color(0xFF250059),
        secondary = Color(0xFFA5306F),
        onSecondary = Color.White,
        background = Color(0xFFFDF7FF),
        onBackground = Color(0xFF1D1A22),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1D1A22),
        surfaceVariant = Color(0xFFE8E0EB),
        onSurfaceVariant = Color(0xFF4A454E),
        surfaceContainer = Color(0xFFF4EDF7),
        outline = Color(0xFF7B757E)
    )

    private fun greenDark() = darkColorScheme(
        primary = Color(0xFF7EDC9B),
        onPrimary = Color(0xFF00391C),
        primaryContainer = Color(0xFF00522C),
        onPrimaryContainer = Color(0xFF9AF9B6),
        secondary = Color(0xFFC6E8A0),
        onSecondary = Color(0xFF213600),
        background = Color(0xFF07160D),
        onBackground = Color(0xFFDCE9DE),
        surface = Color(0xFF0C1E14),
        onSurface = Color(0xFFDCE9DE),
        surfaceVariant = Color(0xFF1B3123),
        onSurfaceVariant = Color(0xFFB6CBBC),
        surfaceContainer = Color(0xFF11271A),
        surfaceContainerHigh = Color(0xFF173121),
        surfaceContainerHighest = Color(0xFF1F3D2A),
        outline = Color(0xFF46614F),
        outlineVariant = Color(0xFF2A4433)
    )

    private fun greenLight() = lightColorScheme(
        primary = Color(0xFF19693C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA2F5B7),
        onPrimaryContainer = Color(0xFF00210E),
        secondary = Color(0xFF4C662B),
        onSecondary = Color.White,
        background = Color(0xFFF6FBF4),
        onBackground = Color(0xFF181D18),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF181D18),
        surfaceVariant = Color(0xFFDDE5DB),
        onSurfaceVariant = Color(0xFF414942),
        surfaceContainer = Color(0xFFECF2E9),
        outline = Color(0xFF717971)
    )

    private fun minimalDark() = darkColorScheme(
        primary = Color(0xFFC9C9D2),
        onPrimary = Color(0xFF2E2E36),
        primaryContainer = Color(0xFF44444E),
        onPrimaryContainer = Color(0xFFE5E5EC),
        secondary = Color(0xFFB0B0BA),
        onSecondary = Color(0xFF2A2A31),
        background = Color(0xFF121214),
        onBackground = Color(0xFFE4E4E9),
        surface = Color(0xFF1A1A1C),
        onSurface = Color(0xFFE4E4E9),
        surfaceVariant = Color(0xFF2A2A2F),
        onSurfaceVariant = Color(0xFFC3C3CB),
        surfaceContainer = Color(0xFF202024),
        surfaceContainerHigh = Color(0xFF27272C),
        surfaceContainerHighest = Color(0xFF313138),
        outline = Color(0xFF5D5D66),
        outlineVariant = Color(0xFF3A3A41)
    )

    private fun minimalLight() = lightColorScheme(
        primary = Color(0xFF44444E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE1E1E9),
        onPrimaryContainer = Color(0xFF1B1B21),
        secondary = Color(0xFF5C5C66),
        onSecondary = Color.White,
        background = Color(0xFFFAFAFC),
        onBackground = Color(0xFF1B1B1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = Color(0xFFE3E2E8),
        onSurfaceVariant = Color(0xFF46464F),
        surfaceContainer = Color(0xFFF1F1F5),
        outline = Color(0xFF77767F)
    )
}
