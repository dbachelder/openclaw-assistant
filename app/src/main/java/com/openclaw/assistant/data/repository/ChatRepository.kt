package com.openclaw.assistant.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.data.local.AppDatabase
import com.openclaw.assistant.data.local.entity.MessageEntity
import com.openclaw.assistant.data.local.entity.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "ChatRepository"

/**
 * Default page size for paginated message queries.
 * Chosen to balance memory usage with UI smoothness.
 */
const val DEFAULT_PAGE_SIZE = 100

/**
 * Threshold for logging slow queries in debug builds (in milliseconds).
 */
private const val SLOW_QUERY_THRESHOLD_MS = 100L

class ChatRepository private constructor(context: Context) {
    private val chatDao = AppDatabase.getDatabase(context).chatDao()
    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    // Singleton
    companion object {
        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Sessions - Flow-based queries are automatically dispatched to IO dispatcher by Room,
    // but we explicitly flowOn(Dispatchers.IO) for consistency and documentation
    val allSessions: Flow<List<SessionEntity>> = chatDao.getAllSessions()
        .flowOn(Dispatchers.IO)

    val allSessionsWithLatestTime: Flow<List<com.openclaw.assistant.data.local.dao.SessionWithLatestMessageTime>> =
        chatDao.getAllSessionsWithLatestMessageTime()
            .flowOn(Dispatchers.IO)

    @WorkerThread
    suspend fun createSession(title: String = "New Conversation"): String {
        return withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            val id = UUID.randomUUID().toString()
            val session = SessionEntity(id = id, title = title)
            chatDao.insertSession(session)
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("createSession", duration)
            }
            
            id
        }
    }

    @WorkerThread
    suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            chatDao.deleteSession(sessionId)
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("deleteSession", duration)
            }
        }
    }

    @WorkerThread
    suspend fun getLatestSession(): SessionEntity? {
        return withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            val result = chatDao.getLatestSession()
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("getLatestSession", duration)
            }
            
            result
        }
    }

    // Messages
    /**
     * Get all messages for a session as a Flow.
     * Note: For sessions with large message histories, consider using [getMessagesPaginated]
     * or [getRecentMessages] to avoid performance issues.
     */
    fun getMessages(sessionId: String): Flow<List<MessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
            .flowOn(Dispatchers.IO)
    }

    /**
     * Get paginated messages for a session.
     * Use this for large message histories to avoid loading all messages into memory.
     *
     * @param sessionId The session ID to query
     * @param page Page number (0-indexed)
     * @param pageSize Number of messages per page (default: [DEFAULT_PAGE_SIZE])
     * @return List of messages for the requested page
     */
    @WorkerThread
    suspend fun getMessagesPaginated(
        sessionId: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): List<MessageEntity> {
        return withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            val offset = page * pageSize
            val result = chatDao.getMessagesForSessionPaginated(sessionId, pageSize, offset)
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("getMessagesPaginated(page=$page, size=$pageSize)", duration)
            }
            
            result
        }
    }

    /**
     * Get the most recent messages for a session.
     * Useful for displaying the latest messages without loading the entire history.
     *
     * @param sessionId The session ID to query
     * @param limit Maximum number of messages to return (default: [DEFAULT_PAGE_SIZE])
     * @return List of recent messages, ordered by timestamp (oldest first)
     */
    @WorkerThread
    suspend fun getRecentMessages(
        sessionId: String,
        limit: Int = DEFAULT_PAGE_SIZE
    ): List<MessageEntity> {
        return withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            // Query returns most recent first, so we reverse for chronological order
            val result = chatDao.getRecentMessagesForSession(sessionId, limit).reversed()
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("getRecentMessages(limit=$limit)", duration)
            }
            
            result
        }
    }

    /**
     * Get the total message count for a session.
     * Useful for determining if pagination is needed.
     */
    @WorkerThread
    suspend fun getMessageCount(sessionId: String): Int {
        return withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            val result = chatDao.getMessageCountForSession(sessionId)
            
            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("getMessageCount", duration)
            }
            
            result
        }
    }

    @WorkerThread
    suspend fun addMessage(sessionId: String, text: String, isUser: Boolean) {
        withContext(Dispatchers.IO) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            
            // Ensure session exists (simple check or upsert could happen here, but we rely on createSession called first usually)
            // If session doesn't exist, FK constraint fails.
            // For robustness, we could check if session exists, if not create it?
            // But logic dictates session should be created.
            // Let's safe-guard: if we try to add to a session that doesn't exist locally, we create it.
            val session = chatDao.getSessionById(sessionId)
            if (session == null) {
                chatDao.insertSession(SessionEntity(id = sessionId, title = text.take(20)))
            }

            val message = MessageEntity(
                sessionId = sessionId,
                content = text,
                isUser = isUser
            )
            chatDao.insertMessage(message)

            if (BuildConfig.DEBUG) {
                val duration = System.currentTimeMillis() - startTime
                logQueryTime("addMessage", duration)
            }
        }
    }

    /**
     * Logs query execution time in debug builds.
     * Logs warnings for queries that exceed [SLOW_QUERY_THRESHOLD_MS].
     */
    private fun logQueryTime(operation: String, durationMs: Long) {
        if (!BuildConfig.DEBUG) return
        
        val message = "DB Operation [$operation] took ${durationMs}ms"
        if (durationMs > SLOW_QUERY_THRESHOLD_MS) {
            Log.w(TAG, "SLOW QUERY: $message")
        } else {
            Log.d(TAG, message)
        }
    }
}
