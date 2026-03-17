package com.example.rexray_vision

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
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

class ImageSaver(
    private val context: Context,
    private val characteristics: CameraCharacteristics,
    private val byteBufferPool: ByteBufferPool?,
    private val onImagesSaved: (Int) -> Unit,
    private val threadCount: Int
) {
    private val tag = "ImageSaver"
    private val imageQueue = LinkedBlockingQueue<ImageSaveRequest>()
    private lateinit var imageSaverExecutor: ThreadPoolExecutor
    private val savedImageCount = AtomicInteger(0)

    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount = _activeTaskCount.asStateFlow()

    sealed class ImageSaveRequest {
        data class Raw(val buffer: ByteBuffer, val result: TotalCaptureResult, val rotation: Int) : ImageSaveRequest()
        data class Jpeg(val bytes: ByteArray, val timestamp: Long, val rotation: Int) : ImageSaveRequest()
    }

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

    fun saveRaw(buffer: ByteBuffer, result: TotalCaptureResult, rotation: Int) {
        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
        _activeTaskCount.value++
        if (!imageQueue.offer(ImageSaveRequest.Raw(buffer, result, rotation))) {
            Log.w(tag, "Image queue full, dropping RAW frame for timestamp $timestamp.")
            _activeTaskCount.value--
            byteBufferPool?.release(buffer)
        }
    }

    fun saveJpeg(bytes: ByteArray, timestamp: Long, rotation: Int) {
        _activeTaskCount.value++
        if (!imageQueue.offer(ImageSaveRequest.Jpeg(bytes, timestamp, rotation))) {
            Log.w(tag, "Image queue full, dropping JPEG frame for timestamp $timestamp.")
            _activeTaskCount.value--
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
                
                val activity = context as? CaptureActivity
                val projectName = activity?.getProjectName() ?: "DefaultProject"
                val cameraName = activity?.getCameraName() ?: "DefaultCamera"

                when (request) {
                    is ImageSaveRequest.Raw -> {
                        val (buffer, result, rotation) = request
                        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
                        val dngCreator = DngCreator(characteristics, result)
                        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                        dngCreator.setOrientation(getExifOrientation(rotation, sensorOrientation))

                        try {
                            if (saveRawToCache(dngCreator, buffer, projectName, cameraName, timestamp)) {
                                savedImageCount.incrementAndGet()
                            }
                        } finally {
                            _activeTaskCount.value--
                            byteBufferPool?.release(buffer)
                        }
                    }
                    is ImageSaveRequest.Jpeg -> {
                        val (bytes, timestamp, _) = request
                        try {
                            if (saveJpegToCache(bytes, projectName, cameraName, timestamp)) {
                                savedImageCount.incrementAndGet()
                            }
                        } finally {
                            _activeTaskCount.value--
                        }
                    }
                }

            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(tag, "Error saving image", e)
            }
        }
    }

    private fun saveRawToCache(dngCreator: DngCreator, buffer: ByteBuffer, projectName: String, cameraName: String, timestamp: Long): Boolean {
        val fileName = generateFileName(projectName, cameraName, timestamp, "dng")
        val dngFile = File(getCacheDir(), fileName)

        try {
            FileOutputStream(dngFile).use { outputStream ->
                val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
                dngCreator.writeByteBuffer(outputStream, size, buffer, 0L)
            }
            Log.i(tag, "RAW_SAVE_SUCCESS: $fileName")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to save DNG: $fileName", e)
            if (dngFile.exists()) dngFile.delete()
        }
        return false
    }

    private fun saveJpegToCache(bytes: ByteArray, projectName: String, cameraName: String, timestamp: Long): Boolean {
        val fileName = generateFileName(projectName, cameraName, timestamp, "jpg")
        val jpegFile = File(getCacheDir(), fileName)

        try {
            FileOutputStream(jpegFile).use { it.write(bytes) }
            Log.i(tag, "JPEG_SAVE_SUCCESS: $fileName")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to save JPEG: $fileName", e)
            if (jpegFile.exists()) jpegFile.delete()
        }
        return false
    }

    private fun generateFileName(projectName: String, cameraName: String, timestamp: Long, extension: String): String {
        val bootTimeMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() / 1_000_000)
        val epochMs = bootTimeMs + (timestamp / 1_000_000)
        val timeStampFormatted = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(epochMs))
        return "${projectName}_${cameraName}_$timeStampFormatted.$extension"
    }

    private fun getCacheDir(): File {
        val dir = File(context.filesDir, "dng_cache") 
        if (!dir.exists()) dir.mkdirs()
        return dir
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
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
    }
}
