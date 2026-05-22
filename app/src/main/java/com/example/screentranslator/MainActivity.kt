package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }

    private lateinit var downloadButton: Button
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val resultCode = result.resultCode
            val data: Intent? = result.data
            android.util.Log.d("MainActivity", "Screen capture result: resultCode=$resultCode, data=$data")
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Start OverlayService dulu
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(Intent(this, OverlayService::class.java))
                } else {
                    startService(Intent(this, OverlayService::class.java))
                }
                // Langsung start CaptureService dengan token
                val serviceIntent = Intent(this, CaptureService::class.java)
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadButton = findViewById(R.id.downloadButton)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)

        downloadButton.setOnClickListener { startDownload() }
        startButton.setOnClickListener { maybeStartTranslator() }

        checkModelAvailability()
    }

    private fun checkModelAvailability() {
        statusText.text = "Checking model..."
        downloadButton.visibility = View.GONE
        startButton.visibility = View.GONE

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        val translator = Translation.getClient(options)

        translator.translate("test")
            .addOnSuccessListener {
                translator.close()
                statusText.text = "Model ready ✓"
                downloadButton.visibility = View.GONE
                startButton.visibility = View.VISIBLE
            }
            .addOnFailureListener {
                translator.close()
                statusText.text = "Model not found. Please download first."
                downloadButton.visibility = View.VISIBLE
                startButton.visibility = View.GONE
            }
    }

    private fun startDownload() {
        statusText.text = "Downloading model..."
        downloadButton.isEnabled = false

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        val translator = Translation.getClient(options)

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.close()
                statusText.text = "Model ready ✓"
                downloadButton.visibility = View.GONE
                startButton.visibility = View.VISIBLE
                downloadButton.isEnabled = true
            }
            .addOnFailureListener { e ->
                translator.close()
                statusText.text = "Download failed. Check internet connection."
                downloadButton.isEnabled = true
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun maybeStartTranslator() {
        if (!ensureNotificationPermission()) return

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow overlay permission then return to start translator",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        android.util.Log.d("MainActivity", "Requesting screen capture permission...")
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        requestScreenCapture.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
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
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                maybeStartTranslator()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
