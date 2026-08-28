package com.pocketai.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeakPhase {
    /** Speak Mode is off. */
    IDLE,

    /** Microphone open, waiting for or hearing the user. */
    LISTENING,

    /** The user finished; the model is producing an answer. */
    THINKING,

    /** PocketAI is talking. */
    SPEAKING,

    /** Something went wrong that the user has to resolve. */
    ERROR
}

data class SpeakState(
    val active: Boolean = false,
    val phase: SpeakPhase = SpeakPhase.IDLE,
    /** Live transcript while the user is still talking. */
    val partial: String = "",
    /** Microphone level, 0..1. */
    val level: Float = 0f,
    val language: SpokenLanguage = SpokenLanguage.ENGLISH,
    val autoDetectLanguage: Boolean = true,
    val recognitionOnDevice: Boolean = false,
    val voiceName: String? = null,
    val voiceOffline: Boolean = true,
    val voiceNeedsDownload: Boolean = false,
    val voiceUnsupported: Boolean = false,
    val notice: String? = null,
    val error: String? = null
)

/**
 * Runs the back-and-forth of a spoken conversation.
 *
 * The loop is: listen until the user stops talking, hand the transcript to the
 * model, speak the answer as it is written, then listen again. Nothing needs to
 * be tapped between turns - the recogniser's endpointer decides when a turn
 * ended, and the synthesiser's queue draining decides when PocketAI's turn
 * ended.
 *
 * Interruption is a button, not a hot microphone. Listening through PocketAI's
 * own voice needs acoustic echo cancellation that cannot be relied on across
 * devices; without it the recogniser mostly transcribes the phone talking to
 * itself. A button that always works is worth more than a feature that
 * sometimes does.
 */
