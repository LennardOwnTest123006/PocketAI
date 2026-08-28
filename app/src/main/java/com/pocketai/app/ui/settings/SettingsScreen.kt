package com.pocketai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.BuildConfig
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.core.PerformanceMode
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.data.repo.SpeakSettings
import com.pocketai.app.data.repo.AnimationLevel
import com.pocketai.app.data.repo.CodeTheme
import com.pocketai.app.data.repo.DarkModePreference
import com.pocketai.app.data.repo.EmojiStyle
import com.pocketai.app.data.repo.SearchProvider
import com.pocketai.app.data.repo.TableStyle
import com.pocketai.app.data.repo.TextColors
import com.pocketai.app.llm.ResponseMode
import com.pocketai.app.ui.theme.PocketThemes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenBenchmark: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val caps = remember { viewModel.capabilities() }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val prefs = viewModel.prefs

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ------------------------------------------------------------ AI
            item {
                SettingsSection("AI") {
                    SettingsRow(
                        title = "Model",
                        subtitle = engineState.loadedModel?.displayName
                            ?: "No model loaded - tap to open the model manager",
                        onClick = onOpenModels
                    )
                    SettingsSlider(
                        title = "Context length",
                        value = settings.generation.contextLength.toFloat(),
                        range = 1024f..16384f,
                        steps = 14,
                        format = { "${it.toInt()} tokens" },
                        onChange = { scope.launch { prefs.setContextLength(it.toInt()) } },
                        onChangeFinished = {
                            scope.launch {
                                viewModel.reloadModel()
                                snackbarHost.showSnackbar("Model reloaded with the new context length.")
                            }
                        }
                    )
                    SettingsSlider(
                        title = "Temperature",
                        value = settings.generation.temperature,
                        range = 0f..1.5f,
                        format = { String.format("%.2f", it) },
                        onChange = { scope.launch { prefs.setTemperature(it) } }
                    )
                    SettingsSlider(
                        title = "Top-p",
                        value = settings.generation.topP,
                        range = 0.1f..1f,
                        format = { String.format("%.2f", it) },
                        onChange = { scope.launch { prefs.setTopP(it) } }
                    )
                    SettingsSlider(
                        title = "Top-k",
                        value = settings.generation.topK.toFloat(),
                        range = 0f..120f,
                        format = { if (it < 1) "off" else it.toInt().toString() },
                        onChange = { scope.launch { prefs.setTopK(it.toInt()) } }
                    )
                    SettingsSlider(
                        title = "Repeat penalty",
                        value = settings.generation.repeatPenalty,
                        range = 1f..1.5f,
                        format = { String.format("%.2f", it) },
                        onChange = { scope.launch { prefs.setRepeatPenalty(it) } }
                    )
                    SettingsSlider(
                        title = "Maximum output",
                        value = settings.generation.maxOutputTokens.toFloat(),
                        range = 128f..4096f,
                        steps = 30,
                        format = { "${it.toInt()} tokens" },
                        onChange = { scope.launch { prefs.setMaxOutputTokens(it.toInt()) } }
                    )
                    OptionPills(
                        title = "Response mode",
                        subtitle = settings.responseMode.description +
                            "  Budget: up to ${settings.responseMode.maxTokens} tokens.",
                        options = ResponseMode.entries,
                        selected = settings.responseMode,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setResponseMode(it) } }
                    )
                    SettingsSwitch(
                        title = "Show thinking",
                        subtitle = "Display the reasoning models emit inside <think> blocks. " +
                            "Models that produce none show nothing - PocketAI never invents it.",
                        checked = settings.showThinking,
                        onChange = { scope.launch { prefs.setShowThinking(it) } }
                    )
                    SettingsSwitch(
                        title = "Performance details",
                        subtitle = "Show tokens per second, token counts and latency under replies.",
                        checked = settings.showPerformanceStats,
                        onChange = { scope.launch { prefs.setShowPerformanceStats(it) } }
                    )
                }
            }

            // --------------------------------------------------- Performance
            item {
                SettingsSection("Performance") {
                    OptionPills(
                        title = "Performance mode",
                        subtitle = settings.performanceMode.description,
                        options = PerformanceMode.entries,
                        selected = settings.performanceMode,
                        label = { it.title },
                        onSelect = { mode ->
                            scope.launch {
                                prefs.setPerformanceMode(mode)
                                viewModel.reloadModel()
                            }
                        }
                    )
                    if (PerformanceMode.recommendedFor(caps) != settings.performanceMode) {
                        SettingsRow(
                            title = "Recommended for this device",
                            subtitle = "${PerformanceMode.recommendedFor(caps).title} suits " +
                                "${DeviceCapabilities.formatGb(caps.totalRamBytes)} of RAM and " +
                                "${caps.cpuCores} cores. Tap to switch.",
                            onClick = {
                                scope.launch {
                                    prefs.setPerformanceMode(PerformanceMode.recommendedFor(caps))
                                    viewModel.reloadModel()
                                }
                            }
                        )
                    }
                    SettingsSlider(
                        title = "CPU threads",
                        value = settings.threadOverride.toFloat(),
                        range = 0f..caps.cpuCores.toFloat(),
                        steps = (caps.cpuCores - 1).coerceAtLeast(0),
                        format = { value ->
                            if (value < 1) "Automatic (${settings.performanceMode.threadsFor(caps)})"
                            else value.toInt().toString()
                        },
                        onChange = { scope.launch { prefs.setThreadOverride(it.toInt()) } },
                        onChangeFinished = { scope.launch { viewModel.reloadModel() } }
                    )
                    SettingsRow(
                        title = "Performance benchmark",
                        subtitle = "Measure time-to-first-token and tokens per second on this device.",
                        onClick = onOpenBenchmark
                    )
                    SettingsRow(
                        title = "Acceleration",
                        subtitle = buildString {
                            append(engineState.acceleration)
                            if (engineState.devices.isNotEmpty()) {
                                append(" · ")
                                append(engineState.devices.joinToString(", ") { it.name })
                            }
                        }
                    )
                    if (engineState.gpuAvailable) {
                        SettingsSlider(
                            title = "GPU layers",
                            value = settings.gpuLayers.toFloat(),
                            range = 0f..64f,
                            format = { if (it < 1) "CPU only" else "${it.toInt()} layers" },
                            onChange = { scope.launch { prefs.setGpuLayers(it.toInt()) } },
                            onChangeFinished = { scope.launch { viewModel.reloadModel() } }
                        )
                    }
                    SettingsSwitch(
                        title = "Memory mapping",
                        subtitle = "Loads weights straight from storage. Keep this on unless a model fails to load.",
                        checked = settings.useMmap,
                        onChange = {
                            scope.launch { prefs.setUseMmap(it); viewModel.reloadModel() }
                        }
                    )
                    SettingsSwitch(
                        title = "Lock model in RAM",
                        subtitle = "Prevents the system paging weights out. Faster, but uses more memory.",
                        checked = settings.useMlock,
                        onChange = {
                            scope.launch { prefs.setUseMlock(it); viewModel.reloadModel() }
                        }
                    )
                    SettingsSwitch(
                        title = "Flash attention",
                        subtitle = "Reduces memory use for long conversations on supported models.",
                        checked = settings.flashAttention,
                        onChange = {
                            scope.launch { prefs.setFlashAttention(it); viewModel.reloadModel() }
                        }
                    )
                }
            }

            // ---------------------------------------------------- Appearance
            item {
                SettingsSection("Speak Mode") {
                    SettingsSwitch(
                        title = "Answer in the language I speak",
                        subtitle = "PocketAI detects the language of each spoken turn and " +
                            "replies in it. Turn off to always use the language you pick in " +
                            "Speak Mode.",
                        checked = settings.speak.autoDetectLanguage,
                        onChange = { scope.launch { prefs.setSpeakAutoDetect(it) } }
                    )
                    SettingsSwitch(
                        title = "Keep the conversation going",
                        subtitle = "Listen again as soon as PocketAI finishes talking, so a " +
                            "conversation needs no tapping.",
                        checked = settings.speak.continuousConversation,
                        onChange = { scope.launch { prefs.setSpeakContinuous(it) } }
                    )
                    SettingsSwitch(
                        title = "Shorter spoken answers",
                        subtitle = "A written answer can be skimmed; a spoken one has to be " +
                            "sat through. Keeps replies to roughly a minute.",
                        checked = settings.speak.shorterSpokenReplies,
                        onChange = { scope.launch { prefs.setSpeakShorterReplies(it) } }
                    )
                    SettingsSwitch(
                        title = "On-device speech recognition only",
                        subtitle = "Refuses recognisers that upload audio. If your phone has " +
                            "no offline recogniser for a language, Speak Mode will say so " +
                            "rather than send your voice away.",
                        checked = settings.speak.onDeviceRecognitionOnly,
                        onChange = { scope.launch { prefs.setSpeakOnDeviceOnly(it) } }
                    )
                    SettingsSlider(
                        title = "Voice pitch",
                        value = settings.speak.voicePitch,
                        range = SpeakSettings.PITCH_RANGE,
                        format = { "%.2f".format(it) },
                        onChange = { scope.launch { prefs.setSpeakPitch(it) } }
                    )
                    SettingsSlider(
                        title = "Speaking rate",
                        value = settings.speak.voiceRate,
                        range = SpeakSettings.RATE_RANGE,
                        format = { "%.2f".format(it) },
                        onChange = { scope.launch { prefs.setSpeakRate(it) } }
                    )
                    SettingsRow(
                        title = "Voices and offline languages",
                        subtitle = "PocketAI uses the voices Android has installed and picks " +
                            "the best offline one for each language. Add or download voices " +
                            "in Android's own text-to-speech settings."
                    )
                }

                SettingsSection("Appearance") {
                    ThemePicker(
                        selectedId = settings.themeId,
                        onSelect = { scope.launch { prefs.setThemeId(it) } }
                    )
                    OptionPills(
                        title = "Light and dark",
                        options = DarkModePreference.entries,
                        selected = settings.darkMode,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setDarkMode(it) } }
                    )
                    SettingsSwitch(
                        title = "Use system colours",
                        subtitle = "Material You dynamic colour (Android 12 and newer).",
                        checked = settings.dynamicColor,
                        onChange = { scope.launch { prefs.setDynamicColor(it) } }
                    )
                }
            }

            item {
                SettingsSection("Text size") {
                    val sizes = settings.textSizes
                    SettingsSlider("Message text", sizes.body, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(body = it)) } })
                    SettingsSlider("Headings", sizes.heading, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(heading = it)) } })
                    SettingsSlider("Subheadings", sizes.subheading, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(subheading = it)) } })
                    SettingsSlider("Code", sizes.code, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(code = it)) } })
                    SettingsSlider("Tables", sizes.table, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(table = it)) } })
                    SettingsSlider("Thinking", sizes.thinking, 11f..34f,
                        format = { "${it.toInt()} sp" },
                        onChange = { scope.launch { prefs.setTextSizes(sizes.copy(thinking = it)) } })
                }
            }

            item {
                SettingsSection("Text colours") {
                    val colors = settings.textColors
                    ColorSettingRow("AI text", colors.aiText, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(aiText = it)) }
                    }
                    ColorSettingRow("Your text", colors.userText, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(userText = it)) }
                    }
                    ColorSettingRow("Thinking text", colors.thinkingText, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(thinkingText = it)) }
                    }
                    ColorSettingRow("Headings", colors.heading, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(heading = it)) }
                    }
                    ColorSettingRow("Subheadings", colors.subheading, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(subheading = it)) }
                    }
                    ColorSettingRow("Links", colors.link, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(link = it)) }
                    }
                    ColorSettingRow("Code text", colors.codeText, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(codeText = it)) }
                    }
                    ColorSettingRow("Table text", colors.tableText, TextColors.UNSET) {
                        scope.launch { prefs.setTextColors(colors.copy(tableText = it)) }
                    }
                }
            }

            item {
                SettingsSection("Chat layout") {
                    SettingsSlider(
                        title = "Message spacing",
                        value = settings.messageSpacing,
                        range = 2f..32f,
                        format = { "${it.toInt()} dp" },
                        onChange = { scope.launch { prefs.setMessageSpacing(it) } }
                    )
                    SettingsSlider(
                        title = "Corner radius",
                        value = settings.messageCornerRadius,
                        range = 0f..32f,
                        format = { "${it.toInt()} dp" },
                        onChange = { scope.launch { prefs.setMessageCorner(it) } }
                    )
                    SettingsSlider(
                        title = "Message width",
                        value = settings.messageMaxWidthPercent,
                        range = 0.6f..1f,
                        format = { "${(it * 100).toInt()}%" },
                        onChange = { scope.launch { prefs.setMessageWidth(it) } }
                    )
                    OptionPills(
                        title = "Code theme",
                        options = CodeTheme.entries,
                        selected = settings.codeTheme,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setCodeTheme(it) } }
                    )
                    OptionPills(
                        title = "Table style",
                        options = TableStyle.entries,
                        selected = settings.tableStyle,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setTableStyle(it) } }
                    )
                    OptionPills(
                        title = "Animations",
                        subtitle = "Lower settings reduce rendering work while generating.",
                        options = AnimationLevel.entries,
                        selected = settings.animationLevel,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setAnimationLevel(it) } }
                    )
                    OptionPills(
                        title = "Emoji style",
                        subtitle = "Guides how freely the model uses emoji in its answers.",
                        options = EmojiStyle.entries,
                        selected = settings.emojiStyle,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setEmojiStyle(it) } }
                    )
                    SettingsRow(
                        title = "Reset appearance",
                        subtitle = "Restore the default theme, sizes and colours.",
                        onClick = {
                            scope.launch {
                                prefs.resetAppearance()
                                snackbarHost.showSnackbar("Appearance reset.")
                            }
                        }
                    )
                }
            }

            // ----------------------------------------------------------- Web
            item {
                SettingsSection("Web") {
                    SettingsSwitch(
                        title = "Web Search",
                        subtitle = if (settings.localOnlyMode)
                            "Disabled because Local-only mode is on."
                        else
                            "When on, your question is sent to the selected search provider " +
                                "and the results are given to the local model.",
                        checked = settings.webSearchEnabled,
                        enabled = !settings.localOnlyMode,
                        onChange = { scope.launch { prefs.setWebSearchEnabled(it) } }
                    )
                    OptionPills(
                        title = "Search provider",
                        subtitle = settings.searchProvider.privacyNote,
                        options = SearchProvider.entries,
                        selected = settings.searchProvider,
                        label = { it.label },
                        onSelect = { scope.launch { prefs.setSearchProvider(it) } }
                    )
                    SettingsSlider(
                        title = "Results per search",
                        value = settings.searchResultCount.toFloat(),
                        range = 1f..8f,
                        steps = 6,
                        format = { it.toInt().toString() },
                        onChange = { scope.launch { prefs.setSearchResultCount(it.toInt()) } }
                    )
                    SettingsSwitch(
                        title = "Show sources",
                        subtitle = "List the pages used underneath web-assisted answers.",
                        checked = settings.showSources,
                        onChange = { scope.launch { prefs.setShowSources(it) } }
                    )
                }
            }

            // ------------------------------------------------------- Privacy
            item {
                SettingsSection("Privacy") {
                    SettingsSwitch(
                        title = "Local-only mode",
                        subtitle = "Blocks every network feature, including web search.",
                        checked = settings.localOnlyMode,
                        onChange = { scope.launch { prefs.setLocalOnlyMode(it) } }
                    )
                    SettingsRow(
                        title = "Privacy Center",
                        subtitle = "See exactly what is stored and what can leave the device.",
                        onClick = onOpenPrivacy
                    )
                }
            }

            // --------------------------------------------------------- About
            item {
                SettingsSection("About") {
                    SettingsRow("PocketAI version", BuildConfig.VERSION_NAME)
                    SettingsRow(
                        title = "Inference engine",
                        subtitle = if (engineState.nativeAvailable)
                            "llama.cpp (GGUF) · ${engineState.acceleration}"
                        else "Unavailable on this device"
                    )
                    engineState.modelInfo?.let { info ->
                        SettingsRow(
                            title = "Loaded model",
                            subtitle = listOfNotNull(
                                info.architecture.takeIf { it.isNotBlank() },
                                "${info.layers} layers",
                                "context ${info.contextSize}",
                                "vocab ${info.vocabSize}"
                            ).joinToString(" · ")
                        )
                    }
                    SettingsRow(
                        title = "Device",
                        subtitle = "${caps.manufacturer} ${caps.deviceModel} · ${caps.socModel} · " +
                            "${DeviceCapabilities.formatGb(caps.totalRamBytes)} RAM · " +
                            "${ModelRepository.formatBytes(caps.availableStorageBytes)} free"
                    )
                    SettingsRow(
                        title = "Open-source licenses",
                        subtitle = "llama.cpp, Jetpack Compose, OkHttp, jsoup and others",
                        onClick = onOpenLicenses
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePicker(selectedId: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Theme", fontSize = 15.sp)
        Text(
            text = PocketThemes.byId(selectedId).description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PocketThemes.all.forEach { theme ->
                val active = theme.id == selectedId
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .width(66.dp)
                        .clickable { onSelect(theme.id) }
                ) {
                    Row(
                        modifier = Modifier
                            .height(38.dp)
                            .width(58.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        theme.swatch.forEach { swatch ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(swatch)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = theme.label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
