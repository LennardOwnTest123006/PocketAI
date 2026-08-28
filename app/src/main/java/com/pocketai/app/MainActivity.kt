package com.pocketai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.ui.PocketAiNavigation
import com.pocketai.app.ui.theme.PocketTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single activity host.
 *
 * Configuration changes - rotation, folding and unfolding the Galaxy Z Flip6,
 * the keyboard opening - are handled by Compose and the ViewModels, so no chat
 * state is ever rebuilt or lost.
 */
class MainActivity : ComponentActivity() {

    private val settingsState = MutableStateFlow<AppSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash only until the stored preferences are readable, so the
        // very first frame already uses the right theme instead of flashing.
        splash.setKeepOnScreenCondition { settingsState.value == null }

        val container = (application as PocketAiApplication).container
        lifecycleScope.launch {
            container.settingsRepository.settings.collect { settingsState.value = it }
        }

        setContent {
            val settings by settingsState.collectAsState()
            val resolved = settings ?: AppSettings()
            PocketTheme(settings = resolved) {
                if (settings != null) {
                    PocketAiNavigation(settings = resolved)
                }
            }
        }
    }
}
