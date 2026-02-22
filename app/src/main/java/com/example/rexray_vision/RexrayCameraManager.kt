package com.example.rexray_vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.util.Size

class RexrayCameraManager(private val context: Context, private val backgroundHandler: Handler) {

    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    var cameraDevice: CameraDevice? = null
    lateinit var rawImageReader: ImageReader
    lateinit var analysisImageReader: ImageReader
    var previewSize: Size? = null

    @SuppressLint("MissingPermission")
    fun openCamera(width: Int, height: Int, cameraStateCallback: CameraDevice.StateCallback) {
        val cameraId = cameraManager.cameraIdList.getOrNull(0) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        if (characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) != true) return

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: return
        previewSize = chooseOptimalPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java), width, height, rawSize)
        rawImageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 30)
        analysisImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)

        cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
        if (this::rawImageReader.isInitialized) rawImageReader.close()
        if (this::analysisImageReader.isInitialized) analysisImageReader.close()
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, aspectRatio: Size): Size {
        val bigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width && it.width >= textureViewWidth && it.height >= textureViewHeight }
        if (bigEnough.isNotEmpty()) return bigEnough.minByOrNull { it.width * it.height }!!
        val notBigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width }
        if (notBigEnough.isNotEmpty()) return notBigEnough.maxByOrNull { it.width * it.height }!!
        return choices[0]
    }
}
