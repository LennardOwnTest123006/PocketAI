package com.pocketai.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE favorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query(
        """
        UPDATE conversations
        SET updatedAt = :now,
            preview = :preview,
            messageCount = (SELECT COUNT(*) FROM messages WHERE conversationId = :id)
        WHERE id = :id
        """
    )
    suspend fun touch(id: Long, now: Long, preview: String)

    /**
     * Matches on the conversation title as well as any message body, so users
     * can find a chat by something that was said inside it.
     */
    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.updatedAt AS updatedAt,
               c.pinned AS pinned, c.favorite AS favorite, c.preview AS preview,
               (SELECT m.content FROM messages m
                 WHERE m.conversationId = c.id AND m.content LIKE '%' || :query || '%'
                 ORDER BY m.createdAt LIMIT 1) AS matchedSnippet
        FROM conversations c
        WHERE c.title LIKE '%' || :query || '%'
           OR EXISTS (SELECT 1 FROM messages m2
                       WHERE m2.conversationId = c.id AND m2.content LIKE '%' || :query || '%')
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """
    )
    fun search(query: String): Flow<List<ConversationSearchResult>>
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Used by "edit and resend" and "regenerate": drop everything after a point. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND (createdAt > :after OR (createdAt = :after AND id > :afterId))")
    suspend fun deleteAfter(conversationId: Long, after: Long, afterId: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Transaction
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>
}
