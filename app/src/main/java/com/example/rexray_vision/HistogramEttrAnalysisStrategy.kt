package com.example.rexray_vision

import android.media.Image

class HistogramEttrAnalysisStrategy : ExposureAnalysisStrategy {
    override fun analyze(image: Image): Int {
        val yBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yValues = ByteArray(ySize)
        yBuffer.get(yValues)

        val sortedY = yValues.map { it.toInt() and 0xFF }.sorted()
        val percentile95Index = (ySize * 0.95).toInt()
        return sortedY.getOrNull(percentile95Index) ?: -1
    }

    override fun getHistogram(image: Image): IntArray {
        val yBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yValues = ByteArray(ySize)
        yBuffer.get(yValues)

        // Rewind the buffer so it can be read again by the analyze() method
        yBuffer.rewind()

        val histogram = IntArray(256)
        for (y in yValues) {
            histogram[y.toInt() and 0xFF]++
        }
        return histogram
    }
}
