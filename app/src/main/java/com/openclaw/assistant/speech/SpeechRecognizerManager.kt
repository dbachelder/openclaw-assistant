package com.openclaw.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * 音声認識マネージャー
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * 音声認識を利用可能かチェック
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 音声認識を開始し、結果をFlowで返す
     * language が null の場合はシステムデフォルトを使用する
     */
    fun startListening(language: String? = null): Flow<SpeechResult> = callbackFlow {
        // デフォルト言語の決定
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()
        
        android.util.Log.e("SpeechRecognizerManager", "startListening called, language=$targetLanguage, isAvailable=${isAvailable()}")

        // Clean slate: Ensure any previous instance is safely destroyed
        destroyInternal()

        // Wait for Android to release internal resources (critical for 2nd+ invocations)
        kotlinx.coroutines.delay(300)

        // Use application context to avoid activity/service lifecycle leaks
        val appContext = context.applicationContext
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = newRecognizer

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechResult.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                trySend(SpeechResult.Processing)
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error ($error)"
                }
                
                android.util.Log.e("SpeechRecognizerManager", "onError: $errorMessage ($error)")
                trySend(SpeechResult.Error(errorMessage, error))
                close()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.Result(
                        text = matches[0],
                        confidence = confidence?.getOrNull(0) ?: 0f,
                        alternatives = matches.drop(1)
                    ))
                } else {
                    trySend(SpeechResult.Error("No results found", SpeechRecognizer.ERROR_NO_MATCH))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.PartialResult(matches[0]))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        // Run on Main thread
        kotlinx.coroutines.Dispatchers.Main.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
             try {
                 newRecognizer.startListening(intent)
             } catch (e: Exception) {
                 trySend(SpeechResult.Error("Start error: ${e.message}"))
                 close()
             }
        })

        awaitClose {
            destroyInternal()
        }
    }

    private fun destroyInternal() {
        recognizer?.let { rec ->
            kotlinx.coroutines.Dispatchers.Main.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
                try {
                    rec.cancel()
                    rec.destroy()
                } catch (e: Exception) {
                    // Ignore
                }
            })
        }
        recognizer = null
    }

    /**
     * Stop listening manually
     */
    fun stopListening() { 
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() { 
        destroyInternal()
    }
}

/**
 * 音声認識の結果
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Listening : SpeechResult()
    object Processing : SpeechResult()
    data class RmsChanged(val rmsdB: Float) : SpeechResult()
    data class PartialResult(val text: String) : SpeechResult()
    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    data class Error(val message: String, val code: Int? = null) : SpeechResult()
}
