package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }

    private lateinit var downloadButton: Button
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val resultCode = result.resultCode
            val data: Intent? = result.data

            Log.d(TAG, "Screen capture result: resultCode=$resultCode, data=$data")

            if (resultCode == Activity.RESULT_OK && data != null) {
                // FIX: Pada Android 14+ (API 34), MediaProjection token hanya valid
                // jika CaptureService di-start SEGERA dari callback ini, tanpa delay.
                // Jangan pakai postDelayed atau handler delay di sini.
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                Log.d(TAG, "Starting CaptureService...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.w(TAG, "Screen capture denied or cancelled")
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_LONG).show()
                stopService(Intent(this, OverlayService::class.java))
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

        // FIX: Jangan start OverlayService sebelum MediaProjection dialog.
        // Di Android 14+, system mensyaratkan foreground service dengan type mediaProjection
        // harus di-start dalam konteks yang sama dengan saat user grant permission.
        // OverlayService (dataSync) di-start duluan menyebabkan konflik foreground service type.
        //
        // Urutan yang benar:
        // 1. Request screen capture permission (dialog muncul)
        // 2. Setelah user OK → start CaptureService (mediaProjection type)
        // 3. CaptureService notify OverlayService untuk mulai via broadcast
        //
        // Tapi karena OverlayService perlu visible sebelum capture,
        // kita start OverlayService TANPA delay, lalu request capture SEGERA.
        // Tidak perlu postDelayed — overlay tidak perlu fully drawn sebelum dialog.

        Log.d(TAG, "Starting OverlayService...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            startService(Intent(this, OverlayService::class.java))
        }

        // Langsung request — tidak perlu delay 500ms
        // Android akan handle ordering secara internal
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        Log.d(TAG, "Requesting screen capture permission...")
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
