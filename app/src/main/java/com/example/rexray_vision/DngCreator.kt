package com.example.rexray_vision

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream

class DngCreator(characteristics: CameraCharacteristics, result: CaptureResult) {

    private val mCharacteristics: CameraCharacteristics = characteristics
    private val mResult: CaptureResult = result
    private var mOrientation = ExifInterface.ORIENTATION_NORMAL

    fun setOrientation(orientation: Int) {
        mOrientation = orientation
    }

    fun writeImage(stream: OutputStream, image: Image) {
        val dngResult = DngResult(mCharacteristics, mResult, image)
        dngResult.writeDng(stream, mOrientation)
    }
}
