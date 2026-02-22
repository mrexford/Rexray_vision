package com.example.rexray_vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private lateinit var discoverButton: Button
    private lateinit var serviceListView: ListView
    private lateinit var serviceListAdapter: ArrayAdapter<String>
    private lateinit var switchModeButton: Button

    private var networkService: NetworkService? = null
    private var isBound = false
    private var isDiscovering = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            networkService = binder.getService()
            isBound = true
            observeDiscoveredServices()
            observeConnectionState()

            if (intent.getBooleanExtra("autoDiscover", false)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    networkService?.discoverServices()
                    isDiscovering = true
                }, 1000)
            }
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

        discoverButton = findViewById(R.id.discoverButton)
        serviceListView = findViewById(R.id.serviceListView)
        switchModeButton = findViewById(R.id.switchModeButton)

        serviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        serviceListView.adapter = serviceListAdapter

        switchModeButton.setOnClickListener { 
            networkService?.stopSelf()
            startActivity(Intent(this, CaptureActivity::class.java))
            finish()
        }

        discoverButton.setOnClickListener {
            if (!isDiscovering) {
                networkService?.discoverServices()
                isDiscovering = true
            }
        }

        serviceListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val serviceName = serviceListAdapter.getItem(position)
            val serviceInfo = networkService?.discoveredServices?.value?.find { it.serviceName == serviceName }
            serviceInfo?.let { 
                networkService?.resolveService(it) 
                Toast.makeText(this, "Connecting to $serviceName", Toast.LENGTH_SHORT).show()
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
            networkService?.stopDiscovery()
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
                    intent.putExtra("isClient", true)
                    startActivity(intent)
                }
            }
        }
    }
}
