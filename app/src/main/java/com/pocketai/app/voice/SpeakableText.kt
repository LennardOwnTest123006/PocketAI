package com.pocketai.app.voice

/**
 * Turns a model's Markdown answer into something worth listening to.
 *
 * Handing raw Markdown to a synthesiser is the difference between an assistant
 * and a novelty: it reads "star star important star star", spells out URLs
 * character by character, and recites an entire code block as punctuation. What
 * is good on screen and what is good in the ear are simply different texts.
 *
 * Code is the clearest case. Nobody wants forty lines of Kotlin read out, so a
 * block is replaced by a short spoken note. The code stays on screen, where it
 * is useful.
 */
object SpeakableText {

    /**
     * Everything up to an unterminated code fence.
     *
     * While a fence is still open the text after it is not yet knowable - it may
     * turn out to be code, which is spoken very differently from prose. Holding
     * it back keeps the part already spoken from ever needing to be revised.
     */
    fun stableRegion(markdown: String): String {
        var index = 0
        var open = -1
        while (true) {
            val fence = markdown.indexOf("```", index)
            if (fence < 0) break
            open = if (open < 0) fence else -1
            index = fence + 3
        }
        return if (open >= 0) markdown.substring(0, open) else markdown
    }

    /**
     * Strips Markdown down to prose a synthesiser can read straight out.
     *
     * [terminateLastLine] must be false while text is still arriving. Each line
     * is given a full stop so that list items are heard as separate statements
     * rather than one breathless sentence - but doing that to the last line of a
     * half-written answer invents a sentence boundary that is not there, and the
     * chunker would speak the fragment before the rest of it exists.
     */
    fun normalize(
        markdown: String,
        codeBlockNote: String = DEFAULT_CODE_NOTE,
        terminateLastLine: Boolean = true
    ): String {
        var text = markdown

        // Fenced code: announce once, never read out.
        text = FENCED_CODE.replace(text) { " $codeBlockNote. " }
        // Images before links, so alt text does not survive as a bare word.
        text = IMAGE.replace(text) { it.groupValues[1].ifBlank { "" } }
        // Links: the label carries the meaning, the URL is noise out loud.
        text = LINK.replace(text) { it.groupValues[1] }
        text = BARE_URL.replace(text) { " " }
        text = INLINE_CODE.replace(text) { it.groupValues[1] }

        val spoken = ArrayList<String>()
        for (rawLine in text.lines()) {
            var line = rawLine.trim()

            if (line.isEmpty()) continue
            // A rule is a visual device with nothing to say.
            if (HORIZONTAL_RULE.matches(line)) continue
            // Table rows read as gibberish; the separator row doubly so.
            if (TABLE_SEPARATOR.matches(line)) continue
            if (line.startsWith("|") && line.endsWith("|")) {
                val cells = line.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.isEmpty()) continue
                // Read a row as a short phrase rather than a wall of pipes.
                line = cells.joinToString(", ")
            }

            line = HEADING.replace(line) { "" }
            line = BLOCKQUOTE.replace(line) { "" }
            // "- point" becomes a sentence so the voice pauses between items.
            line = BULLET.replace(line) { "" }
            line = NUMBERED.replace(line) { "" }

            line = BOLD_ITALIC.replace(line) { it.groupValues[2] }
            line = STRIKETHROUGH.replace(line) { it.groupValues[1] }

            if (line.isBlank()) continue
            spoken.add(line)
        }

        val out = StringBuilder()
        spoken.forEachIndexed { index, line ->
            out.append(line)
            val isLast = index == spoken.lastIndex
            if (!isLast || terminateLastLine) {
                val last = line.last()
                // Punctuation that already carries a pause is left alone, so a
                // line ending in a colon is not read as "here you go dot".
                if (last !in SENTENCE_ENDINGS && last != ':' && last != ';' && last != ',') {
                    out.append('.')
                }
            }
            out.append('\n')
        }

        return out.toString()
            .replace(EMOJI, " ")
            .replace(MULTI_SPACE, " ")
            .replace(MULTI_NEWLINE, "\n")
            .trim()
    }

    /**
     * Splits [text] into complete sentences, returning them along with how many
     * characters were consumed. A trailing fragment is left unconsumed so the
     * caller can wait for the rest of it.
     */
    fun completeSentences(text: String): Pair<List<String>, Int> {
        val sentences = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in SENTENCE_ENDINGS || ch == '\n') {
                // Run on through "?!" and "..." so they stay one break.
                var end = i
                while (end + 1 < text.length && text[end + 1] in SENTENCE_ENDINGS) end++

                val boundary = end + 1 >= text.length || text[end + 1].isWhitespace() || ch == '\n'
                if (boundary && !isDecimalPoint(text, i) && !endsWithAbbreviation(text, start, i)) {
                    val piece = text.substring(start, end + 1).trim()
                    if (piece.isNotBlank() && piece.any { it.isLetterOrDigit() }) sentences.add(piece)
                    // Skip the whitespace that follows, so it is not re-emitted.
                    var next = end + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    start = next
                    i = next
                    continue
                }
                i = end + 1
                continue
            }
            i++
        }
        return sentences to start
    }

    /** "3.14" and "1.5x" are one token, not two sentences. */
    private fun isDecimalPoint(text: String, dot: Int): Boolean =
        text[dot] == '.' &&
            dot > 0 && text[dot - 1].isDigit() &&
            dot + 1 < text.length && text[dot + 1].isDigit()

    private fun endsWithAbbreviation(text: String, start: Int, dot: Int): Boolean {
        if (text[dot] != '.') return false
        val word = text.substring(start, dot + 1)
            .takeLastWhile { !it.isWhitespace() }
            .lowercase()
        return word in ABBREVIATIONS
    }

    const val DEFAULT_CODE_NOTE = "There is a code block in my answer on screen"

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '。', '！', '？', '…')

    /**
     * Abbreviations whose full stop does not end a sentence. Deliberately short:
     * a missed split costs one slightly long utterance, a wrong split cuts a
     * sentence in half mid-thought.
     */
    private val ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "vs.", "etc.", "e.g.", "i.e.",
        "z.b.", "u.a.", "usw.", "bzw.", "ca.", "nr.", "abb.", "inkl.", "evtl.",
        "p.ej.", "ej.", "cf.", "fig.", "no.", "approx."
    )

    private val FENCED_CODE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val HEADING = Regex("^#{1,6}\\s*")
    private val BLOCKQUOTE = Regex("^>+\\s*")
    private val BULLET = Regex("^[-*+]\\s+")
    private val NUMBERED = Regex("^\\d+[.)]\\s+")
    private val HORIZONTAL_RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val TABLE_SEPARATOR = Regex("^\\|?[\\s:|-]*\\|[\\s:|-]*$")
    private val BOLD_ITALIC = Regex("(\\*{1,3}|_{1,3})(.+?)\\1")
    private val STRIKETHROUGH = Regex("~~(.+?)~~")
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_NEWLINE = Regex("\n{2,}")

    /**
     * Pictographs and the variation selectors and joiners that bind them.
     * Reading these out loud produces either silence or a surprise word.
     */
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1F1E6}-\\x{1F1FF}\\x{200D}]"
    )
}
