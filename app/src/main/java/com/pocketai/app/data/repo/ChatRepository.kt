package com.pocketai.app.data.repo

import com.pocketai.app.data.db.ConversationDao
import com.pocketai.app.data.db.ConversationEntity
import com.pocketai.app.data.db.ConversationSearchResult
import com.pocketai.app.data.db.MessageDao
import com.pocketai.app.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * All conversation persistence. Everything stays on the device - there is no
 * network path out of this class.
 */
class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun search(query: String): Flow<List<ConversationSearchResult>> = conversationDao.search(query)

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun messages(conversationId: Long): List<ChatMessage> =
        messageDao.forConversation(conversationId).map { it.toDomain() }

    suspend fun conversation(id: Long): Conversation? = conversationDao.byId(id)?.toDomain()

    suspend fun createConversation(title: String = "New chat", modelId: String? = null): Long {
        val now = System.currentTimeMillis()
        return conversationDao.insert(
            ConversationEntity(
                title = title,
                createdAt = now,
                updatedAt = now,
                modelId = modelId
            )
        )
    }

    suspend fun addMessage(message: ChatMessage): Long {
        val id = messageDao.insert(message.toEntity())
        refreshSummary(message.conversationId)
        return id
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message.toEntity())
        refreshSummary(message.conversationId)
    }

    suspend fun deleteMessage(message: ChatMessage) {
        messageDao.deleteById(message.id)
        refreshSummary(message.conversationId)
    }

    /** Removes every message after [message] - used by regenerate and edit-and-resend. */
    suspend fun truncateAfter(message: ChatMessage) {
        messageDao.deleteAfter(message.conversationId, message.createdAt, message.id)
        refreshSummary(message.conversationId)
    }

    suspend fun rename(conversationId: Long, title: String) =
        conversationDao.rename(conversationId, title.trim().ifBlank { "New chat" }, System.currentTimeMillis())

    suspend fun setPinned(conversationId: Long, pinned: Boolean) =
        conversationDao.setPinned(conversationId, pinned)

    suspend fun setFavorite(conversationId: Long, favorite: Boolean) =
        conversationDao.setFavorite(conversationId, favorite)

    suspend fun deleteConversation(conversationId: Long) = conversationDao.deleteById(conversationId)

    suspend fun deleteAllConversations() = conversationDao.deleteAll()

    suspend fun conversationCount(): Int = conversationDao.count()

    suspend fun messageCount(): Int = messageDao.count()

    /**
     * Derives a readable title from the first user message.
     * Only ever applied while the chat still carries its placeholder name, so a
     * title the user chose is never overwritten.
     */
    suspend fun autoTitleIfNeeded(conversationId: Long, firstUserMessage: String) {
        val existing = conversationDao.byId(conversationId) ?: return
        if (existing.title != "New chat") return
        val cleaned = firstUserMessage
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
        if (cleaned.isNotBlank()) {
            conversationDao.rename(conversationId, cleaned, System.currentTimeMillis())
        }
    }

    private suspend fun refreshSummary(conversationId: Long) {
        val messages = messageDao.forConversation(conversationId)
        val preview = messages.lastOrNull { it.role != ChatRole.SYSTEM.wire }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?: ""
        conversationDao.touch(conversationId, System.currentTimeMillis(), preview)
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        favorite = favorite,
        modelId = modelId,
        messageCount = messageCount,
        preview = preview
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = ChatRole.fromWire(role),
        content = content,
        thinking = thinking,
        createdAt = createdAt,
        modelName = modelName,
        stats = GenerationStats(
            generatedTokens = generatedTokens,
            firstTokenMs = firstTokenMs,
            totalMs = totalMs,
            tokensPerSecond = tokensPerSecond
        ),
        usedWebSearch = usedWebSearch,
        sources = sourcesJson?.let {
            runCatching { json.decodeFromString<List<WebSource>>(it) }.getOrDefault(emptyList())
        } ?: emptyList(),
        attachmentName = attachmentName,
        isError = isError
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.wire,
        content = content,
        thinking = thinking,
        createdAt = createdAt,
        modelName = modelName,
        tokensPerSecond = stats.tokensPerSecond,
        generatedTokens = stats.generatedTokens,
        firstTokenMs = stats.firstTokenMs,
        totalMs = stats.totalMs,
        usedWebSearch = usedWebSearch,
        sourcesJson = if (sources.isEmpty()) null else json.encodeToString(sources),
        attachmentName = attachmentName,
        isError = isError
    )
}
