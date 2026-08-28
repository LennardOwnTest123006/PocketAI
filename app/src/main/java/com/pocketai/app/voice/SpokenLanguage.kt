package com.pocketai.app.voice

import java.util.Locale

/**
 * The languages Speak Mode can hold a conversation in.
 *
 * Three separate things have to agree on a language for a spoken turn to work:
 * the recogniser that transcribes the user, the model that writes the reply, and
 * the synthesiser that reads it out. This enum is the single identity they are
 * matched on, so a mismatch is impossible by construction.
 *
 * [nativeName] is what the user sees. Someone speaking German should not have to
 * hunt for "German" in an English list.
 */
enum class SpokenLanguage(
    val tag: String,
    val englishName: String,
    val nativeName: String
) {
    ENGLISH("en", "English", "English"),
    GERMAN("de", "German", "Deutsch"),
    FRENCH("fr", "French", "Français"),
    SPANISH("es", "Spanish", "Español"),
    ITALIAN("it", "Italian", "Italiano"),
    PORTUGUESE("pt", "Portuguese", "Português"),
    DUTCH("nl", "Dutch", "Nederlands"),
    POLISH("pl", "Polish", "Polski"),
    SWEDISH("sv", "Swedish", "Svenska"),
    DANISH("da", "Danish", "Dansk"),
    NORWEGIAN("nb", "Norwegian", "Norsk"),
    FINNISH("fi", "Finnish", "Suomi"),
    CZECH("cs", "Czech", "Čeština"),
    SLOVAK("sk", "Slovak", "Slovenčina"),
    HUNGARIAN("hu", "Hungarian", "Magyar"),
    ROMANIAN("ro", "Romanian", "Română"),
    TURKISH("tr", "Turkish", "Türkçe"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt"),
    RUSSIAN("ru", "Russian", "Русский"),
    UKRAINIAN("uk", "Ukrainian", "Українська"),
    GREEK("el", "Greek", "Ελληνικά"),
    ARABIC("ar", "Arabic", "العربية"),
    HEBREW("he", "Hebrew", "עברית"),
    HINDI("hi", "Hindi", "हिन्दी"),
    THAI("th", "Thai", "ไทย"),
    JAPANESE("ja", "Japanese", "日本語"),
    KOREAN("ko", "Korean", "한국어"),
    CHINESE("zh", "Chinese", "中文");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** Told to the model, in the language itself, so the instruction reinforces itself. */
    val replyInstruction: String
        get() = "Reply in $englishName ($nativeName). Write natural, fluent $englishName."

    companion object {
        fun fromTag(tag: String?): SpokenLanguage? {
            if (tag.isNullOrBlank()) return null
            val primary = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            return when (primary) {
                // Tags that do not match an enum value one-for-one.
                "no", "nn" -> NORWEGIAN
                "iw" -> HEBREW          // legacy code still emitted by some engines
                "in" -> INDONESIAN      // ditto
                else -> entries.firstOrNull { it.tag == primary }
            }
        }
    }
}
