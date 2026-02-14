package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Simple webhook client - POSTs to the configured URL
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * POST message to webhook URL and return response
     */
    suspend fun sendMessage(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        try {
            // OpenAI Chat Completions format for /v1/chat/completions
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw/voice-agent")
                addProperty("user", sessionId)
                val messagesArray = JsonArray()
                val userMessage = JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", message)
                }
                messagesArray.add(userMessage)
                add("messages", messagesArray)
            }

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)  // Use URL as-is
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response")
                    )
                }

                // Extract response text from JSON
                val parsed = parseResponse(responseBody)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST message and stream the response as SSE events.
     * Adds stream=true and Accept: text/event-stream.
     */
    fun sendMessageStream(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null
    ): Flow<StreamEvent> = flow {
        val requestBody = JsonObject().apply {
            addProperty("model", "openclaw/voice-agent")
            addProperty("user", sessionId)
            addProperty("stream", true)
            val messagesArray = JsonArray()
            val userMessage = JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", message)
            }
            messagesArray.add(userMessage)
            add("messages", messagesArray)
        }

        val jsonBody = gson.toJson(requestBody)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val streamUrl = deriveStreamUrl(webhookUrl)

        val requestBuilder = Request.Builder()
            .url(streamUrl)
            .post(jsonBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")

        if (!authToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: response.message
            emit(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
            response.close()
            return@flow
        }

        val body = response.body ?: run {
            emit(StreamEvent.Error("Empty response body"))
            response.close()
            return@flow
        }

        val reader = BufferedReader(InputStreamReader(body.byteStream()))
        var currentEvent = "text"
        var dataBuffer = StringBuilder()

        try {
            var line = reader.readLine()
            while (line != null) {
                when {
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        dataBuffer.append(line.removePrefix("data:").trim())
                    }
                    line.isBlank() && dataBuffer.isNotEmpty() -> {
                        val data = dataBuffer.toString()
                        dataBuffer = StringBuilder()

                        if (data == "[DONE]") {
                            break
                        }

                        val event = parseSSEEvent(currentEvent, data)
                        if (event != null) {
                            emit(event)
                        }
                        currentEvent = "text"
                    }
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Test connection to the webhook
     */
    suspend fun testConnection(
        webhookUrl: String,
        authToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Try a HEAD request first (lightweight)
            var requestBuilder = Request.Builder()
                .url(webhookUrl)
                .head()

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            var request = requestBuilder.build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                    // If Method Not Allowed (405), try POST
                    if (response.code == 405) {
                         // Fallthrough to POST
                    } else {
                         return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                // Fallthrough to POST on error (some servers reject HEAD)
            }

            // Fallback: POST with minimal OpenAI format
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw/voice-agent")
                addProperty("user", "connection-test")
                val messagesArray = JsonArray()
                val testMessage = JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                }
                messagesArray.add(testMessage)
                add("messages", messagesArray)
            }
            
            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            request = requestBuilder.build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errorBody = response.body?.string() ?: response.message
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse full JSON response into OpenClawResponse
     */
    private fun parseResponse(json: String): OpenClawResponse {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)

            // Check for API error response
            obj.getAsJsonObject("error")?.let { error ->
                val errorMsg = error.get("message")?.asString ?: "Unknown error"
                return OpenClawResponse(error = "API Error: $errorMsg")
            }

            val text = obj.getAsJsonArray("choices")?.let { choices ->
                choices.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
            }
            ?: obj.get("response")?.asString
            ?: obj.get("text")?.asString
            ?: obj.get("message")?.asString
            ?: obj.get("content")?.asString
            ?: json

            val audioUrl = obj.get("audio_url")?.asString
            val model = obj.get("model")?.asString

            OpenClawResponse(response = text, audioUrl = audioUrl, model = model)
        } catch (e: IOException) {
            OpenClawResponse(error = e.message)
        } catch (e: Exception) {
            OpenClawResponse(response = json)
        }
    }

    /**
     * Derive a streaming URL from the base webhook URL.
     * e.g. /v1/chat/completions -> /v1/chat/completions (with stream=true in body)
     */
    private fun deriveStreamUrl(webhookUrl: String): String {
        // Just use the same URL — streaming is indicated by stream=true in the body
        return webhookUrl
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null,
    val audioUrl: String? = null,
    val model: String? = null
) {
    fun getResponseText(): String? = response
    fun hasServerAudio(): Boolean = !audioUrl.isNullOrBlank()
}
