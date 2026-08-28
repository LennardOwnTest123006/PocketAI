package com.pocketai.app.llm

import android.content.Context
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.core.PerformanceMode
import com.pocketai.app.core.ThermalLevel
import com.pocketai.app.core.ThermalMonitor
import com.pocketai.app.data.model.InstalledModel
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.repo.GenerationStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class NativeModelInfo(
    val description: String = "",
    val architecture: String = "",
    val name: String = "",
    val parameters: Long = 0,
    val sizeBytes: Long = 0,
    val trainedContext: Int = 0,
    val contextSize: Int = 0,
    val layers: Int = 0,
    val vocabSize: Int = 0,
    val hasChatTemplate: Boolean = false
)

data class BackendDevice(
    val name: String,
    val description: String,
    val isGpu: Boolean,
    val totalBytes: Long
)

data class EngineState(
    val nativeAvailable: Boolean = false,
    val initialized: Boolean = false,
    val loadedModel: InstalledModel? = null,
    val isLoading: Boolean = false,
    val loadProgress: Float = 0f,
    val isGenerating: Boolean = false,
    val contextSize: Int = 0,
    val devices: List<BackendDevice> = emptyList(),
    val gpuAvailable: Boolean = false,
    val modelInfo: NativeModelInfo? = null,
    val lastError: String? = null
) {
    val isReady: Boolean get() = initialized && loadedModel != null && !isLoading
    val acceleration: String
        get() = when {
            !nativeAvailable -> "unavailable"
            gpuAvailable -> "CPU + GPU (Vulkan)"
            else -> "CPU (ARM64 optimised)"
        }
}

data class WarmResult(val tokens: Int, val cachedTokens: Int, val millis: Long) {
    val evaluated: Int get() = (tokens - cachedTokens).coerceAtLeast(0)
}

sealed interface LoadResult {
    data class Success(val info: NativeModelInfo) : LoadResult
    data class Failure(val message: String) : LoadResult
}

sealed interface GenerationOutcome {
    data class Success(val stats: GenerationStats) : GenerationOutcome
    data class Failure(val message: String, val recoverable: Boolean = true) : GenerationOutcome
}

/** One turn handed to the chat template. */
data class PromptTurn(val role: ChatRole, val content: String)

/**
 * Owns the native llama.cpp session: loading, prompt assembly, streaming
 * generation and cancellation.
 *
 * All native calls are funnelled onto a single dedicated thread so the JNI
 * session is never touched concurrently, and so the UI thread never blocks.
 */
