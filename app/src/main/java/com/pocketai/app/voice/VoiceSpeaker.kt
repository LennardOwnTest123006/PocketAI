package com.pocketai.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** What the synthesiser can currently do for one language. */
data class VoiceAvailability(
    val language: SpokenLanguage,
    val supported: Boolean,
    val needsDownload: Boolean,
    val offline: Boolean,
    val voiceName: String?
) {
    val usable: Boolean get() = supported && !needsDownload
}

/**
 * PocketAI's speaking voice.
 *
 * Android does not let an app ship its own synthesiser voice, and there is no
 * honest way to pretend otherwise. What it does allow is choosing deliberately
 * from the voices the device has, and being consistent about it - so this picks
 * the best available voice for each language by a fixed rule and applies the
 * same pitch and pace everywhere. The result is a voice that stays recognisably
 * the same character whether it is speaking German or Japanese, rather than a
 * different stranger each time the language changes.
 *
 * Offline voices are preferred over network ones, which matters twice over:
 * PocketAI answers without a server, so its voice should not need one either.
 */
class VoiceSpeaker(private val context: Context) {

    interface Listener {
        /** The queue drained - the user's turn. */
        fun onFinishedSpeaking()
        fun onSpeechError(message: String)
    }

    private var tts: TextToSpeech? = null
    private var listener: Listener? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Utterances handed to the engine but not yet finished. */
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())
    private val utteranceCounter = AtomicLong(0)

    private var currentLanguage: SpokenLanguage? = null

    /** The voice signature - see the class comment. */
    var pitch: Float = 1.0f
        set(value) { field = value; tts?.setPitch(value) }
    var speechRate: Float = 1.0f
        set(value) { field = value; tts?.setSpeechRate(value) }

    fun initialize(listener: Listener, onReady: (Boolean) -> Unit = {}) {
        this.listener = listener
        if (tts != null) { onReady(_ready.value); return }
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) configure()
            _ready.value = ok
            if (!ok) listener.onSpeechError("No text-to-speech engine is available on this device.")
            onReady(ok)
        }
    }

    private fun configure() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setPitch(pitch)
        engine.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { queued.remove(it) }
                if (queued.isEmpty()) {
                    _speaking.value = false
                    listener?.onFinishedSpeaking()
                }
            }

            @Deprecated("Kept for engines that still call the old overload")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { queued.remove(it) }
                Log.w(TAG, "utterance failed: $utteranceId ($errorCode)")
                if (queued.isEmpty()) {
                    _speaking.value = false
                    // Still hand the turn back; a failed utterance must not
                    // leave the conversation waiting forever.
                    listener?.onFinishedSpeaking()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { queued.remove(it) }
                if (queued.isEmpty()) _speaking.value = false
            }
        })
    }

    /**
     * Points the engine at [language], choosing the voice PocketAI will use for
     * it. Returns what the device can actually do, so the caller can tell the
     * user the truth rather than failing silently.
     */
    fun useLanguage(language: SpokenLanguage): VoiceAvailability {
        val engine = tts ?: return VoiceAvailability(language, false, false, false, null)
        val result = engine.setLanguage(language.locale)

        val supported = result != TextToSpeech.LANG_NOT_SUPPORTED
        val needsDownload = result == TextToSpeech.LANG_MISSING_DATA
        if (!supported) {
            return VoiceAvailability(language, supported = false, needsDownload = false,
                offline = false, voiceName = null)
        }

        val chosen = pickVoice(engine, language)
        if (chosen != null) engine.voice = chosen
        currentLanguage = language

        return VoiceAvailability(
            language = language,
            supported = true,
            needsDownload = needsDownload ||
                chosen?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
            offline = chosen?.isNetworkConnectionRequired == false,
            voiceName = chosen?.name
        )
    }

    /**
     * The device's best voice for a language, by a fixed rule so the same phone
     * always yields the same voice.
     */
    private fun pickVoice(engine: TextToSpeech, language: SpokenLanguage): Voice? {
        val candidates = runCatching { engine.voices }.getOrNull()
            ?.filter { it.locale.language == language.locale.language }
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?: return null
        if (candidates.isEmpty()) return null

        val country = Locale.getDefault().country
        return candidates.maxWithOrNull(
            compareBy<Voice> { if (it.isNetworkConnectionRequired) 0 else 1 }
                .thenBy { it.quality }
                .thenBy { if (it.locale.country.equals(country, ignoreCase = true)) 1 else 0 }
                // Deterministic tie-break: the same voice every launch.
                .thenByDescending { it.name }
        )
    }

    /** Queues one utterance. Speech begins immediately if nothing is playing. */
    fun say(text: String) {
        val engine = tts ?: return
        val speech = text.trim()
        if (speech.isEmpty()) return
        val id = "pocketai-" + utteranceCounter.incrementAndGet()
        queued.add(id)
        _speaking.value = true
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        val result = engine.speak(speech, TextToSpeech.QUEUE_ADD, params, id)
        if (result != TextToSpeech.SUCCESS) {
            queued.remove(id)
            if (queued.isEmpty()) _speaking.value = false
        }
    }

    /** Stops immediately and drops anything queued - used for barge-in. */
    fun stop() {
        queued.clear()
        _speaking.value = false
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        _ready.value = false
        listener = null
    }

    /** Languages this device can actually speak, for the settings screen. */
    fun availableLanguages(): List<SpokenLanguage> {
        val engine = tts ?: return emptyList()
        return SpokenLanguage.entries.filter {
            runCatching { engine.isLanguageAvailable(it.locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    private companion object {
        const val TAG = "PocketAIVoice"
    }
}
