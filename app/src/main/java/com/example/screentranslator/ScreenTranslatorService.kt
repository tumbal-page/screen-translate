package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


class ScreenTranslatorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var overlayView: OverlayView? = null
    private var translator: Translator? = null
    private lateinit var recognizer: TextRecognizer
    private val translationCache = ConcurrentHashMap<String, String>()
    private val isPlaying = AtomicBoolean(true)

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupRecognizer()
        setupTranslator()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        removeOverlay()
        translator?.close()
        handler?.removeCallbacksAndMessages(null)
    }

    private fun setupRecognizer() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun setupTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)
    }

    private fun setupOverlay() {
        overlayView = OverlayView(this)

        // Callback play/pause dari bubble
        overlayView?.onPlayPause = { playing ->
            isPlaying.set(playing)
            Log.d(TAG, "Translator ${if (playing) "resumed" else "paused"}")
        }

        // Callback stop dari bubble
        overlayView?.onStop = {
            Log.d(TAG, "Stop requested from overlay")
            stopSelf()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Hapus FLAG_NOT_TOUCHABLE — overlay sekarang interaktif
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(overlayView, params)
    }

    private fun removeOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay", e)
            }
        }
        overlayView = null
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "ScreenTranslator", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                // Hanya proses frame kalau sedang playing
                if (isPlaying.get()) {
                    processFrame(image)
                }
                image.close()
            }
        }, handler)
    }

    private fun stopProjection() {
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun processFrame(image: Image) {
        val bitmap = imageToBitmap(image) ?: return
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { text: Text -> handleTextBlocks(text) }
            .addOnFailureListener { e -> Log.e(TAG, "Recognition failed", e) }
    }

    private fun handleTextBlocks(result: Text) {
        val t = translator ?: return
        val lines = result.textBlocks
            .flatMap { it.lines }
            .filter { it.boundingBox != null && it.text.trim().isNotEmpty() }

        if (lines.isEmpty()) return

        val totalLines = lines.size
        val collectedBoxes = ConcurrentHashMap<Int, OverlayView.Box>()

        lines.forEachIndexed { index, line ->
            val boundingBox = line.boundingBox!!
            val text = line.text.trim()
            val cached = translationCache[text]

            if (cached != null) {
                collectedBoxes[index] = OverlayView.Box(boundingBox, cached)
                if (collectedBoxes.size == totalLines) {
                    overlayView?.updateBoxes(collectedBoxes.values.toList())
                }
            } else {
                t.translate(text)
                    .addOnSuccessListener { translated ->
                        translationCache[text] = translated
                        collectedBoxes[index] = OverlayView.Box(boundingBox, translated)
                        if (collectedBoxes.size == totalLines) {
                            overlayView?.updateBoxes(collectedBoxes.values.toList())
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Translation failed for: $text", e)
                        collectedBoxes[index] = OverlayView.Box(boundingBox, text)
                        if (collectedBoxes.size == totalLines) {
                            overlayView?.updateBoxes(collectedBoxes.values.toList())
                        }
                    }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val buffer: ByteBuffer = image.planes[0].buffer
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Translator",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.description = "Shows screen translation service status"
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Screen Translator")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenTranslatorService"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "screen_translator"
    }
}
