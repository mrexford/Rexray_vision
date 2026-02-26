package com.example.rexray_vision

import android.media.Image
import java.nio.ByteBuffer

class HistogramEttrAnalysisStrategy : ExposureAnalysisStrategy {

    override fun getHistogram(image: Image): IntArray {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height

        val histogram = IntArray(256) { 0 }
        val rowBuffer = ByteArray(width)

        yBuffer.rewind()
        for (row in 0 until height) {
            yBuffer.position(row * rowStride)
            if (width == rowStride) {
                yBuffer.get(rowBuffer, 0, width)
            } else {
                for (col in 0 until width) {
                    rowBuffer[col] = yBuffer.get(row * rowStride + col * pixelStride)
                }
            }

            for (col in 0 until width) {
                val pixelValue = rowBuffer[col].toInt() and 0xFF
                histogram[pixelValue]++
            }
        }

        return histogram
    }

    override fun analyze(image: Image): Int {
        val histogram = getHistogram(image)
        val totalPixels = image.width * image.height
        if (totalPixels == 0) {
            return -1
        }

        // Find the 95th percentile brightness value
        val percentileThreshold = (totalPixels * 0.95).toLong()
        var cumulativePixels = 0L
        for (i in 0..255) {
            cumulativePixels += histogram[i]
            if (cumulativePixels >= percentileThreshold) {
                return i
            }
        }
        return 255 // Should be hit only if all pixels are in last bin
    }
}
