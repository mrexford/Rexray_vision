package com.example.rexray_vision

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream

class DngResult(characteristics: CameraCharacteristics, result: CaptureResult, image: Image) {

    fun writeDng(stream: OutputStream, orientation: Int) {
        // This is a placeholder for the actual DNG writing logic.
    }
}
