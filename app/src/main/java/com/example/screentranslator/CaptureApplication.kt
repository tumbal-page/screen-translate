package com.example.screentranslator

import android.app.Application
import com.google.mlkit.common.MlKit

class CaptureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MlKit.initialize(this)
        } catch (e: IllegalStateException) {
            // Already initialized, ignore
        }
    }
}
