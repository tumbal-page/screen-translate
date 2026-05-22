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

    private var overlayView: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
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
                        overlayView?.updateBoxes(overlayBoxes)
                    }
                }
                ACTION_CLEAR_BOXES -> overlayView?.clearBoxes()
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
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        setupOverlay()
        registerUpdateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) { }
        removeOverlay()
        handler?.removeCallbacksAndMessages(null)
    }

    private fun setupOverlay() {
        overlayView = OverlayView(this)
        overlayView?.setInitialPaused()

        overlayView?.onPlayPause = { isPlaying ->
            val i = Intent(CaptureService.ACTION_PLAY_PAUSE).apply {
                `package` = packageName
                putExtra(CaptureService.EXTRA_IS_PLAYING, isPlaying)
            }
            sendBroadcast(i)
            if (!isPlaying) overlayView?.clearBoxes()
        }

        overlayView?.onStop = {
            sendBroadcast(Intent(CaptureService.ACTION_STOP).apply { `package` = packageName })
            stopSelf()
        }

        overlayView?.onDrag = { dx, dy ->
            val params = overlayParams
            if (params != null) {
                params.x += dx.toInt()
                params.y += dy.toInt()
                val metrics = resources.displayMetrics
                params.x = params.x.coerceIn(0, metrics.widthPixels - 200)
                params.y = params.y.coerceIn(0, metrics.heightPixels - 200)
                posX = params.x
                posY = params.y
                try {
                    wm.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "updateViewLayout drag failed", e)
                }
            }
        }

        overlayView?.onExpandChanged = {
            val params = overlayParams
            if (params != null) {
                try {
                    wm.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "updateViewLayout expand failed", e)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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

        overlayParams = params
        wm.addView(overlayView, params)
        Log.d(TAG, "Overlay added")
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { wm.removeView(it) } catch (e: Exception) { Log.e(TAG, "removeOverlay failed", e) }
        }
        overlayView = null
        overlayParams = null
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
