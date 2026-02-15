package com.example.rexray_vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistogramView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var histogram = IntArray(256)
    private val barPaint = Paint()
    private val backgroundPaint = Paint()
    private val textPaint = Paint()
    private var maxVal = 1

    init {
        barPaint.color = Color.WHITE
        barPaint.strokeWidth = 2f
        backgroundPaint.color = Color.argb(128, 0, 0, 0)
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
    }

    fun updateHistogram(newHistogram: IntArray) {
        histogram = newHistogram
        maxVal = histogram.maxOrNull() ?: 1
        postInvalidate() 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / 256f

        for (i in 0..255) {
            val barHeight = (histogram[i].toFloat() / maxVal) * height
            canvas.drawRect(i * barWidth, height - barHeight, (i + 1) * barWidth, height, barPaint)
        }

        canvas.drawText("R", 10f, 30f, textPaint)
        canvas.drawText("G", 10f, 60f, textPaint)
        canvas.drawText("B", 10f, 90f, textPaint)
    }
}