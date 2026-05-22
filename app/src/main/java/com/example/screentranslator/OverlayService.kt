package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    // Full-screen transparent view: hanya menggambar kotak terjemahan, tidak touchable
    private var boxesView: OverlayBoxesView? = null
    // Control bubble kecil: touchable, bisa di-drag
    private var controlView: OverlayView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var handler: Handler? = null
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private var posX = 100
    private var posY = 300

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_UPDATE_BOXES -> {
                    val boxes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(EXTRA_BOXES, BoxData::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(EXTRA_BOXES)
                    }
                    boxes?.let { list ->
                        val overlayBoxes = list.map {
                            OverlayView.Box(Rect(it.left, it.top, it.right, it.bottom), it.text)
                        }
                        boxesView?.updateBoxes(overlayBoxes)
                    }
                }
                ACTION_CLEAR_BOXES -> boxesView?.clearBoxes()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        setupBoxesOverlay()
        setupControlBubble()
        registerUpdateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) { }
        removeOverlays()
        handler?.removeCallbacksAndMessages(null)
    }

    private fun setupBoxesOverlay() {
        val type = overlayType()
        boxesView = OverlayBoxesView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(boxesView, params)
        Log.d(TAG, "Boxes overlay added")
    }

    private fun setupControlBubble() {
        val type = overlayType()
        controlView = OverlayView(this)
        controlView?.setInitialPaused()

        controlView?.onPlayPause = { isPlaying ->
            boxesView?.setPlaying(isPlaying)
            val i = Intent(CaptureService.ACTION_PLAY_PAUSE).apply {
                `package` = packageName
                putExtra(CaptureService.EXTRA_IS_PLAYING, isPlaying)
            }
            sendBroadcast(i)
            if (!isPlaying) boxesView?.clearBoxes()
        }

        controlView?.onStop = {
            sendBroadcast(Intent(CaptureService.ACTION_STOP).apply { `package` = packageName })
            stopSelf()
        }

        controlView?.onDrag = { dx, dy ->
            val params = controlParams ?: return@onDrag
            params.x += dx.toInt()
            params.y += dy.toInt()
            val metrics = resources.displayMetrics
            params.x = params.x.coerceIn(0, metrics.widthPixels - 200)
            params.y = params.y.coerceIn(0, metrics.heightPixels - 200)
            posX = params.x
            posY = params.y
            try { wm.updateViewLayout(controlView, params) } catch (e: Exception) {
                Log.e(TAG, "drag failed", e)
            }
        }

        controlView?.onExpandChanged = {
            val params = controlParams ?: return@onExpandChanged
            try { wm.updateViewLayout(controlView, params) } catch (e: Exception) {
                Log.e(TAG, "expand failed", e)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }
        controlParams = params
        wm.addView(controlView, params)
        Log.d(TAG, "Control bubble added")
    }

    private fun removeOverlays() {
        boxesView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        controlView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        boxesView = null
        controlView = null
        controlParams = null
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun registerUpdateReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_BOXES)
            addAction(ACTION_CLEAR_BOXES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Overlay Control", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Translator")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "overlay_service"
        const val ACTION_UPDATE_BOXES = "com.example.screentranslator.UPDATE_BOXES"
        const val ACTION_CLEAR_BOXES = "com.example.screentranslator.CLEAR_BOXES"
        const val EXTRA_BOXES = "boxes"
    }
}
