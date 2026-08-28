package com.pocketai.app.llm

import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.WebSource

/**
 * Builds PocketAI's own instructions for whichever local model is loaded.
 *
 * The prompt deliberately tells the model what it can and cannot know: it must
 * not claim live internet access, and web-sourced facts have to be attributed.
 */
object SystemPrompt {

    /**
     * Builds PocketAI's instructions.
     *
     * Deliberately does NOT vary per message. The prompt is the first ~250
     * tokens of every request, so keeping it byte-identical lets the KV cache
     * serve it for free on every turn after the first, and lets it be warmed
     * ahead of time. Anything that changes per message (web results) belongs in
     * the user turn instead.
     */
    fun build(
        settings: AppSettings,
        modelLabel: String?,
        mode: ResponseMode
    ): String = buildString {
        appendLine("You are PocketAI, a helpful AI assistant running entirely on the user's Android phone.")
        appendLine()
        appendLine("Honesty:")
        appendLine("- You run locally through on-device inference; nothing is sent to a server to answer.")
        if (modelLabel != null) appendLine("- The local model loaded right now is $modelLabel.")
        appendLine("- You have no live internet access of your own, and your training data has a cutoff.")
        appendLine("- If a message contains a SEARCH RESULTS block, those are web results fetched for that question: base any current-information claim on them and make clear which parts came from the web. Otherwise say plainly when an answer may be out of date, and never present remembered training data as current.")
        appendLine()
        appendLine("Answering:")
        appendLine("- ${mode.styleHint}")
        appendLine("- Match structure to the question: headings and lists only when they genuinely help, a table when comparing several items across the same fields, fenced code blocks with a language tag for code.")
        appendLine("- Never repeat the question back before answering, and never make the same point twice.")
        appendLine("- If a request is ambiguous, state your assumption in one line and answer it.")
        appendLine("- ${settings.emojiStyle.promptHint}")
        appendLine()
        append("You are good at explaining, summarising, rewriting, translating, brainstorming, writing and debugging code, analysing text, extracting key facts, and comparing options.")
    }.trim()

    /** Formats retrieved pages for the model, with explicit numbering for citation. */
    fun webContextBlock(query: String, sources: List<WebSource>): String = buildString {
        appendLine("SEARCH RESULTS for \"$query\":")
        appendLine()
        sources.forEachIndexed { index, source ->
            appendLine("[${index + 1}] ${source.title}")
            appendLine("Source: ${source.url}")
            if (source.snippet.isNotBlank()) appendLine(source.snippet.trim())
            appendLine()
        }
        appendLine("Use these results to answer the question that follows. Refer to sources as [1], [2] and so on where it helps. If they are insufficient, say so.")
    }.trim()

    /** Instruction wrapper for the dedicated summarise action. */
    fun summarize(mode: SummaryMode, text: String): String = buildString {
        appendLine(mode.instruction)
        appendLine()
        appendLine("Text to summarise:")
        appendLine("\"\"\"")
        appendLine(text.trim())
        appendLine("\"\"\"")
    }.trim()
}

enum class SummaryMode(val id: String, val label: String, val instruction: String) {
    SHORT(
        "short", "Short",
        "Summarise the following text in two or three sentences. Keep only what matters most."
    ),
    NORMAL(
        "normal", "Normal",
        "Summarise the following text in a clear paragraph or two, covering the main points in order."
    ),
    DETAILED(
        "detailed", "Detailed",
        "Write a detailed summary of the following text. Use short headings for each major topic and keep the original structure."
    ),
    BULLETS(
        "bullets", "Bullet points",
        "Summarise the following text as a bullet list. One idea per bullet, no more than fifteen bullets."
    ),
    KEY_FACTS(
        "key_facts", "Key facts",
        "Extract the key facts from the following text as a list. Include names, numbers, dates and figures exactly as written. Do not add anything that is not in the text."
    ),
    SIMPLE(
        "simple", "Simple explanation",
        "Explain what the following text says in plain, everyday language, as if to someone new to the topic. Avoid jargon."
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}
