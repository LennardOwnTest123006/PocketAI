package com.pocketai.app.voice

/**
 * Feeds a synthesiser sentence by sentence while the model is still writing.
 *
 * Waiting for the full answer would put the whole generation time in front of
 * the first word, which for a long reply is most of a minute of silence. Speech
 * instead starts as soon as the first sentence is complete, and the model
 * usually stays comfortably ahead of the voice from there.
 *
 * Sentences, not tokens, are the unit: a synthesiser needs a whole clause to get
 * the prosody right, and feeding it fragments produces a robotic stammer.
 */
class SpeechChunker(private val codeBlockNote: String = SpeakableText.DEFAULT_CODE_NOTE) {

    /** How far into the normalized text has already been handed over. */
    private var spoken = 0

    /** Sentences that became complete since the last call. */
    fun next(cumulativeAnswer: String): List<String> {
        // The tail is still being written, so it must not be given a full stop
        // that would make a fragment look like a finished sentence.
        val speech = SpeakableText.normalize(
            SpeakableText.stableRegion(cumulativeAnswer),
            codeBlockNote,
            terminateLastLine = false
        )
        if (speech.length <= spoken) return emptyList()
        val fresh = speech.substring(spoken)
        val (sentences, consumed) = SpeakableText.completeSentences(fresh)
        spoken += consumed
        return sentences
    }

    /**
     * Whatever is left once generation has stopped.
     *
     * The last sentence often has no terminator, either because the model ended
     * without one or because it was cut off at the token budget, so it would
     * otherwise never be spoken.
     */
    fun remainder(cumulativeAnswer: String): String? {
        val speech = SpeakableText.normalize(cumulativeAnswer, codeBlockNote)
        if (speech.length <= spoken) return null
        val tail = speech.substring(spoken).trim()
        spoken = speech.length
        return tail.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
    }

    fun reset() { spoken = 0 }
}
