package com.pocketai.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a listening attempt ended without a transcript. The controller decides policy. */
enum class ListenErrorKind {
    /** Nothing was heard. Normal - just listen again. */
    NO_SPEECH,

    /** A transient recogniser hiccup (client reset, busy, audio, network). Listen again. */
    TRANSIENT,

    /**
     * The recogniser in use cannot handle this language. On a Samsung phone the
     * on-device recogniser often has only a couple of languages installed, so
     * this is the common first-run failure - and the reason it must not be fatal.
     */
    LANGUAGE_UNAVAILABLE,

    /** The microphone permission is missing. Only the user can resolve this. */
    PERMISSION,

    /** The device has no speech recogniser at all. */
    UNAVAILABLE
}

/**
 * Continuous listening with automatic end-of-speech detection.
 *
 * The dictation button elsewhere in PocketAI opens the system's own recogniser
 * dialog, which is right for typing one message but cannot work here: it needs a
 * tap per utterance, takes over the screen, and offers no way to interrupt. A
 * hands-free conversation needs the recogniser bound directly, which is why
 * Speak Mode is the only part of the app that asks for the microphone.
 *
 * Knowing when the user has stopped talking is the recogniser's own endpointer:
 * it reports [RecognitionListener.onEndOfSpeech] after a run of silence, and the
 * silence threshold is set below. No timer of ours decides when your turn ended.
 *
 * The recogniser instance is kept alive across turns and only recreated when the
 * on-device/online choice changes. Destroying and recreating it every turn - the
 * obvious way to write this - is exactly what makes Samsung's recogniser return
 * ERROR_CLIENT and ERROR_RECOGNIZER_BUSY, so it is deliberately avoided.
 */
class VoiceListener(private val context: Context) {

    interface Listener {
        /** Interim text, updated as the user speaks. Not yet final. */
        fun onPartialTranscript(text: String)
        /** The user stopped talking and this is what they said. */
        fun onFinalTranscript(text: String)
        /** The endpointer detected the end of an utterance. */
        fun onEndOfSpeech()
        /** Listening ended without a transcript; [kind] says what the controller should do. */
        fun onListenError(message: String, kind: ListenErrorKind)
        /** Microphone level, 0..1, for the UI. */
        fun onLevel(level: Float)
    }

    private var recognizer: SpeechRecognizer? = null
    /** null = no recogniser yet; otherwise whether the live one is the on-device kind. */
    private var recognizerIsOnDevice: Boolean? = null
    private var listener: Listener? = null
    private var language: SpokenLanguage = SpokenLanguage.ENGLISH
    private var forceOnline = false
    private var sawResult = false

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** True when recognition runs on the device rather than through a server. */
    var onDevice: Boolean = false
        private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Whether the device can transcribe without sending audio anywhere.
     *
     * PocketAI's whole promise is that nothing leaves the phone, so this is
     * worth reporting to the user rather than assuming either way.
     */
    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    /**
     * Starts (or restarts) listening. Must be called on the main thread.
     *
     * @param preferOnDevice try the on-device recogniser first when the device has one.
     * @param forceOnline never use the on-device recogniser, e.g. after it reported the
     *        language was not installed offline. Overrides [preferOnDevice].
     */
    fun start(
        listener: Listener,
        language: SpokenLanguage,
        preferOnDevice: Boolean,
        forceOnline: Boolean
    ) {
        this.listener = listener
        this.language = language
        this.forceOnline = forceOnline

        if (!hasPermission()) {
            listener.onListenError(
                "Microphone permission is needed for Speak Mode.", ListenErrorKind.PERMISSION
            )
            return
        }
        if (!isAvailable()) {
            listener.onListenError(
                "This device has no speech recogniser installed.", ListenErrorKind.UNAVAILABLE
            )
            return
        }

        val useOnDevice = preferOnDevice && !forceOnline && onDeviceAvailable() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        // Reuse the existing recogniser unless the on-device/online choice changed.
        // Recreating it every turn is what triggers BUSY/CLIENT on some devices.
        if (recognizer == null || recognizerIsOnDevice != useOnDevice) {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = runCatching {
                if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            }.getOrElse {
                Log.w(TAG, "recogniser creation failed", it)
                recognizerIsOnDevice = null
                runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            }
            recognizer?.setRecognitionListener(recognitionListener)
            recognizerIsOnDevice = if (recognizer != null) useOnDevice else null
        } else {
            // Clear any lingering session before starting a fresh one.
            runCatching { recognizer?.cancel() }
        }

        val engine = recognizer
        if (engine == null) {
            listener.onListenError(
                "The speech recogniser could not be started.", ListenErrorKind.TRANSIENT
            )
            return
        }

        onDevice = recognizerIsOnDevice == true
        sawResult = false
        runCatching { engine.startListening(buildIntent(onDevice)) }
            .onFailure {
                Log.w(TAG, "startListening failed", it)
                listener.onListenError(
                    "Could not start listening.", ListenErrorKind.TRANSIENT
                )
            }
        _listening.value = true
    }

