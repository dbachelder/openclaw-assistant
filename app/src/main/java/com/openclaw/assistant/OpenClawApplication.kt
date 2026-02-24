package com.openclaw.assistant

import android.app.Application
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.security.Security

class OpenClawApplication : Application() {

    val nodeRuntime: com.openclaw.assistant.node.NodeRuntime by lazy {
        com.openclaw.assistant.node.NodeRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        initializeFirebase()
        initializeSecurityProvider()
    }

    private fun initializeFirebase() {
        if (!BuildConfig.FIREBASE_ENABLED) {
            Log.i("OpenClawApp", "Firebase disabled in debug build")
            return
        }
        try {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            Log.i("OpenClawApp", "Firebase Crashlytics initialized")
        } catch (e: Exception) {
            Log.w("OpenClawApp", "Firebase initialization failed - falling back to local logging", e)
        }
    }

    private fun initializeSecurityProvider() {
        try {
            val bcProvider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance() as java.security.Provider
            Security.removeProvider("BC")
            Security.insertProviderAt(bcProvider, 1)
        } catch (e: Throwable) {
            Log.e("OpenClawApp", "Failed to register Bouncy Castle provider", e)
            recordExceptionSafely(e)
        }
    }

    private fun recordExceptionSafely(throwable: Throwable) {
        if (!BuildConfig.FIREBASE_ENABLED) {
            Log.w("OpenClawApp", "Skipping Firebase exception recording (disabled)", throwable)
            return
        }
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (e: Exception) {
            Log.w("OpenClawApp", "Failed to record exception to Firebase", e)
        }
    }
}
