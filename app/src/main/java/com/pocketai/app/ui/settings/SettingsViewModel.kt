package com.pocketai.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.SettingsRepository
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

    /** Applied after a runtime setting changes so the new value actually takes effect. */
    suspend fun reloadModel() {
        if (container.inferenceEngine.state.value.loadedModel == null) return
        container.modelSession.reloadCurrent(settings.value)
    }
}
