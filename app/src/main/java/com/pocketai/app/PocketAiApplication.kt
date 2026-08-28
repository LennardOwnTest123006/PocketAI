package com.pocketai.app

import android.app.Application
import android.content.ComponentCallbacks2
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

        // Only release the model once the process is genuinely in the
        // background. TRIM_MEMORY_RUNNING_CRITICAL fires while the app is still
        // in the foreground - and a phone running a multi-gigabyte model is
        // exactly the situation where it fires. Unloading there threw away a
        // model the user was actively chatting with and made the *next* message
        // pay a multi-second reload, which is far worse than the memory it saved.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            releaseModel()
        }
    }

    @Deprecated("Kept for older platform versions that still call it.")
    override fun onLowMemory() {
        super.onLowMemory()
        // Genuine system-wide pressure: give the weights back rather than be killed.
        releaseModel()
    }

    private fun releaseModel() {
        container.inferenceEngine.stop()
        // Reloaded automatically on the next message by
        // ModelSessionController.ensureLoaded().
        appScope.launch { container.modelSession.unload() }
    }
}
