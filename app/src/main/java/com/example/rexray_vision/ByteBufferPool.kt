package com.example.rexray_vision

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class ByteBufferPool(private val bufferSize: Int, private val poolSize: Int) {

    private val pool = ArrayBlockingQueue<ByteBuffer>(poolSize)

    init {
        for (i in 0 until poolSize) {
            pool.add(ByteBuffer.allocateDirect(bufferSize))
        }
    }

    fun acquire(size: Int = bufferSize): ByteBuffer {
        return if (size > bufferSize) {
            ByteBuffer.allocateDirect(size)
        } else {
            pool.poll() ?: ByteBuffer.allocateDirect(bufferSize)
        }
    }

    fun release(buffer: ByteBuffer) {
        if (buffer.capacity() == bufferSize) {
            pool.offer(buffer)
        }
    }
}
