package com.example.rexray_vision

import android.media.Image

interface ExposureAnalysisStrategy {
    fun analyze(image: Image): Int
    fun getHistogram(image: Image): IntArray
}
