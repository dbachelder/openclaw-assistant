package com.openclaw.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val nodeRuntime = (application as OpenClawApplication).nodeRuntime

    val allSessions: StateFlow<List<SessionEntity>> = if (settingsRepository.useNodeChat) {
        nodeRuntime.chatSessions.map { entries ->
            entries.map { entry ->
                SessionEntity(
                    id = entry.key,
                    title = entry.displayName ?: "New Session",
                    createdAt = entry.updatedAtMs ?: System.currentTimeMillis()
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        chatRepository.allSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        if (settingsRepository.useNodeChat) {
            nodeRuntime.refreshChatSessions(limit = 100)
        }
    }

    fun createSession(name: String, onCreated: (String) -> Unit) {
        if (settingsRepository.useNodeChat) {
            val id = "chat-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
            viewModelScope.launch {
                nodeRuntime.patchChatSession(id, name.trim())
                onCreated(id)
            }
        } else {
            viewModelScope.launch {
                val id = chatRepository.createSession(name.trim())
                onCreated(id)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        if (settingsRepository.useNodeChat) {
            viewModelScope.launch {
                val ok = nodeRuntime.deleteChatSession(sessionId)
                if (ok) {
                    nodeRuntime.refreshChatSessions()
                }
            }
        } else {
            viewModelScope.launch {
                chatRepository.deleteSession(sessionId)
            }
        }
    }
}
