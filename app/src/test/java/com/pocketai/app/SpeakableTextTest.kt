package com.pocketai.app

import com.pocketai.app.voice.SpeakableText
import com.pocketai.app.voice.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What looks right on screen and what sounds right out loud are different texts.
 * These pin down the difference.
 */
class SpeakableTextTest {

    @Test
    fun `formatting markers are not read out`() {
        val spoken = SpeakableText.normalize("This is **important** and *this* is `code`.")
        assertEquals("This is important and this is code.", spoken)
    }

    @Test
    fun `headings lose their hashes but keep their words`() {
        val spoken = SpeakableText.normalize("## Setup steps\nInstall it first.")
        assertFalse(spoken.contains("#"))
        assertTrue(spoken.contains("Setup steps"))
    }

    @Test
    fun `a code block is announced rather than recited`() {
        val spoken = SpeakableText.normalize(
            "Here you go:\n```kotlin\nfun main() { println(\"hi\") }\n```\nThat is all.",
            codeBlockNote = "There is code on screen"
        )
        assertFalse("code must not be spoken", spoken.contains("println"))
        assertFalse(spoken.contains("```"))
        assertTrue(spoken.contains("There is code on screen"))
        assertTrue(spoken.contains("That is all."))
    }

    @Test
    fun `links are read as their label, not their URL`() {
        val spoken = SpeakableText.normalize("See [the Kotlin docs](https://kotlinlang.org/docs).")
        assertTrue(spoken.contains("the Kotlin docs"))
        assertFalse(spoken.contains("kotlinlang.org"))
        assertFalse(spoken.contains("https"))
    }

    @Test
    fun `a bare URL is dropped rather than spelled out`() {
        val spoken = SpeakableText.normalize("Try https://example.com/a/b?c=d for details.")
        assertFalse(spoken.contains("example.com"))
        assertTrue(spoken.contains("for details"))
    }

    @Test
    fun `list items become separate sentences so the voice pauses`() {
        val spoken = SpeakableText.normalize("- first thing\n- second thing\n1. third thing")
        assertFalse(spoken.contains("- "))
        assertTrue(spoken.contains("first thing."))
        assertTrue(spoken.contains("second thing."))
        assertTrue(spoken.contains("third thing."))
    }

    @Test
    fun `table pipes and rules do not become noise`() {
        val spoken = SpeakableText.normalize(
            "| Name | Size |\n|------|------|\n| Qwen | 4 GB |\n\n---\n\nDone."
        )
        assertFalse(spoken.contains("|"))
        assertFalse(spoken.contains("---"))
        assertTrue(spoken.contains("Name, Size"))
        assertTrue(spoken.contains("Qwen, 4 GB"))
    }

    @Test
    fun `emoji are removed`() {
        val spoken = SpeakableText.normalize("All done 🚀 and working ✅")
        assertTrue(spoken.contains("All done"))
        assertFalse(spoken.any { it.code in 0x1F300..0x1FAFF })
    }

    @Test
    fun `decimals do not split a sentence`() {
        val (sentences, _) = SpeakableText.completeSentences("It costs 3.50 euros today. Next. ")
        assertEquals(listOf("It costs 3.50 euros today.", "Next."), sentences)
    }

    @Test
    fun `an abbreviation does not split a sentence`() {
        val (sentences, _) = SpeakableText.completeSentences("Ask Dr. Meier about it. Then wait. ")
        assertEquals(listOf("Ask Dr. Meier about it.", "Then wait."), sentences)
    }

    @Test
    fun `an unfinished sentence is left for the next chunk`() {
        val (sentences, consumed) = SpeakableText.completeSentences("Done. And this is unfinis")
        assertEquals(listOf("Done."), sentences)
        assertEquals("only the finished sentence is consumed", "Done. ".length, consumed)
    }

    @Test
    fun `text after an unterminated code fence is held back`() {
        // Until the fence closes there is no way to know whether what follows is
        // prose or code, and code must never be read out.
        val held = SpeakableText.stableRegion("Here it is:\n```kotlin\nfun main(")
        assertEquals("Here it is:\n", held)
    }

    @Test
    fun `a closed fence leaves the whole text speakable`() {
        val text = "One.\n```\ncode\n```\nTwo."
        assertEquals(text, SpeakableText.stableRegion(text))
    }
}

/** The chunker is what lets speech start before the model has finished writing. */
class SpeechChunkerTest {

    @Test
    fun `sentences are handed over as they complete`() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.next("The answer is"))
        assertEquals(listOf("The answer is yes."), chunker.next("The answer is yes. And also"))
        assertEquals(listOf("And also no."), chunker.next("The answer is yes. And also no. Third"))
    }

    @Test
    fun `nothing is ever spoken twice`() {
        val chunker = SpeechChunker()
        val spoken = ArrayList<String>()
        var text = ""
        listOf("Hello there. ", "How are you? ", "I am fine. ").forEach {
            text += it
            spoken += chunker.next(text)
        }
        assertEquals(listOf("Hello there.", "How are you?", "I am fine."), spoken)
    }

    @Test
    fun `the final unterminated sentence is still spoken`() {
        // Models often stop without a full stop, or get cut off at the budget.
        // It gains one here so the voice ends on a falling note instead of
        // trailing off as though more were coming.
        val chunker = SpeechChunker()
        chunker.next("All good. Now something else")
        assertEquals("Now something else.", chunker.remainder("All good. Now something else"))
    }

    @Test
    fun `a half-written sentence is never spoken early`() {
        // Every line is given a full stop so list items are heard separately,
        // which mid-stream would turn the growing tail into a finished
        // sentence and have the voice stammer it out a fragment at a time.
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.next("The capital of France"))
        assertEquals(emptyList<String>(), chunker.next("The capital of France is"))
        assertEquals(
            listOf("The capital of France is Paris."),
            chunker.next("The capital of France is Paris. It has")
        )
    }

    @Test
    fun `list items are still separated once they are complete`() {
        val chunker = SpeechChunker()
        val spoken = chunker.next("- first item\n- second item\n- third")
        assertEquals(listOf("first item.", "second item."), spoken)
    }

    @Test
    fun `remainder returns nothing when everything was already spoken`() {
        val chunker = SpeechChunker()
        chunker.next("Complete sentence. ")
        assertNull(chunker.remainder("Complete sentence. "))
    }

    @Test
    fun `code held back mid-stream is still announced once the fence closes`() {
        val chunker = SpeechChunker("code on screen")
        chunker.next("Here it is. ```kotlin\nfun x(")
        val spoken = chunker.next("Here it is. ```kotlin\nfun x() {}\n``` Done.")
        assertTrue(spoken.any { it.contains("code on screen") })
        assertFalse(spoken.any { it.contains("fun x") })
    }
}
