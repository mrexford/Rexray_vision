package com.example.rexray_vision

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
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
    private val byteBufferPool: ByteBufferPool,
    private val onImagesSaved: (Int) -> Unit
) {
    private val tag = "ImageSaver"
    private val imageQueue = LinkedBlockingQueue<Pair<ByteBuffer, TotalCaptureResult>>()
    private lateinit var imageSaverExecutor: ThreadPoolExecutor
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val savedImageCount = AtomicInteger(0)

    fun start() {
        Log.d(tag, "Starting ImageSaver.")
        val threadFactory = ThreadFactory {
            val thread = Thread(it)
            thread.priority = Process.THREAD_PRIORITY_BACKGROUND
            thread
        }
        imageSaverExecutor = ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(), threadFactory)
        imageSaverExecutor.execute(imageSaverRunnable)
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
    }

    fun save(buffer: ByteBuffer, result: TotalCaptureResult) {
        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
        Log.d(tag, "save - Queuing image with timestamp $timestamp. Buffer capacity: ${buffer.capacity()}. Queue size: ${imageQueue.size}")
        if (!imageQueue.offer(Pair(buffer, result))) {
            Log.w(tag, "Image queue full, dropping frame for timestamp $timestamp.")
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
                val (buffer, result) = imageQueue.take()
                val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
                Log.d(tag, "runnable - Dequeued image with timestamp $timestamp. Buffer capacity: ${buffer.capacity()}. Remaining in queue: ${imageQueue.size}")

                val activity = context as? CaptureActivity
                val projectName = activity?.getProjectName() ?: "DefaultProject"
                val cameraName = activity?.getCameraName() ?: "DefaultCamera"

                val dngCreator = DngCreator(characteristics, result)
                val rotation = windowManager.defaultDisplay.rotation
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                dngCreator.setOrientation(getExifOrientation(rotation, sensorOrientation))

                Log.d(tag, "runnable - Attempting to save DNG for timestamp $timestamp.")
                if (saveImageWithMediaStore(dngCreator, buffer, projectName, cameraName, timestamp)) {
                    savedImageCount.incrementAndGet()
                }

                // IMPORTANT: Release the buffer back to the pool
                byteBufferPool.release(buffer)
                Log.d(tag, "runnable - Released buffer for timestamp $timestamp back to pool.")

            } catch (_: InterruptedException) {
                Log.d(tag, "ImageSaver runnable interrupted. Shutting down.")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(tag, "Error saving image", e)
            }
        }
    }

    private fun saveImageWithMediaStore(dngCreator: DngCreator, buffer: ByteBuffer, projectName: String, cameraName: String, timestamp: Long): Boolean {
        val timeStampFormatted = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp / 1_000_000))
        val fileName = "${projectName}_${cameraName}_$timeStampFormatted.dng"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rexray_vision")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            try {
                resolver.openOutputStream(it).use { outputStream ->
                    outputStream?.let {
                        val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        if (size != null) {
                            Log.d(tag, "Writing DNG: $fileName, Buffer position: ${buffer.position()}, limit: ${buffer.limit()}, capacity: ${buffer.capacity()}")
                            dngCreator.writeByteBuffer(it, size, buffer, 0L)
                        } else {
                            Log.e(tag, "SENSOR_INFO_PIXEL_ARRAY_SIZE is null, cannot save DNG for $fileName")
                            return false
                        }
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
                Log.i(tag, "DNG_SAVE_SUCCESS: $fileName, URI: $uri")
                return true
            } catch (e: Exception) {
                Log.e(tag, "Failed to save DNG: $fileName", e)
                resolver.delete(it, null, null)
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
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
