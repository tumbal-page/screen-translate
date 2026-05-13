package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.DisplayManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
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

/**
 * ScreenTranslatorService uses MediaProjection to capture the screen, recognizes
 * English text with ML Kit, translates it to Indonesian, and draws translated
 * text in an overlay. This service runs in the foreground to avoid being
 * killed by the OS. This implementation is simplified for demonstration and
 * may require further optimization for production use.
 */
class ScreenTranslatorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var overlayView: OverlayView? = null
    private lateinit var translator: Translator
    private lateinit var recognizer: TextRecognizer
    private val translationCache = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        setupMlKitClients()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification())
        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        translator.close()
        recognizer.close()
        removeOverlay()
        handler?.removeCallbacksAndMessages(null)
    }

    private fun setupMlKitClients() {
        // Prepare translation client for English -> Indonesian
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)
        // Download model asynchronously
        translator.downloadModelIfNeeded()

        // Prepare text recognizer for Latin script (good for English)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun setupOverlay() {
        // Initialize overlay view and attach it to WindowManager
        overlayView = OverlayView(this)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(overlayView, params)
    }

    private fun removeOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
        }
        overlayView = null
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display = wm.defaultDisplay
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
        // Set a listener to receive image frames
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                processFrame(image)
                image.close()
            }
        }, handler)
    }

    private fun stopProjection() {
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * Converts the captured Image into a Bitmap and runs recognition and
     * translation on it. Translations are cached to minimize duplicate
     * translations.
     */
    private fun processFrame(image: Image) {
        val bitmap = imageToBitmap(image) ?: return
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { text: Text -> handleTextBlocks(text) }
            .addOnFailureListener { e: Exception -> Log.e(TAG, "Recognition failed", e) }
    }

    private fun handleTextBlocks(result: Text) {
        val boxTranslations = mutableListOf<OverlayView.Box>()
        // Iterate over each line for better granularity
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val boundingBox = line.boundingBox
                val text = line.text.trim()
                if (boundingBox != null && text.isNotEmpty()) {
                    val cached = translationCache[text]
                    if (cached != null) {
                        boxTranslations.add(OverlayView.Box(boundingBox, cached))
                    } else {
                        translator.translate(text)
                            .addOnSuccessListener { translated: String ->
                                translationCache[text] = translated
                                boxTranslations.add(OverlayView.Box(boundingBox, translated))
                                overlayView?.updateBoxes(boxTranslations)
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Translation failed", e)
                            }
                    }
                }
            }
        }
        // If we added any translations synchronously (from cache), update overlay
        if (boxTranslations.isNotEmpty()) {
            overlayView?.updateBoxes(boxTranslations)
        }
    }

    /**
     * Converts an RGBA_8888 Image into a Bitmap. Returns null if conversion
     * fails. Note: For production use, more efficient conversion should be
     * implemented. This method assumes all planes share the same pixel stride.
     */
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
            // Crop out padding to get the actual size
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Screen Translator")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
        return builder.build()
    }

    companion object {
        private const val TAG = "ScreenTranslatorService"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "screen_translator"
    }
}