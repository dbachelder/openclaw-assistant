package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.CameraHudKind
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.gateway.GatewayEndpoint
import com.openclaw.assistant.gateway.GatewaySession
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException

class CameraHandler(
  private val appContext: Context,
  private val camera: CameraCaptureManager,
  private val prefs: SecurePrefs,
  private val connectedEndpoint: () -> GatewayEndpoint?,
  private val externalAudioCaptureActive: MutableStateFlow<Boolean>,
  private val showCameraHud: (message: String, kind: CameraHudKind, autoHideMs: Long?) -> Unit,
  private val triggerCameraFlash: () -> Unit,
  private val invokeErrorFromThrowable: (err: Throwable) -> Pair<String, String>,
) {

  suspend fun handleSnap(paramsJson: String?): GatewaySession.InvokeResult {
    val logFile = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "camera_debug.log") else null
    fun camLog(msg: String) {
      if (!BuildConfig.DEBUG) return
      val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
      logFile?.appendText("[$ts] $msg\n")
      android.util.Log.w("openclaw", "camera.snap: $msg")
    }
    try {
      logFile?.writeText("") // clear
      camLog("starting, params=$paramsJson")
      camLog("calling showCameraHud")
      showCameraHud("Taking photo…", CameraHudKind.Photo, null)
      camLog("calling triggerCameraFlash")
      triggerCameraFlash()
      val res =
        try {
          camLog("calling camera.snap()")
          val r = camera.snap(paramsJson)
          camLog("success, payload size=${r.payloadJson.length}")
          r
        } catch (err: Throwable) {
          camLog("inner error: ${err::class.java.simpleName}: ${err.message}")
          camLog("stack: ${err.stackTraceToString().take(2000)}")
          val (code, message) = invokeErrorFromThrowable(err)
          showCameraHud(message, CameraHudKind.Error, 2200)
          return GatewaySession.InvokeResult.error(code = code, message = message)
        }
      camLog("returning result")
      showCameraHud("Photo captured", CameraHudKind.Success, 1600)
      return GatewaySession.InvokeResult.ok(res.payloadJson)
    } catch (err: Throwable) {
      camLog("outer error: ${err::class.java.simpleName}: ${err.message}")
      camLog("stack: ${err.stackTraceToString().take(2000)}")
      return GatewaySession.InvokeResult.error(code = "UNAVAILABLE", message = err.message ?: "camera snap failed")
    }
  }

  suspend fun handleClip(paramsJson: String?): GatewaySession.InvokeResult {
    val clipLogFile = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "camera_debug.log") else null
    fun clipLog(msg: String) {
      if (!BuildConfig.DEBUG) return
      val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
      clipLogFile?.appendText("[CLIP $ts] $msg\n")
      android.util.Log.w("openclaw", "camera.clip: $msg")
    }
    val includeAudio = paramsJson?.contains("\"includeAudio\":true") != false
    if (includeAudio) externalAudioCaptureActive.value = true
    try {
      clipLogFile?.writeText("") // clear
      clipLog("starting, params=$paramsJson includeAudio=$includeAudio")
      clipLog("calling showCameraHud")
      showCameraHud("Recording…", CameraHudKind.Recording, null)
      val filePayload =
        try {
          clipLog("calling camera.clip()")
          val r = camera.clip(paramsJson)
          clipLog("success, file size=${r.file.length()}")
          r
        } catch (err: Throwable) {
          clipLog("inner error: ${err::class.java.simpleName}: ${err.message}")
          clipLog("stack: ${err.stackTraceToString().take(2000)}")
          val (code, message) = invokeErrorFromThrowable(err)
          showCameraHud(message, CameraHudKind.Error, 2400)
          return GatewaySession.InvokeResult.error(code = code, message = message)
        }
      // Upload file via HTTP instead of base64 through WebSocket
      clipLog("uploading via HTTP...")
      return uploadClipWithFallback(filePayload) { msg -> clipLog(msg) }
    } catch (err: Throwable) {
      clipLog("outer error: ${err::class.java.simpleName}: ${err.message}")
      clipLog("stack: ${err.stackTraceToString().take(2000)}")
      return GatewaySession.InvokeResult.error(code = "UNAVAILABLE", message = err.message ?: "camera clip failed")
    } finally {
      if (includeAudio) externalAudioCaptureActive.value = false
    }
  }

  /**
   * Uploads the clip via HTTP with fallback to base64 encoding.
   * Returns InvokeResult for success (URL) or base64 fallback.
   */
  private suspend fun uploadClipWithFallback(
    filePayload: CameraCaptureManager.FilePayload,
    clipLog: (String) -> Unit
  ): GatewaySession.InvokeResult {
    return try {
      val uploadUrl = performHttpUpload(filePayload, clipLog)
      clipLog("returning URL result: $uploadUrl")
      showCameraHud("Clip captured", CameraHudKind.Success, 1800)
      GatewaySession.InvokeResult.ok(
        """{"format":"mp4","url":"$uploadUrl","durationMs":${filePayload.durationMs},"hasAudio":${filePayload.hasAudio}}"""
      )
    } catch (err: Throwable) {
      clipLog("upload failed: ${err.message}, falling back to base64")
      fallbackToBase64(filePayload, clipLog, err)
    }
  }

  /**
   * Performs the HTTP upload and returns the URL from the response.
   * Validates HTTPS scheme and parses JSON response properly.
   */
  private suspend fun performHttpUpload(
    filePayload: CameraCaptureManager.FilePayload,
    clipLog: (String) -> Unit
  ): String = withContext(Dispatchers.IO) {
    if (!filePayload.file.exists() || !filePayload.file.canRead()) {
      throw IOException("clip file not readable: ${filePayload.file.absolutePath}")
    }

    val ep = connectedEndpoint()
    val gatewayHost = if (ep != null) {
      val isHttps = ep.tlsEnabled || ep.port == 443
      if (!isHttps) {
        clipLog("refusing to upload over plain HTTP — bearer token would be exposed; falling back to base64")
        throw IOException("HTTPS required for upload (bearer token protection)")
      }
      if (ep.port == 443) "https://${ep.host}" else "https://${ep.host}:${ep.port}"
    } else {
      clipLog("error: no gateway endpoint connected, cannot upload")
      throw IOException("no gateway endpoint connected")
    }

    val token = prefs.loadGatewayToken() ?: ""
    val client = okhttp3.OkHttpClient.Builder()
      .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
      .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
      .callTimeout(130, java.util.concurrent.TimeUnit.SECONDS)
      .build()

    val body = filePayload.file.asRequestBody("video/mp4".toMediaType())
    val req = okhttp3.Request.Builder()
      .url("$gatewayHost/upload/clip.mp4")
      .put(body)
      .header("Authorization", "Bearer $token")
      .build()

    clipLog("uploading ${filePayload.file.length()} bytes to $gatewayHost/upload/clip.mp4")

    client.newCall(req).execute().use { resp ->
      val respBody = resp.body?.string() ?: ""
      clipLog("upload response: ${resp.code} $respBody")

      if (!resp.isSuccessful) {
        throw IOException("upload failed: HTTP ${resp.code} - ${resp.message}")
      }

      val url = parseUploadUrlFromJson(respBody)
        ?: throw IOException("upload response missing 'url' field: $respBody")

      if (!url.startsWith("https://")) {
        throw IOException("upload returned non-HTTPS URL (security policy violation): $url")
      }

      url
    }
  }

  /**
   * Parses the upload URL from JSON response using Gson.
   * Returns null if parsing fails or URL field is missing.
   */
  private fun parseUploadUrlFromJson(json: String): String? {
    return try {
      val gson = Gson()
      val jsonObject = gson.fromJson(json, JsonObject::class.java)
      jsonObject?.get("url")?.asString
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Fallback to base64 encoding when HTTP upload fails.
   * Ensures temp file is deleted in all paths.
   */
  private suspend fun fallbackToBase64(
    filePayload: CameraCaptureManager.FilePayload,
    clipLog: (String) -> Unit,
    originalError: Throwable
  ): GatewaySession.InvokeResult {
    return try {
      val bytes = withContext(Dispatchers.IO) {
        if (!filePayload.file.exists()) {
          throw IOException("clip file not found for base64 fallback: ${filePayload.file.absolutePath}")
        }
        if (!filePayload.file.canRead()) {
          throw IOException("clip file not readable for base64 fallback: ${filePayload.file.absolutePath}")
        }
        filePayload.file.readBytes()
      }

      val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
      clipLog("base64 fallback successful, encoded ${bytes.size} bytes")
      showCameraHud("Clip captured", CameraHudKind.Success, 1800)
      GatewaySession.InvokeResult.ok(
        """{"format":"mp4","base64":"$base64","durationMs":${filePayload.durationMs},"hasAudio":${filePayload.hasAudio}}"""
      )
    } catch (err: Throwable) {
      clipLog("base64 fallback failed: ${err.message}")
      val message = "upload failed (${originalError.message}) and base64 fallback also failed (${err.message})"
      showCameraHud("Clip failed", CameraHudKind.Error, 2400)
      GatewaySession.InvokeResult.error(code = "UNAVAILABLE", message = message)
    } finally {
      withContext(Dispatchers.IO) {
        if (filePayload.file.exists() && !filePayload.file.delete()) {
          clipLog("warning: failed to delete temp file: ${filePayload.file.absolutePath}")
        }
      }
    }
  }
}
