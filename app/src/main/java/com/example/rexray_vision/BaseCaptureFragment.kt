package com.example.rexray_vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    private var byteBufferPool: ByteBufferPool? = null
    private var captureStateHandler: CaptureStateHandler? = null
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
            initializeResourcesAndSession(camera)
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

    private fun initializeResourcesAndSession(camera: CameraDevice) {
        val characteristics = cameraManager.getCameraCharacteristics(camera.id)
        val mode = (activity as? CaptureActivity)?.getCaptureMode() ?: NetworkService.CaptureMode.JPEG
        
        if (mode == NetworkService.CaptureMode.RAW) {
            val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSize = configurationMap?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.get(0)
            
            if (rawSize != null) {
                val bufferSize = (rawSize.width * rawSize.height * 2.5).toInt()
                byteBufferPool = ByteBufferPool(bufferSize, 15)
                
                val dngWriterThreads = (activity as? CaptureActivity)?.getDngWriterThreads() ?: 1
                imageSaver = ImageSaver(requireContext(), characteristics, byteBufferPool, {}, dngWriterThreads)
                imageSaver.start()
                captureStateHandler = CaptureStateHandler(::handleRawCapture, byteBufferPool!!)
                createCameraPreviewSession()
            } else {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "RAW Capture not supported on this device.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val dngWriterThreads = (activity as? CaptureActivity)?.getDngWriterThreads() ?: 1
            imageSaver = ImageSaver(requireContext(), characteristics, null, {}, dngWriterThreads)
            imageSaver.start()
            createCameraPreviewSession()
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
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
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraSessionManager = CameraSessionManager(rexrayCameraManager, backgroundHandler, cameraExecutor)
        
        lifecycleScope.launch {
            (activity as? CaptureActivity)?.networkService?.captureMode?.collectLatest { mode ->
                rexrayCameraManager.cameraDevice?.let { camera ->
                    cameraExecutor.execute {
                        cameraSessionManager.close()
                        if (this@BaseCaptureFragment::imageSaver.isInitialized) {
                            imageSaver.stop()
                        }
                        activity?.runOnUiThread {
                            initializeResourcesAndSession(camera)
                        }
                    }
                }
            }
        }
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
        cameraExecutor.execute {
            try {
                cameraSessionManager.close()
                if (::rexrayCameraManager.isInitialized) {
                    rexrayCameraManager.closeCamera()
                }
                
                if (::imageSaver.isInitialized) {
                    if (imageSaver.activeTaskCount.value == 0) {
                        imageSaver.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during async onStop cleanup", e)
            } finally {
                stopBackgroundThread()
            }
        }
    }

    fun stopPreviewAndReleaseCamera() {
        cameraExecutor.execute {
            Log.d(tag, "Explicitly closing session and camera for arming state.")
            cameraSessionManager.close()
            if (::rexrayCameraManager.isInitialized) {
                rexrayCameraManager.closeCamera()
            }
            if (::imageSaver.isInitialized) {
                imageSaver.stop()
            }
        }
    }

    fun stopPreview() {
        cameraExecutor.execute {
            cameraSessionManager.close()
        }
    }

    fun startPreview() {
        if (textureView.isAvailable) {
            checkPermissionsAndOpenCamera(textureView.width, textureView.height)
        }
    }

    fun getPhysicalIds(): List<String> {
        return listOf(rexrayCameraManager.cameraDevice?.id ?: "0")
    }

    private fun checkPermissionsAndOpenCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            rexrayCameraManager.openCamera(width, height, cameraStateCallback)
        }
    }

    private fun createCameraPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        rexrayCameraManager.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
        previewSurface = Surface(texture)

        val mode = (activity as? CaptureActivity)?.getCaptureMode() ?: NetworkService.CaptureMode.JPEG

        if (mode == NetworkService.CaptureMode.RAW) {
            rexrayCameraManager.rawImageReader.setOnImageAvailableListener({ reader: ImageReader ->
                val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
                if (image != null) {
                    if (isCapturing.get()) {
                        captureStateHandler?.handleImage(image)
                    } else {
                        image.close()
                    }
                }
            }, backgroundHandler)
        } else {
            rexrayCameraManager.jpegImageReader.setOnImageAvailableListener({ reader: ImageReader ->
                val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
                if (image != null) {
                    if (isCapturing.get()) {
                        handleJpegCapture(image)
                    } else {
                        image.close()
                    }
                }
            }, backgroundHandler)
        }

        rexrayCameraManager.analysisImageReader.setOnImageAvailableListener({ reader: ImageReader ->
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
            val pGain = pGain * error
            val dTerm = dGain * derivative

            val unCappedAdjustment = pGain + iGain * integral + dTerm
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
            (activity as? CaptureActivity)?.broadcastSettings()
        }
        listener?.onAutoIsoStateChanged(newState)
    }

    fun updatePreview(iso: Int, shutterSpeed: Long) {
        try {
            cameraSessionManager.updatePreview(iso, shutterSpeed)
        } catch (e: IllegalStateException) {
            Log.w(tag, "updatePreview called on closed session. Ignoring.")
        }
    }

    fun startBurstCapture(iso: Int, shutterSpeed: Long, captureRate: Int, captureLimit: Int) {
        capturedImageCount.set(0)
        isAwaitingMigration.set(false)
        burstRotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        
        val mode = (activity as? CaptureActivity)?.getCaptureMode() ?: NetworkService.CaptureMode.JPEG
        if (mode == NetworkService.CaptureMode.RAW) {
            captureStateHandler?.clear()
        }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (isCapturing.get()) {
                    if (mode == NetworkService.CaptureMode.RAW) {
                        captureStateHandler?.handleResult(result)
                    }
                }
            }
        }
        previewSurface?.let { cameraSessionManager.startBurstCapture(iso, shutterSpeed, captureRate, captureCallback, it) }
        isCapturing.set(true)
    }

    fun stopBurstCapture() {
        if (isCapturing.getAndSet(false)) {
            val activity = activity as? CaptureActivity
            activity?.let {
                cameraSessionManager.stopBurstAndResumePreview(it.getIso(), it.getShutterSpeed())
            }
            
            val mode = (activity as? CaptureActivity)?.getCaptureMode() ?: NetworkService.CaptureMode.JPEG
            if (mode == NetworkService.CaptureMode.RAW) {
                captureStateHandler?.clear()
            }
            isAwaitingMigration.set(true)
        }
    }

    fun getImageSaver(): ImageSaver = imageSaver
    fun isAwaitingMigration(): Boolean = isAwaitingMigration.get()
    fun markMigrationStarted() {
        isAwaitingMigration.set(false)
    }

    private fun handleRawCapture(buffer: ByteBuffer, result: TotalCaptureResult) {
        val count = capturedImageCount.incrementAndGet()
        val captureLimit = (activity as? CaptureActivity)?.getCaptureLimit() ?: 0
        if (count <= captureLimit) {
            imageSaver.saveRaw(buffer, result, burstRotation)
            listener?.onImageCaptured()
            if (count == captureLimit) {
                activity?.runOnUiThread {
                    listener?.onCaptureLimitReached()
                }
            }
        } else {
            byteBufferPool?.release(buffer)
        }
    }

    private fun handleJpegCapture(image: Image) {
        try {
            val count = capturedImageCount.incrementAndGet()
            val captureLimit = (activity as? CaptureActivity)?.getCaptureLimit() ?: 0
            if (count <= captureLimit) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                imageSaver.saveJpeg(bytes, image.timestamp, burstRotation)
                listener?.onImageCaptured()
                
                if (count == captureLimit) {
                    activity?.runOnUiThread {
                        listener?.onCaptureLimitReached()
                    }
                }
            }
        } finally {
            image.close()
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
    }

    private fun stopBackgroundThread() {
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
            try {
                backgroundThread.join()
            } catch (e: InterruptedException) {
                Log.e(tag, "Error stopping background thread", e)
            }
        }
    }

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
