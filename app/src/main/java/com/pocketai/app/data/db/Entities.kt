package com.pocketai.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val modelId: String? = null,
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    val preview: String = ""
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("createdAt")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user", "assistant" or "system". */
    val role: String,
    val content: String,
    /** Raw reasoning the model itself emitted; null when it produced none. */
    val thinking: String? = null,
    val createdAt: Long,
    val modelName: String? = null,
    val tokensPerSecond: Double = 0.0,
    val generatedTokens: Int = 0,
    val firstTokenMs: Long = 0,
    val totalMs: Long = 0,
    val usedWebSearch: Boolean = false,
    /** JSON array of {title,url,snippet} when the answer was web assisted. */
    val sourcesJson: String? = null,
    val attachmentName: String? = null,
    val isError: Boolean = false
)

/** Row returned by the conversation search query. */
data class ConversationSearchResult(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val favorite: Boolean,
    val preview: String,
    val matchedSnippet: String?
)
