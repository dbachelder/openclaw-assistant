package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"

/**
 * テキスト読み上げ（TTS）マネージャー
 */
class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        Log.e(TAG, "Initializing TTS with system default engine...")
        // Use system default engine to support Samsung, Google, etc.
        tts = TextToSpeech(context.applicationContext) { status ->
            Log.e(TAG, "TTS init callback, status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                setupVoice()
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                Log.e(TAG, "TTS init FAILED with status=$status")
            }
        }
    }

    private fun setupVoice() {
        val currentLocale = Locale.getDefault()
        Log.e(TAG, "Current system locale: $currentLocale")

        // Try to set language based on system locale
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to Japanese if default fails and it's not already Japanese
            if (currentLocale != Locale.JAPANESE) {
                tts?.setLanguage(Locale.JAPANESE)
            }
        }

        // Try to select the best quality voice for the current language
        try {
            val voices = tts?.voices
            val bestVoice = voices?.filter { it.locale.language == tts?.language?.language }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == tts?.language?.language }

            if (bestVoice != null) {
                tts?.voice = bestVoice
                Log.e(TAG, "Selected voice: ${bestVoice.name} (${bestVoice.locale})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice: ${e.message}")
        }

        // Adjust rate based on language
        val rate = if (tts?.language?.language == "ja") 1.5f else 1.2f
        tts?.setSpeechRate(rate)
        tts?.setPitch(1.0f)
    }

    /**
     * テキストを読み上げ（suspend版）
     */
    suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

        if (isInitialized) {
            applyLanguageForText(text)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            // 初期化待ち
            pendingSpeak = {
                applyLanguageForText(text)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (continuation.isActive && !isInitialized) {
                    pendingSpeak = null
                    continuation.resume(false)
                }
            }, 3000)
        }

        continuation.invokeOnCancellation {
            stop()
        }
    }

    /**
     * テキストの言語に合わせてTTS設定を微調整（簡易判別）
     */
    private fun applyLanguageForText(text: String) {
        // Japanese contains Hiragana or Katakana
        val hasJapanese = text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        if (hasJapanese) {
            tts?.language = Locale.JAPANESE
            tts?.setSpeechRate(1.5f)
        } else if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            // Likely English or other Latin-based
            if (tts?.language?.language != Locale.ENGLISH.language) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.2f)
            }
        }
    }

    /**
     * テキストを読み上げ（Flow版 - 進捗通知あり）
     */
    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }

            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error("読み上げエラー"))
                close()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                trySend(TTSState.Error("読み上げエラー: $errorCode"))
                close()
            }
        }

        if (isInitialized) {
            applyLanguageForText(text)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            trySend(TTSState.Preparing)
        } else {
            trySend(TTSState.Preparing)
            pendingSpeak = {
                applyLanguageForText(text)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }

        awaitClose {
            stop()
        }
    }

    /**
     * キューに追加して読み上げ
     */
    fun speakQueued(text: String) {
        if (isInitialized) {
            applyLanguageForText(text)
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /**
     * 読み上げを停止
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * リソースを解放
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 初期化済みかチェック
     */
    fun isReady(): Boolean = isInitialized
}

/**
 * TTSの状態
 */
sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
