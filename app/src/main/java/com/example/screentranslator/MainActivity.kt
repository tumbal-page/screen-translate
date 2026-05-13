package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity requests the necessary permissions (overlay and screen capture)
 * then starts the foreground ScreenTranslatorService. A simple button allows
 * users to initiate the process.
 */
class MainActivity : AppCompatActivity() {

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val resultCode = result.resultCode
            val data: Intent? = result.data
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Start foreground service with projection data
                val serviceIntent = Intent(this, ScreenTranslatorService::class.java)
                serviceIntent.putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, resultCode)
                serviceIntent.putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(
                    this,
                    "Screen capture permission denied. Cannot start translator.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton: Button = findViewById(R.id.startButton)
        startButton.setOnClickListener {
            maybeStartTranslator()
        }
    }

    private fun maybeStartTranslator() {
        // Request overlay permission if not granted
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Toast.makeText(
                this,
                "Allow overlay permission then return to start translator",
                Toast.LENGTH_LONG
            ).show()
            startActivity(intent)
            return
        }
        // Request screen capture permission
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        requestScreenCapture.launch(captureIntent)
    }
}