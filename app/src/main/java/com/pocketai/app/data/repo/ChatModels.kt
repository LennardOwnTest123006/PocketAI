package com.pocketai.app.data.repo

import kotlinx.serialization.Serializable

enum class ChatRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromWire(value: String): ChatRole =
            entries.firstOrNull { it.wire == value } ?: USER
    }
}

@Serializable
data class WebSource(
    val title: String,
    val url: String,
    val snippet: String = ""
) {
    val host: String
        get() = runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }
            .getOrDefault(url)
}

/** Timings reported by the native engine for one generation. */
data class GenerationStats(
    val promptTokens: Int = 0,
    /** Tokens served straight from the KV cache, i.e. not re-evaluated. */
    val cachedTokens: Int = 0,
    /** Tokens the prefill actually had to run through the model. */
    val evaluatedTokens: Int = 0,
    val generatedTokens: Int = 0,
    val firstTokenMs: Long = 0,
    val promptMs: Long = 0,
    val decodeMs: Long = 0,
    val totalMs: Long = 0,
    val promptTokensPerSecond: Double = 0.0,
    val tokensPerSecond: Double = 0.0,
    val stopReason: String = ""
) {
    val hasData: Boolean get() = generatedTokens > 0 || totalMs > 0

    /** Share of the prompt that the cache covered, for the benchmark screen. */
    val cacheHitRatio: Double
        get() = if (promptTokens > 0) cachedTokens.toDouble() / promptTokens else 0.0
}

data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long = 0,
    val role: ChatRole,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val modelName: String? = null,
    val stats: GenerationStats = GenerationStats(),
    val usedWebSearch: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    val attachmentName: String? = null,
    val isError: Boolean = false
)

data class Conversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val favorite: Boolean,
    val modelId: String?,
    val messageCount: Int,
    val preview: String
)
