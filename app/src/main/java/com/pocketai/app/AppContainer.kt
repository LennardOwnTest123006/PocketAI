package com.pocketai.app

import android.content.Context
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.db.PocketAiDatabase
import com.pocketai.app.data.model.ModelDownloadManager
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.data.repo.ChatRepository
import com.pocketai.app.data.repo.SettingsRepository
import com.pocketai.app.doc.DocumentExtractor
import com.pocketai.app.export.ChatExporter
import com.pocketai.app.llm.InferenceEngine
import com.pocketai.app.llm.ModelSessionController
import com.pocketai.app.web.WebSearchClient

/**
 * Manual dependency container.
 *
 * PocketAI is a single-process app with a handful of long-lived singletons, so
 * a plain container keeps startup fast and avoids pulling in a DI framework.
 */
class AppContainer(private val context: Context) {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    private val database: PocketAiDatabase by lazy { PocketAiDatabase.get(context) }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.conversationDao(), database.messageDao())
    }

    val modelRepository: ModelRepository by lazy { ModelRepository(context) }

    val downloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(context, modelRepository)
    }

    val inferenceEngine: InferenceEngine by lazy { InferenceEngine(context) }

    val modelSession: ModelSessionController by lazy {
        ModelSessionController(
            engine = inferenceEngine,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
            capabilities = { deviceCapabilities() }
        )
    }

    val webSearchClient: WebSearchClient by lazy { WebSearchClient(context) }

    val documentExtractor: DocumentExtractor by lazy { DocumentExtractor(context) }

    val exporter: ChatExporter by lazy { ChatExporter(context) }

    /** Re-read each time it is requested so storage figures stay current. */
    fun deviceCapabilities(): DeviceCapabilities = DeviceCapabilities.read(context)
}
