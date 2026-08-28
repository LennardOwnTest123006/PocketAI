package com.pocketai.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Model weights are by far the largest allocation this process holds.
        // Under real pressure, releasing them keeps the app alive instead of
        // being killed outright; the next message reloads the model
        // automatically through ModelSessionController.ensureLoaded().
        if (level >= TRIM_MEMORY_CRITICAL) {
            container.inferenceEngine.stop()
            appScope.launch { container.modelSession.unload() }
        }
    }

    private companion object {
        // ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, inlined because the
        // constant itself is deprecated while the callback still delivers it.
        const val TRIM_MEMORY_CRITICAL = 15
    }
}
