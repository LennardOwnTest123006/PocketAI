package com.pocketai.app.data.model

import com.pocketai.app.core.DeviceCapabilities

/**
 * A model PocketAI knows how to fetch.
 *
 * Sizes here are the published figures and are only used to warn the user up
 * front; the downloader always re-checks the real Content-Length before it
 * writes anything, so the progress UI never shows an invented number.
 */
/**
 * What a model is *for*. Bigger is not better on a phone: total response time
 * is generated_tokens / decode_speed, so a 4B model answering a one-line
 * question is slower in a way the user feels and no smarter in a way they see.
 */
enum class ModelTier(val label: String, val summary: String) {
    FASTEST("Fastest", "Answers in about a second. Best for quick questions and dictation."),
    BALANCED("Balanced", "The best intelligence-per-second trade-off on a modern phone."),
    SMARTEST("Smartest", "Strongest reasoning, noticeably slower. Worth it for hard problems.")
}

data class CatalogModel(
    val id: String,
    val displayName: String,
    val publisher: String,
    val description: String,
    val parametersLabel: String,
    val quantization: String,
    val approxSizeBytes: Long,
    val minRamGb: Double,
    val recommendedRamGb: Double,
    val contextLength: Int,
    val supportsThinking: Boolean,
    val tier: ModelTier,
    /**
     * Rough decode speed on a current flagship (Snapdragon 8 Gen 3 class),
     * used only for ordering and for setting expectations in the UI. The
     * benchmark screen measures the real figure for the actual device.
     */
    val estimatedTokensPerSecond: Int,
    val license: String,
    val downloadUrl: String,
    val fileName: String
) {
    /** RAM the weights plus a working KV cache will occupy while loaded. */
    val estimatedRamBytes: Long
        get() = (approxSizeBytes * 1.25).toLong() + 220L * 1024 * 1024

    fun fits(caps: DeviceCapabilities): ModelFit {
        val storageOk = caps.availableStorageBytes > approxSizeBytes * 1.1
        val ramGb = caps.totalRamGb
        return when {
            !storageOk -> ModelFit.NOT_ENOUGH_STORAGE
            ramGb < minRamGb -> ModelFit.NOT_ENOUGH_RAM
            ramGb < recommendedRamGb -> ModelFit.TIGHT
            else -> ModelFit.GOOD
        }
    }
}

enum class ModelFit { GOOD, TIGHT, NOT_ENOUGH_RAM, NOT_ENOUGH_STORAGE }

/**
 * Curated, openly licensed GGUF builds that run well on a phone.
 *
 * All entries are public downloads - PocketAI never ships or requires an API key.
 */
object ModelCatalog {

