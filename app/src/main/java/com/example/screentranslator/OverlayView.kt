package com.example.screentranslator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Box(val rect: Rect, val translation: String)

    private val boxes = mutableListOf<Box>()
    var isPlaying = true
        private set
    private var isExpanded = false

    var onPlayPause: ((Boolean) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onExpandChanged: ((Boolean) -> Unit)? = null

    val bubbleRadius = 60f
    private val panelWidth = 300f
    private val panelHeight = 140f
    private val btnRadius = 40f

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private val dragThreshold = 8f

    private val panelRect = RectF()
    private var playBtnCx = 0f
    private var playBtnCy = 0f
    private var stopBtnCx = 0f
    private var stopBtnCy = 0f

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 80, 80, 80)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val btnPlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val btnStopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 200, 50, 50)
        style = Paint.Style.FILL
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 200)
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    fun updateBoxes(newBoxes: List<Box>) {
        synchronized(boxes) {
            boxes.clear()
            boxes.addAll(newBoxes)
        }
        postInvalidate()
    }

    fun clearBoxes() {
        synchronized(boxes) { boxes.clear() }
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bubbleSize = (bubbleRadius * 2).toInt() + 40
        val w = if (isExpanded) bubbleSize + panelWidth.toInt() + 20 else bubbleSize
        val h = if (isExpanded) maxOf(bubbleSize, panelHeight.toInt() + 40) else bubbleSize
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isExpanded) drawPanel(canvas)
        drawBubble(canvas)
    }

    private fun drawBubble(canvas: Canvas) {
        val cx = bubbleRadius + 20f
        val cy = height / 2f
        bubbleBorderPaint.color = if (isPlaying)
            Color.argb(255, 0, 200, 100) else Color.argb(255, 150, 150, 150)
        canvas.drawCircle(cx, cy, bubbleRadius, bubblePaint)
        canvas.drawCircle(cx, cy, bubbleRadius, bubbleBorderPaint)
        canvas.drawText(
            if (isPlaying) "ON" else "II",
            cx, cy + bubbleTextPaint.textSize / 3, bubbleTextPaint
        )
    }

    private fun drawPanel(canvas: Canvas) {
        val bubbleCx = bubbleRadius + 20f
        val bubbleCy = height / 2f
        val px = bubbleCx + bubbleRadius + 10f
        val py = bubbleCy - panelHeight / 2
        panelRect.set(px, py, px + panelWidth, py + panelHeight)

        canvas.drawRoundRect(panelRect, 24f, 24f, panelPaint)
        canvas.drawRoundRect(panelRect, 24f, 24f, panelBorderPaint)

        playBtnCx = px + panelWidth * 0.33f
        playBtnCy = py + panelHeight / 2
        btnPlayPaint.color = if (isPlaying)
            Color.argb(220, 255, 165, 0) else Color.argb(220, 0, 180, 80)
        canvas.drawCircle(playBtnCx, playBtnCy, btnRadius, btnPlayPaint)
        canvas.drawText(
            if (isPlaying) "⏸" else "▶",
            playBtnCx, playBtnCy + btnTextPaint.textSize / 3, btnTextPaint
        )

        stopBtnCx = px + panelWidth * 0.67f
        stopBtnCy = py + panelHeight / 2
        canvas.drawCircle(stopBtnCx, stopBtnCy, btnRadius, btnStopPaint)
        canvas.drawText("■", stopBtnCx, stopBtnCy + btnTextPaint.textSize / 3, btnTextPaint)

        canvas.drawText(
            if (isPlaying) "Pause" else "Play",
            playBtnCx, playBtnCy + btnRadius + 22f, labelPaint
        )
        canvas.drawText("Stop", stopBtnCx, stopBtnCy + btnRadius + 22f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x
                touchStartY = y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - touchStartX
                val dy = y - touchStartY
                if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                    isDragging = true
                }
                if (isDragging) onDrag?.invoke(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) handleTap(x, y)
                return true
            }
        }
        return false
    }

    private fun handleTap(x: Float, y: Float) {
        val bubbleCx = bubbleRadius + 20f
        val bubbleCy = height / 2f
        if (isExpanded) {
            when {
                dist(x, y, playBtnCx, playBtnCy) < btnRadius -> {
                    isPlaying = !isPlaying
                    if (!isPlaying) clearBoxes()
                    onPlayPause?.invoke(isPlaying)
                    postInvalidate()
                }
                dist(x, y, stopBtnCx, stopBtnCy) < btnRadius -> {
                    onStop?.invoke()
                }
                else -> {
                    isExpanded = false
                    onExpandChanged?.invoke(false)
                    requestLayout()
                    postInvalidate()
                }
            }
        } else {
            if (dist(x, y, bubbleCx, bubbleCy) < bubbleRadius + 20f) {
                isExpanded = true
                onExpandChanged?.invoke(true)
                requestLayout()
                postInvalidate()
            }
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
