package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenClaw API Client (OpenAI-compatible Chat Completions)
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // OpenClaw responses may take time
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Send user message to OpenClaw and get response
     */
    suspend fun sendMessage(
        baseUrl: String,
        message: String,
        sessionId: String,
        userId: String? = null,
        authToken: String? = null,
        agentId: String = "main"
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        try {
            // Build OpenAI-compatible request
            val messages = listOf(
                ChatMessage(role = "user", content = message)
            )
            
            val requestBody = ChatCompletionRequest(
                model = "openclaw:$agentId",
                messages = messages,
                user = sessionId  // Use session ID as user for session persistence
            )

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            // Append /v1/chat/completions if not already present
            val apiUrl = if (baseUrl.endsWith("/v1/chat/completions")) {
                baseUrl
            } else {
                baseUrl.trimEnd('/') + "/v1/chat/completions"
            }

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            // Add auth token if provided
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response from server")
                    )
                }

                try {
                    val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
                    val assistantMessage = chatResponse.choices?.firstOrNull()?.message?.content
                    Result.success(OpenClawResponse(response = assistantMessage))
                } catch (e: Exception) {
                    // Fallback: try to use raw response
                    Result.success(OpenClawResponse(response = responseBody))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * OpenAI Chat Completion Request
 */
data class ChatCompletionRequest(
    @SerializedName("model")
    val model: String,

    @SerializedName("messages")
    val messages: List<ChatMessage>,

    @SerializedName("user")
    val user: String? = null
)

/**
 * Chat Message
 */
data class ChatMessage(
    @SerializedName("role")
    val role: String,

    @SerializedName("content")
    val content: String
)

/**
 * OpenAI Chat Completion Response
 */
data class ChatCompletionResponse(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("choices")
    val choices: List<Choice>? = null,

    @SerializedName("error")
    val error: ErrorInfo? = null
)

data class Choice(
    @SerializedName("index")
    val index: Int = 0,

    @SerializedName("message")
    val message: ChatMessage? = null,

    @SerializedName("finish_reason")
    val finishReason: String? = null
)

data class ErrorInfo(
    @SerializedName("message")
    val message: String? = null,

    @SerializedName("type")
    val type: String? = null
)

/**
 * OpenClaw Response (simplified for app use)
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    /**
     * Get response text
     */
    fun getResponseText(): String? {
        return response
    }
}
