package com.pocketai.app.llm

/**
 * Splits a model's token stream into reasoning and final answer.
 *
 * PocketAI never invents reasoning: this only reacts to `<think>` / `<thinking>`
 * blocks that the model itself emitted. A model that produces no such block
 * yields an empty thinking section and the Thinking panel is not shown at all.
 *
 * Tags routinely straddle a token boundary, so any suffix that could still grow
 * into a tag is held back until the next chunk resolves it.
 */
class ThinkingStreamParser {

    data class Delta(val thinking: String, val answer: String)

    private val openTags = listOf("<think>", "<thinking>")
    private val closeTags = listOf("</think>", "</thinking>")

    private val hold = StringBuilder()
    private var inThinking = false

    val isInsideThinking: Boolean get() = inThinking

    fun push(chunk: String): Delta {
        if (chunk.isEmpty()) return EMPTY
        hold.append(chunk)
        val thinking = StringBuilder()
        val answer = StringBuilder()

        while (true) {
            val text = hold.toString()
            val tags = if (inThinking) closeTags else openTags
            val match = firstMatch(text, tags)
            if (match != null) {
                val (index, tag) = match
                if (inThinking) thinking.append(text, 0, index) else answer.append(text, 0, index)
                hold.delete(0, index + tag.length)
                inThinking = !inThinking
                continue
            }
            // No complete tag: emit everything that cannot be part of one.
            val keep = partialTagSuffixLength(text, tags)
            val safeEnd = text.length - keep
            if (safeEnd > 0) {
                if (inThinking) thinking.append(text, 0, safeEnd) else answer.append(text, 0, safeEnd)
                hold.delete(0, safeEnd)
            }
            break
        }
        return Delta(thinking.toString(), answer.toString())
    }

    /** Flush whatever is still buffered once generation ends. */
    fun finish(): Delta {
        val rest = hold.toString()
        hold.setLength(0)
        if (rest.isEmpty()) return EMPTY
        return if (inThinking) Delta(rest, "") else Delta("", rest)
    }

    private fun firstMatch(text: String, tags: List<String>): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (tag in tags) {
            val idx = text.indexOf(tag, ignoreCase = true)
            if (idx >= 0 && (best == null || idx < best!!.first)) best = idx to tag
        }
        return best
    }

    /** Length of the trailing substring that is still a viable prefix of some tag. */
    private fun partialTagSuffixLength(text: String, tags: List<String>): Int {
        val maxTag = tags.maxOf { it.length }
        val limit = minOf(maxTag - 1, text.length)
        for (len in limit downTo 1) {
            val suffix = text.substring(text.length - len)
            if (tags.any { it.length > len && it.regionMatches(0, suffix, 0, len, ignoreCase = true) }) {
                return len
            }
        }
        return 0
    }

    companion object {
        private val EMPTY = Delta("", "")

        /**
         * Non-streaming split, used when re-parsing stored text.
         * Returns thinking (may be null) and the answer.
         */
        fun splitComplete(raw: String): Pair<String?, String> {
            val parser = ThinkingStreamParser()
            val a = parser.push(raw)
            val b = parser.finish()
            val thinking = (a.thinking + b.thinking).trim()
            val answer = (a.answer + b.answer).trim()
            return (thinking.ifBlank { null }) to answer
        }
    }
}
