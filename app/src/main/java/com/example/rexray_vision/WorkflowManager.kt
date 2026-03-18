package com.example.rexray_vision

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorkflowManager(private val context: Context) {
    private val TAG = "WorkflowManager"

    enum class AppState { DISARMED, ARMING, ARMED, CAPTURING }
    
    private val _currentState = MutableStateFlow(AppState.DISARMED)
    val currentState = _currentState.asStateFlow()

    fun transitionToArmed(onTeardownComplete: () -> Unit) {
        _currentState.value = AppState.ARMING
        Log.i(TAG, "Transitioning to ARMED state. Tearing down setup camera...")
        
        // Teardown logic
        onTeardownComplete()
        
        _currentState.value = AppState.ARMED
        Log.i(TAG, "System ARMED. ARCore/IMU initialized.")
    }

    fun transitionToDisarmed(onSetupReady: () -> Unit) {
        _currentState.value = AppState.DISARMED
        Log.i(TAG, "Transitioning to DISARMED state. Restarting setup camera...")
        
        onSetupReady()
    }
}
