package com.example.rexray_vision

import android.media.Image

class HistogramEttrAnalysisStrategy : ExposureAnalysisStrategy {
    override fun analyze(image: Image): Int {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val yValues = mutableListOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                yValues.add(yBuffer.get(y * yRowStride + x * yPixelStride).toInt() and 0xFF)
            }
        }

        val sortedY = yValues.sorted()
        val percentile95Index = (sortedY.size * 0.95).toInt()
        return sortedY.getOrNull(percentile95Index) ?: -1
    }

    override fun getHistogram(image: Image): IntArray {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val histogram = IntArray(256)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val yValue = yBuffer.get(y * yRowStride + x * yPixelStride).toInt() and 0xFF
                histogram[yValue]++
            }
        }

        yBuffer.rewind()
        return histogram
    }
}
