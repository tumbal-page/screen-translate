package com.example.screentranslator

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayBoxesView(context: Context) : View(context) {

    private val boxes = mutableListOf<OverlayView.Box>()
    private var isPlaying = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (!playing) clearBoxes() else postInvalidate()
    }

    fun updateBoxes(newBoxes: List<OverlayView.Box>) {
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
        if (!isPlaying) return
        val snapshot: List<OverlayView.Box>
        synchronized(boxes) { snapshot = boxes.toList() }
        for (box in snapshot) {
            canvas.drawRect(box.rect, bgPaint)
            canvas.drawText(box.translation, box.rect.left.toFloat() + 4f, box.rect.bottom.toFloat() - 6f, textPaint)
        }
    }
}
