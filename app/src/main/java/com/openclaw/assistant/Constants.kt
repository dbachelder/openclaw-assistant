package com.openclaw.assistant

/**
 * Centralized constants for the OpenClaw Assistant app
 */
object Constants {
    
    // Service IDs and Notifications
    const val HOTWORD_NOTIFICATION_ID = 1001
    const val NODE_NOTIFICATION_ID = 1002
    const val SESSION_NOTIFICATION_ID = 1003
    
    const val HOTWORD_CHANNEL_ID = "hotword_channel"
    const val NODE_CHANNEL_ID = "node_channel"
    const val SESSION_CHANNEL_ID = "session_channel"
    
    // Audio and Speech
    const val SAMPLE_RATE = 16000.0f
    const val AUDIO_BUFFER_SIZE = 1024
    
    // Timeouts (in milliseconds)
    const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    const val PENDING_SESSION_TIMEOUT_MS = 30_000L // 30 seconds
    const val PENDING_RUN_TIMEOUT_MS = 120_000L // 2 minutes
    const val CONNECTION_TIMEOUT_MS = 30_000L // 30 seconds
    const val READ_TIMEOUT_MS = 120_000L // 2 minutes
    const val WRITE_TIMEOUT_MS = 30_000L // 30 seconds
    const val UPDATE_CHECK_TIMEOUT_MS = 5_000L // 5 seconds
    
    // Retry Configuration
    const val MAX_AUDIO_RETRIES = 5
    const val MAX_CONNECTION_RETRIES = 3
    
    // Gateway and Discovery
    const val GATEWAY_SERVICE_TYPE = "_openclaw-gw._tcp."
    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_HTTPS_PORT = 8443
    
    // API Endpoints
    const val GITHUB_API_RELEASES = "https://api.github.com/repos/yuga-hashimoto/openclaw-assistant/releases/latest"
    
    // Wake Words
    const val WAKE_WORD_OPEN_CLAW = "open_claw"
    const val WAKE_WORD_HEY_ASSISTANT = "hey_assistant"
    const val WAKE_WORD_JARVIS = "jarvis"
    const val WAKE_WORD_COMPUTER = "computer"
    const val WAKE_WORD_CUSTOM = "custom"
    
    // Preference Keys (must match existing keys for backward compatibility)
    const val PREFS_NAME = "openclaw_secure_prefs"
    const val KEY_HTTP_URL = "webhook_url"  // Note: kept as "webhook_url" for backward compatibility
    const val KEY_AUTH_TOKEN = "auth_token"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_HOTWORD_ENABLED = "hotword_enabled"
    const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
    const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
    const val KEY_TTS_ENABLED = "tts_enabled"
    const val KEY_CONTINUOUS_MODE = "continuous_mode"
    const val KEY_IS_VERIFIED = "is_verified"
    const val KEY_RESUME_LATEST_SESSION = "resume_latest_session"
    const val KEY_TTS_SPEED = "tts_speed"
    const val KEY_TTS_ENGINE = "tts_engine"
    const val KEY_GATEWAY_PORT = "gateway_port"
    const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
    const val KEY_USE_NODE_CHAT = "use_node_chat"
    const val KEY_CONNECTION_TYPE = "connection_type"
    const val KEY_SPEECH_SILENCE_TIMEOUT = "speech_silence_timeout"
    const val KEY_THINKING_SOUND_ENABLED = "thinking_sound_enabled"
    const val KEY_SPEECH_LANGUAGE = "speech_language"
    
    // Intent Actions
    const val ACTION_SHOW_ASSISTANT = "com.openclaw.assistant.ACTION_SHOW_ASSISTANT"
    const val ACTION_RESUME_HOTWORD = "com.openclaw.assistant.ACTION_RESUME_HOTWORD"
    const val ACTION_PAUSE_HOTWORD = "com.openclaw.assistant.ACTION_PAUSE_HOTWORD"
    
    // Connection Types
    const val CONNECTION_TYPE_GATEWAY = "gateway"
    const val CONNECTION_TYPE_HTTP = "http"

    // TTS
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    // Log Tags
    const val TAG_HOTWORD = "HotwordService"
    const val TAG_ASSISTANT = "OpenClawAssistantSvc"
    const val TAG_GATEWAY = "OpenClaw/Gateway"
    const val TAG_UPDATE = "UpdateChecker"
    const val TAG_CHAT = "ChatController"
}