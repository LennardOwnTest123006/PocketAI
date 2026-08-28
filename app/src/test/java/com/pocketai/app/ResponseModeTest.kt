package com.pocketai.app

import com.pocketai.app.llm.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Total response time is dominated by `generated_tokens / decode_speed`, so the
 * token budget is the single most load-bearing number in the app. These tests
 * pin down the two properties that matter: budgets stay small for small
 * questions, and the ceiling never silently overrides the user.
 */
class ResponseModeTest {

    @Test
    fun `fast mode budgets far fewer tokens than thinking mode`() {
        val question = "What is the capital of Portugal?"
        val fast = ResponseMode.FAST.budgetFor(question, userCeiling = 4096)
        val balanced = ResponseMode.BALANCED.budgetFor(question, userCeiling = 4096)
        val thinking = ResponseMode.THINKING.budgetFor(question, userCeiling = 4096)

        assertTrue("fast=$fast balanced=$balanced", fast < balanced)
        assertTrue("balanced=$balanced thinking=$thinking", balanced < thinking)
        // A one-line factual question must not be given an essay-sized budget.
        assertTrue("fast budget was $fast", fast <= 200)
    }

    @Test
    fun `explicit requests for detail get the full budget`() {
        val detailed = "Explain in detail, step by step, how a transformer works"
        assertEquals(
            ResponseMode.BALANCED.maxTokens,
            ResponseMode.BALANCED.budgetFor(detailed, userCeiling = 4096)
        )
        assertEquals(
            ResponseMode.THINKING.maxTokens,
            ResponseMode.THINKING.budgetFor(detailed, userCeiling = 4096)
        )
    }

    @Test
    fun `the user ceiling always wins`() {
        val budget = ResponseMode.THINKING.budgetFor(
            "Write a comprehensive guide to Kotlin coroutines",
            userCeiling = 256
        )
        assertEquals(256, budget)
    }

    @Test
    fun `budgets stay usable even with an absurd ceiling`() {
        val budget = ResponseMode.FAST.budgetFor("hi", userCeiling = 1)
        assertTrue("budget was $budget", budget >= 64)
    }

    @Test
    fun `fast mode disables reasoning on models that support it`() {
        val switched = ResponseMode.FAST.applyReasoningSwitch("hello", modelSupportsThinking = true)
        assertTrue(switched.endsWith("/no_think"))
    }

    @Test
    fun `the reasoning switch never leaks into models that do not use it`() {
        val untouched = ResponseMode.FAST.applyReasoningSwitch("hello", modelSupportsThinking = false)
        assertEquals("hello", untouched)
        assertFalse(untouched.contains("/no_think"))
    }

    @Test
    fun `thinking mode asks for reasoning rather than suppressing it`() {
        val switched = ResponseMode.THINKING.applyReasoningSwitch("solve this", modelSupportsThinking = true)
        assertTrue(switched.endsWith("/think"))
        assertFalse(switched.contains("/no_think"))
    }

    @Test
    fun `an explicit switch typed by the user is respected`() {
        val alreadySet = ResponseMode.FAST.applyReasoningSwitch(
            "think hard about this /think",
            modelSupportsThinking = true
        )
        assertFalse("must not append a contradicting switch", alreadySet.contains("/no_think"))
    }

    @Test
    fun `every mode round-trips through its id`() {
        ResponseMode.entries.forEach { mode ->
            assertEquals(mode, ResponseMode.fromId(mode.id))
        }
        assertEquals(ResponseMode.BALANCED, ResponseMode.fromId("nonsense"))
        assertEquals(ResponseMode.BALANCED, ResponseMode.fromId(null))
    }
}
