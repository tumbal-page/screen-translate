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
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val resultCode = result.resultCode
            val data: Intent? = result.data
            if (resultCode == Activity.RESULT_OK && data != null) {
                
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
        if (!ensureNotificationPermission()) {
            return
        }

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
        
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        requestScreenCapture.launch(captureIntent)
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_POST_NOTIFICATIONS
            )
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_POST_NOTIFICATIONS) {
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            maybeStartTranslator()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to show the translator service status.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}