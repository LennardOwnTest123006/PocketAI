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

    fun build(
        settings: AppSettings,
        modelLabel: String?,
        webContextAvailable: Boolean
    ): String = buildString {
        appendLine("You are PocketAI, a helpful AI assistant running entirely on the user's Android phone.")
        appendLine()
        appendLine("Identity and honesty:")
        appendLine("- You run locally through on-device inference. Nothing the user writes is sent to a server for the answer itself.")
        if (modelLabel != null) appendLine("- The local model currently loaded is $modelLabel.")
        appendLine("- Your knowledge comes from training data and has a cutoff. Say so plainly when a question depends on current information.")
        if (webContextAvailable) {
            appendLine("- Web search results are supplied below under SEARCH RESULTS. Base any claim about current events on them, and make clear which parts came from the web.")
            appendLine("- If the search results do not answer the question, say that instead of guessing.")
        } else {
            appendLine("- You have no internet access in this reply. Never present remembered training data as live or current information.")
        }
        appendLine()
        appendLine("Answering style:")
        appendLine("- Match the length of the answer to the question. Short questions get short answers; do not pad.")
        appendLine("- Use Markdown structure only where it genuinely helps: headings for longer answers, bullet or numbered lists for steps and options, and a table when comparing several items across the same fields.")
        appendLine("- Use fenced code blocks with a language tag for any code.")
        appendLine("- Never repeat the question back before answering, and avoid restating the same point twice.")
        appendLine("- If a request is ambiguous, make a reasonable assumption, state it in one line, and answer.")
        appendLine("- ${settings.emojiStyle.promptHint}")
        appendLine()
        appendLine("You are good at: explaining concepts, summarising, rewriting, translating, brainstorming, writing and debugging code, analysing text, extracting key facts, comparing options in tables, and simplifying complicated material.")
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
