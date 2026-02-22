package com.openclaw.assistant.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    suspend fun takePicture(lifecycleOwner: LifecycleOwner): File = suspendCancellableCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                    .format(System.currentTimeMillis())
                val photoFile = File(context.filesDir, "$name.jpg")

                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                            if (continuation.isActive) {
                                continuation.resumeWithException(exc)
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                            if (continuation.isActive) {
                                continuation.resume(photoFile)
                            }
                        }
                    }
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                if (continuation.isActive) {
                    continuation.resumeWithException(exc)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun recordVideo(lifecycleOwner: LifecycleOwner, durationMs: Long = 5000, includeAudio: Boolean = true): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)

                    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                        .format(System.currentTimeMillis())
                    val videoFile = File(context.filesDir, "$name.mp4")

                    val outputOptions = FileOutputOptions.Builder(videoFile).build()

                    val pendingRecording = recorder.prepareRecording(context, outputOptions)
                    if (includeAudio) {
                        try {
                            pendingRecording.withAudioEnabled()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Audio recording permission missing for video", e)
                        }
                    }

                    val recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                        when (recordEvent) {
                            is VideoRecordEvent.Finalize -> {
                                if (!recordEvent.hasError()) {
                                    Log.d(TAG, "Video capture succeeded: ${videoFile.absolutePath}")
                                    if (continuation.isActive) {
                                        continuation.resume(videoFile)
                                    }
                                } else {
                                    Log.e(TAG, "Video capture failed: ${recordEvent.error}")
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(RuntimeException("Video capture failed: ${recordEvent.error}"))
                                    }
                                }
                            }
                        }
                    }

                    // Stop recording after duration
                    val stopJob = launch {
                        delay(durationMs)
                        recording.stop()
                    }

                    continuation.invokeOnCancellation {
                        stopJob.cancel()
                        recording.stop()
                    }

                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                    if (continuation.isActive) {
                        continuation.resumeWithException(exc)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
