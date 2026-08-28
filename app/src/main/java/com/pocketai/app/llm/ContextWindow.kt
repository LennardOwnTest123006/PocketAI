package com.pocketai.app.llm

import com.pocketai.app.data.repo.ChatRole

/**
 * Decides which turns survive when a conversation outgrows the context window.
 *
 * Two things matter here, and they pull in the same direction:
 *
 * 1. **Quality.** The opening exchange usually carries the task setup ("here is
 *    my code", "answer in German"), so dropping it first is exactly wrong.
 * 2. **Latency.** The KV cache can only reuse a prefix. Trimming from the front
 *    changes the very first tokens after the system prompt and therefore
 *    invalidates the entire cache, forcing a full re-evaluation of the whole
 *    remaining conversation. Trimming from the *middle* leaves the system
 *    prompt and opening exchange untouched, so that prefix stays cached.
 *
 * Kept free of Android and JNI dependencies so the policy can be tested directly.
 */
object ContextWindow {

    /** Turns kept at the front as an anchor once a conversation is long enough. */
    private const val ANCHOR_TURNS = 2

    /** Only anchor once there is enough history for it to be meaningful. */
    private const val MIN_TURNS_FOR_ANCHOR = 6

    const val ELISION_NOTE =
        "[Earlier messages in this conversation were omitted to fit the context window.]"

    data class Result(
        val turns: List<PromptTurn>,
        val droppedTurns: Int
    ) {
        val trimmed: Boolean get() = droppedTurns > 0
    }

    /**
     * Returns the largest window (anchor + recent turns) that fits [budget].
     *
     * [measure] must return the token count of a candidate window. It is called
     * O(log n) times rather than once per dropped exchange - each call is a
     * template render plus a tokenizer round trip, which is not something to do
     * fifty times while the user waits.
     */
    fun select(
        turns: List<PromptTurn>,
        budget: Int,
        measure: (List<PromptTurn>) -> Int
    ): Result {
        if (turns.isEmpty()) return Result(turns, 0)
        if (measure(turns) <= budget) return Result(turns, 0)

        val anchor = if (turns.size >= MIN_TURNS_FOR_ANCHOR) ANCHOR_TURNS else 0
        // Always leave the anchor plus the final exchange in place.
        val maxDropped = (((turns.size - anchor - 2) / 2) * 2).coerceAtLeast(0)
        if (maxDropped <= 0) return Result(turns, 0)

        // Dropping more can only shrink the window, so the predicate is
        // monotonic and a binary search over whole exchanges is exact.
        var low = 1
        var high = maxDropped / 2
        var bestExchanges = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (measure(build(turns, anchor, mid * 2)) <= budget) {
                bestExchanges = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        val dropped = if (bestExchanges > 0) bestExchanges * 2 else maxDropped
        return Result(build(turns, anchor, dropped), dropped)
    }

    /** Anchor + elision note + the most recent turns. */
    private fun build(turns: List<PromptTurn>, anchor: Int, dropped: Int): List<PromptTurn> {
        if (dropped <= 0) return turns
        val head = turns.take(anchor)
        val tail = turns.drop(anchor + dropped)
        if (tail.isEmpty()) return turns.takeLast(2)
        // A short note keeps the model from inventing continuity with turns it
        // can no longer see. Its position is fixed relative to the anchor, so it
        // does not disturb the cached prefix.
        val note = PromptTurn(ChatRole.USER, ELISION_NOTE)
        return head + note + tail
    }
}
