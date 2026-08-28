package com.pocketai.app.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.app.PocketAiApplication
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.ChatMessage
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.db.ConversationSearchResult
import com.pocketai.app.data.repo.Conversation
import com.pocketai.app.data.repo.GenerationStats
import com.pocketai.app.data.repo.WebSource
import com.pocketai.app.doc.ExtractedDocument
import com.pocketai.app.doc.ExtractionResult
import com.pocketai.app.export.ExportFormat
import com.pocketai.app.llm.GenerationOutcome
import com.pocketai.app.llm.PromptTurn
import com.pocketai.app.llm.SessionResult
import com.pocketai.app.llm.SummaryMode
import com.pocketai.app.llm.SystemPrompt
import com.pocketai.app.llm.ThinkingStreamParser
import com.pocketai.app.voice.SpeakController
import com.pocketai.app.voice.SpeakState
import com.pocketai.app.voice.SpokenLanguage
import com.pocketai.app.voice.SpokenPrompt
import com.pocketai.app.web.SearchOutcome
import com.pocketai.app.web.WebSearchClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Text currently being produced by the model. */
data class StreamingState(
    val answer: String = "",
    val thinking: String = "",
    val insideThinking: Boolean = false,
    val usedWebSearch: Boolean = false,
    val sources: List<WebSource> = emptyList()
)

/** A transient banner such as "Searching the web" or "Loading model". */
data class ChatStatus(val text: String, val progress: Float? = null)

