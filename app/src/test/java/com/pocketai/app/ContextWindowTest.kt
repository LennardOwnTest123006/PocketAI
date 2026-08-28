package com.pocketai.app

import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.llm.ContextWindow
import com.pocketai.app.llm.PromptTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trimming policy is a latency decision as much as a quality one: anything
 * that changes the front of the prompt invalidates the whole KV cache and
 * forces the model to re-read the conversation from scratch.
 */
class ContextWindowTest {

    private fun conversation(exchanges: Int): List<PromptTurn> =
        (0 until exchanges).flatMap { i ->
            listOf(
                PromptTurn(ChatRole.USER, "question $i"),
                PromptTurn(ChatRole.ASSISTANT, "answer $i")
            )
        }

    /** One "token" per turn keeps the arithmetic obvious. */
    private val countTurns: (List<PromptTurn>) -> Int = { it.size }

    @Test
    fun `a conversation that fits is returned untouched`() {
        val turns = conversation(3)
        val result = ContextWindow.select(turns, budget = 100, measure = countTurns)
        assertEquals(turns, result.turns)
        assertFalse(result.trimmed)
        assertEquals(0, result.droppedTurns)
    }

    @Test
    fun `trimming keeps the opening exchange as a stable prefix`() {
        // Dropping from the front would change the first tokens after the system
        // prompt and invalidate every cached token behind them.
        val turns = conversation(10)
        val result = ContextWindow.select(turns, budget = 8, measure = countTurns)

        assertTrue(result.trimmed)
        assertEquals("question 0", result.turns[0].content)
        assertEquals("answer 0", result.turns[1].content)
    }

    @Test
    fun `trimming keeps the most recent exchange`() {
        val turns = conversation(10)
        val result = ContextWindow.select(turns, budget = 8, measure = countTurns)
        assertEquals("answer 9", result.turns.last().content)
        assertTrue(result.turns.any { it.content == "question 9" })
    }

    @Test
    fun `an elision note replaces what was dropped`() {
        val turns = conversation(10)
        val result = ContextWindow.select(turns, budget = 8, measure = countTurns)
        assertTrue(
            "the model should be told history was omitted",
            result.turns.any { it.content == ContextWindow.ELISION_NOTE }
        )
    }

    @Test
    fun `the result actually fits the budget`() {
        val turns = conversation(20)
        val budget = 9
        val result = ContextWindow.select(turns, budget, measure = countTurns)
        assertTrue("kept ${result.turns.size} turns for a budget of $budget",
            result.turns.size <= budget)
    }

    @Test
    fun `short conversations are not anchored`() {
        // With only two exchanges there is no "middle" worth preserving.
        val turns = conversation(2)
        val result = ContextWindow.select(turns, budget = 2, measure = countTurns)
        assertTrue(result.turns.size <= turns.size)
        assertEquals("answer 1", result.turns.last().content)
    }

    @Test
    fun `an impossible budget still returns something usable`() {
        val turns = conversation(8)
        val result = ContextWindow.select(turns, budget = 1, measure = countTurns)
        assertTrue("must never return an empty prompt", result.turns.isNotEmpty())
        assertEquals("answer 7", result.turns.last().content)
    }

    @Test
    fun `an empty conversation is handled`() {
        val result = ContextWindow.select(emptyList(), budget = 10, measure = countTurns)
        assertTrue(result.turns.isEmpty())
        assertFalse(result.trimmed)
    }

    @Test
    fun `the retained prefix is identical across successive trims`() {
        // As the conversation grows the front of the prompt must not move,
        // otherwise every turn pays a full re-evaluation.
        val first = ContextWindow.select(conversation(10), budget = 8, measure = countTurns)
        val later = ContextWindow.select(conversation(14), budget = 8, measure = countTurns)
        assertEquals(first.turns.take(3), later.turns.take(3))
    }
}
