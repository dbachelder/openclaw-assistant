package com.openclaw.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionUiModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isGateway: Boolean
)

class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val nodeRuntime = (application as OpenClawApplication).nodeRuntime

    val isGatewayConfigured: Boolean
        get() = nodeRuntime.manualEnabled.value && nodeRuntime.manualHost.value.isNotBlank()
                
    val isHttpConfigured: Boolean
        get() = settingsRepository.isConfigured()

    val allSessions: StateFlow<List<SessionUiModel>> = combine(
        nodeRuntime.chatSessions,
        chatRepository.allSessionsWithLatestTime
    ) { nodeEntries, localSessions ->
        val gatewayModels = nodeEntries.map { entry ->
            SessionUiModel(
                id = entry.key,
                title = entry.displayName ?: "New Session",
                createdAt = entry.updatedAtMs ?: System.currentTimeMillis(),
                isGateway = true
            )
        }
        val httpModels = localSessions.map { session ->
            SessionUiModel(
                id = session.id,
                title = session.title,
                createdAt = session.latestMessageTime ?: session.createdAt,
                isGateway = false
            )
        }
        
        (gatewayModels + httpModels).sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        if (settingsRepository.useNodeChat) {
            nodeRuntime.refreshChatSessions(limit = 100)
        }
    }

    fun createSession(name: String, isGateway: Boolean, onCreated: (String, Boolean) -> Unit) {
        if (isGateway) {
            val id = "chat-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
            viewModelScope.launch {
                nodeRuntime.patchChatSession(id, name.trim())
                onCreated(id, true)
            }
        } else {
            viewModelScope.launch {
                val id = chatRepository.createSession(name.trim())
                onCreated(id, false)
            }
        }
    }

    fun setUseNodeChat(useNodeChat: Boolean) {
        settingsRepository.useNodeChat = useNodeChat
    }

    fun deleteSession(sessionId: String, isGateway: Boolean) {
        if (isGateway) {
            viewModelScope.launch {
                nodeRuntime.deleteChatSession(sessionId)
                nodeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.deleteSession(sessionId)
            }
        }
    }
}
