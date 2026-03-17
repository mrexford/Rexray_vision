package com.example.rexray_vision

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class ByteBufferPool(private val bufferSize: Int, private val poolSize: Int) {

    private val pool = ArrayBlockingQueue<ByteBuffer>(poolSize)

    init {
        Log.d(TAG, "Initializing ByteBufferPool with $poolSize buffers of $bufferSize bytes each. Total: ${poolSize * bufferSize / 1024 / 1024} MB")
        try {
            for (i in 0 until poolSize) {
                pool.add(ByteBuffer.allocateDirect(bufferSize))
            }
            Log.d(TAG, "Successfully pre-allocated $poolSize buffers.")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to pre-allocate ByteBufferPool. Requested: ${poolSize * bufferSize} bytes", e)
            throw e
        }
    }

    fun acquire(): ByteBuffer? {
        val buffer = pool.poll()
        if (buffer == null) {
            Log.w(TAG, "Pool is empty. Cannot acquire buffer.")
        }
        return buffer
    }

    fun release(buffer: ByteBuffer) {
        if (buffer.capacity() == bufferSize) {
            buffer.clear()
            if (!pool.offer(buffer)) {
                Log.w(TAG, "Could not return buffer to the pool, it's likely full.")
            }
        } else {
            Log.w(TAG, "Released a buffer with a non-standard size. It will not be pooled.")
        }
    }

    companion object {
        private const val TAG = "ByteBufferPool"
    }
}
