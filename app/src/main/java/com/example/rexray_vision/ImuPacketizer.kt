package com.example.rexray_vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

data class ImuSample(
    val timestampNanos: Long,
    val gyro: FloatArray,
    val accel: FloatArray
)

class ImuPacketizer(private val context: Context) : SensorEventListener {
    private val TAG = "ImuPacketizer"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
    
    private val samples = mutableListOf<ImuSample>()
    private var lastGyro: FloatArray? = null
    private var lastAccel: FloatArray? = null

    fun startHighFreqLogging() {
        samples.clear()
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stopLogging() {
        sensorManager.unregisterListener(this)
    }

    fun getSamples(): List<ImuSample> = samples.toList()

    /**
     * Finds the closest IMU sample to the given camera sensor timestamp.
     * In a professional implementation, we would interpolate between the two closest samples.
     */
    fun getClosestSample(targetTimestampNanos: Long): ImuSample? {
        if (samples.isEmpty()) return null
        return samples.minByOrNull { abs(it.timestampNanos - targetTimestampNanos) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            lastGyro = event.values.clone()
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            lastAccel = event.values.clone()
        }

        if (lastGyro != null && lastAccel != null) {
            samples.add(ImuSample(event.timestamp, lastGyro!!, lastAccel!!))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun exportToCsv(projectName: String): File? {
        val dir = File(context.filesDir, "burst_capture")
        if (!dir.exists()) dir.mkdirs()
        
        val file = File(dir, "${projectName}_imu.csv")
        return try {
            FileOutputStream(file).use { fos ->
                fos.write("timestamp_ns,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z\n".toByteArray())
                samples.forEach { s ->
                    val line = "${s.timestampNanos},${s.gyro[0]},${s.gyro[1]},${s.gyro[2]},${s.accel[0]},${s.accel[1]},${s.accel[2]}\n"
                    fos.write(line.toByteArray())
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export IMU data", e)
            null
        }
    }
}
