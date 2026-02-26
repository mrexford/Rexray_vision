package com.example.rexray_vision

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val primaryButton: Button = findViewById(R.id.primaryButton)
        val clientButton: Button = findViewById(R.id.clientButton)

        primaryButton.setOnClickListener {
            val intent = Intent(this, CaptureActivity::class.java).apply {
                putExtra("ROLE", "PRIMARY")
            }
            startActivity(intent)
            finish()
        }

        clientButton.setOnClickListener {
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
