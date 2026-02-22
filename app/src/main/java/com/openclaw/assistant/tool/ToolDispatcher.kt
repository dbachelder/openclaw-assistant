package com.openclaw.assistant.tool

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.assistant.camera.CameraManager
import java.io.File

class ToolDispatcher(private val context: Context) {
    private val cameraManager = CameraManager(context)
    private val gson = Gson()

    suspend fun dispatch(
        name: String,
        argumentsJson: String?,
        lifecycleOwner: LifecycleOwner
    ): String {
        Log.d(TAG, "Dispatching tool: $name with args: $argumentsJson")

        return when (name) {
            "camera.snap" -> {
                val file = cameraManager.takePicture(lifecycleOwner)
                "Photo captured: ${file.name}"
            }
            "camera.clip" -> {
                val args = try {
                    if (argumentsJson != null) gson.fromJson(argumentsJson, JsonObject::class.java) else null
                } catch (e: Exception) {
                    null
                }
                val duration = args?.get("duration")?.asLong ?: 5000L
                val audio = args?.get("audio")?.asBoolean ?: true
                val file = cameraManager.recordVideo(lifecycleOwner, duration, audio)
                "Video recorded: ${file.name}"
            }
            "node.invoke" -> {
                // Handle node.invoke protocol: usually { "command": "...", "args": { ... } }
                val payload = try {
                    gson.fromJson(argumentsJson, JsonObject::class.java)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid arguments for node.invoke")
                }
                val command = payload.get("command")?.asString
                    ?: throw IllegalArgumentException("Missing command in node.invoke")
                val argsObj = payload.get("args")
                val argsString = argsObj?.let { if (it.isJsonObject) it.toString() else it.asString }
                dispatch(command, argsString, lifecycleOwner)
            }
            else -> {
                throw UnsupportedOperationException("Unknown tool: $name")
            }
        }
    }

    companion object {
        private const val TAG = "ToolDispatcher"
    }
}
