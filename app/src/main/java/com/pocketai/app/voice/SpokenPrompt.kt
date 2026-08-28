package com.pocketai.app.voice

/**
 * The extra instruction a spoken turn carries.
 *
 * It goes in the user turn rather than the system prompt on purpose. The system
 * prompt is the first few hundred tokens of every request and is kept
 * byte-identical so the KV cache can serve it for free; making it vary by
 * language would invalidate that cache on every turn and cost more time than
 * Speak Mode saves.
 */
object SpokenPrompt {

    /**
     * Two things the model cannot infer on its own: which language to answer
     * in, and that its answer is going to be read out rather than displayed.
     *
     * The second matters as much as the first. A model writing for a screen
     * reaches for headings, bullet lists and code fences, all of which are
     * noise in the ear - and a spoken answer that runs for three paragraphs is
     * an interruption, not an answer.
     */
    fun instruction(language: SpokenLanguage, shorter: Boolean): String = buildString {
        append(language.replyInstruction)
        append(" This answer will be read aloud by a speech synthesiser, so write it to be ")
        append("heard: plain sentences, no Markdown, no headings, no bullet lists, no emoji, ")
        append("and no code blocks unless the user explicitly asked for code. ")
        if (shorter) {
            append("Keep it to a few sentences - say the useful part and stop. ")
            append("If the full answer is genuinely long, give the short version and offer to go deeper.")
        } else {
            append("Keep the phrasing natural enough to say out loud.")
        }
    }
}
