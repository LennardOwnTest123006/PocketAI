package com.pocketai.app.data.repo

import com.pocketai.app.core.PerformanceMode

/** How liberally the model is told to use emoji. */
enum class EmojiStyle(val id: String, val label: String, val promptHint: String) {
    NONE("none", "None", "Do not use any emoji."),
    MINIMAL("minimal", "Minimal", "Use an emoji only when it genuinely aids scanning, at most one or two per answer."),
    NATURAL("natural", "Natural", "Use emoji naturally where they help - section headings, list markers, or a friendly tone. Never more than one per line and never in code or tables."),
    EXPRESSIVE("expressive", "Expressive", "Use emoji freely and warmly to give the answer personality, while keeping the text readable.");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NATURAL
    }
}

/** Controls how much motion the UI uses; the lowest level keeps rendering cheapest. */
enum class AnimationLevel(val id: String, val label: String) {
    NONE("none", "Off"),
    REDUCED("reduced", "Reduced"),
    FULL("full", "Full");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: FULL
    }
}

enum class CodeTheme(val id: String, val label: String) {
    POCKET_NIGHT("pocket_night", "Pocket Night"),
    MIDNIGHT_BLUE("midnight_blue", "Midnight Blue"),
    SOLAR_LIGHT("solar_light", "Solar Light"),
    MONO_GREY("mono_grey", "Mono Grey");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: POCKET_NIGHT
    }
}

enum class TableStyle(val id: String, val label: String) {
    LINED("lined", "Lined"),
    STRIPED("striped", "Striped"),
    MINIMAL("minimal", "Minimal");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: LINED
    }
}

enum class SearchProvider(val id: String, val label: String, val privacyNote: String) {
    DUCKDUCKGO(
        "duckduckgo", "DuckDuckGo",
        "DuckDuckGo does not store personal search histories or build advertising profiles."
    ),
    WIKIPEDIA(
        "wikipedia", "Wikipedia",
        "Queries go to the Wikimedia API. Useful for background and reference topics."
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

enum class DarkModePreference(val id: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Always light"),
    DARK("dark", "Always dark");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Colour overrides for individual pieces of chat text.
 * [UNSET] means "use whatever the active theme provides".
 */
data class TextColors(
    val aiText: Int = UNSET,
    val userText: Int = UNSET,
    val thinkingText: Int = UNSET,
    val heading: Int = UNSET,
    val subheading: Int = UNSET,
    val link: Int = UNSET,
    val codeText: Int = UNSET,
    val tableText: Int = UNSET
) {
    companion object {
        const val UNSET = 0
    }
}

/** Independent size controls, all in sp. */
data class TextSizes(
    val body: Float = 16f,
    val heading: Float = 22f,
    val subheading: Float = 18f,
    val code: Float = 13f,
    val table: Float = 14f,
    val thinking: Float = 14f
) {
    companion object {
        val MIN = 11f
        val MAX = 34f
    }
}

data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 256,
    /**
     * Hard user ceiling. The per-request budget is the smaller of this and the
     * active [com.pocketai.app.llm.ResponseMode] budget.
     */
    val maxOutputTokens: Int = 768,
    val contextLength: Int = 4096,
    val seed: Int = -1
)

/** Everything the user can configure, resolved into one immutable snapshot. */
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val selectedModelId: String? = null,

    val generation: GenerationSettings = GenerationSettings(),
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val threadOverride: Int = 0,          // 0 = derive from the performance mode
    val gpuLayers: Int = 0,               // 0 = CPU only; >0 offloads layers when a GPU backend exists
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val flashAttention: Boolean = true,

    val showThinking: Boolean = true,
    val responseMode: com.pocketai.app.llm.ResponseMode = com.pocketai.app.llm.ResponseMode.BALANCED,
    val emojiStyle: EmojiStyle = EmojiStyle.NATURAL,

    val themeId: String = "pocket_dark",
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    val dynamicColor: Boolean = false,
    val textSizes: TextSizes = TextSizes(),
    val textColors: TextColors = TextColors(),
    val messageSpacing: Float = 12f,
    val messageCornerRadius: Float = 20f,
    val messageMaxWidthPercent: Float = 0.92f,
    val codeTheme: CodeTheme = CodeTheme.POCKET_NIGHT,
    val tableStyle: TableStyle = TableStyle.LINED,
    val animationLevel: AnimationLevel = AnimationLevel.FULL,

    val webSearchEnabled: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.DUCKDUCKGO,
    val searchResultCount: Int = 4,
    val showSources: Boolean = true,

    val localOnlyMode: Boolean = false,
    val showPerformanceStats: Boolean = true,

    val speak: SpeakSettings = SpeakSettings()
) {
    /** Web access is only ever attempted when both switches allow it. */
    val webSearchUsable: Boolean get() = webSearchEnabled && !localOnlyMode
}

/** Everything Speak Mode remembers between conversations. */
data class SpeakSettings(
    /**
     * Let the language of the reply follow the language that was spoken. Off
     * means PocketAI always answers in [languageTag].
     */
    val autoDetectLanguage: Boolean = true,
    /** Empty means "follow the phone's language". */
    val languageTag: String = "",
    /**
     * PocketAI's voice signature. Slightly below neutral pitch and a touch under
     * conversational pace reads as calm and stays intelligible in every
     * language, including ones whose voices run fast by default.
     */
    val voicePitch: Float = 0.96f,
    val voiceRate: Float = 0.98f,
    /**
     * Refuse recognisers that would upload audio. Off by default so Speak Mode
     * works out of the box: it always prefers the on-device recogniser, and only
     * falls back to the online one (announcing it) when the phone has no offline
     * pack for the language. Turning this on makes that fallback a hard stop for
     * users who want nothing sent off the device.
     */
    val onDeviceRecognitionOnly: Boolean = false,
    /** Keep the turn-taking loop going instead of stopping after one answer. */
    val continuousConversation: Boolean = true,
    /** Cap spoken replies; a paragraph read aloud is already a long answer. */
    val shorterSpokenReplies: Boolean = true
) {
    companion object {
        val PITCH_RANGE = 0.7f..1.3f
        val RATE_RANGE = 0.7f..1.4f
    }
}
