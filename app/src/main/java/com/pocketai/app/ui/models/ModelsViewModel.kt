package com.pocketai.app.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.model.CatalogModel
import com.pocketai.app.data.model.ImportResult
import com.pocketai.app.data.model.InstalledModel
import com.pocketai.app.data.model.ModelCatalog
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.llm.SessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(
    val installed: List<InstalledModel> = emptyList(),
    val capabilities: DeviceCapabilities? = null,
    val busyMessage: String? = null,
    val loadProgress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
    val importProgress: Float? = null
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PocketAiApplication).container
    private val repository = container.modelRepository
    private val session = container.modelSession
    private val engine = container.inferenceEngine

    val settingsPrefs: com.pocketai.app.data.repo.SettingsRepository = container.settingsRepository

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val engineState = engine.state
    val downloadState = container.downloadManager.state

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    val catalog: List<CatalogModel> = ModelCatalog.models

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val installed = repository.refresh()
            _uiState.value = _uiState.value.copy(
                installed = installed,
                capabilities = container.deviceCapabilities()
            )
        }
    }

    fun download(model: CatalogModel) {
        container.downloadManager.start(
            url = model.downloadUrl,
            fileName = model.fileName,
            displayName = model.displayName,
            catalogId = model.id,
            expectedBytes = model.approxSizeBytes
        )
    }

    /** Downloads a GGUF the user pasted a direct link to. */
    fun downloadCustom(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(
                error = "Model links must start with https:// so the download is encrypted."
            )
            return
        }
        val name = trimmed.substringAfterLast('/').substringBefore('?')
            .ifBlank { "custom-model.gguf" }
        if (!name.endsWith(".gguf", true)) {
            _uiState.value = _uiState.value.copy(
                error = "That link does not point at a .gguf file."
            )
            return
        }
        container.downloadManager.start(
            url = trimmed,
            fileName = name,
            displayName = name.removeSuffix(".gguf"),
            catalogId = null
        )
    }

    fun pauseDownload() = container.downloadManager.pause()

    fun resumeDownload(model: CatalogModel?) {
        val state = downloadState.value
        val url = model?.downloadUrl
        if (url != null) {
            container.downloadManager.start(
                url = url,
                fileName = state.fileName,
                displayName = state.displayName,
                catalogId = state.catalogId,
                expectedBytes = state.totalBytes
            )
        }
    }

    fun cancelDownload() = container.downloadManager.cancel()

    fun dismissDownload() {
        container.downloadManager.clearFinished()
        refresh()
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busyMessage = "Importing model", importProgress = 0f)
            val result = repository.importFromUri(uri) { copied, total ->
                val progress = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
                _uiState.value = _uiState.value.copy(importProgress = progress)
            }
            _uiState.value = when (result) {
                is ImportResult.Success -> _uiState.value.copy(
                    busyMessage = null,
                    importProgress = null,
                    message = "${result.model.displayName} imported."
                )
                is ImportResult.Failed -> _uiState.value.copy(
                    busyMessage = null,
                    importProgress = null,
                    error = result.message
                )
            }
            refresh()
        }
    }

    fun load(model: InstalledModel) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busyMessage = "Loading ${model.displayName}", loadProgress = 0f)
            val result = session.load(model, settings.value) { progress ->
                _uiState.value = _uiState.value.copy(loadProgress = progress)
            }
            if (result is SessionResult.Ready) {
                // Evaluate the system prompt now so the first message is fast.
                _uiState.value = _uiState.value.copy(busyMessage = "Warming up")
                session.warmUp(settings.value)
            }
            _uiState.value = when (result) {
                is SessionResult.Ready -> _uiState.value.copy(
                    busyMessage = null,
                    message = "${model.displayName} is ready."
                )
                is SessionResult.Failed -> _uiState.value.copy(
                    busyMessage = null,
                    error = result.message
                )
                SessionResult.NoModelInstalled -> _uiState.value.copy(busyMessage = null)
            }
        }
    }

    fun unload() {
        viewModelScope.launch {
            session.unload()
            _uiState.value = _uiState.value.copy(message = "Model unloaded and memory released.")
        }
    }

    fun delete(model: InstalledModel) {
        viewModelScope.launch {
            if (engine.state.value.loadedModel?.absolutePath == model.absolutePath) {
                session.unload()
            }
            val removed = repository.delete(model)
            _uiState.value = _uiState.value.copy(
                message = if (removed) "${model.displayName} deleted. Your chats were kept."
                else null,
                error = if (removed) null else "The model file could not be deleted."
            )
            refresh()
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
