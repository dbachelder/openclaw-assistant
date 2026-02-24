package com.openclaw.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Embedded
import androidx.room.Relation
import com.openclaw.assistant.data.local.entity.MessageEntity
import com.openclaw.assistant.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

data class SessionWithLatestMessageTime(
    val id: String,
    val title: String,
    val createdAt: Long,
    val latestMessageTime: Long?
)

@Dao
interface ChatDao {
    // Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("""
        SELECT s.id, s.title, s.createdAt, MAX(m.timestamp) as latestMessageTime 
        FROM sessions s 
        LEFT JOIN messages m ON s.id = m.sessionId 
        GROUP BY s.id 
        ORDER BY COALESCE(MAX(m.timestamp), s.createdAt) DESC
    """)
    fun getAllSessionsWithLatestMessageTime(): Flow<List<SessionWithLatestMessageTime>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSession(): SessionEntity?

    // Messages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>

    /**
     * Get paginated messages for a session. Use this for large message histories
     * to avoid loading all messages into memory at once.
     *
     * @param sessionId The session ID to query
     * @param limit Maximum number of messages to return
     * @param offset Number of messages to skip (for pagination)
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesForSessionPaginated(sessionId: String, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Get the most recent messages for a session, useful for showing the latest
     * messages without loading the entire history.
     *
     * @param sessionId The session ID to query
     * @param limit Maximum number of messages to return (most recent)
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForSession(sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCountForSession(sessionId: String): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}
