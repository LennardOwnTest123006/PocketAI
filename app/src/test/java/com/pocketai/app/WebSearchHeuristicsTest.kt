package com.pocketai.app

import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.EmojiStyle
import com.pocketai.app.llm.ResponseMode
import com.pocketai.app.llm.SummaryMode
import com.pocketai.app.llm.SystemPrompt
import com.pocketai.app.data.repo.WebSource
import com.pocketai.app.web.WebSearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchHeuristicsTest {

    @Test
    fun `recognises questions that depend on current information`() {
        listOf(
            "What happened today in tech?",
            "search the internet for the latest news",
            "what is the current price of a Pixel",
            "look this up for me"
        ).forEach { assertTrue(it, WebSearchClient.looksTimeSensitive(it)) }
    }

    @Test
    fun `leaves timeless questions alone`() {
        listOf(
            "Explain how a transformer works",
            "Rewrite this paragraph to be shorter",
            "What is the capital of Portugal?"
        ).forEach { assertFalse(it, WebSearchClient.looksTimeSensitive(it)) }
    }

    @Test
    fun `strips conversational filler from the outgoing query`() {
        assertTrue(WebSearchClient.toQuery("search the web for llama.cpp releases") == "llama.cpp releases")
        assertTrue(WebSearchClient.toQuery("look up android 15 features?") == "android 15 features")
    }

    @Test
    fun `query length is bounded`() {
        val long = "a".repeat(1000)
        assertTrue(WebSearchClient.toQuery(long).length <= 200)
    }
}

class SystemPromptTest {

    private fun prompt(
        settings: AppSettings = AppSettings(),
        model: String? = "Qwen3 1.7B",
        mode: ResponseMode = ResponseMode.BALANCED
    ) = SystemPrompt.build(settings, model, mode)

    @Test
    fun `always states that it has no live internet access`() {
        val text = prompt()
        assertTrue(text.contains("PocketAI"))
        assertTrue(text.contains("no live internet access"))
        assertTrue(text.contains("Qwen3 1.7B"))
    }

    @Test
    fun `explains how to treat supplied search results`() {
        // The convention is stated once, up front, instead of being swapped in
        // and out per message - see the stability test below for why.
        assertTrue(prompt().contains("SEARCH RESULTS"))
    }

    @Test
    fun `is byte-identical across turns so the kv cache can serve it`() {
        // The system prompt is the first ~300 tokens of every request. If it
        // varied per message the cached prefix would miss every single turn and
        // the model would re-evaluate it from scratch, which is precisely the
        // latency this design exists to avoid.
        val settings = AppSettings()
        val first = SystemPrompt.build(settings, "Qwen3 1.7B", ResponseMode.BALANCED)
        val second = SystemPrompt.build(settings, "Qwen3 1.7B", ResponseMode.BALANCED)
        assertEquals(first, second)
    }

    @Test
    fun `carries the response mode style hint`() {
        assertTrue(prompt(mode = ResponseMode.FAST).contains(ResponseMode.FAST.styleHint))
        assertTrue(prompt(mode = ResponseMode.THINKING).contains(ResponseMode.THINKING.styleHint))
    }

    @Test
    fun `carries the emoji preference through to the model`() {
        val none = prompt(AppSettings(emojiStyle = EmojiStyle.NONE))
        assertTrue(none.contains("Do not use any emoji"))
        val expressive = prompt(AppSettings(emojiStyle = EmojiStyle.EXPRESSIVE))
        assertTrue(expressive.contains("freely"))
    }

    @Test
    fun `numbers sources so the model can cite them`() {
        val block = SystemPrompt.webContextBlock(
            query = "android 15",
            sources = listOf(
                WebSource("Android 15 features", "https://example.com/a", "A summary"),
                WebSource("Release notes", "https://example.com/b", "")
            )
        )
        assertTrue(block.contains("[1] Android 15 features"))
        assertTrue(block.contains("[2] Release notes"))
        assertTrue(block.contains("https://example.com/a"))
    }

    @Test
    fun `summary modes each carry a distinct instruction`() {
        val instructions = SummaryMode.entries.map { it.instruction }
        assertTrue(instructions.size == instructions.toSet().size)
        val bullets = SystemPrompt.summarize(SummaryMode.BULLETS, "some long text")
        assertTrue(bullets.contains("bullet list"))
        assertTrue(bullets.contains("some long text"))
    }
}
