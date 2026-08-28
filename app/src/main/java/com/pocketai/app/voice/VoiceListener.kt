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
 */
class VoiceListener(private val context: Context) {

    interface Listener {
        /** Interim text, updated as the user speaks. Not yet final. */
        fun onPartialTranscript(text: String)
        /** The user stopped talking and this is what they said. */
        fun onFinalTranscript(text: String)
        /** The endpointer detected the end of an utterance. */
        fun onEndOfSpeech()
        /** [recoverable] means listening can simply be restarted. */
        fun onListenError(message: String, recoverable: Boolean)
        /** Microphone level, 0..1, for the UI. */
        fun onLevel(level: Float)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var language: SpokenLanguage = SpokenLanguage.ENGLISH
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

    /** Must be called on the main thread - [SpeechRecognizer] requires it. */
    fun start(listener: Listener, language: SpokenLanguage, preferOnDevice: Boolean) {
        this.listener = listener
        this.language = language

        if (!hasPermission()) {
            listener.onListenError("Microphone permission is needed for Speak Mode.", false)
            return
        }
        if (!isAvailable()) {
            listener.onListenError("This device has no speech recogniser installed.", false)
            return
        }

        stop()
        val useOnDevice = preferOnDevice && onDeviceAvailable()
        recognizer = runCatching {
            // The version check is repeated here rather than left to
            // onDeviceAvailable() so the API-level guard is visible at the call.
            if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrElse {
            Log.w(TAG, "recogniser creation failed", it)
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        onDevice = useOnDevice
        recognizer?.setRecognitionListener(recognitionListener)
        sawResult = false
        runCatching { recognizer?.startListening(buildIntent(useOnDevice)) }
            .onFailure { listener.onListenError("Could not start listening: ${it.message}", true) }
        _listening.value = true
    }

    private fun buildIntent(useOnDevice: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask for offline recognition even when not using the on-device
            // recogniser class; some engines honour it and keep audio local.
            if (!useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

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

    fun stop() {
        _listening.value = false
        recognizer?.let { engine ->
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }
        recognizer = null
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
                listener?.onListenError(SILENCE_MESSAGE, true)
            } else {
                listener?.onFinalTranscript(text)
            }
        }

        override fun onError(error: Int) {
            _listening.value = false
            // A result already arrived; a trailing error is not worth surfacing.
            if (sawResult) return
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            listener?.onListenError(describe(error), recoverable)
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        SpeechRecognizer.ERROR_CLIENT -> "The speech recogniser stopped unexpectedly."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission was denied."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "This device's recogniser needs a network connection. Install offline speech " +
                "recognition for ${language.englishName} in Android settings to keep Speak " +
                "Mode fully on-device."
        SpeechRecognizer.ERROR_NO_MATCH -> SILENCE_MESSAGE
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser was busy."
        SpeechRecognizer.ERROR_SERVER -> "The speech recogniser reported a server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SILENCE_MESSAGE
        else -> "Speech recognition failed."
    }

    private companion object {
        const val TAG = "PocketAIVoice"
        const val SILENCE_MESSAGE = "I did not catch that."
        const val SILENCE_TO_END_MS = 1300
        const val SILENCE_POSSIBLY_DONE_MS = 900
        const val MIN_UTTERANCE_MS = 700
    }
}
