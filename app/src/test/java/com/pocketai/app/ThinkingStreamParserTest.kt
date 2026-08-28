package com.pocketai.app

import com.pocketai.app.llm.ThinkingStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingStreamParserTest {

    @Test
    fun `passes plain output straight through`() {
        val parser = ThinkingStreamParser()
        val delta = parser.push("Hello there")
        assertEquals("Hello there", delta.answer)
        assertEquals("", delta.thinking)
    }

    @Test
    fun `separates a complete think block`() {
        val parser = ThinkingStreamParser()
        val a = parser.push("<think>reasoning here</think>Final answer")
        val b = parser.finish()
        assertEquals("reasoning here", a.thinking + b.thinking)
        assertEquals("Final answer", a.answer + b.answer)
    }

    @Test
    fun `handles a tag split across chunks`() {
        val parser = ThinkingStreamParser()
        val thinking = StringBuilder()
        val answer = StringBuilder()
        listOf("<th", "ink>step one", " step two</thi", "nk>The answer").forEach { chunk ->
            val delta = parser.push(chunk)
            thinking.append(delta.thinking)
            answer.append(delta.answer)
        }
        val tail = parser.finish()
        thinking.append(tail.thinking)
        answer.append(tail.answer)
        assertEquals("step one step two", thinking.toString())
        assertEquals("The answer", answer.toString())
    }

    @Test
    fun `never invents thinking when the model emits none`() {
        val (thinking, answer) = ThinkingStreamParser.splitComplete("Just a normal reply.")
        assertNull(thinking)
        assertEquals("Just a normal reply.", answer)
    }

    @Test
    fun `treats an unterminated block as thinking`() {
        val parser = ThinkingStreamParser()
        parser.push("<think>still reasoning")
        assertTrue(parser.isInsideThinking)
        assertEquals("still reasoning", parser.finish().thinking)
    }
}
