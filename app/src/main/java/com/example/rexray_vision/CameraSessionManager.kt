package com.example.rexray_vision

import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

class CameraSessionManager(
    private val rexrayCameraManager: RexrayCameraManager,
    private val backgroundHandler: Handler,
    private val cameraExecutor: Executor
) {
    private val tag = "CameraSessionManager"
    var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    fun createSession(previewSurface: Surface, sessionStateCallback: CameraCaptureSession.StateCallback) {
        val cameraDevice = rexrayCameraManager.cameraDevice ?: return
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(rexrayCameraManager.analysisImageReader.surface)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    OutputConfiguration(previewSurface),
                    OutputConfiguration(rexrayCameraManager.rawImageReader.surface),
                    OutputConfiguration(rexrayCameraManager.analysisImageReader.surface)
                ),
                cameraExecutor,
                sessionStateCallback
            )
            cameraDevice.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to create camera session", e)
        }
    }

    fun updatePreview(iso: Int, shutterSpeed: Long) {
        val builder = previewRequestBuilder ?: run {
            Log.w(tag, "updatePreview called before previewRequestBuilder was initialized. Ignoring.")
            return
        }
        try {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            cameraCaptureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to update preview", e)
        }
    }

    fun startBurstCapture(iso: Int, shutterSpeed: Long, captureRate: Int, captureCallback: CameraCaptureSession.CaptureCallback, previewSurface: Surface) {
        val cameraDevice = rexrayCameraManager.cameraDevice ?: return
        try {
            val frameDuration = 1_000_000_000L / captureRate
            var effectiveShutterSpeed = shutterSpeed
            if (shutterSpeed > frameDuration) {
                Log.w(tag, "Shutter speed ($shutterSpeed) is longer than frame duration ($frameDuration) for the requested capture rate ($captureRate fps). Capping shutter speed to frame duration.")
                effectiveShutterSpeed = frameDuration
            }

            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rexrayCameraManager.rawImageReader.surface)
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, effectiveShutterSpeed)
                set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            }
            cameraCaptureSession?.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch(e: CameraAccessException) {
            Log.e(tag, "Failed to start burst capture", e)
        }
    }

    fun captureSingle(iso: Int, shutterSpeed: Long, callback: CameraCaptureSession.CaptureCallback) {
        val cameraDevice = rexrayCameraManager.cameraDevice ?: return
        try {
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(rexrayCameraManager.analysisImageReader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            }
            cameraCaptureSession?.capture(captureBuilder.build(), callback, backgroundHandler)
        } catch(e: CameraAccessException) {
             Log.e(tag, "Failed to capture single frame for analysis", e)
        }
    }

    fun stopBurstAndResumePreview(iso: Int, shutterSpeed: Long) {
        try {
            cameraCaptureSession?.abortCaptures()
            cameraCaptureSession?.stopRepeating()
            updatePreview(iso, shutterSpeed)
        } catch (e: CameraAccessException) {
            Log.e(tag, "Error stopping burst capture", e)
        }
    }

    fun close() {
        try {
            cameraCaptureSession?.stopRepeating()
            cameraCaptureSession?.abortCaptures()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping camera session on close", e)
        }
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        previewRequestBuilder = null
    }
}
