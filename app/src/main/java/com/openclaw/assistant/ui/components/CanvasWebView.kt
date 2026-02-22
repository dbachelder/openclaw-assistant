package com.openclaw.assistant.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openclaw.assistant.ui.chat.CanvasUiState
import java.io.ByteArrayOutputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CanvasWebView(
    state: CanvasUiState,
    onSnapshotCaptured: (String, String) -> Unit,
    onEvalCompleted: () -> Unit,
    onA2uiProcessed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Handle URL navigation
    LaunchedEffect(state.url, state.lastNavigationTime) {
        if (state.url.isNotEmpty()) {
            webViewInstance?.loadUrl(state.url)
        }
    }

    // Handle JS evaluation
    LaunchedEffect(state.pendingEval) {
        state.pendingEval?.let { code ->
            webViewInstance?.evaluateJavascript(code) {
                onEvalCompleted()
            }
        }
    }

    // Handle A2UI Push
    LaunchedEffect(state.pendingA2uiPush) {
        state.pendingA2uiPush?.let { data ->
            // Assume the canvas listener is window.onA2UIPushed
            val script = "if (window.onA2UIPushed) { window.onA2UIPushed($data); } else { window.postMessage({type: 'a2ui.push', data: $data}, '*'); }"
            webViewInstance?.evaluateJavascript(script) {
                onA2uiProcessed()
            }
        }
    }

    // Handle A2UI Reset
    LaunchedEffect(state.pendingA2uiReset) {
        if (state.pendingA2uiReset) {
            val script = "if (window.onA2UIReset) { window.onA2UIReset(); } else { window.postMessage({type: 'a2ui.reset'}, '*'); }"
            webViewInstance?.evaluateJavascript(script) {
                onA2uiProcessed()
            }
        }
    }

    // Handle Snapshot
    LaunchedEffect(state.pendingSnapshotId) {
        state.pendingSnapshotId?.let { toolCallId ->
            webViewInstance?.let { webView ->
                webView.postDelayed({
                    val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)

                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                    onSnapshotCaptured(toolCallId, base64)
                }, 100) // Small delay to ensure rendering is complete
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webViewInstance = this

                if (state.url.isNotEmpty()) {
                    loadUrl(state.url)
                }
            }
        },
        update = {
            // Usually we handle updates via LaunchedEffect, but can use this too
        }
    )
}
