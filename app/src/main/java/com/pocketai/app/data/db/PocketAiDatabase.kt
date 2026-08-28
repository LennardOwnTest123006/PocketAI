package com.pocketai.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PocketAiDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: PocketAiDatabase? = null

        fun get(context: Context): PocketAiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketAiDatabase::class.java,
                    "pocketai.db"
                )
                    // Conversations are the user's data: never wipe them silently
                    // on a schema change - a future version must ship a migration.
                    .build()
                    .also { instance = it }
            }
    }
}
