package com.example.rexray_vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class RexrayCameraManager(val context: Context, private val backgroundHandler: Handler) {

    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    var cameraDevice: CameraDevice? = null
    lateinit var rawImageReader: ImageReader
    lateinit var jpegImageReader: ImageReader
    lateinit var analysisImageReader: ImageReader
    var previewSize: Size? = null
    val cameraClosed = Semaphore(0)

    fun isRawInitialized(): Boolean = this::rawImageReader.isInitialized
    fun isJpegInitialized(): Boolean = this::jpegImageReader.isInitialized

    @SuppressLint("MissingPermission")
    fun openCamera(width: Int, height: Int, cameraStateCallback: CameraDevice.StateCallback) {
        val cameraId = cameraManager.cameraIdList.getOrNull(0) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        
        // Mode detection for reader initialization
        val mode = (context as? CaptureActivity)?.getCaptureMode() ?: NetworkService.CaptureMode.RAW

        if (mode == NetworkService.CaptureMode.RAW) {
            if (characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) != true) {
                Log.e("RexrayCameraManager", "RAW capture not supported")
                return
            }
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: return
            previewSize = chooseOptimalPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java), width, height, rawSize)
            rawImageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 30)
        } else {
            val jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: return
            previewSize = chooseOptimalPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java), width, height, jpegSize)
            jpegImageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 30)
        }

        analysisImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            if (!cameraClosed.tryAcquire(2, TimeUnit.SECONDS)) {
                Log.e("RexrayCameraManager", "Camera closing timeout")
            }
        } catch (e: InterruptedException) {
            Log.e("RexrayCameraManager", "Interrupted while closing camera", e)
        } finally {
            if (this::rawImageReader.isInitialized) rawImageReader.close()
            if (this::jpegImageReader.isInitialized) jpegImageReader.close()
            if (this::analysisImageReader.isInitialized) analysisImageReader.close()
        }
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, aspectRatio: Size): Size {
        val bigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width && it.width >= textureViewWidth && it.height >= textureViewHeight }
        if (bigEnough.isNotEmpty()) return bigEnough.minByOrNull { it.width * it.height }!!
        val notBigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width }
        if (notBigEnough.isNotEmpty()) return notBigEnough.maxByOrNull { it.width * it.height }!!
        return choices[0]
    }
}
