package com.openclaw.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.gateway.GatewayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val gatewayClient = GatewayClient.getInstance(application)

    private val _gatewaySessions = MutableStateFlow<List<SessionEntity>>(emptyList())

    val allSessions: StateFlow<List<SessionEntity>> = if (settingsRepository.useNodeChat) {
        _gatewaySessions.asStateFlow()
    } else {
        chatRepository.allSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        if (settingsRepository.useNodeChat) {
            viewModelScope.launch {
                val sessions = gatewayClient.getSessions(limit = 100)
                if (sessions != null) {
                    _gatewaySessions.value = sessions.map { entry ->
                        SessionEntity(
                            id = entry.key,
                            title = entry.displayName ?: "New Session",
                            createdAt = entry.updatedAtMs ?: System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    fun createSession(name: String, onCreated: (String) -> Unit) {
        if (settingsRepository.useNodeChat) {
            // Gateway auto-creates the session upon receiving the first message
            val id = UUID.randomUUID().toString()
            onCreated(id)
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
                val ok = gatewayClient.deleteSession(sessionId)
                if (ok) {
                    refreshSessions()
                }
            }
        } else {
            viewModelScope.launch {
                chatRepository.deleteSession(sessionId)
            }
        }
    }
}
