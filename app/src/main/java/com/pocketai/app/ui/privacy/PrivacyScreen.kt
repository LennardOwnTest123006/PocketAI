package com.pocketai.app.ui.privacy

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.ui.settings.SettingsRow
import com.pocketai.app.ui.settings.SettingsSection
import com.pocketai.app.ui.settings.SettingsSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrivacyStats(
    val conversationCount: Int = 0,
    val messageCount: Int = 0,
    val modelCount: Int = 0,
    val modelBytes: Long = 0,
    val freeStorage: Long = 0,
    val appBytes: Long = 0
)

class PrivacyViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PocketAiApplication).container
    val prefs = container.settingsRepository

    val settings = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.pocketai.app.data.repo.AppSettings())

    private val _stats = MutableStateFlow(PrivacyStats())
    val stats: StateFlow<PrivacyStats> = _stats.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val models = container.modelRepository.refresh()
            _stats.value = PrivacyStats(
                conversationCount = container.chatRepository.conversationCount(),
                messageCount = container.chatRepository.messageCount(),
                modelCount = models.size,
                modelBytes = models.sumOf { it.sizeBytes },
                freeStorage = container.deviceCapabilities().availableStorageBytes,
                appBytes = installedAppSize()
            )
        }
    }

    /** Size of the installed APK itself, so storage figures add up for the user. */
    private fun installedAppSize(): Long = runCatching {
        java.io.File(getApplication<Application>().applicationInfo.sourceDir).length()
    }.getOrDefault(0L)

    fun deleteConversations(onDone: (String) -> Unit) {
        viewModelScope.launch {
            container.chatRepository.deleteAllConversations()
            refresh()
            onDone("All conversations deleted from this device.")
        }
    }

    fun deleteModels(onDone: (String) -> Unit) {
        viewModelScope.launch {
            container.modelSession.unload()
            val count = container.modelRepository.deleteAll()
            container.settingsRepository.setSelectedModel(null)
            refresh()
            onDone("$count model file(s) deleted. Your chats were kept.")
        }
    }

    fun clearEverything(onDone: (String) -> Unit) {
        viewModelScope.launch {
            container.modelSession.unload()
            container.chatRepository.deleteAllConversations()
            container.modelRepository.deleteAll()
            container.settingsRepository.clearAll()
            refresh()
            onDone("All PocketAI data cleared.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(viewModel: PrivacyViewModel, onBack: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScopeCompat()
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Privacy Center") },
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
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Your AI runs on this phone",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "PocketAI generates every reply with a model stored on this " +
                                "device. There is no account, no sign-in, and your messages are " +
                                "never sent to a PocketAI server - none exists.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            item {
                SettingsSection("What leaves this device") {
                    PrivacyPoint(
                        icon = Icons.Outlined.Memory,
                        title = "Local AI",
                        body = "Nothing. Your message, the conversation history and any document " +
                            "you attach are processed by the on-device inference engine."
                    )
                    PrivacyPoint(
                        icon = Icons.Outlined.Language,
                        title = "Web Search",
                        body = if (settings.webSearchUsable)
                            "Currently ON. When you send a message, your question is sent to " +
                                "${settings.searchProvider.label} to fetch results. The results " +
                                "are then given to the local model."
                        else
                            "Currently OFF. No search request is made and nothing is sent out."
                    )
                    PrivacyPoint(
                        icon = Icons.Outlined.Storage,
                        title = "Model downloads",
                        body = "Only when you start one. The file is fetched over HTTPS directly " +
                            "from the model provider you chose."
                    )
                    PrivacyPoint(
                        icon = Icons.Outlined.Mic,
                        title = "Voice input",
                        body = "Dictation uses Android's own speech recognition. Depending on your " +
                            "device and language pack, that may process audio in the cloud - " +
                            "PocketAI only receives the finished text."
                    )
                }
            }

            item {
                SettingsSection("Stored on this device") {
                    SettingsRow(
                        "Conversations",
                        "${stats.conversationCount} chats · ${stats.messageCount} messages, " +
                            "in a private app database"
                    )
                    SettingsRow(
                        "Models",
                        "${stats.modelCount} file(s) · ${ModelRepository.formatBytes(stats.modelBytes)} " +
                            "in app-private storage"
                    )
                    SettingsRow(
                        "PocketAI itself",
                        ModelRepository.formatBytes(stats.appBytes) + " of application code"
                    )
                    SettingsRow(
                        "Free storage",
                        ModelRepository.formatBytes(stats.freeStorage)
                    )
                    SettingsRow(
                        "Analytics and ads",
                        "None. PocketAI contains no analytics SDK, no advertising SDK and no tracking."
                    )
                }
            }

            item {
                SettingsSection("Permissions PocketAI requests") {
                    PrivacyPoint(
                        icon = Icons.Outlined.Language,
                        title = "Internet",
                        body = "Used only for model downloads and, if you enable it, web search."
                    )
                    PrivacyPoint(
                        icon = Icons.Outlined.Notifications,
                        title = "Notifications",
                        body = "Shows download progress so a large model can finish in the background."
                    )
                    PrivacyPoint(
                        icon = Icons.Outlined.Mic,
                        title = "Microphone - not requested",
                        body = "PocketAI holds no microphone permission. Dictation hands off to " +
                            "Android's own speech recogniser, which asks for its own consent and " +
                            "returns only the finished text."
                    )
                }
            }

            item {
                SettingsSection("Controls") {
                    SettingsSwitch(
                        title = "Local-only mode",
                        subtitle = "Blocks all network features, including web search and downloads.",
                        checked = settings.localOnlyMode,
                        onChange = { value -> scope.launch { viewModel.prefs.setLocalOnlyMode(value) } }
                    )
                    SettingsRow(
                        title = "Delete all conversations",
                        subtitle = "Removes every chat and message. Models stay installed.",
                        onClick = { confirm = ConfirmAction.CONVERSATIONS }
                    )
                    SettingsRow(
                        title = "Delete all models",
                        subtitle = "Frees ${ModelRepository.formatBytes(stats.modelBytes)}. Your chats are kept.",
                        onClick = { confirm = ConfirmAction.MODELS }
                    )
                    SettingsRow(
                        title = "Clear all local data",
                        subtitle = "Chats, models and settings. This cannot be undone.",
                        onClick = { confirm = ConfirmAction.EVERYTHING }
                    )
                }
            }
        }
    }

    confirm?.let { action ->
        val (title, message, label) = when (action) {
            ConfirmAction.CONVERSATIONS -> Triple(
                "Delete all conversations?",
                "Every chat and message on this device will be permanently removed.",
                "Delete chats"
            )
            ConfirmAction.MODELS -> Triple(
                "Delete all models?",
                "All downloaded and imported model files will be removed. " +
                    "Your conversations are kept and you can download models again later.",
                "Delete models"
            )
            ConfirmAction.EVERYTHING -> Triple(
                "Clear all local data?",
                "This removes every conversation, every model file and all your settings. " +
                    "It cannot be undone.",
                "Clear everything"
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    val done: (String) -> Unit = { text ->
                        scope.launch { snackbarHost.showSnackbar(text) }
                    }
                    when (action) {
                        ConfirmAction.CONVERSATIONS -> viewModel.deleteConversations(done)
                        ConfirmAction.MODELS -> viewModel.deleteModels(done)
                        ConfirmAction.EVERYTHING -> viewModel.clearEverything(done)
                    }
                    confirm = null
                }) {
                    Text(label, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

private enum class ConfirmAction { CONVERSATIONS, MODELS, EVERYTHING }

@Composable
private fun PrivacyPoint(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

/** Static attribution for the third-party code PocketAI ships. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val entries = listOf(
        Triple("llama.cpp / ggml", "MIT License", "Georgi Gerganov and contributors - on-device GGUF inference"),
        Triple("Jetpack Compose", "Apache License 2.0", "The Android Open Source Project"),
        Triple("AndroidX (Room, DataStore, Lifecycle, Navigation)", "Apache License 2.0", "The Android Open Source Project"),
        Triple("Material Components / Material 3", "Apache License 2.0", "Google LLC"),
        Triple("Kotlin and kotlinx.coroutines", "Apache License 2.0", "JetBrains s.r.o."),
        Triple("kotlinx.serialization", "Apache License 2.0", "JetBrains s.r.o."),
        Triple("OkHttp", "Apache License 2.0", "Square, Inc."),
        Triple("jsoup", "MIT License", "Jonathan Hedley")
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "PocketAI is built on the following open-source projects. " +
                        "Model weights carry their own licences, shown in the model manager.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(entries.size) { index ->
                val (name, license, author) = entries[index]
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(license, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            author,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
