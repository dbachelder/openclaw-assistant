package com.openclaw.assistant

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics

class OpenClawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseCrashlytics.getInstance()
            .setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
    }
}
