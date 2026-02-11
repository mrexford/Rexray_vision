package com.example.rexray_vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var histogram = IntArray(256)
    private val paint = Paint()
    private val linePaint = Paint()
    private var maxCount = 1

    init {
        paint.color = Color.argb(180, 100, 100, 100)
        linePaint.color = Color.argb(200, 255, 255, 255)
        linePaint.strokeWidth = 1f
    }

    fun updateHistogram(newHistogram: IntArray) {
        if (newHistogram.size == 256) {
            histogram = newHistogram
            maxCount = histogram.maxOrNull() ?: 1
            postInvalidate() // Redraw the view
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Draw a semi-transparent background
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)

        val barWidth = viewWidth / 256f

        for (i in 0..255) {
            if (histogram[i] == 0) continue
            val barHeight = (histogram[i].toFloat() / maxCount) * viewHeight
            val x = i * barWidth
            val y = viewHeight - barHeight
            canvas.drawRect(x, y, x + barWidth, viewHeight, linePaint)
        }
    }
}
