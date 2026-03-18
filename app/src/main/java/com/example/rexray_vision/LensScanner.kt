package com.example.rexray_vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.util.Locale
import kotlin.math.sqrt

data class LensSpecs(
    val openId: String,        // The ID used to call openCamera (always a logical ID)
    val physicalId: String?,   // The specific physical lens ID if applicable
    val label: String,
    val focalLength: Float?,
    val equivalentFocalLength: Float?,
    val zoomLevel: Float?,
    val aperture: Float?,
    val sensorWidth: Float?,
    val sensorHeight: Float?,
    val facing: Int?
)

class LensScanner(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun enumeratePhysicalCameras(): List<LensSpecs> {
        val lensSpecsList = mutableListOf<LensSpecs>()
        try {
            for (logicalId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(logicalId)
                val physicalIds = characteristics.physicalCameraIds

                if (physicalIds.isEmpty()) {
                    // Standard single camera
                    lensSpecsList.add(extractSpecs(logicalId, null, characteristics, "Logical"))
                } else {
                    // Multi-camera: Add entries for each physical lens, but they all reference the logicalId for opening
                    for (pId in physicalIds) {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        lensSpecsList.add(extractSpecs(logicalId, pId, pChars, "Physical"))
                    }
                }
            }
        } catch (e: Exception) {
            // Handle or log error
        }
        // Use a unique combination of logical + physical to avoid duplicates
        return lensSpecsList.distinctBy { "${it.openId}_${it.physicalId}" }
    }

    private fun extractSpecs(openId: String, physicalId: String?, characteristics: CameraCharacteristics, type: String): LensSpecs {
        val fl = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val ap = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

        var equivalentFocalLength: Float? = null
        var zoomLevel: Float? = null

        if (fl != null && physicalSize != null) {
            val sensorDiagonal = sqrt((physicalSize.width * physicalSize.width + physicalSize.height * physicalSize.height).toDouble())
            equivalentFocalLength = (fl * (43.27 / sensorDiagonal)).toFloat()
            zoomLevel = equivalentFocalLength / 26.0f
        }

        val facingText = when(facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "EXT"
        }

        val zoomStr = if (zoomLevel != null) String.format(Locale.US, "%.1fx", zoomLevel) else "N/A"
        val eqFlStr = if (equivalentFocalLength != null) "${equivalentFocalLength.toInt()}mm eq" else "??mm"
        val idLabel = if (physicalId != null) "$openId:$physicalId" else openId
        
        val label = "[$facingText] $zoomStr ($eqFlStr) | ID:$idLabel | f/${ap ?: "N/A"} | FL:${if (fl != null) String.format(Locale.US, "%.2f", fl) else "??"}mm | $type"

        return LensSpecs(openId, physicalId, label, fl, equivalentFocalLength, zoomLevel, ap, physicalSize?.width, physicalSize?.height, facing)
    }
}
