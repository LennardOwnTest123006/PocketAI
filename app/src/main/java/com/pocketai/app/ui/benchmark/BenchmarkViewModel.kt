package com.pocketai.app.ui.benchmark

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.core.ThermalLevel
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.GenerationStats
import com.pocketai.app.llm.GenerationOutcome
import com.pocketai.app.llm.PromptTurn
import com.pocketai.app.llm.ResponseMode
import com.pocketai.app.llm.SessionResult
import com.pocketai.app.llm.SystemPrompt
import com.pocketai.app.data.repo.ChatRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One measured generation. */
data class BenchmarkRun(
    val label: String,
    val stats: GenerationStats,
    val threads: Int,
    val warmed: Boolean
) {
    val ttftSeconds: Double get() = stats.firstTokenMs / 1000.0
    val totalSeconds: Double get() = stats.totalMs / 1000.0
}

data class ThreadResult(val threads: Int, val tokensPerSecond: Double, val ttftMs: Long)

/** Static facts about the machine and the loaded model. */
data class BenchmarkEnvironment(
    val modelName: String = "-",
    val parameters: String = "-",
    val quantization: String = "-",
    val modelSizeBytes: Long = 0,
    val contextSize: Int = 0,
    val architecture: String = "-",
    val backend: String = "-",
    val devices: List<String> = emptyList(),
    val gpuAvailable: Boolean = false,
    val vulkanSupported: Boolean = false,
    val vulkanUsed: Boolean = false,
    val nnapiSupported: Boolean = false,
    val nnapiNote: String = "",
    val threads: Int = 0,
    val cpuCores: Int = 0,
    val performanceCores: Int = 0,
    val soc: String = "-",
    val totalRamBytes: Long = 0,
    val processMemoryBytes: Long = 0,
    val thermal: ThermalLevel = ThermalLevel.UNKNOWN
)

data class BenchmarkUiState(
    val environment: BenchmarkEnvironment = BenchmarkEnvironment(),
    val runs: List<BenchmarkRun> = emptyList(),
    val threadSweep: List<ThreadResult> = emptyList(),
    val running: Boolean = false,
    val statusText: String? = null,
    val error: String? = null,
    val message: String? = null
)

/**
 * Measures the real pipeline instead of describing it.
 *
 * Every number here comes from an actual generation on this device: nothing is
 * estimated, and the cold/warm pair exists specifically so the effect of
 * pre-evaluating the system prompt can be seen rather than claimed.
 */
