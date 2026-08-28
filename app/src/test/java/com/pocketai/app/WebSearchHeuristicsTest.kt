package com.pocketai.app

import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.EmojiStyle
import com.pocketai.app.llm.SummaryMode
import com.pocketai.app.llm.SystemPrompt
import com.pocketai.app.data.repo.WebSource
import com.pocketai.app.web.WebSearchClient
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

    @Test
    fun `tells the model it has no internet when search is off`() {
        val prompt = SystemPrompt.build(
            settings = AppSettings(),
            modelLabel = "Qwen3 1.7B",
            webContextAvailable = false
        )
        assertTrue(prompt.contains("no internet access"))
        assertTrue(prompt.contains("PocketAI"))
        assertTrue(prompt.contains("Qwen3 1.7B"))
    }

    @Test
    fun `switches to source attribution when web results are supplied`() {
        val prompt = SystemPrompt.build(
            settings = AppSettings(),
            modelLabel = null,
            webContextAvailable = true
        )
        assertTrue(prompt.contains("SEARCH RESULTS"))
        assertFalse(prompt.contains("no internet access"))
    }

    @Test
    fun `carries the emoji preference through to the model`() {
        val none = SystemPrompt.build(
            AppSettings(emojiStyle = EmojiStyle.NONE), null, false
        )
        assertTrue(none.contains("Do not use any emoji"))
        val expressive = SystemPrompt.build(
            AppSettings(emojiStyle = EmojiStyle.EXPRESSIVE), null, false
        )
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
