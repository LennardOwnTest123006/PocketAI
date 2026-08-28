package com.pocketai.app

import android.app.Application

class PocketAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under real memory pressure the weights are the largest thing we hold.
        // Releasing them keeps the app alive instead of being killed outright;
        // the model is reloaded automatically on the next message.
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            container.inferenceEngine.stop()
        }
    }
}
