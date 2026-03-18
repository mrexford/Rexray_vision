package com.example.rexray_vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private val TAG = "SetupActivity"
    private lateinit var refreshButton: Button
    private lateinit var becomeServerButton: Button
    private lateinit var closeAppButton: Button
    private lateinit var testingButton: Button
    private lateinit var serviceListView: ListView
    private lateinit var serviceListAdapter: ArrayAdapter<String>

    private var networkService: NetworkService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            networkService = binder.getService()
            isBound = true
            observeDiscoveredServices()
            observeConnectionState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(TAG, "Service disconnected")
            isBound = false
            networkService = null
            Toast.makeText(this@SetupActivity, "Network service connection lost", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        refreshButton = findViewById(R.id.refreshButton)
        becomeServerButton = findViewById(R.id.becomeServerButton)
        closeAppButton = findViewById(R.id.closeAppButton)
        testingButton = findViewById(R.id.testingButton)
        serviceListView = findViewById(R.id.serviceListView)

        serviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        serviceListView.adapter = serviceListAdapter

        refreshButton.setOnClickListener {
            serviceListAdapter.clear()
            serviceListAdapter.notifyDataSetChanged()
            networkService?.discoverServices()
            Toast.makeText(this, "Searching for servers...", Toast.LENGTH_SHORT).show()
        }

        becomeServerButton.setOnClickListener {
            val intent = Intent(this, CaptureActivity::class.java)
            intent.putExtra("ROLE", "PRIMARY")
            intent.putExtra("NEW_PROJECT", true)
            startActivity(intent)
        }

        testingButton.setOnClickListener {
            val intent = Intent(this, TestingActivity::class.java)
            startActivity(intent)
        }

        closeAppButton.setOnClickListener {
            stopService(Intent(this, NetworkService::class.java))
            finishAffinity()
        }

        serviceListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val serviceName = serviceListAdapter.getItem(position)
            val serviceInfo = networkService?.discoveredServices?.value?.find { it.serviceName == serviceName }
            serviceInfo?.let { 
                Toast.makeText(this, "Connecting to $serviceName...", Toast.LENGTH_SHORT).show()
                networkService?.resolveService(it) 
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, NetworkService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun observeDiscoveredServices() {
        lifecycleScope.launch {
            networkService?.discoveredServices?.collect { services ->
                serviceListAdapter.clear()
                serviceListAdapter.addAll(services.map { it.serviceName })
                serviceListAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            networkService?.isConnectedToPrimary?.collect { isConnected ->
                if (isConnected) {
                    val intent = Intent(this@SetupActivity, CaptureActivity::class.java)
                    intent.putExtra("ROLE", "CLIENT")
                    startActivity(intent)
                }
            }
        }
    }
}