class InferenceEngine(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocketai-inference").apply { priority = Thread.MAX_PRIORITY }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val loadMutex = Mutex()
    private val thermal = ThermalMonitor(context)

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val isGenerating: Boolean get() = _state.value.isGenerating

    suspend fun initialize(): Boolean = withContext(dispatcher) {
        if (_state.value.initialized) return@withContext true
        if (!LlamaNative.loadLibrary()) {
            _state.value = _state.value.copy(
                nativeAvailable = false,
                lastError = "The on-device inference engine could not be loaded " +
                    "(${LlamaNative.loadError ?: "unknown reason"}). This build may not " +
                    "support your CPU architecture."
            )
            return@withContext false
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        val ok = runCatching { LlamaNative.nativeInit(nativeDir) }.getOrElse {
            _state.value = _state.value.copy(
                nativeAvailable = true,
                initialized = false,
                lastError = "Inference backend failed to start: ${it.message}"
            )
            return@withContext false
        }
        val devices = readDevices()
        _state.value = _state.value.copy(
            nativeAvailable = true,
            initialized = ok,
            devices = devices,
            gpuAvailable = devices.any { it.isGpu },
            lastError = if (ok) null else "Inference backend failed to start."
        )
        ok
    }

    private fun readDevices(): List<BackendDevice> = runCatching {
        val root = JSONObject(LlamaNative.nativeBackendInfo())
        val array = root.optJSONArray("devices") ?: return@runCatching emptyList()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            BackendDevice(
                name = o.optString("name"),
                description = o.optString("description"),
                isGpu = o.optInt("type") == 1,
                totalBytes = o.optLong("totalBytes")
            )
        }
    }.getOrDefault(emptyList())

    suspend fun loadModel(
        model: InstalledModel,
        settings: AppSettings,
        caps: DeviceCapabilities,
        onProgress: (Float) -> Unit = {}
    ): LoadResult = loadMutex.withLock {
        if (!initialize()) {
            return LoadResult.Failure(_state.value.lastError ?: "Inference engine unavailable.")
        }
        val file = File(model.absolutePath)
        if (!file.exists()) {
            return LoadResult.Failure("The model file is missing. It may have been deleted.")
        }

        _state.value = _state.value.copy(isLoading = true, loadProgress = 0f, lastError = null)
        releaseHandle()

        val threads = resolveThreads(settings, caps)
        val requestedCtx = settings.generation.contextLength
            .coerceAtMost(settings.performanceMode.contextCeiling())
            .coerceAtLeast(512)
        val trainedCap = if (model.trainedContextLength > 0)
            model.trainedContextLength else requestedCtx
        val nCtx = requestedCtx.coerceAtMost(trainedCap)
        val gpuLayers = if (_state.value.gpuAvailable) settings.gpuLayers else 0

        val result = withContext(dispatcher) {
            runCatching {
                LlamaNative.nativeLoadModel(
                    path = file.absolutePath,
                    nCtx = nCtx,
                    nThreads = threads,
                    nGpuLayers = gpuLayers,
                    useMmap = settings.useMmap,
                    useMlock = settings.useMlock,
                    flashAttn = settings.flashAttention,
                    progress = LoadProgressListener { p ->
                        val clamped = p.coerceIn(0f, 1f)
                        _state.value = _state.value.copy(loadProgress = clamped)
                        onProgress(clamped)
                    }
                )
            }
        }

        val newHandle = result.getOrDefault(0L)
        if (newHandle == 0L) {
            _state.value = _state.value.copy(isLoading = false, loadProgress = 0f)
            val reason = result.exceptionOrNull()?.message
            return LoadResult.Failure(
                reason ?: "This model could not be loaded. It may be corrupted, an " +
                    "unsupported GGUF variant, or too large for the memory available " +
                    "(${DeviceCapabilities.formatGb(caps.totalRamBytes)} total RAM)."
            )
        }

        handle = newHandle
        val info = withContext(dispatcher) { readModelInfo(newHandle) }
        _state.value = _state.value.copy(
            loadedModel = model,
            isLoading = false,
            loadProgress = 1f,
            contextSize = info.contextSize,
            modelInfo = info,
            lastError = null
        )
        return LoadResult.Success(info)
    }

    private fun readModelInfo(h: Long): NativeModelInfo = runCatching {
        val o = JSONObject(LlamaNative.nativeModelInfo(h))
        NativeModelInfo(
            description = o.optString("description"),
            architecture = o.optString("architecture"),
            name = o.optString("name"),
            parameters = o.optLong("parameters"),
            sizeBytes = o.optLong("sizeBytes"),
            trainedContext = o.optInt("nCtxTrain"),
            contextSize = o.optInt("nCtx"),
            layers = o.optInt("nLayer"),
            vocabSize = o.optInt("vocabSize"),
            hasChatTemplate = o.optBoolean("hasChatTemplate")
        )
    }.getOrDefault(NativeModelInfo())

    suspend fun unload() = loadMutex.withLock {
        withContext(dispatcher) { releaseHandle() }
        _state.value = _state.value.copy(
            loadedModel = null,
            contextSize = 0,
            modelInfo = null,
            loadProgress = 0f
        )
    }

    private fun releaseHandle() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { LlamaNative.nativeFreeModel(h) }
        }
    }

    /** Cancels the running generation. Safe to call from any thread. */
    fun stop() {
        val h = handle
        if (h != 0L) runCatching { LlamaNative.nativeRequestStop(h) }
    }

    /**
     * Evaluates [prefix] into the KV cache ahead of time.
     *
     * The system prompt is ~400 tokens and is identical for every message, so
     * paying for it once in the background turns the first reply's prefill into
     * just the user's own tokens. Safe to call repeatedly - an already-cached
     * prefix costs nothing.
     */
    suspend fun warmPrefix(prefix: String): WarmResult = withContext(dispatcher) {
        val h = handle
        if (h == 0L || prefix.isBlank()) return@withContext WarmResult(0, 0, 0)
        runCatching {
            val o = JSONObject(LlamaNative.nativeWarmPrefix(h, prefix))
            if (o.has("error")) WarmResult(0, 0, 0)
            else WarmResult(
                tokens = o.optInt("tokens"),
                cachedTokens = o.optInt("cachedTokens"),
                millis = o.optLong("ms")
            )
        }.getOrDefault(WarmResult(0, 0, 0))
    }

    /** Drops the KV cache, e.g. when starting a brand new conversation. */
    suspend fun resetContext() = withContext(dispatcher) {
        val h = handle
        if (h != 0L) runCatching { LlamaNative.nativeResetContext(h) }
        Unit
    }

    suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        val h = handle
        if (h == 0L) -1 else runCatching { LlamaNative.nativeTokenCount(h, text) }.getOrDefault(-1)
    }

    /**
     * Threads for this request, after thermal headroom.
     *
     * A throttled SoC delivers fewer tokens per second at full width than a
     * cool one does at reduced width, so backing off is a speed decision as
     * much as a temperature one.
     */
    private fun resolveThreads(settings: AppSettings, caps: DeviceCapabilities): Int {
        val configured = if (settings.threadOverride > 0) {
            settings.threadOverride.coerceAtMost(caps.cpuCores)
        } else {
            settings.performanceMode.threadsFor(caps)
        }
        return thermal.adjustThreads(configured)
    }

    /** Current thermal state, surfaced in the benchmark screen. */
    fun thermalLevel(): ThermalLevel = thermal.current()

    /**
     * Renders the conversation with the model's own chat template, trimming the
     * oldest turns until the prompt fits the context window.
     *
     * The system message is never dropped, so PocketAI's identity and the web
     * search context survive trimming.
     */
    suspend fun buildPrompt(
        systemPrompt: String,
        turns: List<PromptTurn>,
        reserveTokens: Int
    ): String = withContext(dispatcher) {
        val h = handle
        val ctx = if (h != 0L) LlamaNative.nativeContextSize(h) else 4096
        val budget = (ctx - reserveTokens - 64).coerceAtLeast(256)

        // Trimming happens from the middle so the system prompt and opening
        // exchange stay byte-identical, which keeps them in the KV cache.
        val selection = ContextWindow.select(turns, budget) { window ->
            val candidate = render(h, systemPrompt, window)
            if (h == 0L) candidate.length / 4
            else runCatching { LlamaNative.nativeTokenCount(h, candidate) }
                .getOrDefault(candidate.length / 4)
        }
        render(h, systemPrompt, selection.turns)
    }

    private fun render(h: Long, systemPrompt: String, turns: List<PromptTurn>): String {
        val roles = ArrayList<String>(turns.size + 1)
        val contents = ArrayList<String>(turns.size + 1)
        if (systemPrompt.isNotBlank()) {
            roles.add("system"); contents.add(systemPrompt)
        }
        turns.forEach { roles.add(it.role.wire); contents.add(it.content) }

        if (h != 0L) {
            val templated = runCatching {
                LlamaNative.nativeApplyChatTemplate(
                    h, roles.toTypedArray(), contents.toTypedArray(), true
                )
            }.getOrNull()
            if (!templated.isNullOrBlank()) return templated
        }
        // Fallback for GGUF files that carry no chat template at all.
        return buildString {
            for (i in roles.indices) {
                append("<|im_start|>").append(roles[i]).append('\n')
                append(contents[i]).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Streams a completion. [onToken] is invoked on the inference thread for
     * every decoded piece of text, so callers must not block inside it.
     */
    suspend fun generate(
        prompt: String,
        settings: AppSettings,
        caps: DeviceCapabilities,
        /** Ceiling for this specific request; see ResponseMode.budgetFor. */
        maxTokens: Int = settings.generation.maxOutputTokens,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        val h = handle
        if (h == 0L) {
            return GenerationOutcome.Failure("No model is loaded. Choose one in Models first.", false)
        }
        _state.value = _state.value.copy(isGenerating = true)
        return try {
            val raw = withContext(dispatcher) {
                LlamaNative.nativeGenerate(
                    handle = h,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = settings.generation.temperature,
                    topP = settings.generation.topP,
                    topK = settings.generation.topK,
                    minP = settings.generation.minP,
                    repeatPenalty = settings.generation.repeatPenalty,
                    repeatLastN = settings.generation.repeatLastN,
                    seed = settings.generation.seed,
                    nThreads = resolveThreads(settings, caps),
                    callback = TokenListener { piece -> onToken(piece) }
                )
            }
            parseOutcome(raw)
        } catch (t: Throwable) {
            GenerationOutcome.Failure(t.message ?: "Generation failed unexpectedly.")
        } finally {
            _state.value = _state.value.copy(isGenerating = false)
        }
    }

    private fun parseOutcome(raw: String): GenerationOutcome = runCatching {
        val o = JSONObject(raw)
        val error = o.optString("error").takeIf { it.isNotBlank() }
        if (error != null) {
            return@runCatching GenerationOutcome.Failure(
                when (error) {
                    "no_model" -> "No model is loaded."
                    "busy" -> "The model is already generating a reply."
                    "empty_prompt" -> "There was nothing to send to the model."
                    "context_overflow" -> {
                        val used = o.optInt("promptTokens")
                        val ctx = o.optInt("nCtx")
                        "This conversation no longer fits in the context window " +
                            "($used tokens, limit $ctx). Start a new chat or raise the " +
                            "context length in Settings."
                    }
                    "decode_failed" -> "The model failed while processing this prompt. " +
                        "This usually means the device ran out of memory."
                    else -> "Generation failed ($error)."
                }
            )
        }
        GenerationOutcome.Success(
            GenerationStats(
                promptTokens = o.optInt("promptTokens"),
                cachedTokens = o.optInt("cachedTokens"),
                evaluatedTokens = o.optInt("evaluatedTokens"),
                generatedTokens = o.optInt("generatedTokens"),
                firstTokenMs = o.optLong("firstTokenMs"),
                promptMs = o.optLong("promptMs"),
                decodeMs = o.optLong("decodeMs"),
                totalMs = o.optLong("totalMs"),
                promptTokensPerSecond = o.optDouble("promptTokensPerSecond", 0.0),
                tokensPerSecond = o.optDouble("tokensPerSecond", 0.0),
                stopReason = o.optString("stopReason")
            )
        )
    }.getOrElse { GenerationOutcome.Failure("Could not read the generation result.") }

    fun shutdown() {
        releaseHandle()
        runCatching { executor.shutdownNow() }
    }
}