data class ChatUiState(
    val conversationId: Long = 0L,
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val streaming: StreamingState? = null,
    val status: ChatStatus? = null,
    val error: String? = null,
    val notice: String? = null,
    val attachment: ExtractedDocument? = null,
    val composerText: String = "",
    val editingMessageId: Long? = null
) {
    val isBusy: Boolean get() = streaming != null || status != null
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PocketAiApplication).container
    private val chats = container.chatRepository
    private val engine = container.inferenceEngine
    private val session = container.modelSession
    private val search = container.webSearchClient
    private val settingsRepository = container.settingsRepository

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val engineState = engine.state

    /** Exposed so appearance controls in the chat can write settings directly. */
    val prefs: com.pocketai.app.data.repo.SettingsRepository get() = settingsRepository

    val exporter: com.pocketai.app.export.ChatExporter get() = container.exporter

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = chats.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Matches conversation titles and anything said inside them. */
    val searchResults: StateFlow<List<ConversationSearchResult>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList()) else chats.search(query.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val conversationId = MutableStateFlow(0L)

    private var generationJob: Job? = null
    private var publisherJob: Job? = null
    private var warmJob: Job? = null

    /**
     * Speak Mode. Built lazily because constructing it touches the speech
     * services, which is wasted work for the many sessions that never use it.
     */
    private var speakEverUsed = false
    private val speak: SpeakController by lazy {
        speakEverUsed = true
        SpeakController(getApplication<Application>()) { transcript, language ->
            onSpokenInput(transcript, language)
        }
    }
    val speakState: StateFlow<SpeakState> get() = speak.state

    /**
     * Language of the turn currently being answered, when it arrived by voice.
     * Null for typed messages, which keeps the prompt unchanged for them.
     */
    private var spokenLanguage: SpokenLanguage? = null

    // Streaming buffers written from the inference thread, published on a timer
    // so the UI recomposes at a steady rate instead of once per token.
    private val streamLock = Any()
    private val thinkingBuffer = StringBuilder()
    private val answerBuffer = StringBuilder()
    private var streamDirty = false
    private var streamInsideThinking = false

    init {
        viewModelScope.launch {
            conversationId
                .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else chats.observeMessages(id) }
                .collectLatest { messages ->
                    _uiState.value = _uiState.value.copy(messages = messages)
                }
        }
        viewModelScope.launch {
            // Warm the engine up in the background so the first message is fast.
            engine.initialize()
            val current = settingsRepository.settings.first()
            if (current.onboardingComplete) preloadModel(current)
        }
        viewModelScope.launch { openMostRecentOrNew() }
    }

    private suspend fun preloadModel(current: AppSettings) {
        setStatus(ChatStatus("Preparing the local model", 0f))
        when (val result = session.ensureLoaded(current) { p ->
            setStatus(ChatStatus("Loading model", p))
        }) {
            is SessionResult.Ready -> {
                setStatus(ChatStatus("Warming up"))
                session.warmUp(current)
                setStatus(null)
            }
            SessionResult.NoModelInstalled -> setStatus(null)
            is SessionResult.Failed -> {
                setStatus(null)
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    private suspend fun openMostRecentOrNew() {
        val existing = chats.observeConversations().first()
        val target = existing.firstOrNull()
        if (target != null) selectConversation(target.id) else newChat()
    }

    // ---------------------------------------------------------------- chats

    fun newChat() {
        viewModelScope.launch {
            stopGeneration()
            val id = chats.createConversation(modelId = settings.value.selectedModelId)
            engine.resetContext()
            rewarmInBackground()
            conversationId.value = id
            _uiState.value = _uiState.value.copy(
                conversationId = id,
                title = "New chat",
                messages = emptyList(),
                streaming = null,
                error = null,
                attachment = null,
                composerText = "",
                editingMessageId = null
            )
        }
    }

    fun selectConversation(id: Long) {
        viewModelScope.launch {
            stopGeneration()
            engine.resetContext()
            rewarmInBackground()
            conversationId.value = id
            val conversation = chats.conversation(id)
            _uiState.value = _uiState.value.copy(
                conversationId = id,
                title = conversation?.title ?: "Chat",
                streaming = null,
                error = null,
                attachment = null,
                editingMessageId = null
            )
        }
    }

    /**
     * Clearing the KV cache also discards the pre-evaluated system prompt, so
     * switching chats would otherwise make the next message pay the full cold
     * prefill again. Re-warming happens off the critical path so the new chat
     * appears immediately.
     */
    private fun rewarmInBackground() {
        warmJob?.cancel()
        warmJob = viewModelScope.launch { session.warmUp(settings.value) }
    }

    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch {
            chats.rename(id, title)
            if (id == conversationId.value) {
                _uiState.value = _uiState.value.copy(title = title.trim().ifBlank { "New chat" })
            }
        }
    }

    fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch { chats.setPinned(id, pinned) }

    fun setFavorite(id: Long, favorite: Boolean) =
        viewModelScope.launch { chats.setFavorite(id, favorite) }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            chats.deleteConversation(id)
            if (id == conversationId.value) openMostRecentOrNew()
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch {
            stopGeneration()
            chats.deleteAllConversations()
            newChat()
        }
    }

    // ------------------------------------------------------------- composer

    fun updateComposer(text: String) {
        _uiState.value = _uiState.value.copy(composerText = text)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, notice = null)
    }

    fun clearAttachment() {
        _uiState.value = _uiState.value.copy(attachment = null)
    }

    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            setStatus(ChatStatus("Reading document"))
            when (val result = container.documentExtractor.extract(uri)) {
                is ExtractionResult.Success -> {
                    setStatus(null)
                    _uiState.value = _uiState.value.copy(
                        attachment = result.document,
                        notice = buildString {
                            append("Attached ${result.document.name}")
                            if (result.document.truncated) {
                                append(" (shortened to fit the context window)")
                            }
                        }
                    )
                }
                is ExtractionResult.Failed -> {
                    setStatus(null)
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------ messaging

    fun send() {
        val state = _uiState.value
        val text = state.composerText.trim()
        val attachment = state.attachment
        if (text.isEmpty() && attachment == null) return
        if (state.streaming != null) return

        val editing = state.editingMessageId
        // A typed message carries no spoken language, so the reply is not
        // steered into whatever was last said out loud.
        if (!speakState.value.active) spokenLanguage = null
        _uiState.value = state.copy(composerText = "", attachment = null, editingMessageId = null)

        viewModelScope.launch {
            if (editing != null) {
                val original = state.messages.firstOrNull { it.id == editing }
                if (original != null) {
                    chats.truncateAfter(original)
                    chats.updateMessage(original.copy(content = text))
                    runGeneration(continueFrom = null)
                    return@launch
                }
            }
            val body = buildUserContent(text, attachment)
            val conversation = ensureConversation()
            chats.addMessage(
                ChatMessage(
                    conversationId = conversation,
                    role = ChatRole.USER,
                    content = body,
                    attachmentName = attachment?.name
                )
            )
            chats.autoTitleIfNeeded(conversation, text.ifBlank { attachment?.name.orEmpty() })
            chats.conversation(conversation)?.let {
                _uiState.value = _uiState.value.copy(title = it.title)
            }
            runGeneration(continueFrom = null)
        }
    }

    // ------------------------------------------------------------ speak mode

    /**
     * A finished spoken turn. The transcript is stored as an ordinary user
     * message, so a spoken conversation is a normal conversation: searchable,
     * exportable, and resumable by typing.
     */
    private fun onSpokenInput(transcript: String, language: SpokenLanguage) {
        spokenLanguage = language
        _uiState.value = _uiState.value.copy(composerText = transcript, attachment = null)
        send()
    }

    fun startSpeakMode() {
        val speech = settings.value.speak
        val language = SpokenLanguage.fromTag(speech.languageTag)
            ?: SpokenLanguage.fromTag(Locale.getDefault().toLanguageTag())
            ?: SpokenLanguage.ENGLISH
        speak.start(
            language = language,
            autoDetect = speech.autoDetectLanguage,
            onDeviceRecognitionOnly = speech.onDeviceRecognitionOnly,
            continuousConversation = speech.continuousConversation,
            pitch = speech.voicePitch,
            speechRate = speech.voiceRate
        )
    }

    fun stopSpeakMode() {
        speak.stop()
        spokenLanguage = null
    }

    /** Cuts PocketAI off mid-sentence and gives the turn straight back. */
    fun interruptSpeaking() = speak.interrupt()

    /** Re-opens the microphone when continuous conversation is switched off. */
    fun listenAgain() = speak.listenAgain()

    fun setSpokenLanguage(language: SpokenLanguage) {
        speak.useLanguage(language)
        viewModelScope.launch { settingsRepository.setSpeakLanguage(language.tag) }
    }

    fun setSpeakAutoDetect(enabled: Boolean) {
        speak.setAutoDetect(enabled)
        viewModelScope.launch { settingsRepository.setSpeakAutoDetect(enabled) }
    }

    fun clearSpeakMessages() = speak.clearMessages()

    val speakRecognitionAvailable: Boolean get() = speak.recognitionAvailable
    val speakOnDeviceAvailable: Boolean get() = speak.onDeviceRecognitionAvailable
    fun hasMicrophonePermission(): Boolean = speak.hasMicrophonePermission()

    private fun buildUserContent(text: String, attachment: ExtractedDocument?): String {
        if (attachment == null) return text
        return buildString {
            appendLine("Document: ${attachment.name}")
            appendLine("\"\"\"")
            appendLine(attachment.text)
            appendLine("\"\"\"")
            appendLine()
            append(text.ifBlank { "Summarise this document and list the key points." })
        }
    }

    private suspend fun ensureConversation(): Long {
        var id = conversationId.value
        if (id == 0L) {
            id = chats.createConversation(modelId = settings.value.selectedModelId)
            conversationId.value = id
            _uiState.value = _uiState.value.copy(conversationId = id)
        }
        return id
    }

    fun regenerate(message: ChatMessage) {
        if (_uiState.value.streaming != null) return
        viewModelScope.launch {
            chats.deleteMessage(message)
            chats.truncateAfter(message)
            runGeneration(continueFrom = null)
        }
    }

    /** Continues an answer that stopped early, appending to the same message. */
    fun continueGeneration(message: ChatMessage) {
        if (_uiState.value.streaming != null) return
        viewModelScope.launch { runGeneration(continueFrom = message) }
    }

    fun summarize(message: ChatMessage, mode: SummaryMode) {
        if (_uiState.value.streaming != null) return
        viewModelScope.launch {
            val conversation = ensureConversation()
            chats.addMessage(
                ChatMessage(
                    conversationId = conversation,
                    role = ChatRole.USER,
                    content = SystemPrompt.summarize(mode, message.content)
                )
            )
            runGeneration(continueFrom = null)
        }
    }

    /** Summarises whatever the user pasted into the composer. */
    fun summarizeText(text: String, mode: SummaryMode) {
        if (_uiState.value.streaming != null || text.isBlank()) return
        _uiState.value = _uiState.value.copy(composerText = "", editingMessageId = null)
        viewModelScope.launch {
            val conversation = ensureConversation()
            chats.addMessage(
                ChatMessage(
                    conversationId = conversation,
                    role = ChatRole.USER,
                    content = SystemPrompt.summarize(mode, text)
                )
            )
            chats.autoTitleIfNeeded(conversation, "Summary: ${text.take(40)}")
            runGeneration(continueFrom = null)
        }
    }

    fun beginEdit(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            composerText = message.content,
            editingMessageId = message.id
        )
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(composerText = "", editingMessageId = null)
    }

    fun resend(message: ChatMessage) {
        if (_uiState.value.streaming != null) return
        viewModelScope.launch {
            chats.truncateAfter(message)
            runGeneration(continueFrom = null)
        }
    }

    fun deleteMessage(message: ChatMessage) {
        viewModelScope.launch { chats.deleteMessage(message) }
    }

    fun stopGeneration() {
        engine.stop()
    }

    // ------------------------------------------------------------ generation

    private suspend fun runGeneration(continueFrom: ChatMessage?) {
        val current = settings.value
        val conversation = ensureConversation()

        when (val ready = session.ensureLoaded(current) { p ->
            setStatus(ChatStatus("Loading model", p))
        }) {
            SessionResult.NoModelInstalled -> {
                setStatus(null)
                _uiState.value = _uiState.value.copy(
                    error = "No model is installed yet. Open Models to download or import one."
                )
                return
            }
            is SessionResult.Failed -> {
                setStatus(null)
                _uiState.value = _uiState.value.copy(error = ready.message)
                return
            }
            is SessionResult.Ready -> setStatus(null)
        }

        val history = chats.messages(conversation)
        val lastUser = history.lastOrNull { it.role == ChatRole.USER }

        // ---- optional web retrieval ---------------------------------------
        var sources: List<WebSource> = emptyList()
        var webBlock: String? = null
        if (current.webSearchUsable && lastUser != null && continueFrom == null) {
            setStatus(ChatStatus("Searching the web"))
            val query = WebSearchClient.toQuery(lastUser.content)
            when (val outcome = search.search(query, current.searchProvider, current.searchResultCount)) {
                is SearchOutcome.Success -> {
                    sources = outcome.sources
                    webBlock = SystemPrompt.webContextBlock(outcome.query, outcome.sources)
                }
                SearchOutcome.Offline -> _uiState.value = _uiState.value.copy(
                    notice = "No internet connection - answering with the local model only."
                )
                SearchOutcome.NoResults -> _uiState.value = _uiState.value.copy(
                    notice = "The web search returned nothing useful - answering locally."
                )
                is SearchOutcome.Failed -> _uiState.value = _uiState.value.copy(
                    notice = "Web search failed (${outcome.message}) - answering locally."
                )
            }
            setStatus(null)
        }

        val loadedModel = engine.state.value.loadedModel
        val mode = current.responseMode
        val systemPrompt = SystemPrompt.build(
            settings = current,
            modelLabel = loadedModel?.displayName,
            mode = mode
        )

        val turns = ArrayList<PromptTurn>()
        history.filter { it.role != ChatRole.SYSTEM && !it.isError }.forEach { message ->
            val isLastUser = message.id == lastUser?.id
            var content = message.content
            if (isLastUser) {
                if (webBlock != null) content = webBlock + "\n\n" + content
                // Spoken turns tell the model which language to answer in and
                // that it is writing for the ear. Kept in the user turn so the
                // system prompt stays byte-identical and cacheable.
                spokenLanguage?.let { language ->
                    content = SpokenPrompt.instruction(
                        language = language,
                        shorter = current.speak.shorterSpokenReplies
                    ) + "\n\n" + content
                }
                // Reasoning models accept a per-turn switch; Fast mode uses it so
                // a one-line question does not pay for a thinking pass.
                content = mode.applyReasoningSwitch(
                    content,
                    modelSupportsThinking = loadedModel?.supportsThinking == true
                )
            }
            turns.add(PromptTurn(message.role, content))
        }
        // When continuing, the partial answer is appended to the prompt rather
        // than sent as a finished assistant turn, so the model carries straight on.
        val continuing = continueFrom != null
        if (continuing && turns.lastOrNull()?.role == ChatRole.ASSISTANT) {
            turns.removeAt(turns.lastIndex)
        }
        if (turns.isEmpty()) return

        // The budget is a ceiling, not a truncation point: the model still stops
        // at its own end-of-turn token. It exists so a short question does not
        // get charged for the maximum-length answer the model would otherwise
        // drift into, which is what dominates total response time.
        var tokenBudget = mode.budgetFor(
            userMessage = lastUser?.content.orEmpty(),
            userCeiling = current.generation.maxOutputTokens
        )
        if (spokenLanguage != null && current.speak.shorterSpokenReplies) {
            // Spoken answers are bounded by patience, not by the context window.
            // A synthesiser reads at roughly 150 words a minute, so a 640-token
            // reply is over three minutes of talking at someone.
            tokenBudget = tokenBudget.coerceAtMost(SPOKEN_TOKEN_CEILING)
        }

        var prompt = engine.buildPrompt(
            systemPrompt = systemPrompt,
            turns = turns,
            reserveTokens = tokenBudget
        )
        if (continuing) {
            prompt += continueFrom?.content.orEmpty()
        }

        startStreaming(sources, webBlock != null)
        val parser = ThinkingStreamParser()

        generationJob = viewModelScope.launch {
            val outcome = engine.generate(prompt, current, container.deviceCapabilities(), tokenBudget) { piece ->
                val delta = parser.push(piece)
                synchronized(streamLock) {
                    if (delta.thinking.isNotEmpty()) thinkingBuffer.append(delta.thinking)
                    if (delta.answer.isNotEmpty()) answerBuffer.append(delta.answer)
                    streamInsideThinking = parser.isInsideThinking
                    streamDirty = true
                }
            }
            val tail = parser.finish()
            synchronized(streamLock) {
                if (tail.thinking.isNotEmpty()) thinkingBuffer.append(tail.thinking)
                if (tail.answer.isNotEmpty()) answerBuffer.append(tail.answer)
                streamDirty = true
            }
            finishStreaming(conversation, outcome, sources, webBlock != null, continueFrom)
        }
    }

    private fun startStreaming(sources: List<WebSource>, usedWeb: Boolean) {
        synchronized(streamLock) {
            thinkingBuffer.setLength(0)
            answerBuffer.setLength(0)
            streamDirty = false
            streamInsideThinking = false
        }
        _uiState.value = _uiState.value.copy(
            streaming = StreamingState(usedWebSearch = usedWeb, sources = sources),
            error = null
        )
        publisherJob?.cancel()
        publisherJob = viewModelScope.launch {
            while (true) {
                delay(STREAM_PUBLISH_INTERVAL_MS)
                var answer: String? = null
                var thinking: String? = null
                var inside = false
                synchronized(streamLock) {
                    if (streamDirty) {
                        answer = answerBuffer.toString()
                        thinking = thinkingBuffer.toString()
                        inside = streamInsideThinking
                        streamDirty = false
                    }
                }
                val currentAnswer = answer
                if (currentAnswer != null) {
                    val streaming = _uiState.value.streaming ?: continue
                    _uiState.value = _uiState.value.copy(
                        streaming = streaming.copy(
                            answer = currentAnswer,
                            thinking = thinking.orEmpty(),
                            insideThinking = inside
                        )
                    )
                    // Speaking starts on the first finished sentence rather than
                    // at the end of generation, so the reply is already being
                    // heard while the rest of it is still being written.
                    if (speakState.value.active) speak.onAnswerDelta(currentAnswer)
                }
            }
        }
    }

    private suspend fun finishStreaming(
        conversationId: Long,
        outcome: GenerationOutcome,
        sources: List<WebSource>,
        usedWeb: Boolean,
        continuedFrom: ChatMessage?
    ) {
        publisherJob?.cancel()
        publisherJob = null

        val answer: String
        val thinking: String
        synchronized(streamLock) {
            answer = answerBuffer.toString()
            thinking = thinkingBuffer.toString()
        }
        val streamingSources = _uiState.value.streaming?.sources ?: sources
        _uiState.value = _uiState.value.copy(streaming = null)

        val stats = (outcome as? GenerationOutcome.Success)?.stats ?: GenerationStats()
        val failure = outcome as? GenerationOutcome.Failure

        val trimmedAnswer = answer.trim()
        val trimmedThinking = thinking.trim()

        if (speakState.value.active) {
            // Only the answer is spoken. Reasoning is shown on screen when the
            // user asked for it, but reading it out loud would bury the reply.
            if (trimmedAnswer.isNotEmpty()) speak.onAnswerFinished(trimmedAnswer)
            else speak.onAnswerFailed()
        }

        if (trimmedAnswer.isEmpty() && trimmedThinking.isEmpty()) {
            // Stopped before the model produced anything, or the request failed
            // outright. Either way there is nothing worth writing to the chat.
            if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
            return
        }

        val modelName = engine.state.value.loadedModel?.displayName
        if (continuedFrom != null) {
            chats.updateMessage(
                continuedFrom.copy(
                    content = (continuedFrom.content + trimmedAnswer).trim(),
                    thinking = listOfNotNull(continuedFrom.thinking, trimmedThinking.ifBlank { null })
                        .joinToString("\n").ifBlank { null },
                    stats = stats
                )
            )
        } else {
            chats.addMessage(
                ChatMessage(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = trimmedAnswer,
                    thinking = trimmedThinking.ifBlank { null },
                    modelName = modelName,
                    stats = stats,
                    usedWebSearch = usedWeb,
                    sources = streamingSources
                )
            )
        }
        if (failure != null) {
            _uiState.value = _uiState.value.copy(notice = failure.message)
        }
    }

    private fun setStatus(status: ChatStatus?) {
        _uiState.value = _uiState.value.copy(status = status)
    }

    // --------------------------------------------------------------- export

    suspend fun exportCurrent(format: ExportFormat): File? {
        val id = conversationId.value
        val conversation = chats.conversation(id) ?: return null
        val messages = chats.messages(id)
        return container.exporter.export(conversation, messages, format)
    }

    suspend fun exportAll(): File {
        val all = chats.observeConversations().first()
        val payload = all.map { conversation -> conversation to chats.messages(conversation.id) }
        return container.exporter.exportAll(payload)
    }

    suspend fun importFrom(uri: Uri): String {
        val text = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return "The file could not be read."

        val parsed = container.exporter.parseImport(text)
        val conversations = parsed.getOrElse {
            return "That file is not a PocketAI export (${it.message})."
        }
        var imported = 0
        conversations.forEach { exported ->
            val id = chats.createConversation(title = exported.title)
            exported.messages.forEach { message ->
                chats.addMessage(
                    ChatMessage(
                        conversationId = id,
                        role = ChatRole.fromWire(message.role),
                        content = message.content,
                        thinking = message.thinking,
                        createdAt = message.createdAt,
                        modelName = message.modelName,
                        usedWebSearch = message.usedWebSearch
                    )
                )
            }
            if (exported.pinned) chats.setPinned(id, true)
            if (exported.favorite) chats.setFavorite(id, true)
            imported++
        }
        return "Imported $imported " + if (imported == 1) "conversation." else "conversations."
    }

    override fun onCleared() {
        if (speakEverUsed) speak.release()
        publisherJob?.cancel()
        generationJob?.cancel()
        engine.stop()
        super.onCleared()
    }

    private companion object {
        // ~25 UI updates a second: smooth to read, far cheaper than per-token.
        const val STREAM_PUBLISH_INTERVAL_MS = 40L

        /**
         * Ceiling for a reply that will be spoken aloud. Roughly 250 words,
         * which is about a minute and a half of speech - already long for one
         * conversational turn.
         */
        const val SPOKEN_TOKEN_CEILING = 340
    }
}
