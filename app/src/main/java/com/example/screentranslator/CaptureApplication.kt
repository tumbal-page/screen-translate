package com.example.screentranslator

import android.app.Application

class CaptureApplication : Application() {
    private lateinit var logServer: LogServer

    override fun onCreate() {
        super.onCreate()
        logServer = LogServer(8765)
        logServer.start()
        AppLog.i("App", "LogServer started on port 8765")
    }

    override fun onTerminate() {
        super.onTerminate()
        logServer.stop()
    }
}
