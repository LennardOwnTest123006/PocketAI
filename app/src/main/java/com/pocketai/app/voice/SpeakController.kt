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
    /** Privacy setting: never fall back to an online recogniser. */
    private var onDeviceOnly = false
    /** Runtime flag: the on-device recogniser could not do the language, so use online. */
    private var forceOnline = false
    /** True between button press and release: the mic must stay open the whole time. */
    private var held = false
    /**
     * Speech captured so far in the current hold. The system recogniser stops
     * on its own after any pause, so a hold is stitched together from however
     * many pieces it returns, and only submitted when the finger lifts.
     */
    private val heldText = StringBuilder()
    /** Restarts within the current hold, to bound a fast error loop and trigger fallback. */
    private var restartsThisHold = 0

    val recognitionAvailable: Boolean get() = listener.isAvailable()
    val onDeviceRecognitionAvailable: Boolean get() = listener.onDeviceAvailable()
    fun hasMicrophonePermission(): Boolean = listener.hasPermission()

    /** Must run on the main thread. */
    fun start(
        language: SpokenLanguage,
        autoDetect: Boolean,
        onDeviceRecognitionOnly: Boolean,
        pitch: Float,
        speechRate: Float
    ) {
        onDeviceOnly = onDeviceRecognitionOnly
        forceOnline = false
        held = false
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
            // thread either. Speak Mode is hold-to-talk, so after setup it waits
            // in IDLE for the user to press and hold, rather than opening the
            // microphone on its own.
            if (ready) main.post {
                applyVoice(language)
                _state.value = _state.value.copy(phase = SpeakPhase.IDLE)
            }
        }
    }

    fun stop() {
        held = false
        listener.stop()
        speaker.stop()
        chunker.reset()
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
        // A different language may be installed for on-device recognition even
        // when the last one was not, so give on-device another chance.
        forceOnline = false
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

    /** Hands a finished utterance to the model. */
    private fun submitUtterance(text: String) {
        val language = resolveLanguage(text)
        _state.value = _state.value.copy(
            phase = SpeakPhase.THINKING, partial = "", level = 0f, language = language
        )
        chunker = SpeechChunker(codeNoteFor(language))
        onUtterance(text, language)
    }

    /** Submits what was gathered this hold, or returns to idle if nothing was. */
    private fun submitHeld() {
        val full = heldText.toString().trim()
        heldText.setLength(0)
        if (full.isNotBlank()) {
            submitUtterance(full)
        } else {
            _state.value = _state.value.copy(
                phase = SpeakPhase.IDLE, partial = "", level = 0f,
                notice = "I did not catch that. Hold the button and speak clearly."
            )
        }
    }

    private fun beginListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginListening() }
            return
        }
        if (!_state.value.active) return
        speaker.stop()
        _state.value = _state.value.copy(phase = SpeakPhase.LISTENING, partial = "", level = 0f)
        // Use the phone's DEFAULT recogniser (Google's), which handles on-device
        // vs network itself and is far more reliable than the forced on-device
        // one. The forced on-device recogniser is used only when the user demands
        // "on-device only" for privacy.
        listener.start(
            listenerImpl,
            _state.value.language,
            preferOnDevice = onDeviceOnly,
            forceOnline = forceOnline
        )
        _state.value = _state.value.copy(recognitionOnDevice = listener.onDevice)
    }

    /**
     * The user pressed and is holding the talk button: open the microphone.
     *
     * Holding while PocketAI is speaking barges in - its voice stops and the
     * microphone opens. Holding while it is still generating an answer is
     * ignored, because there is nothing to listen into yet.
     */
    fun holdStart() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { holdStart() }
            return
        }
        if (!_state.value.active) return
        when (_state.value.phase) {
            SpeakPhase.THINKING -> return
            SpeakPhase.SPEAKING -> speaker.stop()
            else -> Unit
        }
        held = true
        restartsThisHold = 0
        heldText.setLength(0)
        beginListening()
    }

    /** The user released the talk button: finalise the utterance and answer. */
    fun holdEnd() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { holdEnd() }
            return
        }
        held = false
        if (listener.listening.value) {
            // Actively capturing: ask for the final chunk. onResults follows and,
            // with held now false, submits everything gathered this hold.
            listener.finishListening()
        } else {
            // Released during a restart gap: no result will arrive, so submit
            // whatever was already captured.
            submitHeld()
        }
    }

    /**
     * The recogniser quit before the button was released. While the finger is
     * still down the microphone must stay open, so restart it - after a short
     * beat to avoid a tight loop, and switching to the online recogniser if the
     * on-device one keeps failing.
     */
    private fun restartWhileHeld() {
        if (!held || !_state.value.active) return
        restartsThisHold++
        // Once anything has been captured this hold, never give up while held -
        // trailing silence (the user pausing to think) must not end the turn.
        // The cap only guards a recogniser that produces nothing at all.
        if (heldText.isEmpty() && restartsThisHold >= MAX_RESTARTS_PER_HOLD) {
            held = false
            _state.value = _state.value.copy(
                phase = SpeakPhase.IDLE, partial = "", level = 0f,
                notice = "The recogniser would not start. Hold to talk and try again."
            )
            return
        }
        // If the on-device recogniser stumbles a couple of times, stop trusting it
        // for the rest of this session and use the online one, which is reliable.
        if (restartsThisHold >= ON_DEVICE_PATIENCE && !forceOnline && !onDeviceOnly) {
            forceOnline = true
            _state.value = _state.value.copy(recognitionOnDevice = false)
        }
        main.postDelayed({
            if (held && _state.value.active) beginListening()
        }, RESTART_DELAY_MS)
    }

    private val listenerImpl = object : VoiceListener.Listener {
        override fun onPartialTranscript(text: String) {
            // Live words mean the recogniser is working; reset the error budget.
            if (text.isNotBlank()) restartsThisHold = 0
            // Show what has been gathered this hold plus the live words.
            val prefix = heldText.toString().trim()
            val shown = if (prefix.isBlank()) text else "$prefix $text"
            _state.value = _state.value.copy(partial = shown)
        }

        override fun onFinalTranscript(text: String) {
            // A real chunk means the recogniser is working, so the error budget
            // for this hold resets.
            restartsThisHold = 0
            if (text.isNotBlank()) {
                if (heldText.isNotEmpty()) heldText.append(' ')
                heldText.append(text.trim())
            }
            if (held) {
                // The recogniser stopped on its own pause detection, but the
                // finger is still down - keep listening and stitch the pieces.
                _state.value = _state.value.copy(partial = heldText.toString())
                restartWhileHeld()
            } else {
                // Released: this was the final chunk. Submit the whole hold.
                submitHeld()
            }
        }

        override fun onEndOfSpeech() {
            if (_state.value.phase == SpeakPhase.LISTENING) {
                _state.value = _state.value.copy(level = 0f)
            }
        }

        override fun onListenError(message: String, kind: ListenErrorKind) {
            if (!_state.value.active) return
            when (kind) {
                ListenErrorKind.PERMISSION, ListenErrorKind.UNAVAILABLE -> {
                    held = false
                    _state.value = _state.value.copy(
                        phase = SpeakPhase.ERROR, active = false, error = message
                    )
                    listener.stop()
                }

                ListenErrorKind.LANGUAGE_UNAVAILABLE -> {
                    // The on-device recogniser has no pack for this language.
                    // Switch to the online one and keep going.
                    if (!forceOnline && !onDeviceOnly) {
                        forceOnline = true
                        _state.value = _state.value.copy(
                            recognitionOnDevice = false,
                            notice = "No offline ${_state.value.language.englishName} pack; " +
                                "using online recognition. Install it in Android Settings > " +
                                "General management > Voice input to keep it on-device."
                        )
                        if (held) restartWhileHeld() else submitHeld()
                    } else if (held) {
                        // Cannot go online, but keep the mic open; the user may
                        // still be understood, and releasing submits what we have.
                        restartWhileHeld()
                    } else {
                        submitHeld()
                        _state.value = _state.value.copy(
                            notice = "This phone cannot recognise " +
                                "${_state.value.language.englishName} speech offline. Pick another " +
                                "language, or turn off \"on-device only\" in Speak Mode settings."
                        )
                    }
                }

                ListenErrorKind.NO_SPEECH, ListenErrorKind.TRANSIENT -> {
                    if (held) {
                        // Finger still down: keep the mic open by restarting.
                        restartWhileHeld()
                    } else {
                        // Released: submit anything gathered before the last chunk
                        // came back empty.
                        submitHeld()
                    }
                }
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
            _state.value = _state.value.copy(phase = SpeakPhase.IDLE, level = 0f)
        }
    }

    /** Generation failed; return to IDLE so the user can hold and try again. */
    fun onAnswerFailed() {
        if (!_state.value.active) return
        held = false
        chunker.reset()
        _state.value = _state.value.copy(phase = SpeakPhase.IDLE, level = 0f)
    }

    /** Stops PocketAI mid-sentence and returns to IDLE, ready for the next hold. */
    fun interrupt() {
        held = false
        speaker.stop()
        if (_state.value.active) {
            _state.value = _state.value.copy(phase = SpeakPhase.IDLE, level = 0f)
        }
    }

    private val speakerListener = object : VoiceSpeaker.Listener {
        override fun onFinishedSpeaking() {
            if (!_state.value.active || _state.value.phase != SpeakPhase.SPEAKING) return
            // Hold-to-talk: after the answer is spoken, wait for the next press.
            _state.value = _state.value.copy(phase = SpeakPhase.IDLE, level = 0f)
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
        /** Beat between a premature recogniser stop and reopening, while held.
         *  Short so the gap between stitched pieces is not noticeable. */
        const val RESTART_DELAY_MS = 60L
        /** On-device stumbles allowed before switching to the online recogniser. */
        const val ON_DEVICE_PATIENCE = 2
        /** Cap on CONSECUTIVE empty restarts (any speech resets it and lifts the
         *  cap for the rest of the hold), so silence while holding keeps the mic
         *  open but a truly dead recogniser cannot spin forever. */
        const val MAX_RESTARTS_PER_HOLD = 300
    }
}
