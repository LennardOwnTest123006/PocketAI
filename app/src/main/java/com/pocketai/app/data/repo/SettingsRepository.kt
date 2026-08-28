package com.pocketai.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketai.app.core.PerformanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketai_settings")

/**
 * Single source of truth for user preferences.
 *
 * Every setting is written individually and read back as one [AppSettings]
 * snapshot, so the UI observes a single flow and changes apply immediately.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val selectedModelId = stringPreferencesKey("selected_model_id")

        val temperature = floatPreferencesKey("gen_temperature")
        val topP = floatPreferencesKey("gen_top_p")
        val topK = intPreferencesKey("gen_top_k")
        val minP = floatPreferencesKey("gen_min_p")
        val repeatPenalty = floatPreferencesKey("gen_repeat_penalty")
        val repeatLastN = intPreferencesKey("gen_repeat_last_n")
        val maxOutputTokens = intPreferencesKey("gen_max_tokens")
        val contextLength = intPreferencesKey("gen_context_length")

        val performanceMode = stringPreferencesKey("perf_mode")
        val threadOverride = intPreferencesKey("perf_threads")
        val gpuLayers = intPreferencesKey("perf_gpu_layers")
        val useMmap = booleanPreferencesKey("perf_mmap")
        val useMlock = booleanPreferencesKey("perf_mlock")
        val flashAttention = booleanPreferencesKey("perf_flash_attn")

        val showThinking = booleanPreferencesKey("show_thinking")
        val responseMode = stringPreferencesKey("response_mode")
        val emojiStyle = stringPreferencesKey("emoji_style")

        val themeId = stringPreferencesKey("theme_id")
        val darkMode = stringPreferencesKey("dark_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")

        val sizeBody = floatPreferencesKey("size_body")
        val sizeHeading = floatPreferencesKey("size_heading")
        val sizeSubheading = floatPreferencesKey("size_subheading")
        val sizeCode = floatPreferencesKey("size_code")
        val sizeTable = floatPreferencesKey("size_table")
        val sizeThinking = floatPreferencesKey("size_thinking")

        val colorAi = intPreferencesKey("color_ai")
        val colorUser = intPreferencesKey("color_user")
        val colorThinking = intPreferencesKey("color_thinking")
        val colorHeading = intPreferencesKey("color_heading")
        val colorSubheading = intPreferencesKey("color_subheading")
        val colorLink = intPreferencesKey("color_link")
        val colorCode = intPreferencesKey("color_code")
        val colorTable = intPreferencesKey("color_table")

        val messageSpacing = floatPreferencesKey("message_spacing")
        val messageCorner = floatPreferencesKey("message_corner")
        val messageWidth = floatPreferencesKey("message_width")
        val codeTheme = stringPreferencesKey("code_theme")
        val tableStyle = stringPreferencesKey("table_style")
        val animationLevel = stringPreferencesKey("animation_level")

        val webSearchEnabled = booleanPreferencesKey("web_search_enabled")
        val searchProvider = stringPreferencesKey("search_provider")
        val searchResultCount = intPreferencesKey("search_result_count")
        val showSources = booleanPreferencesKey("show_sources")

        val localOnlyMode = booleanPreferencesKey("local_only_mode")
        val showPerformanceStats = booleanPreferencesKey("show_perf_stats")

        val speakAutoDetect = booleanPreferencesKey("speak_auto_detect")
        val speakLanguage = stringPreferencesKey("speak_language")
        val speakPitch = floatPreferencesKey("speak_pitch")
        val speakRate = floatPreferencesKey("speak_rate")
        val speakOnDeviceOnly = booleanPreferencesKey("speak_on_device_only")
        val speakContinuous = booleanPreferencesKey("speak_continuous")
        val speakShorterReplies = booleanPreferencesKey("speak_shorter_replies")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            // A corrupted preference file must not stop the app from starting.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            val defaults = AppSettings()
            AppSettings(
                onboardingComplete = p[Keys.onboardingComplete] ?: false,
                selectedModelId = p[Keys.selectedModelId],
                generation = GenerationSettings(
                    temperature = p[Keys.temperature] ?: defaults.generation.temperature,
                    topP = p[Keys.topP] ?: defaults.generation.topP,
                    topK = p[Keys.topK] ?: defaults.generation.topK,
                    minP = p[Keys.minP] ?: defaults.generation.minP,
                    repeatPenalty = p[Keys.repeatPenalty] ?: defaults.generation.repeatPenalty,
                    repeatLastN = p[Keys.repeatLastN] ?: defaults.generation.repeatLastN,
                    maxOutputTokens = p[Keys.maxOutputTokens] ?: defaults.generation.maxOutputTokens,
                    contextLength = p[Keys.contextLength] ?: defaults.generation.contextLength
                ),
                performanceMode = PerformanceMode.fromId(p[Keys.performanceMode]),
                threadOverride = p[Keys.threadOverride] ?: 0,
                gpuLayers = p[Keys.gpuLayers] ?: 0,
                useMmap = p[Keys.useMmap] ?: true,
                useMlock = p[Keys.useMlock] ?: false,
                flashAttention = p[Keys.flashAttention] ?: true,
                showThinking = p[Keys.showThinking] ?: true,
                responseMode = com.pocketai.app.llm.ResponseMode.fromId(p[Keys.responseMode]),
                emojiStyle = EmojiStyle.fromId(p[Keys.emojiStyle]),
                themeId = p[Keys.themeId] ?: defaults.themeId,
                darkMode = DarkModePreference.fromId(p[Keys.darkMode]),
                dynamicColor = p[Keys.dynamicColor] ?: false,
                textSizes = TextSizes(
                    body = p[Keys.sizeBody] ?: defaults.textSizes.body,
                    heading = p[Keys.sizeHeading] ?: defaults.textSizes.heading,
                    subheading = p[Keys.sizeSubheading] ?: defaults.textSizes.subheading,
                    code = p[Keys.sizeCode] ?: defaults.textSizes.code,
                    table = p[Keys.sizeTable] ?: defaults.textSizes.table,
                    thinking = p[Keys.sizeThinking] ?: defaults.textSizes.thinking
                ),
                textColors = TextColors(
                    aiText = p[Keys.colorAi] ?: TextColors.UNSET,
                    userText = p[Keys.colorUser] ?: TextColors.UNSET,
                    thinkingText = p[Keys.colorThinking] ?: TextColors.UNSET,
                    heading = p[Keys.colorHeading] ?: TextColors.UNSET,
                    subheading = p[Keys.colorSubheading] ?: TextColors.UNSET,
                    link = p[Keys.colorLink] ?: TextColors.UNSET,
                    codeText = p[Keys.colorCode] ?: TextColors.UNSET,
                    tableText = p[Keys.colorTable] ?: TextColors.UNSET
                ),
                messageSpacing = p[Keys.messageSpacing] ?: defaults.messageSpacing,
                messageCornerRadius = p[Keys.messageCorner] ?: defaults.messageCornerRadius,
                messageMaxWidthPercent = p[Keys.messageWidth] ?: defaults.messageMaxWidthPercent,
                codeTheme = CodeTheme.fromId(p[Keys.codeTheme]),
                tableStyle = TableStyle.fromId(p[Keys.tableStyle]),
                animationLevel = AnimationLevel.fromId(p[Keys.animationLevel]),
                webSearchEnabled = p[Keys.webSearchEnabled] ?: false,
                searchProvider = SearchProvider.fromId(p[Keys.searchProvider]),
                searchResultCount = p[Keys.searchResultCount] ?: defaults.searchResultCount,
                showSources = p[Keys.showSources] ?: true,
                localOnlyMode = p[Keys.localOnlyMode] ?: false,
                showPerformanceStats = p[Keys.showPerformanceStats] ?: true,
                speak = SpeakSettings(
                    autoDetectLanguage = p[Keys.speakAutoDetect] ?: false,
                    languageTag = p[Keys.speakLanguage] ?: "",
                    voicePitch = p[Keys.speakPitch] ?: defaults.speak.voicePitch,
                    voiceRate = p[Keys.speakRate] ?: defaults.speak.voiceRate,
                    onDeviceRecognitionOnly = p[Keys.speakOnDeviceOnly] ?: false,
                    continuousConversation = p[Keys.speakContinuous] ?: true,
                    shorterSpokenReplies = p[Keys.speakShorterReplies] ?: true
                )
            )
        }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.onboardingComplete] = value }
    suspend fun setSelectedModel(id: String?) = edit {
        if (id == null) it.remove(Keys.selectedModelId) else it[Keys.selectedModelId] = id
    }

    suspend fun setTemperature(v: Float) = edit { it[Keys.temperature] = v }
    suspend fun setTopP(v: Float) = edit { it[Keys.topP] = v }
    suspend fun setTopK(v: Int) = edit { it[Keys.topK] = v }
    suspend fun setMinP(v: Float) = edit { it[Keys.minP] = v }
    suspend fun setRepeatPenalty(v: Float) = edit { it[Keys.repeatPenalty] = v }
    suspend fun setMaxOutputTokens(v: Int) = edit { it[Keys.maxOutputTokens] = v }
    suspend fun setContextLength(v: Int) = edit { it[Keys.contextLength] = v }

    suspend fun setPerformanceMode(mode: PerformanceMode) = edit { it[Keys.performanceMode] = mode.id }
    suspend fun setThreadOverride(v: Int) = edit { it[Keys.threadOverride] = v }
    suspend fun setGpuLayers(v: Int) = edit { it[Keys.gpuLayers] = v }
    suspend fun setUseMmap(v: Boolean) = edit { it[Keys.useMmap] = v }
    suspend fun setUseMlock(v: Boolean) = edit { it[Keys.useMlock] = v }
    suspend fun setFlashAttention(v: Boolean) = edit { it[Keys.flashAttention] = v }

    suspend fun setShowThinking(v: Boolean) = edit { it[Keys.showThinking] = v }
    suspend fun setResponseMode(v: com.pocketai.app.llm.ResponseMode) =
        edit { it[Keys.responseMode] = v.id }
    suspend fun setEmojiStyle(v: EmojiStyle) = edit { it[Keys.emojiStyle] = v.id }

    suspend fun setThemeId(v: String) = edit { it[Keys.themeId] = v }
    suspend fun setDarkMode(v: DarkModePreference) = edit { it[Keys.darkMode] = v.id }
    suspend fun setDynamicColor(v: Boolean) = edit { it[Keys.dynamicColor] = v }

    suspend fun setTextSizes(sizes: TextSizes) = edit {
        it[Keys.sizeBody] = sizes.body
        it[Keys.sizeHeading] = sizes.heading
        it[Keys.sizeSubheading] = sizes.subheading
        it[Keys.sizeCode] = sizes.code
        it[Keys.sizeTable] = sizes.table
        it[Keys.sizeThinking] = sizes.thinking
    }

    suspend fun setTextColors(colors: TextColors) = edit {
        it[Keys.colorAi] = colors.aiText
        it[Keys.colorUser] = colors.userText
        it[Keys.colorThinking] = colors.thinkingText
        it[Keys.colorHeading] = colors.heading
        it[Keys.colorSubheading] = colors.subheading
        it[Keys.colorLink] = colors.link
        it[Keys.colorCode] = colors.codeText
        it[Keys.colorTable] = colors.tableText
    }

    suspend fun setMessageSpacing(v: Float) = edit { it[Keys.messageSpacing] = v }
    suspend fun setMessageCorner(v: Float) = edit { it[Keys.messageCorner] = v }
    suspend fun setMessageWidth(v: Float) = edit { it[Keys.messageWidth] = v }
    suspend fun setCodeTheme(v: CodeTheme) = edit { it[Keys.codeTheme] = v.id }
    suspend fun setTableStyle(v: TableStyle) = edit { it[Keys.tableStyle] = v.id }
    suspend fun setAnimationLevel(v: AnimationLevel) = edit { it[Keys.animationLevel] = v.id }

    suspend fun setWebSearchEnabled(v: Boolean) = edit { it[Keys.webSearchEnabled] = v }
    suspend fun setSearchProvider(v: SearchProvider) = edit { it[Keys.searchProvider] = v.id }
    suspend fun setSearchResultCount(v: Int) = edit { it[Keys.searchResultCount] = v }
    suspend fun setShowSources(v: Boolean) = edit { it[Keys.showSources] = v }

    suspend fun setLocalOnlyMode(v: Boolean) = edit {
        it[Keys.localOnlyMode] = v
        if (v) it[Keys.webSearchEnabled] = false   // local-only always wins
    }
    suspend fun setShowPerformanceStats(v: Boolean) = edit { it[Keys.showPerformanceStats] = v }

    suspend fun setSpeakAutoDetect(v: Boolean) = edit { it[Keys.speakAutoDetect] = v }
    suspend fun setSpeakLanguage(tag: String) = edit { it[Keys.speakLanguage] = tag }
    suspend fun setSpeakPitch(v: Float) = edit {
        it[Keys.speakPitch] = v.coerceIn(SpeakSettings.PITCH_RANGE)
    }
    suspend fun setSpeakRate(v: Float) = edit {
        it[Keys.speakRate] = v.coerceIn(SpeakSettings.RATE_RANGE)
    }
    suspend fun setSpeakOnDeviceOnly(v: Boolean) = edit { it[Keys.speakOnDeviceOnly] = v }
    suspend fun setSpeakContinuous(v: Boolean) = edit { it[Keys.speakContinuous] = v }
    suspend fun setSpeakShorterReplies(v: Boolean) = edit { it[Keys.speakShorterReplies] = v }

    suspend fun resetAppearance() = edit {
        listOf(
            Keys.themeId, Keys.darkMode, Keys.dynamicColor,
            Keys.sizeBody, Keys.sizeHeading, Keys.sizeSubheading,
            Keys.sizeCode, Keys.sizeTable, Keys.sizeThinking,
            Keys.colorAi, Keys.colorUser, Keys.colorThinking, Keys.colorHeading,
            Keys.colorSubheading, Keys.colorLink, Keys.colorCode, Keys.colorTable,
            Keys.messageSpacing, Keys.messageCorner, Keys.messageWidth,
            Keys.codeTheme, Keys.tableStyle, Keys.animationLevel
        ).forEach { key -> it.remove(key) }
    }

    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
