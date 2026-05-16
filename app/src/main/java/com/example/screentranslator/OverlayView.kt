package com.example.screentranslator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Box(val rect: Rect, val translation: String)

    // State
    private val boxes = mutableListOf<Box>()
    private var isPlaying = true
    private var isExpanded = false

    // Callback ke service
    var onPlayPause: ((Boolean) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    // Bubble position
    private var bubbleX = 100f
    private var bubbleY = 300f
    private val bubbleRadius = 60f
    private val panelWidth = 300f
    private val panelHeight = 140f
    private val panelPadding = 20f
    private val btnRadius = 40f

    // Touch tracking
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private val dragThreshold = 10f

    // Paints
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 200, 100)
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
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }

    // Button centers
    private var playBtnCx = 0f
    private var playBtnCy = 0f
    private var stopBtnCx = 0f
    private var stopBtnCy = 0f
    val panelRect = RectF()

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isPlaying) {
            synchronized(boxes) {
                for (box in boxes) {
                    canvas.drawRect(box.rect, rectPaint)
                    drawMultilineText(
                        box.translation,
                        box.rect.left.toFloat(),
                        box.rect.top.toFloat(),
                        box.rect.right - box.rect.left,
                        canvas
                    )
                }
            }
        }

        if (isExpanded) {
            drawPanel(canvas)
        } else {
            drawBubble(canvas)
        }
    }

    private fun drawBubble(canvas: Canvas) {
        bubbleBorderPaint.color = if (isPlaying)
            Color.argb(255, 0, 200, 100)
        else
            Color.argb(255, 150, 150, 150)

        canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, bubblePaint)
        canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, bubbleBorderPaint)

        val label = if (isPlaying) "ON" else "II"
        canvas.drawText(label, bubbleX, bubbleY + bubbleTextPaint.textSize / 3, bubbleTextPaint)
    }

    private fun drawPanel(canvas: Canvas) {
        val screenW = width.toFloat()
        val px = if (bubbleX + bubbleRadius + panelWidth + panelPadding < screenW)
            bubbleX + bubbleRadius + panelPadding
        else
            bubbleX - bubbleRadius - panelWidth - panelPadding

        val py = bubbleY - panelHeight / 2
        panelRect.set(px, py, px + panelWidth, py + panelHeight)

        canvas.drawRoundRect(panelRect, 24f, 24f, panelPaint)
        canvas.drawRoundRect(panelRect, 24f, 24f, panelBorderPaint)

        playBtnCx = px + panelWidth * 0.33f
        playBtnCy = py + panelHeight / 2
        btnPlayPaint.color = if (isPlaying)
            Color.argb(220, 255, 165, 0)
        else
            Color.argb(220, 0, 180, 80)

        canvas.drawCircle(playBtnCx, playBtnCy, btnRadius, btnPlayPaint)
        val playLabel = if (isPlaying) "⏸" else "▶"
        canvas.drawText(playLabel, playBtnCx, playBtnCy + btnTextPaint.textSize / 3, btnTextPaint)

        stopBtnCx = px + panelWidth * 0.67f
        stopBtnCy = py + panelHeight / 2
        canvas.drawCircle(stopBtnCx, stopBtnCy, btnRadius, btnStopPaint)
        canvas.drawText("■", stopBtnCx, stopBtnCy + btnTextPaint.textSize / 3, btnTextPaint)

        canvas.drawText(
            if (isPlaying) "Pause" else "Play",
            playBtnCx, playBtnCy + btnRadius + 22f, labelPaint
        )
        canvas.drawText("Stop", stopBtnCx, stopBtnCy + btnRadius + 22f, labelPaint)

        drawBubble(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // Hanya consume touch kalau mengenai bubble atau panel
        val hitBubble = isTouchOnBubble(x, y)
        val hitPanel = isExpanded && panelRect.contains(x, y)

        if (!hitBubble && !hitPanel) {
            // Touch di luar area interaktif — teruskan ke app di bawah
            return false
        }

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
                if (isDragging && isTouchOnBubble(touchStartX, touchStartY)) {
                    bubbleX += dx
                    bubbleY += dy
                    bubbleX = bubbleX.coerceIn(bubbleRadius, width - bubbleRadius)
                    bubbleY = bubbleY.coerceIn(bubbleRadius, height - bubbleRadius)
                    touchStartX = x
                    touchStartY = y
                    postInvalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    handleTap(x, y)
                }
                return true
            }
        }
        return false
    }

    private fun handleTap(x: Float, y: Float) {
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
                isTouchOnBubble(x, y) -> {
                    isExpanded = false
                    postInvalidate()
                }
                !panelRect.contains(x, y) -> {
                    isExpanded = false
                    postInvalidate()
                }
            }
        } else {
            if (isTouchOnBubble(x, y)) {
                isExpanded = true
                postInvalidate()
            }
        }
    }

    private fun isTouchOnBubble(x: Float, y: Float): Boolean {
        return dist(x, y, bubbleX, bubbleY) < bubbleRadius + 20f
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun drawMultilineText(text: String, x: Float, y: Float, maxWidth: Int, canvas: Canvas) {
        val words = text.split(" ")
        var line = StringBuilder()
        var offsetY = y + textPaint.textSize
        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            if (textPaint.measureText(testLine) > maxWidth) {
                canvas.drawText(line.toString(), x, offsetY, textPaint)
                line = StringBuilder(word)
                offsetY += textPaint.textSize * 1.2f
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, offsetY, textPaint)
    }
}