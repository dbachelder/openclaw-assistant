package com.openclaw.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService

/**
 * Start hotword service on boot
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")
            
            val settings = SettingsRepository.getInstance(context)
            
            if (settings.hotwordEnabled && settings.isConfigured()) {
                if (hasRequiredPermissions(context)) {
                    Log.d(TAG, "Starting HotwordService on boot")
                    HotwordService.start(context)
                } else {
                    Log.w(TAG, "Required permissions not granted, skipping HotwordService on boot")
                }
            }
        }
    }

    /**
     * Check if all required permissions are granted for the hotword service.
     * On Android 14+ (UPSIDE_DOWN_CAKE), this includes FOREGROUND_SERVICE_MICROPHONE.
     */
    private fun hasRequiredPermissions(context: Context): Boolean {
        // Always require RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        // On Android 14+, also require FOREGROUND_SERVICE_MICROPHONE permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }
}
