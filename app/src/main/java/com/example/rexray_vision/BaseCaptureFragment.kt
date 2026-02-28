package com.example.rexray_vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.*
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class BaseCaptureFragment : Fragment() {

    interface CameraFragmentListener {
        fun onCameraReady()
        fun onImageCaptured()
        fun onCaptureLimitReached()
        fun onHistogramUpdated(histogram: IntArray)
        fun onAutoIsoStateChanged(isAnalyzing: Boolean)
    }

    private var listener: CameraFragmentListener? = null

    private val tag = "BaseCaptureFragment"

    private lateinit var textureView: TextureView

    private var previewSurface: Surface? = null
    private lateinit var cameraSessionManager: CameraSessionManager
    private lateinit var rexrayCameraManager: RexrayCameraManager

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var byteBufferPool: ByteBufferPool
    private lateinit var captureStateHandler: CaptureStateHandler
    private lateinit var imageSaver: ImageSaver

    private val isCapturing = AtomicBoolean(false)
    private val autoIsoEnabled = AtomicBoolean(false)
    private var lastAnalysisTimestamp = 0L
    private val capturedImageCount = AtomicInteger(0)
    private var stableIsoCounter = 0
    private var maxIsoCounter = 0

    private var integral = 0.0
    private var previousError = 0.0

    private val isAwaitingMigration = AtomicBoolean(false)
    private var burstRotation = Surface.ROTATION_0

    // PID gains
    private val pGain = .8
    private val iGain = 0.01
    private val dGain = 1.1

    var exposureAnalysisStrategy: ExposureAnalysisStrategy = HistogramEttrAnalysisStrategy()

    private val cameraManager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(tag, "Camera device opened.")
            rexrayCameraManager.cameraDevice = camera
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSize = configurationMap?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.get(0)
            
            if (rawSize != null) {
                // Calculation Fix: Use 2.5 bytes per pixel (25% overhead) to accommodate hardware stride padding
                // and non-standard packing formats found on physical hardware (e.g. Pixel 6).
                val bufferSize = (rawSize.width * rawSize.height * 2.5).toInt()
                Log.d(tag, "Initializing ByteBufferPool for RAW capture. Size: $rawSize, BufferSize: $bufferSize bytes")
                byteBufferPool = ByteBufferPool(bufferSize, 30)
                
                val dngWriterThreads = (activity as? CaptureActivity)?.getDngWriterThreads() ?: 1
                imageSaver = ImageSaver(requireContext(), characteristics, byteBufferPool, {}, dngWriterThreads)
                imageSaver.start()
                captureStateHandler = CaptureStateHandler(::handleImageCapture, byteBufferPool)
                createCameraPreviewSession()
            } else {
                Log.e(tag, "Failed to get RAW_SENSOR output sizes.")
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "RAW Capture not supported on this device.", Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(tag, "Camera device disconnected.")
            camera.close()
            rexrayCameraManager.cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(tag, "Camera Error: $error")
            camera.close()
            rexrayCameraManager.cameraDevice = null
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Camera Error: $error. Please restart the app.", Toast.LENGTH_LONG).show()
            }
        }

        override fun onClosed(camera: CameraDevice) {
            Log.d(tag, "Camera device closed.")
            if (::rexrayCameraManager.isInitialized) {
                rexrayCameraManager.cameraClosed.release()
            }
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            Log.d(tag, "SurfaceTexture available.")
            checkPermissionsAndOpenCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
            previewSurface?.release()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is CameraFragmentListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement CameraFragmentListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_base_capture, container, false)
        textureView = view.findViewById(R.id.textureView)
        return view
    }

    override fun onStart() {
        super.onStart()
        startBackgroundThread()
        rexrayCameraManager = RexrayCameraManager(requireContext(), backgroundHandler)
        cameraSessionManager = CameraSessionManager(rexrayCameraManager, backgroundHandler, cameraExecutor)
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            checkPermissionsAndOpenCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(tag, "onStop: Backgrounding camera resources asynchronously.")
        
        cameraExecutor.execute {
            try {
                cameraSessionManager.close()
                if (::rexrayCameraManager.isInitialized) {
                    rexrayCameraManager.closeCamera()
                }
                
                if (::imageSaver.isInitialized) {
                    if (imageSaver.activeTaskCount.value == 0) {
                        Log.d(tag, "ImageSaver is idle, stopping.")
                        imageSaver.stop()
                    } else {
                        Log.d(tag, "ImageSaver has active tasks, letting it finish in background.")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during async onStop cleanup", e)
            } finally {
                stopBackgroundThread()
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun checkPermissionsAndOpenCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            Log.d(tag, "Camera permission granted, opening camera.")
            rexrayCameraManager.openCamera(width, height, cameraStateCallback)
        }
    }

    private fun createCameraPreviewSession() {
        Log.d(tag, "Creating camera preview session.")
        val texture = textureView.surfaceTexture ?: return
        rexrayCameraManager.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
        previewSurface = Surface(texture)

        rexrayCameraManager.rawImageReader.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
            if (image != null) {
                Log.d(tag, "onImageAvailable - Timestamp: ${image.timestamp}")
                if (isCapturing.get()) {
                    captureStateHandler.handleImage(image)
                } else {
                    image.close()
                }
            } else {
                Log.w(tag, "onImageAvailable - Failed to acquire image.")
            }
        }, backgroundHandler)

        rexrayCameraManager.analysisImageReader.setOnImageAvailableListener({ reader ->
            val image: Image? = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
            image?.let {
                val histogram = exposureAnalysisStrategy.getHistogram(it)
                analyzeHistogram(histogram)
                listener?.onHistogramUpdated(histogram)
                it.close()
            }
        }, backgroundHandler)

        val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(tag, "Camera session configured.")
                if (rexrayCameraManager.cameraDevice == null) return
                cameraSessionManager.cameraCaptureSession = session
                listener?.onCameraReady()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(tag, "Camera session configuration failed.")
            }
        }

        previewSurface?.let { cameraSessionManager.createSession(it, sessionStateCallback) }
    }

    private fun analyzeHistogram(histogram: IntArray) {
        val currentTime = System.currentTimeMillis()
        if (autoIsoEnabled.get() && (currentTime - lastAnalysisTimestamp > 100)) { 
            lastAnalysisTimestamp = currentTime

            val totalPixels = histogram.sum()
            val threshold = (totalPixels * 0.01).toInt()
            var cumulative = 0
            var percentileValue = 0
            for (i in histogram.indices.reversed()) {
                cumulative += histogram[i]
                if (cumulative >= threshold) {
                    percentileValue = i
                    break
                }
            }

            val currentIso = (activity as? CaptureActivity)?.getIso() ?: return
            val targetLuminance = 249
            val error = (targetLuminance - percentileValue).toDouble()

            if (abs(error) < 4) {
                stableIsoCounter++
                if (stableIsoCounter >= 20) { 
                    toggleAutoIso()
                    return
                }
            } else {
                stableIsoCounter = 0
            }

            val derivative = error - previousError
            val pTerm = pGain * error
            val dTerm = dGain * derivative

            val unCappedAdjustment = pTerm + iGain * integral + dTerm
            val isoAdjustment = unCappedAdjustment.roundToInt()
            val newIso = (currentIso + isoAdjustment).coerceIn(50, 1000)

            if (newIso == 1000 && percentileValue < targetLuminance) {
                maxIsoCounter++
                if (maxIsoCounter >= 20) { 
                    toggleAutoIso()
                    return
                }
            } else {
                maxIsoCounter = 0
                if (newIso > 50 && newIso < 1000) {
                    integral += error
                }
            }

            previousError = error

            if (newIso != currentIso) {
                // Modified: Only update internal state, do not trigger immediate broadcast
                (activity as? CaptureActivity)?.networkService?.updateIso(newIso)
                (activity as? CaptureActivity)?.updatePreview()
            }
        }
    }

    fun toggleAutoIso() {
        val newState = !autoIsoEnabled.get()
        autoIsoEnabled.set(newState)
        if (newState) {
            (activity as? CaptureActivity)?.setIso(400)
            stableIsoCounter = 0
            maxIsoCounter = 0
            integral = 0.0
            previousError = 0.0
        } else {
            // Send final sync when disabled
            (activity as? CaptureActivity)?.broadcastSettings()
        }
        listener?.onAutoIsoStateChanged(newState)
        Log.d(tag, "Auto-ISO enabled: $newState")
    }

    fun updatePreview(iso: Int, shutterSpeed: Long) {
        try {
            cameraSessionManager.updatePreview(iso, shutterSpeed)
        } catch (e: IllegalStateException) {
            Log.w(tag, "updatePreview called on closed session. Ignoring.")
        }
    }

    fun startBurstCapture(iso: Int, shutterSpeed: Long, captureRate: Int, captureLimit: Int) {
        Log.d(tag, "Starting burst capture.")
        capturedImageCount.set(0)
        isAwaitingMigration.set(false)
        burstRotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        captureStateHandler.clear()
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
                Log.d(tag, "onCaptureCompleted - Timestamp: $timestamp")
                if (isCapturing.get()) {
                    captureStateHandler.handleResult(result)
                }
            }
        }
        previewSurface?.let { cameraSessionManager.startBurstCapture(iso, shutterSpeed, captureRate, captureCallback, it) }
        isCapturing.set(true)
    }

    fun stopBurstCapture() {
        Log.d(tag, "Stopping burst capture.")
        if (isCapturing.getAndSet(false)) {
            val activity = activity as? CaptureActivity
            activity?.let {
                cameraSessionManager.stopBurstAndResumePreview(it.getIso(), it.getShutterSpeed())
            }
            captureStateHandler.clear()
            isAwaitingMigration.set(true)
        }
    }

    fun getImageSaver(): ImageSaver = imageSaver
    fun isAwaitingMigration(): Boolean = isAwaitingMigration.get()
    fun markMigrationStarted() {
        isAwaitingMigration.set(false)
    }

    private fun handleImageCapture(buffer: ByteBuffer, result: TotalCaptureResult) {
        val timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)!!
        Log.d(tag, "handleImageCapture (callback) - Timestamp: $timestamp, Buffer capacity: ${buffer.capacity()}")
        val count = capturedImageCount.incrementAndGet()
        val captureLimit = (activity as? CaptureActivity)?.getCaptureLimit() ?: 0
        if (count <= captureLimit) {
            imageSaver.save(buffer, result, burstRotation)
            listener?.onImageCaptured()
            if (count == captureLimit) {
                Log.d(tag, "Capture limit reached.")
                activity?.runOnUiThread {
                    listener?.onCaptureLimitReached()
                }
            }
        } else {
            Log.w(tag, "handleImageCapture - Capture limit exceeded, releasing buffer for timestamp $timestamp")
            byteBufferPool.release(buffer)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        rexrayCameraManager.previewSize?.let { previewSize ->
            val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0

            val matrix = Matrix()
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = (viewHeight.toFloat() / previewSize.height).coerceAtLeast(viewWidth.toFloat() / previewSize.width)
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
            textureView.setTransform(matrix)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun stopBackgroundThread() {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) rexrayCameraManager.openCamera(textureView.width, textureView.height, cameraStateCallback)
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to use this app.", Toast.LENGTH_LONG).show()
            activity?.finish()
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }
}
