package com.example.rexray_vision

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Initializing MainActivity")
        
        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "onCreate: Layout activity_main inflated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Failed to inflate layout", e)
        }

        val primaryButton: Button = findViewById(R.id.primaryButton)
        val clientButton: Button = findViewById(R.id.clientButton)

        primaryButton.setOnClickListener {
            Log.d(TAG, "primaryButton clicked: Transitioning to CaptureActivity as PRIMARY")
            val intent = Intent(this, CaptureActivity::class.java).apply {
                putExtra("ROLE", "PRIMARY")
            }
            startActivity(intent)
            finish()
        }

        clientButton.setOnClickListener {
            Log.d(TAG, "clientButton clicked: Transitioning to SetupActivity")
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }
}
