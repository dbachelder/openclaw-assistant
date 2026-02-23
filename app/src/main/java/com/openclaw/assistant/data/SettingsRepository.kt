package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.assistant.Constants
import java.util.UUID

/**
 * Secure settings storage
 */
class SettingsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        Constants.PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // HTTP Server URL (required)
    var httpUrl: String
        get() = prefs.getString(Constants.KEY_HTTP_URL, "") ?: ""
        set(value) {
            if (value != httpUrl) {
                prefs.edit().putString(Constants.KEY_HTTP_URL, value).apply()
                isVerified = false
            }
        }

    // Auth Token (optional)
    var authToken: String
        get() = prefs.getString(Constants.KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_AUTH_TOKEN, value).apply()

    // Session ID (auto-generated)
    var sessionId: String
        get() {
            val existing = prefs.getString(Constants.KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(Constants.KEY_SESSION_ID, value).apply()



    // Hotword enabled
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_HOTWORD_ENABLED, value).apply()

    // Wake word selection (preset or custom)
    var wakeWordPreset: String
        get() = prefs.getString(Constants.KEY_WAKE_WORD_PRESET, Constants.WAKE_WORD_OPEN_CLAW) ?: Constants.WAKE_WORD_OPEN_CLAW
        set(value) = prefs.edit().putString(Constants.KEY_WAKE_WORD_PRESET, value).apply()

    // Custom wake word (when preset is "custom")
    var customWakeWord: String
        get() = prefs.getString(Constants.KEY_CUSTOM_WAKE_WORD, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_CUSTOM_WAKE_WORD, value).apply()

    // Get the actual wake words list for Vosk
    fun getWakeWords(): List<String> {
        return when (wakeWordPreset) {
            Constants.WAKE_WORD_OPEN_CLAW -> listOf("open claw")
            Constants.WAKE_WORD_HEY_ASSISTANT -> listOf("hey assistant")
            Constants.WAKE_WORD_JARVIS -> listOf("jarvis")
            Constants.WAKE_WORD_COMPUTER -> listOf("computer")
            Constants.WAKE_WORD_CUSTOM -> {
                val custom = customWakeWord.trim().lowercase()
                if (custom.isNotEmpty()) listOf(custom) else listOf("open claw")
            }
            else -> listOf("open claw")
        }
    }

    // Get display name for current wake word
    fun getWakeWordDisplayName(): String {
        return when (wakeWordPreset) {
            Constants.WAKE_WORD_OPEN_CLAW -> "Open Claw"
            Constants.WAKE_WORD_HEY_ASSISTANT -> "Hey Assistant"
            Constants.WAKE_WORD_JARVIS -> "Jarvis"
            Constants.WAKE_WORD_COMPUTER -> "Computer"
            Constants.WAKE_WORD_CUSTOM -> customWakeWord.ifEmpty { "Custom" }
            else -> "Open Claw"
        }
    }

    // TTS enabled
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_TTS_ENABLED, true) // Default true as per user request
        set(value) = prefs.edit().putBoolean(Constants.KEY_TTS_ENABLED, value).apply()

    // Continuous mode
    var continuousMode: Boolean
        get() = prefs.getBoolean(Constants.KEY_CONTINUOUS_MODE, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_CONTINUOUS_MODE, value).apply()

    // Resume Latest Session
    var resumeLatestSession: Boolean
        get() = prefs.getBoolean(Constants.KEY_RESUME_LATEST_SESSION, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_RESUME_LATEST_SESSION, value).apply()

    // TTS Speed
    var ttsSpeed: Float
        get() = prefs.getFloat(Constants.KEY_TTS_SPEED, 1.2f)
        set(value) = prefs.edit().putFloat(Constants.KEY_TTS_SPEED, value).apply()

    // TTS Engine
    var ttsEngine: String
        get() = prefs.getString(Constants.KEY_TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_TTS_ENGINE, value).apply()

    // Gateway Port for WebSocket agent list connection (default 18789)

    var gatewayPort: Int
        get() = prefs.getInt(Constants.KEY_GATEWAY_PORT, 18789)
        set(value) = prefs.edit().putInt(Constants.KEY_GATEWAY_PORT, value).apply()

    // Speech recognition silence timeout in ms (default 5000ms)
    var speechSilenceTimeout: Long
        get() = prefs.getLong(Constants.KEY_SPEECH_SILENCE_TIMEOUT, 5000L)
        set(value) = prefs.edit().putLong(Constants.KEY_SPEECH_SILENCE_TIMEOUT, value).apply()

    // Speech recognition language (BCP-47 tag, empty = system default)
    var speechLanguage: String
        get() = prefs.getString(Constants.KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_SPEECH_LANGUAGE, value).apply()

    // Thinking sound enabled
    var thinkingSoundEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_THINKING_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(Constants.KEY_THINKING_SOUND_ENABLED, value).apply()

    // Connection Verified
    var isVerified: Boolean
        get() = prefs.getBoolean(Constants.KEY_IS_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_IS_VERIFIED, value).apply()

    // Default Agent ID
    var defaultAgentId: String
        get() = prefs.getString(Constants.KEY_DEFAULT_AGENT_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(Constants.KEY_DEFAULT_AGENT_ID, value).apply()

    // Use NodeRuntime-backed chat pipeline (chat.send / chat history from gateway)
    var useNodeChat: Boolean
        get() = prefs.getBoolean(Constants.KEY_USE_NODE_CHAT, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_USE_NODE_CHAT, value).apply()

    // Connection Type (Gateway vs Legacy)
    var connectionType: String
        get() = prefs.getString(Constants.KEY_CONNECTION_TYPE, Constants.CONNECTION_TYPE_GATEWAY) ?: Constants.CONNECTION_TYPE_GATEWAY
        set(value) = prefs.edit().putString(Constants.KEY_CONNECTION_TYPE, value).apply()

    /**
     * Get the chat completions URL.
     * Supports both base URL (http://server) and full path (http://server/v1/chat/completions).
     */
    fun getChatCompletionsUrl(): String {
        val url = httpUrl.trim().trimEnd('/')
        if (url.isBlank()) return ""
        return if (url.contains("/v1/")) url
        else "$url/v1/chat/completions"
    }

    /**
     * Get the base URL (without path) for WebSocket connections.
     * Extracts base from full path URLs, or returns as-is for base URLs.
     */
    fun getBaseUrl(): String {
        val url = httpUrl.trimEnd('/')
        val idx = url.indexOf("/v1/")
        return if (idx > 0) url.substring(0, idx) else url
    }

    // Check if configured
    fun isConfigured(): Boolean {
        return httpUrl.isNotBlank() && isVerified
    }

    // Generate new session ID
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // Reset session
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        // Wake word presets
        const val WAKE_WORD_OPEN_CLAW = Constants.WAKE_WORD_OPEN_CLAW
        const val WAKE_WORD_HEY_ASSISTANT = Constants.WAKE_WORD_HEY_ASSISTANT
        const val WAKE_WORD_JARVIS = Constants.WAKE_WORD_JARVIS
        const val WAKE_WORD_COMPUTER = Constants.WAKE_WORD_COMPUTER
        const val WAKE_WORD_CUSTOM = Constants.WAKE_WORD_CUSTOM
        
        const val CONNECTION_TYPE_GATEWAY = Constants.CONNECTION_TYPE_GATEWAY
        const val CONNECTION_TYPE_HTTP = Constants.CONNECTION_TYPE_HTTP
        
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"



        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
