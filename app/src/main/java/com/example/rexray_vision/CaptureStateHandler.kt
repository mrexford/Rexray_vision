package com.example.rexray_vision

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class CaptureStateHandler(
    private val onCaptureAvailable: (buffer: ByteBuffer, result: TotalCaptureResult) -> Unit,
    private val byteBufferPool: ByteBufferPool
) {
    private val tag = "CaptureStateHandler"
    private val pendingResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pendingBuffers = ConcurrentHashMap<Long, ByteBuffer>()

    fun handleImage(image: Image) {
        val timestamp = image.timestamp

        val buffer = byteBufferPool.acquire()
        if (buffer == null) {
            Log.w(tag, "handleImage - Dropping frame for timestamp $timestamp due to unavailable buffer.")
            image.close()
            return
        }

        Log.d(tag, "handleImage - Timestamp: $timestamp. Pending results: ${pendingResults.size}, Pending buffers: ${pendingBuffers.size}")

        try {
            val imageBuffer = image.planes[0].buffer
            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride

            // Efficient, zero-allocation row-by-row copy
            val rowWidthInBytes = image.width * pixelStride
            for (y in 0 until image.height) {
                val rowOffset = y * rowStride
                imageBuffer.position(rowOffset)
                imageBuffer.limit(rowOffset + rowWidthInBytes)
                buffer.put(imageBuffer)
            }
            buffer.flip()
            Log.d(tag, "handleImage - Copied image data to buffer for timestamp $timestamp")

            val result = pendingResults.remove(timestamp)
            if (result != null) {
                Log.d(tag, "handleImage - Found matching result for $timestamp. Firing callback.")
                onCaptureAvailable(buffer, result)
            } else {
                Log.d(tag, "handleImage - No result for $timestamp yet. Storing buffer.")
                pendingBuffers[timestamp] = buffer
            }
        } finally {
            image.close()
        }
    }

    fun handleResult(result: TotalCaptureResult) {
        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
        Log.d(tag, "handleResult - Timestamp: $timestamp. Pending results: ${pendingResults.size}, Pending buffers: ${pendingBuffers.size}")

        val buffer = pendingBuffers.remove(timestamp)
        if (buffer != null) {
            Log.d(tag, "handleResult - Found matching buffer for $timestamp. Firing callback and releasing buffer.")
            onCaptureAvailable(buffer, result)
            byteBufferPool.release(buffer)
        } else {
            Log.d(tag, "handleResult - No buffer for $timestamp yet. Storing result.")
            pendingResults[timestamp] = result
        }
    }

    fun clear() {
        Log.d(tag, "Clearing all pending captures. Buffers: ${pendingBuffers.size}, Results: ${pendingResults.size}")
        pendingBuffers.values.forEach { byteBufferPool.release(it) }
        pendingBuffers.clear()
        pendingResults.clear()
    }
}
