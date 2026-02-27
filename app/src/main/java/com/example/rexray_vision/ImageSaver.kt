package com.example.rexray_vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class ImageSaver(
    private val context: Context,
    private val characteristics: CameraCharacteristics,
    private val byteBufferPool: ByteBufferPool,
    private val onImagesSaved: (Int) -> Unit,
    private val threadCount: Int
) {
    private val tag = "ImageSaver"
    private val imageQueue = LinkedBlockingQueue<ImageSaveRequest>()
    private lateinit var imageSaverExecutor: ThreadPoolExecutor
    private val savedImageCount = AtomicInteger(0)

    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount = _activeTaskCount.asStateFlow()

    data class ImageSaveRequest(
        val buffer: ByteBuffer,
        val result: TotalCaptureResult,
        val rotation: Int
    )

    fun start() {
        Log.d(tag, "Starting ImageSaver.")
        val threadFactory = ThreadFactory {
            val thread = Thread(it)
            thread.priority = Process.THREAD_PRIORITY_BACKGROUND
            thread
        }
        imageSaverExecutor = ThreadPoolExecutor(threadCount, threadCount, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(), threadFactory)
        for (i in 0 until threadCount) {
            imageSaverExecutor.execute(imageSaverRunnable)
        }
    }

    fun stop() {
        Log.d(tag, "Stopping ImageSaver.")
        imageSaverExecutor.shutdown()
        try {
            if (!imageSaverExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                imageSaverExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            imageSaverExecutor.shutdownNow()
        }
        flush()
        imageQueue.clear()
        _activeTaskCount.value = 0
    }

    fun save(buffer: ByteBuffer, result: TotalCaptureResult, rotation: Int) {
        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
        Log.d(tag, "save - Queuing image with timestamp $timestamp. Buffer capacity: ${buffer.capacity()}. Queue size: ${imageQueue.size}")
        _activeTaskCount.value++
        if (!imageQueue.offer(ImageSaveRequest(buffer, result, rotation))) {
            Log.w(tag, "Image queue full, dropping frame for timestamp $timestamp.")
            _activeTaskCount.value--
            byteBufferPool.release(buffer)
        }
    }

    fun flush() {
        val count = savedImageCount.getAndSet(0)
        if (count > 0) {
            Log.d(tag, "Flushing $count saved images.")
            onImagesSaved(count)
        }
    }

    private val imageSaverRunnable = Runnable {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val request = imageQueue.take()
                val (buffer, result, rotation) = request
                val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
                Log.d(tag, "runnable - Dequeued image with timestamp $timestamp. Buffer capacity: ${buffer.capacity()}. Remaining in queue: ${imageQueue.size}")

                val activity = context as? CaptureActivity
                val projectName = activity?.getProjectName() ?: "DefaultProject"
                val cameraName = activity?.getCameraName() ?: "DefaultCamera"

                val dngCreator = DngCreator(characteristics, result)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                dngCreator.setOrientation(getExifOrientation(rotation, sensorOrientation))

                Log.d(tag, "runnable - Attempting to save DNG for timestamp $timestamp.")
                try {
                    if (saveImageToCache(dngCreator, buffer, projectName, cameraName, timestamp)) {
                        savedImageCount.incrementAndGet()
                    }
                } finally {
                    _activeTaskCount.value--
                    // IMPORTANT: Release the buffer back to the pool
                    byteBufferPool.release(buffer)
                    Log.d(tag, "runnable - Released buffer for timestamp $timestamp back to pool. Active tasks: ${_activeTaskCount.value}")
                }

            } catch (_: InterruptedException) {
                Log.d(tag, "ImageSaver runnable interrupted. Shutting down.")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(tag, "Error saving image", e)
            }
        }
    }

    private fun saveImageToCache(dngCreator: DngCreator, buffer: ByteBuffer, projectName: String, cameraName: String, timestamp: Long): Boolean {
        val bootTimeMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() / 1_000_000)
        val epochMs = bootTimeMs + (timestamp / 1_000_000)
        val timeStampFormatted = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(epochMs))
        val fileName = "${projectName}_${cameraName}_$timeStampFormatted.dng"

        val cacheDir = File(context.filesDir, "dng_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val dngFile = File(cacheDir, fileName)

        try {
            FileOutputStream(dngFile).use { outputStream ->
                val totalTime = measureTimeMillis {
                    val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    if (size != null) {
                        dngCreator.writeByteBuffer(outputStream, size, buffer, 0L)
                    } else {
                        throw RuntimeException("SENSOR_INFO_PIXEL_ARRAY_SIZE is null")
                    }
                }
                Log.d("ImageSaver_ProcessAndIO", "DNG processing and writing took $totalTime ms")
            }
            Log.i(tag, "DNG_SAVE_SUCCESS: $fileName, Path: ${dngFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to save DNG to cache: $fileName", e)
            if (dngFile.exists()) {
                dngFile.delete()
            }
        }
        return false
    }

    private fun getExifOrientation(rotation: Int, sensorOrientation: Int): Int {
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val result = (sensorOrientation - degrees + 360) % 360
        return when (result) {
            90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }
    }
}
