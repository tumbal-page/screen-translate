package com.example.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * OverlayView draws translated text over the corresponding bounding boxes. It
 * does not intercept touches (handled by WindowManager flags). Each Box
 * contains a rectangle and a translation string.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Box(val rect: Rect, val translation: String)

    private val boxes = mutableListOf<Box>()
    private val rectPaint: Paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 0, 0, 0)
    }
    private val textPaint: Paint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
    }

    /**
     * Replaces current boxes with new set and redraws overlay.
     */
    fun updateBoxes(newBoxes: List<Box>) {
        synchronized(boxes) {
            boxes.clear()
            boxes.addAll(newBoxes)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw each translated box
        synchronized(boxes) {
            for (box in boxes) {
                // Draw semi-transparent background behind the text
                canvas.drawRect(box.rect, rectPaint)
                // Draw translation text inside the bounding box, wrapping if necessary
                drawMultilineText(box.translation, box.rect.left.toFloat(), box.rect.top.toFloat(), box.rect.right - box.rect.left, canvas)
            }
        }
        // Draw service active indicator in top-left corner
        val indicatorText = "ON"
        val indicatorPaint = Paint().apply {
            color = Color.GREEN
            textSize = 48f
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        canvas.drawText(indicatorText, 10f, 50f, indicatorPaint)
    }

    /**
     * Draws multi-line text within a maximum width. Splits the translation if
     * necessary. This is a naive implementation; in production use, consider
     * TextLayout for better alignment and internationalization.
     */
    private fun drawMultilineText(text: String, x: Float, y: Float, maxWidth: Int, canvas: Canvas) {
        val words = text.split(" ")
        var line = StringBuilder()
        var offsetY = y + textPaint.textSize
        for (word in words) {
            val testLine = if (line.isEmpty()) word else "${'$'}{line} ${'$'}word"
            val testWidth = textPaint.measureText(testLine)
            if (testWidth > maxWidth) {
                // draw the current line and start a new one
                canvas.drawText(line.toString(), x, offsetY, textPaint)
                line = StringBuilder(word)
                offsetY += textPaint.textSize * 1.2f
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
        }
        // draw remaining line
        canvas.drawText(line.toString(), x, offsetY, textPaint)
    }
}