class SpeakController(
    context: Context,
    private val onUtterance: (String, SpokenLanguage) -> Unit
) {
    private val listener = VoiceListener(context)
    private val speaker = VoiceSpeaker(context)

    /**
     * The synthesiser reports progress on a binder thread, and that is exactly
     * where the next turn begins. [android.speech.SpeechRecognizer] refuses to
     * be driven from anywhere but the main thread, so every hop back into
     * listening goes through here.
     */
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state.asStateFlow()

    private var chunker = SpeechChunker()
    private var preferOnDevice = true
    private var continuous = true
    private var consecutiveMisses = 0

    val recognitionAvailable: Boolean get() = listener.isAvailable()
    val onDeviceRecognitionAvailable: Boolean get() = listener.onDeviceAvailable()
    fun hasMicrophonePermission(): Boolean = listener.hasPermission()

    /** Must run on the main thread. */
    fun start(
        language: SpokenLanguage,
        autoDetect: Boolean,
        preferOnDeviceRecognition: Boolean,
        continuousConversation: Boolean,
        pitch: Float,
        speechRate: Float
    ) {
        preferOnDevice = preferOnDeviceRecognition
        continuous = continuousConversation
        speaker.pitch = pitch
        speaker.speechRate = speechRate
        _state.value = _state.value.copy(
            active = true,
            language = language,
            autoDetectLanguage = autoDetect,
            error = null,
            notice = null
        )
        speaker.initialize(speakerListener) { ready ->
            // The engine's init callback is not guaranteed to be on the main
            // thread either.
            if (ready) main.post { applyVoice(language); beginListening() }
        }
    }

    fun stop() {
        listener.stop()
        speaker.stop()
        chunker.reset()
        consecutiveMisses = 0
        _state.value = _state.value.copy(
            active = false, phase = SpeakPhase.IDLE, partial = "", level = 0f
        )
    }

    fun release() {
        listener.release()
        speaker.shutdown()
        _state.value = SpeakState()
    }

    /** Switches language for both ends of the conversation. */
    fun useLanguage(language: SpokenLanguage) {
        applyVoice(language)
        _state.value = _state.value.copy(language = language)
    }

    fun setAutoDetect(enabled: Boolean) {
        _state.value = _state.value.copy(autoDetectLanguage = enabled)
    }

    private fun applyVoice(language: SpokenLanguage) {
        val availability = speaker.useLanguage(language)
        _state.value = _state.value.copy(
            language = language,
            voiceName = availability.voiceName,
            voiceOffline = availability.offline,
            voiceNeedsDownload = availability.needsDownload,
            voiceUnsupported = !availability.supported,
            notice = when {
                !availability.supported ->
                    "This phone has no ${language.englishName} voice installed. PocketAI will " +
                        "still answer in ${language.englishName} on screen. Install a " +
                        "${language.englishName} voice in Android's text-to-speech settings to hear it."
                availability.needsDownload ->
                    "The ${language.englishName} voice needs to be downloaded in Android's " +
                        "text-to-speech settings."
                else -> null
            }
        )
    }

    // ------------------------------------------------------------- listening

    private fun beginListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginListening() }
            return
        }
        if (!_state.value.active) return
        speaker.stop()
        _state.value = _state.value.copy(phase = SpeakPhase.LISTENING, partial = "", level = 0f)
        listener.start(listenerImpl, _state.value.language, preferOnDevice)
        _state.value = _state.value.copy(recognitionOnDevice = listener.onDevice)
    }

    private val listenerImpl = object : VoiceListener.Listener {
        override fun onPartialTranscript(text: String) {
            _state.value = _state.value.copy(partial = text)
        }

        override fun onFinalTranscript(text: String) {
            consecutiveMisses = 0
            val language = resolveLanguage(text)
            _state.value = _state.value.copy(
                phase = SpeakPhase.THINKING,
                partial = "",
                level = 0f,
                language = language
            )
            chunker = SpeechChunker(codeNoteFor(language))
            onUtterance(text, language)
        }

        override fun onEndOfSpeech() {
            // The endpointer decided the turn is over; results follow.
            if (_state.value.phase == SpeakPhase.LISTENING) {
                _state.value = _state.value.copy(level = 0f)
            }
        }

        override fun onListenError(message: String, recoverable: Boolean) {
            if (!_state.value.active) return
            if (recoverable) {
                consecutiveMisses++
                if (consecutiveMisses >= MAX_CONSECUTIVE_MISSES) {
                    // Stop rather than hold the microphone open indefinitely.
                    consecutiveMisses = 0
                    _state.value = _state.value.copy(
                        phase = SpeakPhase.IDLE,
                        active = false,
                        notice = "Speak Mode stopped after hearing nothing for a while."
                    )
                    listener.stop()
                } else {
                    beginListening()
                }
            } else {
                _state.value = _state.value.copy(
                    phase = SpeakPhase.ERROR, active = false, error = message
                )
                listener.stop()
            }
        }

        override fun onLevel(level: Float) {
            if (_state.value.phase == SpeakPhase.LISTENING) {
                _state.value = _state.value.copy(level = level)
            }
        }
    }

    /**
     * Which language the reply should be in.
     *
     * Auto-detection only overrides the current language when it is confident;
     * [LanguageDetector] returns null rather than guess, and staying put is the
     * right response to an ambiguous "ok".
     */
    private fun resolveLanguage(transcript: String): SpokenLanguage {
        val current = _state.value.language
        if (!_state.value.autoDetectLanguage) return current
        val detected = LanguageDetector.detect(transcript) ?: return current
        if (detected != current) applyVoice(detected)
        return detected
    }

    // -------------------------------------------------------------- speaking

    /** Streams the answer to the synthesiser as sentences complete. */
    fun onAnswerDelta(cumulativeAnswer: String) {
        if (!_state.value.active) return
        val sentences = chunker.next(cumulativeAnswer)
        if (sentences.isEmpty()) return
        if (_state.value.phase != SpeakPhase.SPEAKING) {
            _state.value = _state.value.copy(phase = SpeakPhase.SPEAKING)
        }
        sentences.forEach(speaker::say)
    }

    /** Generation finished: speak whatever is left, then hand the turn back. */
    fun onAnswerFinished(cumulativeAnswer: String) {
        if (!_state.value.active) return
        val tail = chunker.remainder(cumulativeAnswer)
        if (tail != null) {
            _state.value = _state.value.copy(phase = SpeakPhase.SPEAKING)
            speaker.say(tail)
        } else if (!speaker.speaking.value) {
            // Nothing to say - do not strand the conversation in THINKING.
            beginListening()
        }
    }

    /** Generation failed; say so rather than silently going quiet. */
    fun onAnswerFailed() {
        if (!_state.value.active) return
        chunker.reset()
        beginListening()
    }

    /** Stops PocketAI mid-sentence and gives the turn straight back. */
    fun interrupt() {
        speaker.stop()
        if (_state.value.active) beginListening()
    }

    /** Opens the microphone again after a one-shot turn. */
    fun listenAgain() {
        if (!_state.value.active) return
        consecutiveMisses = 0
        beginListening()
    }

    private val speakerListener = object : VoiceSpeaker.Listener {
        override fun onFinishedSpeaking() {
            if (!_state.value.active || _state.value.phase != SpeakPhase.SPEAKING) return
            if (continuous) {
                beginListening()
            } else {
                // One question, one answer: hand the microphone back only when
                // the user asks for it again.
                _state.value = _state.value.copy(phase = SpeakPhase.IDLE, level = 0f)
            }
        }

        override fun onSpeechError(message: String) {
            _state.value = _state.value.copy(notice = message)
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    /**
     * The spoken stand-in for a code block, in the conversation's language, so a
     * German answer does not suddenly say an English sentence out loud.
     */
    private fun codeNoteFor(language: SpokenLanguage): String = when (language) {
        SpokenLanguage.GERMAN -> "Der Code steht in meiner Antwort auf dem Bildschirm"
        SpokenLanguage.FRENCH -> "Le code se trouve dans ma réponse à l'écran"
        SpokenLanguage.SPANISH -> "El código está en mi respuesta en la pantalla"
        SpokenLanguage.ITALIAN -> "Il codice è nella mia risposta sullo schermo"
        SpokenLanguage.PORTUGUESE -> "O código está na minha resposta no ecrã"
        SpokenLanguage.DUTCH -> "De code staat in mijn antwoord op het scherm"
        SpokenLanguage.POLISH -> "Kod znajduje się w mojej odpowiedzi na ekranie"
        SpokenLanguage.RUSSIAN -> "Код есть в моём ответе на экране"
        SpokenLanguage.TURKISH -> "Kod ekrandaki cevabımda"
        SpokenLanguage.JAPANESE -> "コードは画面の回答にあります"
        SpokenLanguage.KOREAN -> "코드는 화면의 답변에 있습니다"
        SpokenLanguage.CHINESE -> "代码在屏幕上的回答中"
        else -> SpeakableText.DEFAULT_CODE_NOTE
    }

    private companion object {
        const val MAX_CONSECUTIVE_MISSES = 3
    }
}
