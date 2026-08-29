package com.pocketai.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the reader is doing, for the play/stop button on each answer. */
data class ReaderState(
    /** Id of the message being read; 0 when nothing is playing. */
    val speakingMessageId: Long = 0L,
    /** Language the current answer was detected to be in. */
    val language: SpokenLanguage? = null,
    /** The device voice PocketAI is speaking with, for the settings screen. */
    val voiceName: String? = null,
    /** Something the user needs to know, e.g. a missing voice. */
    val notice: String? = null
) {
    fun isSpeaking(messageId: Long) = speakingMessageId == messageId
}

/**
 * Reads one of PocketAI's answers out loud.
 *
 * The language is worked out from the answer itself rather than from a setting:
 * if PocketAI replied in German, [LanguageDetector] sees German and the German
 * voice is used, without the user having to tell it anything. An answer that
 * switches language mid-conversation is therefore read correctly too.
 *
 * Everything is synthesis - there is no microphone and no recognition here, so
 * the app needs no audio permission at all.
 */
class SpeechReader(context: Context) {

    private val speaker = VoiceSpeaker(context)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var initialized = false

    /**
     * Starts reading [text], or stops if that message is already being read.
     *
     * [forcedLanguage] overrides detection when the user has pinned a language
     * in settings; null means "work it out from the text", which is the default.
     */
    fun toggle(
        messageId: Long,
        text: String,
        pitch: Float,
        rate: Float,
        forcedLanguage: SpokenLanguage?
    ) {
        if (_state.value.speakingMessageId == messageId) {
            stop()
            return
        }
        // Switching to another answer cancels the one in progress.
        speaker.stop()

        val language = forcedLanguage ?: LanguageDetector.detect(text) ?: SpokenLanguage.ENGLISH
        val speech = SpeakableText.normalize(text, codeNoteFor(language))
        if (speech.isBlank()) {
            _state.value = _state.value.copy(
                speakingMessageId = 0L,
                notice = "There is nothing here to read out."
            )
            return
        }

        speaker.pitch = pitch
        speaker.speechRate = rate

        val begin = {
            val availability = speaker.useLanguage(language)
            _state.value = ReaderState(
                speakingMessageId = messageId,
                language = language,
                voiceName = availability.voiceName,
                notice = when {
                    !availability.supported ->
                        "This phone has no ${language.englishName} voice installed. Add one in " +
                            "Android's text-to-speech settings to hear ${language.englishName} " +
                            "answers read out."
                    availability.needsDownload ->
                        "The ${language.englishName} voice still needs downloading in Android's " +
                            "text-to-speech settings."
                    else -> null
                }
            )
            if (availability.usable) {
                // Sentence at a time: a synthesiser needs a whole clause to get
                // the intonation right, and it lets a long answer start sooner.
                val chunker = SpeechChunker(codeNoteFor(language))
                val sentences = chunker.next(text) + listOfNotNull(chunker.remainder(text))
                if (sentences.isEmpty()) speaker.say(speech)
                else sentences.forEach(speaker::say)
            } else {
                // No voice for this language - do not pretend to be reading.
                _state.value = _state.value.copy(speakingMessageId = 0L)
            }
        }

        if (initialized) {
            begin()
        } else {
            speaker.initialize(speakerListener) { ready ->
                initialized = ready
                if (ready) main.post(begin)
                else _state.value = _state.value.copy(
                    speakingMessageId = 0L,
                    notice = "This phone has no text-to-speech engine installed."
                )
            }
        }
    }

    fun stop() {
        speaker.stop()
        _state.value = _state.value.copy(speakingMessageId = 0L)
    }

    /** Speaks a short sample so the user can hear pitch and speed changes. */
    fun preview(sample: String, language: SpokenLanguage, pitch: Float, rate: Float) {
        speaker.stop()
        speaker.pitch = pitch
        speaker.speechRate = rate
        val begin = {
            speaker.useLanguage(language)
            speaker.say(sample)
        }
        if (initialized) begin()
        else speaker.initialize(speakerListener) { ready ->
            initialized = ready
            if (ready) main.post(begin)
        }
    }

    /** Languages this phone can actually speak, for the settings screen. */
    fun availableLanguages(): List<SpokenLanguage> = speaker.availableLanguages()

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun release() {
        speaker.shutdown()
        initialized = false
        _state.value = ReaderState()
    }

    private val speakerListener = object : VoiceSpeaker.Listener {
        override fun onFinishedSpeaking() {
            _state.value = _state.value.copy(speakingMessageId = 0L)
        }

        override fun onSpeechError(message: String) {
            _state.value = _state.value.copy(speakingMessageId = 0L, notice = message)
        }
    }

    /**
     * The spoken stand-in for a code block, in the answer's own language, so a
     * German answer does not suddenly say an English sentence out loud.
     */
    private fun codeNoteFor(language: SpokenLanguage): String = when (language) {
        SpokenLanguage.GERMAN -> "Der Code steht in der Antwort auf dem Bildschirm"
        SpokenLanguage.FRENCH -> "Le code se trouve dans la réponse à l'écran"
        SpokenLanguage.SPANISH -> "El código está en la respuesta en la pantalla"
        SpokenLanguage.ITALIAN -> "Il codice è nella risposta sullo schermo"
        SpokenLanguage.PORTUGUESE -> "O código está na resposta no ecrã"
        SpokenLanguage.DUTCH -> "De code staat in het antwoord op het scherm"
        SpokenLanguage.POLISH -> "Kod znajduje się w odpowiedzi na ekranie"
        SpokenLanguage.RUSSIAN -> "Код есть в ответе на экране"
        SpokenLanguage.TURKISH -> "Kod ekrandaki cevapta"
        SpokenLanguage.JAPANESE -> "コードは画面の回答にあります"
        SpokenLanguage.KOREAN -> "코드는 화면의 답변에 있습니다"
        SpokenLanguage.CHINESE -> "代码在屏幕上的回答中"
        else -> SpeakableText.DEFAULT_CODE_NOTE
    }
}
