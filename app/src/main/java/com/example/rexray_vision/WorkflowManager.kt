package com.example.rexray_vision

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorkflowManager(private val context: Context) {
    private val TAG = "WorkflowManager"

    enum class AppState { DISARMED, ARMING, ARMED, CAPTURING, TEARDOWN }
    
    private val _currentState = MutableStateFlow(AppState.DISARMED)
    val currentState = _currentState.asStateFlow()

    suspend fun transitionToArmed(onTeardownComplete: suspend () -> Unit) {
        _currentState.value = AppState.ARMING
        Log.i(TAG, "Transitioning to ARMED state. Tearing down setup camera...")
        
        // Teardown logic
        onTeardownComplete()
        
        _currentState.value = AppState.ARMED
        Log.i(TAG, "System ARMED. ARCore/IMU initialized.")
    }

    suspend fun transitionToDisarmed(onSetupReady: suspend () -> Unit) {
        _currentState.value = AppState.TEARDOWN
        Log.i(TAG, "Transitioning to DISARMED state (TEARDOWN).")
        
        onSetupReady()
        
        _currentState.value = AppState.DISARMED
        Log.i(TAG, "System DISARMED. Setup camera active.")
    }
}
