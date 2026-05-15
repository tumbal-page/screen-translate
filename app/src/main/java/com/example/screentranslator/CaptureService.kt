package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var translator: Translator? = null
    private lateinit var recognizer: TextRecognizer
    private val translationCache = ConcurrentHashMap<String, String>()
    private val isPlaying = AtomicBoolean(true)

    // Receiver untuk command dari OverlayService
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> {
                    val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                    isPlaying.set(playing)
                    if (!playing) {
                        sendBroadcast(Intent(OverlayService.ACTION_CLEAR_BOXES))
                    }
                    Log.d(TAG, "PlayPause: $playing")
                }
                ACTION_STOP -> {
                    Log.d(TAG, "Stop received")
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        // Tidak ada overlay di sini sama sekali
        startForeground(NOTIF_ID, buildNotification())
        setupRecognizer()
        setupTranslator()
        registerCommandReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Update type ke mediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        // Langsung konsumsi token
        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregister failed", e)
        }
        stopProjection()
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

    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
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
            "ScreenCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                if (isPlaying.get()) processFrame(image)
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
        val collectedBoxes = ConcurrentHashMap<Int, BoxData>()

        lines.forEachIndexed { index, line ->
            val rect = line.boundingBox!!
            val text = line.text.trim()
            val cached = translationCache[text]

            if (cached != null) {
                collectedBoxes[index] = BoxData(rect.left, rect.top, rect.right, rect.bottom, cached)
                if (collectedBoxes.size == totalLines) sendBoxesToOverlay(collectedBoxes)
            } else {
                t.translate(text)
                    .addOnSuccessListener { translated ->
                        translationCache[text] = translated
                        collectedBoxes[index] = BoxData(rect.left, rect.top, rect.right, rect.bottom, translated)
                        if (collectedBoxes.size == totalLines) sendBoxesToOverlay(collectedBoxes)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Translation failed: $text", e)
                        collectedBoxes[index] = BoxData(rect.left, rect.top, rect.right, rect.bottom, text)
                        if (collectedBoxes.size == totalLines) sendBoxesToOverlay(collectedBoxes)
                    }
            }
        }
    }

    private fun sendBoxesToOverlay(boxes: ConcurrentHashMap<Int, BoxData>) {
        val intent = Intent(OverlayService.ACTION_UPDATE_BOXES)
        intent.putParcelableArrayListExtra(
            OverlayService.EXTRA_BOXES,
            ArrayList(boxes.values.toList())
        )
        sendBroadcast(intent)
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
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Translator")
            .setContentText("Capturing screen...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "capture_service"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_PLAY_PAUSE = "com.example.screentranslator.PLAY_PAUSE"
        const val ACTION_STOP = "com.example.screentranslator.STOP"
        const val EXTRA_IS_PLAYING = "isPlaying"
    }
}