    private fun buildIntent(useOnDevice: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask to stay offline only when we have NOT been forced online by an
            // on-device failure. Forcing offline on the online recogniser after a
            // language-unavailable error would just reproduce the same failure.
            if (useOnDevice && !forceOnline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            // The endpointer's silence thresholds. Long enough to think mid
            // sentence, short enough that the reply does not feel delayed.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TO_END_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_POSSIBLY_DONE_MS
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_UTTERANCE_MS)
        }

    /**
     * Tells the recogniser the user has stopped talking (button released), so it
     * finalises the current utterance and delivers onResults. This is what makes
     * hold-to-talk exact: the turn ends when the finger lifts, not when the
     * endpointer guesses. Keeps the recogniser alive for the next hold.
     */
    fun finishListening() {
        _listening.value = false
        runCatching { recognizer?.stopListening() }
    }

    /** Full teardown, used when leaving Speak Mode. */
    fun stop() {
        _listening.value = false
        recognizer?.let { engine ->
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }
        recognizer = null
        recognizerIsOnDevice = null
    }

    fun release() {
        stop()
        listener = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // The API reports roughly -2..10 dB; map it to something a meter can use.
            listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onEndOfSpeech() {
            _listening.value = false
            listener?.onEndOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            if (text.isNotBlank()) listener?.onPartialTranscript(text)
        }

        override fun onResults(results: Bundle?) {
            _listening.value = false
            sawResult = true
            val text = firstResult(results)?.trim()
            if (text.isNullOrBlank()) {
                listener?.onListenError(SILENCE_MESSAGE, ListenErrorKind.NO_SPEECH)
            } else {
                listener?.onFinalTranscript(text)
            }
        }

        override fun onError(error: Int) {
            _listening.value = false
            // A result already arrived; a trailing error is not worth surfacing.
            if (sawResult) return
            listener?.onListenError(describe(error), classify(error))
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun classify(error: Int): ListenErrorKind = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenErrorKind.PERMISSION
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenErrorKind.NO_SPEECH
        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 33+).
        // Integer literals so the class still compiles against older SDKs.
        12, 13 -> ListenErrorKind.LANGUAGE_UNAVAILABLE
        // Everything else - client resets, busy, audio, network, server - is a
        // hiccup we recover from by listening again.
        else -> ListenErrorKind.TRANSIENT
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was denied."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SILENCE_MESSAGE
        12, 13 -> "The offline recogniser has no ${language.englishName} pack installed."
        else -> "The speech recogniser hit a temporary error."
    }

    private companion object {
        const val TAG = "PocketAIVoice"
        const val SILENCE_MESSAGE = "I did not catch that."
        // Hold-to-talk drives the turn end with finishListening(), so the
        // endpointer's silence timers are set long to keep them out of the way:
        // a pause mid-sentence must not end the turn while the button is held.
        const val SILENCE_TO_END_MS = 600000
        const val SILENCE_POSSIBLY_DONE_MS = 600000
        const val MIN_UTTERANCE_MS = 300
    }
}
