package com.example.rexray_vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class DualCameraManager(
    private val context: Context,
    private val backgroundHandler: Handler,
    private val storageManager: InternalStorageManager,
    private val timeSyncEngine: TimeSyncEngine,
    private val listener: DualCameraListener
) {
    interface DualCameraListener {
        fun onImageCaptured(data: ByteArray)
        fun onCaptureLimitReached()
        fun onSessionConfigured(logicalId: String)
    }

    private val TAG = "DualCameraManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    private var cameraDevices = mutableMapOf<String, CameraDevice>()
    private var captureSessions = mutableMapOf<String, CameraCaptureSession>()
    private var imageReaders = mutableMapOf<String, ImageReader>()
    
    private val capturedCount = AtomicInteger(0)
    private var currentLimit = 0

    @SuppressLint("MissingPermission")
    fun openAndSetupBurst(targets: List<Pair<String, String?>>) {
        Log.d(TAG, "openAndSetupBurst: Requesting targets: ${targets.joinToString { "${it.first}:${it.second}" }}")
        
        val groupedTargets = targets.groupBy { it.first }
        
        groupedTargets.forEach { (logicalId, targetList) ->
            if (cameraDevices.containsKey(logicalId)) return@forEach
            
            try {
                cameraManager.openCamera(logicalId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.d(TAG, "Camera $logicalId opened successfully")
                        cameraDevices[logicalId] = camera
                        createBurstSession(logicalId, targetList.mapNotNull { it.second })
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera $logicalId disconnected")
                        camera.close()
                        cameraDevices.remove(logicalId)
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera $logicalId error: $error")
                        camera.close()
                        cameraDevices.remove(logicalId)
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call openCamera for $logicalId", e)
            }
        }
    }

    private fun createBurstSession(logicalId: String, physicalIds: List<String>) {
        val camera = cameraDevices[logicalId] ?: return
        val characteristics = cameraManager.getCameraCharacteristics(logicalId)
        val outputConfigurations = mutableListOf<OutputConfiguration>()
        
        if (physicalIds.isEmpty()) {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val size = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            val reader = createReader(logicalId, size)
            outputConfigurations.add(OutputConfiguration(reader.surface))
        } else {
            physicalIds.forEach { pId ->
                val pChars = cameraManager.getCameraCharacteristics(pId)
                val map = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach
                val size = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
                
                val reader = createReader("${logicalId}_$pId", size)
                val config = OutputConfiguration(reader.surface)
                config.setPhysicalCameraId(pId)
                outputConfigurations.add(config)
            }
        }

        // Modern Camera2 Session Configuration (API 28+)
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigurations,
            java.util.concurrent.Executor { command -> backgroundHandler.post(command) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Session configured for $logicalId")
                    captureSessions[logicalId] = session
                    listener.onSessionConfigured(logicalId)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure session for $logicalId")
                }
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun createReader(key: String, size: Size): ImageReader {
        Log.d(TAG, "Creating ImageReader for $key with size $size")
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 15)
        imageReaders[key] = reader

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            
            val count = capturedCount.incrementAndGet()
            if (count <= currentLimit) {
                val sensorTimestamp = image.timestamp
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                val filename = "IMG_${sensorTimestamp}_${key}.jpg"
                storageManager.saveImageToInternal(bytes, filename)
                listener.onImageCaptured(bytes)
                
                if (count == currentLimit) {
                    listener.onCaptureLimitReached()
                }
            }
            image.close()
        }, backgroundHandler)
        
        return reader
    }

    fun startBurst(limit: Int) {
        if (captureSessions.isEmpty()) {
            Log.e(TAG, "startBurst: CANNOT START - No sessions are ready.")
            return
        }
        
        capturedCount.set(0)
        currentLimit = limit
        
        captureSessions.forEach { (logicalId, session) ->
            try {
                val request = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                imageReaders.filter { it.key.startsWith(logicalId) }.values.forEach { 
                    request.addTarget(it.surface)
                }
                session.setRepeatingRequest(request.build(), null, backgroundHandler)
                Log.d(TAG, "Repeating request set for $logicalId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start repeating request for $logicalId", e)
            }
        }
    }

    fun stopBurst() {
        Log.d(TAG, "Stopping burst for all sessions")
        captureSessions.forEach { (_, session) ->
            try {
                session.stopRepeating()
                session.abortCaptures()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping burst", e)
            }
        }
    }

    fun closeAll() {
        Log.d(TAG, "Closing all camera resources")
        captureSessions.values.forEach { it.close() }
        captureSessions.clear()
        cameraDevices.values.forEach { it.close() }
        cameraDevices.clear()
        imageReaders.values.forEach { it.close() }
        imageReaders.clear()
    }
}
