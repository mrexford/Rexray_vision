package com.example.rexray_vision

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.sqrt

class MetadataPacker {
    private val TAG = "MetadataPacker"

    /**
     * Generates an XMP sidecar file for the given image file, containing
     * synchronization and interpolated IMU data.
     */
    fun writeXmpSidecar(imageFile: File, ptpTimestampNanos: Long, imuSample: ImuSample?) {
        try {
            val xmpFile = File(imageFile.parent, "${imageFile.nameWithoutExtension}.xmp")
            
            var roll = 0.0
            var pitch = 0.0
            var accelStr = ""
            var gyroStr = ""

            imuSample?.let {
                val acc = it.accel
                val gyr = it.gyro
                
                // Calculate rough Pitch/Roll for photogrammetry leveling
                roll = Math.toDegrees(atan2(acc[1].toDouble(), acc[2].toDouble()))
                pitch = Math.toDegrees(atan2(-acc[0].toDouble(), sqrt((acc[1] * acc[1] + acc[2] * acc[2]).toDouble())))
                
                accelStr = "${acc[0]},${acc[1]},${acc[2]}"
                gyroStr = "${gyr[0]},${gyr[1]},${gyr[2]}"
            }

            // Construct XMP content
            // Using a standard XMP structure that RealityCapture/Metashape can parse
            val xmpContent = """
                <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about=""
                    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                    xmlns:rexray="http://ns.rexray.com/vision/1.0/"
                    xmp:Roll="$roll"
                    xmp:Pitch="$pitch"
                    rexray:PtpTimestampNanos="$ptpTimestampNanos"
                    rexray:Accelerometer="$accelStr"
                    rexray:Gyroscope="$gyroStr">
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>
            """.trimIndent()

            FileOutputStream(xmpFile).use { fos ->
                fos.write(xmpContent.toByteArray())
            }
            
            Log.d(TAG, "Generated XMP sidecar for ${imageFile.name}: ${xmpFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate XMP for ${imageFile.name}", e)
        }
    }
}
