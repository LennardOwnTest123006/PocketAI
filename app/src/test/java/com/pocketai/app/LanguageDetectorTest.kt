package com.pocketai.app

import com.pocketai.app.voice.LanguageDetector
import com.pocketai.app.voice.SpokenLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Speak Mode answers in the language it was spoken to in, so this decides which
 * language the reply and the voice use. Getting it wrong is loud and obvious.
 */
class LanguageDetectorTest {

    @Test
    fun `recognises German`() {
        assertEquals(
            SpokenLanguage.GERMAN,
            LanguageDetector.detect("Kannst du mir bitte erklären, wie das funktioniert?")
        )
    }

    @Test
    fun `does not confuse German with Dutch`() {
        // These two share a lot of shape; the distinctive function words differ.
        assertEquals(
            SpokenLanguage.GERMAN,
            LanguageDetector.detect("Ich weiß nicht, warum das nicht funktioniert und was ich machen soll")
        )
        assertEquals(
            SpokenLanguage.DUTCH,
            LanguageDetector.detect("Ik weet niet waarom het niet werkt en wat ik moet doen")
        )
    }

    @Test
    fun `recognises English`() {
        assertEquals(
            SpokenLanguage.ENGLISH,
            LanguageDetector.detect("What is the weather going to be like tomorrow?")
        )
    }

    @Test
    fun `recognises the major Latin-script languages`() {
        val cases = mapOf(
            "Pourquoi est-ce que le programme ne fonctionne pas correctement" to SpokenLanguage.FRENCH,
            "¿Por qué no funciona el programa y qué puedo hacer para arreglarlo?" to SpokenLanguage.SPANISH,
            "Perché il programma non funziona e come posso sistemarlo" to SpokenLanguage.ITALIAN,
            "Por que o programa não funciona e como posso corrigir isso" to SpokenLanguage.PORTUGUESE,
            "Dlaczego program nie działa i co mogę z tym zrobić" to SpokenLanguage.POLISH,
            "Neden program çalışmıyor ve bunu nasıl düzeltebilirim" to SpokenLanguage.TURKISH
        )
        cases.forEach { (text, expected) ->
            assertEquals("failed on: $text", expected, LanguageDetector.detect(text))
        }
    }

    @Test
    fun `script alone settles the non-Latin languages`() {
        assertEquals(SpokenLanguage.RUSSIAN, LanguageDetector.detect("Почему это не работает"))
        assertEquals(SpokenLanguage.GREEK, LanguageDetector.detect("Γιατί δεν λειτουργεί αυτό"))
        assertEquals(SpokenLanguage.ARABIC, LanguageDetector.detect("لماذا لا يعمل هذا"))
        assertEquals(SpokenLanguage.HEBREW, LanguageDetector.detect("למה זה לא עובד"))
        assertEquals(SpokenLanguage.HINDI, LanguageDetector.detect("यह काम क्यों नहीं कर रहा है"))
        assertEquals(SpokenLanguage.THAI, LanguageDetector.detect("ทำไมมันถึงไม่ทำงาน"))
        assertEquals(SpokenLanguage.KOREAN, LanguageDetector.detect("이것이 왜 작동하지 않나요"))
    }

    @Test
    fun `kana marks Japanese apart from Chinese`() {
        // Both use Han characters; only Japanese mixes in kana.
        assertEquals(SpokenLanguage.JAPANESE, LanguageDetector.detect("これはなぜ動かないのですか"))
        assertEquals(SpokenLanguage.CHINESE, LanguageDetector.detect("这个为什么不能用"))
    }

    @Test
    fun `Ukrainian is separated from Russian by its own letters`() {
        assertEquals(SpokenLanguage.UKRAINIAN, LanguageDetector.detect("Чому це не працює і що робити"))
    }

    @Test
    fun `an ambiguous scrap of speech is left undecided`() {
        // Returning null keeps the conversation in the language it was already
        // in, which beats flipping languages because someone said "ok".
        assertNull(LanguageDetector.detect("ok"))
        assertNull(LanguageDetector.detect("hmm"))
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("42"))
    }

    @Test
    fun `umlauts alone are enough for a short German phrase`() {
        assertEquals(SpokenLanguage.GERMAN, LanguageDetector.detect("Größe ändern bitte"))
    }

    @Test
    fun `a language embedded in quotes does not hijack the sentence`() {
        // Mostly English, quoting one German word.
        assertEquals(
            SpokenLanguage.ENGLISH,
            LanguageDetector.detect("What does the word \"Fernweh\" mean in English?")
        )
    }
}
