package com.example.rexray_vision

import android.os.SystemClock
import android.util.Log

class TimeSyncEngine {
    private val TAG = "TimeSyncEngine"
    
    // Offset to be added to local SystemClock.elapsedRealtimeNanos() to get Brain time
    private var clockOffsetNanos: Long = 0
    private var isSynced = false

    /**
     * Calculates the clock offset using RTT compensation.
     */
    fun updateOffset(brainSentTimeNanos: Long, nodeReceivedTimeNanos: Long, rttNanos: Long) {
        val estimatedBrainTimeAtNodeReceive = brainSentTimeNanos + (rttNanos / 2)
        clockOffsetNanos = estimatedBrainTimeAtNodeReceive - nodeReceivedTimeNanos
        isSynced = true
        Log.d(TAG, "Updated Clock Offset: $clockOffsetNanos ns (RTT: ${rttNanos / 1_000_000.0} ms)")
    }

    /**
     * Returns the current time synchronized with the Brain.
     */
    fun getSynchronizedTimeNanos(): Long {
        return SystemClock.elapsedRealtimeNanos() + clockOffsetNanos
    }

    fun getOffsetNanos(): Long = clockOffsetNanos

    fun isSynced(): Boolean = isSynced
}
