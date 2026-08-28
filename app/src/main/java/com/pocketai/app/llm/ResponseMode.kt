package com.pocketai.app.llm

/**
 * How much the model is asked to *produce*, as opposed to how hard the CPU is
 * driven (that is [com.pocketai.app.core.PerformanceMode]).
 *
 * This is the single biggest lever on end-to-end latency. Total time is
 * dominated by `generated_tokens / decode_speed`, so a 1024-token ceiling on a
 * model that decodes at ~10 tok/s is ~100 seconds of unavoidable work no matter
 * how well the engine is tuned.
 *
 * The budget is a *ceiling*, never a truncation point: the model still stops at
 * its own end-of-turn token, and a genuinely long answer is allowed to be long.
 * What changes is that PocketAI stops asking short questions to produce essays.
 */
enum class ResponseMode(
    val id: String,
    val label: String,
    val description: String,
    /** Upper bound on generated tokens for this mode. */
    val maxTokens: Int,
    /** Ask reasoning-capable models to skip their thinking pass. */
    val suppressReasoning: Boolean,
    /** Appended to the system prompt to steer length. */
    val styleHint: String
) {
    FAST(
        id = "fast",
        label = "Fast",
        description = "Shortest useful answer, reasoning turned off. Best for quick questions.",
        maxTokens = 320,
        suppressReasoning = true,
        styleHint = "Answer as directly and briefly as the question allows. Skip preamble, " +
            "restating the question, and closing summaries. Two or three sentences is usually right."
    ),
    BALANCED(
        id = "balanced",
        label = "Balanced",
        description = "Recommended. Complete answers without padding.",
        maxTokens = 640,
        suppressReasoning = false,
        styleHint = "Be complete but concise. Do not pad the answer to seem thorough."
    ),
    THINKING(
        id = "thinking",
        label = "Thinking",
        description = "Lets reasoning models work through the problem first. Slowest.",
        maxTokens = 1536,
        suppressReasoning = false,
        styleHint = "Take the space you need to reason carefully, then give a clear final answer."
    );

    /**
     * Picks a budget for one specific question.
     *
     * A three-word question does not need the same ceiling as "write a detailed
     * comparison", and the ceiling is what the model quietly fills when it has
     * nothing better to do.
     */
    fun budgetFor(userMessage: String, userCeiling: Int): Int {
        val text = userMessage.lowercase()
        val wantsDetail = DETAIL_HINTS.any { text.contains(it) }
        val looksShort = userMessage.length < 120 && !wantsDetail

        val budget = when {
            wantsDetail -> maxTokens
            looksShort -> (maxTokens / 2).coerceAtLeast(160)
            else -> maxTokens
        }
        return budget.coerceAtMost(userCeiling).coerceAtLeast(64)
    }

    /**
     * Qwen3 and friends accept `/no_think` as a documented per-turn switch that
     * skips the reasoning pass. Applied only when the loaded model actually
     * emits reasoning, so it never leaks into an unrelated model's prompt.
     */
    fun applyReasoningSwitch(userMessage: String, modelSupportsThinking: Boolean): String {
        if (!modelSupportsThinking) return userMessage
        val marker = if (suppressReasoning) "/no_think" else "/think"
        if (userMessage.contains("/no_think") || userMessage.contains("/think")) return userMessage
        return "$userMessage $marker"
    }

    companion object {
        private val DETAIL_HINTS = listOf(
            "in detail", "detailed", "step by step", "step-by-step", "explain how",
            "walk me through", "essay", "comprehensive", "thorough", "at length",
            "write a guide", "full explanation", "elaborate", "compare", "pros and cons",
            "list all", "everything about"
        )

        fun fromId(id: String?): ResponseMode = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
