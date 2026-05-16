package com.example.screentranslator

import android.app.Application

class CaptureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // MLKit auto-initialized by Google Play Services
        // No manual init needed
    }
}
