package com.example.rexray_vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class HistogramEttrAnalysisStrategy(private val targetPercentile: Float = 0.99f) : ExposureAnalysisStrategy {
    override fun analyze(image: Image): Int {
        val histogram = getHistogram(image)

        if (histogram.isEmpty()) return -1

        var totalPixels = 0
        for (count in histogram) {
            totalPixels += count
        }

        val targetPixelCount = (totalPixels * targetPercentile).toInt()
        var currentPixelCount = 0
        var percentileValue = 255
        for (i in 255 downTo 0) {
            currentPixelCount += histogram[i]
            if (currentPixelCount >= totalPixels - targetPixelCount) {
                percentileValue = i
                break
            }
        }

        return percentileValue
    }

    override fun getHistogram(image: Image): IntArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val yPixelStride = yPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                nv21[yIndex++] = yBuffer[y * yRowStride + x * yPixelStride]
                if (y % 2 == 0 && x % 2 == 0) {
                    if (uvIndex < nv21.size - 1) {
                        vBuffer[y / 2 * vRowStride + x / 2 * vPixelStride].let { nv21[uvIndex++] = it }
                        uBuffer[y / 2 * uRowStride + x / 2 * uPixelStride].let { nv21[uvIndex++] = it }
                    }
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        if (bitmap == null) {
            return IntArray(0)
        }

        val histogram = IntArray(256)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
            histogram[luminance]++
        }
        return histogram
    }
}
