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
import java.util.concurrent.atomic.AtomicInteger

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var translator: Translator? = null
    private lateinit var recognizer: TextRecognizer
    private val translationCache = ConcurrentHashMap<String, String>()

    // FIX 1: isPlaying default true — langsung mulai saat service start,
    // Play/Pause dari OverlayService hanya toggle
    private val isPlaying = AtomicBoolean(true)

    // FIX 2: Throttle — jangan proses setiap frame, cukup 1 frame per 500ms
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL_MS = 500L

    // FIX 3: Flag agar tidak proses frame baru sebelum yang lama selesai
    private val isProcessing = AtomicBoolean(false)

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> {
                    val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                    isPlaying.set(playing)
                    if (!playing) {
                        // Kirim clear ke OverlayService (same process = main process via broadcast)
                        sendBroadcastToMain(Intent(OverlayService.ACTION_CLEAR_BOXES))
                    }
                    Log.d(TAG, "PlayPause received: $playing")
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
        startForeground(NOTIF_ID, buildNotification())
        setupRecognizer()
        setupTranslator()
        registerCommandReceiver()
        Log.d(TAG, "CaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Missing resultCode or data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) { }
        stopProjection()
        translator?.close()
        handler?.removeCallbacksAndMessages(null)
        Log.d(TAG, "CaptureService destroyed")
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
        Log.d(TAG, "Command receiver registered")
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection returned null!")
            stopSelf()
            return
        }

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

        Log.d(TAG, "Starting projection: ${width}x${height} @ ${dpi}dpi")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()

            // FIX 5: Throttle — skip frame kalau interval belum cukup atau sedang proses
            if (!isPlaying.get()) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            if (isProcessing.getAndSet(true)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            lastFrameTime = now
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                processFrame(image)
            } else {
                isProcessing.set(false)
            }
        }, handler)

        Log.d(TAG, "VirtualDisplay created, listening for frames")
    }

    private fun stopProjection() {
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun processFrame(image: Image) {
        val bitmap = imageToBitmap(image)
        image.close() // FIX 6: Close image segera setelah konversi, sebelum async
        if (bitmap == null) {
            isProcessing.set(false)
            return
        }
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { text: Text ->
                bitmap.recycle() // FIX 7: Recycle bitmap setelah OCR selesai
                handleTextBlocks(text)
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                isProcessing.set(false)
                Log.e(TAG, "Recognition failed", e)
            }
    }

    private fun handleTextBlocks(result: Text) {
        val t = translator ?: run {
            isProcessing.set(false)
            return
        }

        val lines = result.textBlocks
            .flatMap { it.lines }
            .filter { it.boundingBox != null && it.text.trim().isNotEmpty() }

        if (lines.isEmpty()) {
            Log.d(TAG, "No text found in frame")
            isProcessing.set(false)
            return
        }

        Log.d(TAG, "Found ${lines.size} lines to translate")

        val totalLines = lines.size
        // FIX 8: Gunakan AtomicInteger untuk counter yang thread-safe
        val doneCount = AtomicInteger(0)
        val collectedBoxes = ConcurrentHashMap<Int, BoxData>()

        lines.forEachIndexed { index, line ->
            val rect = line.boundingBox!!
            val text = line.text.trim()
            val cached = translationCache[text]

            fun finalize(translated: String) {
                collectedBoxes[index] = BoxData(rect.left, rect.top, rect.right, rect.bottom, translated)
                // FIX 9: Gunakan counter atomik — bukan size comparison yang bisa miss
                if (doneCount.incrementAndGet() == totalLines) {
                    sendBoxesToOverlay(collectedBoxes)
                    isProcessing.set(false)
                }
            }

            if (cached != null) {
                finalize(cached)
            } else {
                t.translate(text)
                    .addOnSuccessListener { translated ->
                        translationCache[text] = translated
                        finalize(translated)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Translation failed: $text", e)
                        finalize(text) // fallback: tampilkan teks asli
                    }
            }
        }
    }

    // FIX 10: Kirim broadcast dengan package name yang benar agar diterima
    // OverlayService di main process meski CaptureService di :capture process
    private fun sendBroadcastToMain(intent: Intent) {
        intent.`package` = packageName
        sendBroadcast(intent)
    }

    private fun sendBoxesToOverlay(boxes: ConcurrentHashMap<Int, BoxData>) {
        Log.d(TAG, "Sending ${boxes.size} boxes to overlay")
        val intent = Intent(OverlayService.ACTION_UPDATE_BOXES).apply {
            `package` = packageName
            putParcelableArrayListExtra(
                OverlayService.EXTRA_BOXES,
                ArrayList(boxes.values.sortedBy { it.top })
            )
        }
        sendBroadcast(intent)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val buffer: ByteBuffer = plane.buffer

            // FIX 11: Buat bitmap dengan ukuran yang tepat lalu crop — recycle tmp bitmap
            val tmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            tmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(tmp, 0, 0, width, height)
            tmp.recycle() // Recycle temporary bitmap
            cropped
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
