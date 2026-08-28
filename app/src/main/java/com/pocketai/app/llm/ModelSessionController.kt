package com.pocketai.app.llm

import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.model.InstalledModel
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionResult {
    data class Ready(val model: InstalledModel) : SessionResult
    data object NoModelInstalled : SessionResult
    data class Failed(val message: String) : SessionResult
}

/**
 * Decides which model should be resident and keeps the engine in sync with the
 * user's selection. Shared by the chat and the model manager so both screens
 * always agree on what is loaded.
 */
class ModelSessionController(
    private val engine: InferenceEngine,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val capabilities: () -> DeviceCapabilities
) {
    private val mutex = Mutex()

    /**
     * Loads the selected model if it is not already resident.
     * Reload is skipped when the right model is already in memory, so sending a
     * message never pays the load cost twice.
     */
    suspend fun ensureLoaded(
        settings: AppSettings,
        onProgress: (Float) -> Unit = {}
    ): SessionResult = mutex.withLock {
        // Fast path first: re-scanning the model directory and re-reading GGUF
        // headers on every message would add latency for no benefit.
        val loaded = engine.state.value.loadedModel
        if (loaded != null && (settings.selectedModelId == null || loaded.id == settings.selectedModelId)) {
            return SessionResult.Ready(loaded)
        }

        val installed = modelRepository.refresh()
        if (installed.isEmpty()) return SessionResult.NoModelInstalled

        val wanted = installed.firstOrNull { it.id == settings.selectedModelId }
            ?: installed.first()

        if (loaded != null && loaded.absolutePath == wanted.absolutePath) {
            return SessionResult.Ready(wanted)
        }
        if (settings.selectedModelId != wanted.id) {
            settingsRepository.setSelectedModel(wanted.id)
        }
        return when (val result = engine.loadModel(wanted, settings, capabilities(), onProgress)) {
            is LoadResult.Success -> SessionResult.Ready(wanted)
            is LoadResult.Failure -> SessionResult.Failed(result.message)
        }
    }

    /** Explicit load triggered from the model manager. */
    suspend fun load(
        model: InstalledModel,
        settings: AppSettings,
        onProgress: (Float) -> Unit = {}
    ): SessionResult = mutex.withLock {
        settingsRepository.setSelectedModel(model.id)
        when (val result = engine.loadModel(model, settings, capabilities(), onProgress)) {
            is LoadResult.Success -> SessionResult.Ready(model)
            is LoadResult.Failure -> SessionResult.Failed(result.message)
        }
    }

    /**
     * Pre-evaluates the (stable) system prompt into the KV cache.
     *
     * Costs nothing extra overall - those tokens have to be evaluated for the
     * first message anyway - but moves the work off the critical path so the
     * first reply's time-to-first-token is just the user's own tokens.
     * Cheap and idempotent once the prefix is already cached.
     */
    suspend fun warmUp(settings: AppSettings): WarmResult {
        val model = engine.state.value.loadedModel ?: return WarmResult(0, 0, 0)
        val prompt = SystemPrompt.build(
            settings = settings,
            modelLabel = model.displayName,
            mode = settings.responseMode
        )
        return engine.warmSystemPrompt(prompt)
    }

    suspend fun unload() = mutex.withLock { engine.unload() }

    /** Forces a reload so changed runtime settings (threads, context) take effect. */
    suspend fun reloadCurrent(settings: AppSettings): SessionResult = mutex.withLock {
        val current = engine.state.value.loadedModel ?: return SessionResult.NoModelInstalled
        engine.unload()
        when (val result = engine.loadModel(current, settings, capabilities())) {
            is LoadResult.Success -> SessionResult.Ready(current)
            is LoadResult.Failure -> SessionResult.Failed(result.message)
        }
    }
}