    private const val MB = 1024L * 1024L

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen2.5-0.5b-instruct-q4km",
            displayName = "Qwen2.5 0.5B Instruct",
            publisher = "Alibaba Qwen",
            description = "The quickest way to try PocketAI. Tiny, loads in a second and still handles chat, rewriting and short summaries.",
            parametersLabel = "0.5B",
            quantization = "Q4_K_M",
            approxSizeBytes = 398 * MB,
            minRamGb = 2.0,
            recommendedRamGb = 3.0,
            contextLength = 32768,
            supportsThinking = false,
            tier = ModelTier.FASTEST,
            estimatedTokensPerSecond = 45,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "llama3.2-1b-instruct-q4km",
            displayName = "Llama 3.2 1B Instruct",
            publisher = "Meta",
            description = "A well-rounded small model. Good general chat quality with very low latency.",
            parametersLabel = "1.2B",
            quantization = "Q4_K_M",
            approxSizeBytes = 808 * MB,
            minRamGb = 3.0,
            recommendedRamGb = 4.0,
            contextLength = 131072,
            supportsThinking = false,
            tier = ModelTier.FASTEST,
            estimatedTokensPerSecond = 30,
            license = "Llama 3.2 Community License",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "qwen3-1.7b-q4km",
            displayName = "Qwen3 1.7B",
            publisher = "Alibaba Qwen",
            description = "Emits real step-by-step reasoning inside <think> blocks, which PocketAI shows in the collapsible Thinking section.",
            parametersLabel = "1.7B",
            quantization = "Q4_K_M",
            approxSizeBytes = 1112 * MB,
            minRamGb = 4.0,
            recommendedRamGb = 6.0,
            contextLength = 32768,
            supportsThinking = true,
            tier = ModelTier.BALANCED,
            estimatedTokensPerSecond = 20,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "qwen2.5-1.5b-instruct-q4km",
            displayName = "Qwen2.5 1.5B Instruct",
            publisher = "Alibaba Qwen",
            description = "Strong multilingual chat and summarisation for its size. A good default on 6 GB phones.",
            parametersLabel = "1.5B",
            quantization = "Q4_K_M",
            approxSizeBytes = 1120 * MB,
            minRamGb = 4.0,
            recommendedRamGb = 6.0,
            contextLength = 32768,
            supportsThinking = false,
            tier = ModelTier.BALANCED,
            estimatedTokensPerSecond = 22,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "llama3.2-3b-instruct-q4km",
            displayName = "Llama 3.2 3B Instruct",
            publisher = "Meta",
            description = "Noticeably better reasoning and writing than the 1B. Recommended on 8 GB flagships such as the Galaxy Z Flip6.",
            parametersLabel = "3.2B",
            quantization = "Q4_K_M",
            approxSizeBytes = 2019 * MB,
            minRamGb = 6.0,
            recommendedRamGb = 8.0,
            contextLength = 131072,
            supportsThinking = false,
            tier = ModelTier.SMARTEST,
            estimatedTokensPerSecond = 13,
            license = "Llama 3.2 Community License",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "qwen3-4b-q4km",
            displayName = "Qwen3 4B",
            publisher = "Alibaba Qwen",
            description = "The strongest reasoning model in the catalogue, with genuine <think> output. Needs a flagship with 8 GB or more.",
            parametersLabel = "4.0B",
            quantization = "Q4_K_M",
            approxSizeBytes = 2500 * MB,
            minRamGb = 8.0,
            recommendedRamGb = 12.0,
            contextLength = 32768,
            supportsThinking = true,
            tier = ModelTier.SMARTEST,
            estimatedTokensPerSecond = 9,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true",
            fileName = "Qwen3-4B-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "phi3.5-mini-q4km",
            displayName = "Phi-3.5 Mini Instruct",
            publisher = "Microsoft",
            description = "Punches above its weight on reasoning and code. A solid all-rounder when you have the RAM for it.",
            parametersLabel = "3.8B",
            quantization = "Q4_K_M",
            approxSizeBytes = 2393 * MB,
            minRamGb = 6.0,
            recommendedRamGb = 8.0,
            contextLength = 131072,
            supportsThinking = false,
            tier = ModelTier.SMARTEST,
            estimatedTokensPerSecond = 11,
            license = "MIT",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf"
        ),
        CatalogModel(
            id = "smollm2-1.7b-instruct-q4km",
            displayName = "SmolLM2 1.7B Instruct",
            publisher = "Hugging Face",
            description = "Built specifically for on-device use. Fast, chatty and light on memory.",
            parametersLabel = "1.7B",
            quantization = "Q4_K_M",
            approxSizeBytes = 1060 * MB,
            minRamGb = 4.0,
            recommendedRamGb = 6.0,
            contextLength = 8192,
            supportsThinking = false,
            tier = ModelTier.FASTEST,
            estimatedTokensPerSecond = 24,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf"
        )
    )

    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }

    /**
     * Models this handset can actually run, best first.
     * Nothing that would exhaust RAM is ever suggested.
     */
    fun recommendedFor(caps: DeviceCapabilities): List<CatalogModel> =
        models.filter { it.fits(caps) == ModelFit.GOOD }
            .sortedByDescending { it.estimatedTokensPerSecond }

    /** Models of a given tier that this device can actually run. */
    fun tier(tier: ModelTier, caps: DeviceCapabilities): List<CatalogModel> =
        recommendedFor(caps).filter { it.tier == tier }
            .sortedByDescending { it.estimatedTokensPerSecond }

    /**
     * The single model PocketAI proposes during first-run setup.
     *
     * Deliberately not "the largest that fits". Response time is dominated by
     * decode speed, and a 4B model spends four times as long saying the same
     * thing as a 1.5B for most everyday questions. The default is the Balanced
     * tier - fast enough to feel immediate, capable enough to be useful - and
     * only falls back to the Fastest tier on constrained devices.
     */
    fun defaultFor(caps: DeviceCapabilities): CatalogModel {
        tier(ModelTier.BALANCED, caps).firstOrNull()?.let { return it }
        tier(ModelTier.FASTEST, caps).firstOrNull()?.let { return it }
        val good = recommendedFor(caps)
        return good.minByOrNull { it.approxSizeBytes }
            ?: models.minByOrNull { it.approxSizeBytes }
            ?: models.first()
    }
}
