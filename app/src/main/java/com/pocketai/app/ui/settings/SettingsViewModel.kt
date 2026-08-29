package com.pocketai.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.SettingsRepository
import com.pocketai.app.voice.SpeechReader
import com.pocketai.app.voice.SpokenLanguage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PocketAiApplication).container

    val prefs: SettingsRepository = container.settingsRepository

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val engineState = container.inferenceEngine.state

    fun capabilities(): DeviceCapabilities = container.deviceCapabilities()

    /** Built only if the user actually asks to hear the voice. */
    private var readerEverUsed = false
    private val reader: SpeechReader by lazy {
        readerEverUsed = true
        SpeechReader(getApplication<Application>())
    }

    /** Speaks a sample so pitch and speed changes can be heard as they are made. */
    fun previewVoice() {
        val voice = settings.value.voice
        val language = SpokenLanguage.fromTag(voice.languageTag) ?: SpokenLanguage.ENGLISH
        reader.preview(
            sample = when (language) {
                SpokenLanguage.GERMAN -> "Hallo, ich bin PocketAI. So klinge ich."
                SpokenLanguage.FRENCH -> "Bonjour, je suis PocketAI. Voici ma voix."
                SpokenLanguage.SPANISH -> "Hola, soy PocketAI. Así sueno."
                else -> "Hello, I am PocketAI. This is how I sound."
            },
            language = language,
            pitch = voice.pitch,
            rate = voice.rate
        )
    }

    override fun onCleared() {
        if (readerEverUsed) reader.release()
        super.onCleared()
    }

    /** Applied after a runtime setting changes so the new value actually takes effect. */
    suspend fun reloadModel() {
        if (container.inferenceEngine.state.value.loadedModel == null) return
        container.modelSession.reloadCurrent(settings.value)
    }
}
