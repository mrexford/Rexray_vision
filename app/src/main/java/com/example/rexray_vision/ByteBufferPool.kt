package com.example.rexray_vision

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class ByteBufferPool(private val bufferSize: Int, private val poolSize: Int) {

    private val pool = ArrayBlockingQueue<ByteBuffer>(poolSize)
    private val allocatedBuffers = AtomicInteger(0)

    init {
        Log.d(TAG, "Initializing ByteBufferPool with a capacity of $poolSize buffers of $bufferSize bytes each.")
    }

    fun acquire(): ByteBuffer? {
        val buffer = pool.poll()
        if (buffer != null) {
            return buffer
        }

        if (allocatedBuffers.get() < poolSize) {
            if (allocatedBuffers.incrementAndGet() <= poolSize) {
                Log.d(TAG, "Pool is empty, allocating a new buffer. Total allocated: ${allocatedBuffers.get()}")
                return ByteBuffer.allocateDirect(bufferSize)
            } else {
                // Another thread allocated the last buffer in the meantime.
                allocatedBuffers.decrementAndGet()
            }
        }

        Log.w(TAG, "Pool is empty and cap is reached. Cannot acquire buffer.")
        return null
    }

    fun release(buffer: ByteBuffer) {
        if (buffer.capacity() == bufferSize) {
            buffer.clear()
            if (!pool.offer(buffer)) {
                // Pool is full, which shouldn't happen if acquire/release are balanced.
                // In this case, we just let the buffer be garbage collected.
                Log.w(TAG, "Could not return buffer to the pool, it's likely full.")
                allocatedBuffers.decrementAndGet()
            }
        } else {
            // This buffer was not created by this pool
            Log.w(TAG, "Released a buffer with a non-standard size. It will not be pooled.")
        }
    }

    companion object {
        private const val TAG = "ByteBufferPool"
    }
}