class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PocketAiApplication).container
    private val engine = container.inferenceEngine
    private val session = container.modelSession

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(environment = readEnvironment())
        }
    }

    private fun readEnvironment(): BenchmarkEnvironment {
        val context = getApplication<Application>()
        val caps = container.deviceCapabilities()
        val state = engine.state.value
        val model = state.loadedModel
        val info = state.modelInfo
        val pm = context.packageManager

        val vulkanSupported = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        val gpuDevice = state.devices.firstOrNull { it.isGpu }

        return BenchmarkEnvironment(
            modelName = model?.displayName ?: "No model loaded",
            parameters = model?.parametersLabel ?: "-",
            quantization = model?.quantization ?: "-",
            modelSizeBytes = model?.sizeBytes ?: 0,
            contextSize = state.contextSize,
            architecture = info?.architecture?.takeIf { it.isNotBlank() } ?: "-",
            backend = state.acceleration,
            devices = state.devices.map { "${it.name} (${it.description})" },
            gpuAvailable = state.gpuAvailable,
            vulkanSupported = vulkanSupported,
            // Honest distinction: the driver existing is not the same as this
            // build shipping the Vulkan backend and ggml actually electing it.
            vulkanUsed = gpuDevice != null,
            nnapiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1,
            nnapiNote = "Not used - llama.cpp has no NNAPI backend, so PocketAI " +
                "does not claim one. Acceleration comes from ARM64 SIMD kernels.",
            threads = currentThreads(caps),
            cpuCores = caps.cpuCores,
            performanceCores = caps.performanceCores,
            soc = caps.socModel,
            totalRamBytes = caps.totalRamBytes,
            processMemoryBytes = processMemoryBytes(context),
            thermal = engine.thermalLevel()
        )
    }

    private fun currentThreads(caps: DeviceCapabilities): Int {
        val s = settings.value
        return if (s.threadOverride > 0) s.threadOverride.coerceAtMost(caps.cpuCores)
        else s.performanceMode.threadsFor(caps)
    }

    /** Total PSS of this process - the honest figure for "how much RAM is this using". */
    private fun processMemoryBytes(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        (info?.totalPss?.toLong() ?: 0L) * 1024L
    }.getOrDefault(0L)

    /**
     * Runs the same prompt twice: once with a cold cache and once after the
     * system prompt has been pre-evaluated. The gap between the two TTFT
     * figures is exactly what prefix warming buys.
     */
    fun runBenchmark() {
        if (_uiState.value.running) return
        job = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                running = true, runs = emptyList(), error = null, statusText = "Preparing model"
            )
            val current = settings.value
            when (val ready = session.ensureLoaded(current)) {
                SessionResult.NoModelInstalled -> {
                    finish("Install a model first - there is nothing to benchmark.")
                    return@launch
                }
                is SessionResult.Failed -> {
                    finish(ready.message)
                    return@launch
                }
                is SessionResult.Ready -> Unit
            }

            val caps = container.deviceCapabilities()
            val threads = currentThreads(caps)
            val results = ArrayList<BenchmarkRun>()

            // --- cold: nothing cached, so the prefill includes the system prompt
            _uiState.value = _uiState.value.copy(statusText = "Measuring cold start")
            engine.resetContext()
            runOnce(current, caps, "Cold cache", threads, warmed = false)?.let { results.add(it) }

            // --- warm: system prompt already evaluated
            _uiState.value = _uiState.value.copy(statusText = "Measuring with warm prefix")
            engine.resetContext()
            session.warmUp(current)
            runOnce(current, caps, "Warm prefix", threads, warmed = true)?.let { results.add(it) }

            // --- follow-up: the cache now also holds the previous exchange
            _uiState.value = _uiState.value.copy(statusText = "Measuring follow-up turn")
            runOnce(current, caps, "Follow-up turn", threads, warmed = true)?.let { results.add(it) }

            _uiState.value = _uiState.value.copy(
                runs = results,
                running = false,
                statusText = null,
                environment = readEnvironment()
            )
        }
    }

    private suspend fun runOnce(
        current: AppSettings,
        caps: DeviceCapabilities,
        label: String,
        threads: Int,
        warmed: Boolean
    ): BenchmarkRun? {
        val prompt = engine.buildPrompt(
            systemPrompt = SystemPrompt.build(
                current,
                engine.state.value.loadedModel?.displayName,
                ResponseMode.BALANCED
            ),
            turns = listOf(PromptTurn(ChatRole.USER, BENCHMARK_PROMPT)),
            reserveTokens = BENCHMARK_TOKENS
        )
        val outcome = engine.generate(prompt, current, caps, BENCHMARK_TOKENS) { }
        return when (outcome) {
            is GenerationOutcome.Success ->
                BenchmarkRun(label, outcome.stats, threads, warmed)
            is GenerationOutcome.Failure -> {
                _uiState.value = _uiState.value.copy(error = outcome.message)
                null
            }
        }
    }

    /**
     * Measures tokens/second at several thread counts instead of assuming more
     * threads is faster - past the big-core count it usually is not.
     */
    fun runThreadSweep() {
        if (_uiState.value.running) return
        job = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                running = true, threadSweep = emptyList(), error = null
            )
            val current = settings.value
            if (session.ensureLoaded(current) !is SessionResult.Ready) {
                finish("Load a model before sweeping thread counts.")
                return@launch
            }
            val caps = container.deviceCapabilities()
            val candidates = listOf(2, 4, 6, 8)
                .filter { it <= caps.cpuCores }
                .ifEmpty { listOf(caps.cpuCores) }

            val results = ArrayList<ThreadResult>()
            for (threads in candidates) {
                _uiState.value = _uiState.value.copy(statusText = "Testing $threads threads")
                engine.resetContext()
                session.warmUp(current)
                val probe = current.copy(threadOverride = threads)
                val prompt = engine.buildPrompt(
                    systemPrompt = SystemPrompt.build(
                        current,
                        engine.state.value.loadedModel?.displayName,
                        ResponseMode.BALANCED
                    ),
                    turns = listOf(PromptTurn(ChatRole.USER, BENCHMARK_PROMPT)),
                    reserveTokens = BENCHMARK_TOKENS
                )
                when (val outcome = engine.generate(prompt, probe, caps, BENCHMARK_TOKENS) { }) {
                    is GenerationOutcome.Success -> results.add(
                        ThreadResult(threads, outcome.stats.tokensPerSecond, outcome.stats.firstTokenMs)
                    )
                    is GenerationOutcome.Failure -> {
                        _uiState.value = _uiState.value.copy(error = outcome.message)
                    }
                }
                _uiState.value = _uiState.value.copy(threadSweep = results.toList())
            }
            _uiState.value = _uiState.value.copy(
                running = false,
                statusText = null,
                environment = readEnvironment()
            )
        }
    }

    /** Persists the fastest measured thread count. */
    fun applyBestThreads() {
        val best = _uiState.value.threadSweep.maxByOrNull { it.tokensPerSecond } ?: return
        viewModelScope.launch {
            container.settingsRepository.setThreadOverride(best.threads)
            _uiState.value = _uiState.value.copy(
                message = "Using ${best.threads} threads (%.1f tok/s measured).".format(best.tokensPerSecond),
                environment = readEnvironment()
            )
        }
    }

    fun stop() {
        engine.stop()
        job?.cancel()
        _uiState.value = _uiState.value.copy(running = false, statusText = null)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, message = null)
    }

    private fun finish(error: String) {
        _uiState.value = _uiState.value.copy(running = false, statusText = null, error = error)
    }

    private companion object {
        /** Fixed prompt so runs are comparable with each other and over time. */
        const val BENCHMARK_PROMPT = "In two sentences, explain what a language model is."
        const val BENCHMARK_TOKENS = 64
    }
}
