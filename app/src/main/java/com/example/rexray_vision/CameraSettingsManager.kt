package com.example.rexray_vision

import android.app.Activity
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.log10
import kotlin.math.pow

class CameraSettingsManager(
    private val activity: Activity,
    private val sharedPreferences: SharedPreferences,
    private val cameraManager: CameraManager,
    private val rexrayCameraManager: RexrayCameraManager,
    private val isoSeekBar: SeekBar,
    private val shutterSpeedSeekBar: SeekBar,
    private val captureRateSeekBar: SeekBar,
    private val captureLimitSeekBar: SeekBar,
    private val isoValueTextView: TextView,
    private val shutterSpeedValueTextView: TextView,
    private val captureRateValueTextView: TextView,
    private val captureLimitValueTextView: TextView,
    private val settingsDisplayTextView: TextView,
    private val onSettingsChanged: (Int, Long, Int, Int) -> Unit
) {

    var iso: Int = 400
    var shutterSpeed: Long = 3333333L // 1/300s
    var captureRate: Int = 5
    var captureLimit: Int = 20

    private val shutterSpeeds = (200..1500 step 50).map { 1_000_000_000L / it }.toTypedArray()
    private val captureLimits = arrayOf(1, 5, 10, 20, 40, 80, 160, 300)

    fun setupSettingsControls() {
        loadSettings()

        // ISO
        isoSeekBar.min = 50
        isoSeekBar.max = 1000
        isoSeekBar.progress = iso
        isoValueTextView.text = iso.toString()

        isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                iso = p
                isoValueTextView.text = p.toString()
                if (fromUser) onSettingsChanged(iso, shutterSpeed, captureRate, captureLimit)
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Shutter Speed
        shutterSpeedSeekBar.max = shutterSpeeds.size - 1
        val shutterSpeedIndex = shutterSpeeds.indexOfFirst { it <= shutterSpeed }
        shutterSpeedSeekBar.progress = if (shutterSpeedIndex != -1) shutterSpeedIndex else 0
        shutterSpeedValueTextView.text = activity.getString(R.string.shutter_speed_value_format, (1_000_000_000.0 / shutterSpeed).toLong())

        shutterSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                shutterSpeed = shutterSpeeds[p]
                val shutterInv = 1_000_000_000.0 / shutterSpeed
                shutterSpeedValueTextView.text = activity.getString(R.string.shutter_speed_value_format, shutterInv.toLong())
                if (fromUser) onSettingsChanged(iso, shutterSpeed, captureRate, captureLimit)
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Capture Rate
        captureRateSeekBar.max = 12
        captureRateSeekBar.min = 0
        captureRateSeekBar.progress = captureRate - 3
        captureRateValueTextView.text = activity.getString(R.string.capture_rate_format, captureRate)

        captureRateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                captureRate = p + 3
                captureRateValueTextView.text = activity.getString(R.string.capture_rate_format, captureRate)
                if (fromUser) onSettingsChanged(iso, shutterSpeed, captureRate, captureLimit)
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Capture Limit
        captureLimitSeekBar.max = captureLimits.size - 1
        val captureLimitIndex = captureLimits.indexOf(captureLimit)
        captureLimitSeekBar.progress = if (captureLimitIndex != -1) captureLimitIndex else 0
        captureLimitValueTextView.text = captureLimit.toString()

        captureLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                captureLimit = captureLimits[p]
                captureLimitValueTextView.text = captureLimit.toString()
                if (fromUser) onSettingsChanged(iso, shutterSpeed, captureRate, captureLimit)
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        updateSettingsDisplay()
    }

    fun saveSettings() {
        sharedPreferences.edit().apply {
            putInt("iso", iso)
            putLong("shutterSpeed", shutterSpeed)
            putInt("captureRate", captureRate)
            putInt("captureLimit", captureLimit)
            apply()
        }
    }

    private fun loadSettings() {
        iso = sharedPreferences.getInt("iso", 400)
        shutterSpeed = sharedPreferences.getLong("shutterSpeed", 3333333L)
        captureRate = sharedPreferences.getInt("captureRate", 5)
        captureLimit = sharedPreferences.getInt("captureLimit", 20)
    }

    fun updateSettingsDisplay() {
        val shutterSpeedString = "1/${1_000_000_000L / shutterSpeed}"
        val settingsText = "ISO: $iso, S: $shutterSpeedString, FPS: $captureRate, Limit: $captureLimit"
        settingsDisplayTextView.text = settingsText
    }
}
