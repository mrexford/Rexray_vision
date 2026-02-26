package com.example.rexray_vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CaptureActivity : AppCompatActivity(), BaseCaptureFragment.CameraFragmentListener, PrimaryControlsFragment.PrimaryControlsListener, ClientControlsFragment.ClientControlsListener {

    private val tag = "CaptureActivity"
    private lateinit var role: String

    private var networkService: NetworkService? = null
    private var isBound = false
    private lateinit var sharedPreferences: SharedPreferences
    private var networkStateJob: Job? = null

    private val isArmed = AtomicBoolean(false)
    private lateinit var currentProjectName: String
    private lateinit var cameraName: String
    private var iso = 400
    private var shutterSpeed = 3333333L // 1/300s
    private var captureRate = 5
    private var captureLimit = 5

    private var sessionCaptureCount = 0

    private lateinit var loadingIndicator: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var captureCounter: TextView
    private lateinit var captureInProgressBorder: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            networkService = binder.getService()
            isBound = true
            if (role == "PRIMARY") {
                networkService?.registerService(0, "$currentProjectName-$cameraName")
            }
            networkService?.setCameraName(cameraName)
            observeNetworkState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(tag, "Service disconnected")
            isBound = false
            networkService = null
            Toast.makeText(this@CaptureActivity, "Network service connection lost", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        role = intent.getStringExtra("ROLE") ?: "CLIENT"
        val newProject = intent.getBooleanExtra("NEW_PROJECT", false)

        loadingIndicator = findViewById(R.id.loadingIndicator)
        loadingStatus = findViewById(R.id.loadingStatus)
        captureCounter = findViewById(R.id.captureCounter)
        captureInProgressBorder = findViewById(R.id.captureInProgressBorder)

        sharedPreferences = getSharedPreferences("RexrayVisionPrefs", Context.MODE_PRIVATE)
        Log.d(tag, "onCreate: Using default settings.")
        // loadSettings() // Force default settings on startup
        Log.d(tag, "onCreate: Default settings: ISO=$iso, Shutter Speed=$shutterSpeed")
        setupNewProject(newProject)
        generateCameraName(false)

        updateCaptureCounter()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.baseCaptureFragmentContainer, BaseCaptureFragment())
                .commit()

            if (role == "PRIMARY") {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.controlsFragmentContainer, PrimaryControlsFragment())
                    .commit()
            } else {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.controlsFragmentContainer, ClientControlsFragment())
                    .commit()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, NetworkService::class.java).also { intent ->
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

    private fun observeNetworkState() {
        networkStateJob = lifecycleScope.launch {
            launch {
                if (role == "CLIENT") {
                    networkService?.isConnectedToPrimary?.drop(1)?.collect { isConnected ->
                        if (!isConnected) {
                            finish()
                        }
                    }
                }
            }
            launch {
                networkService?.incomingMessages?.collect { messagePair ->
                    messagePair?.let { (socket, message) ->
                        when (message) {
                            is NetworkService.Message.ArmCapture -> onArmCapture(false)
                            is NetworkService.Message.DisarmCapture -> onDisarmCapture(false)
                            is NetworkService.Message.StartCapture -> onStartCapture()
                            is NetworkService.Message.StopCapture -> onStopCapture()
                            is NetworkService.Message.SetParams -> {
                                Log.d(tag, "Received SetParams from network: ISO=${message.iso}, Shutter Speed=${message.shutterSpeed}")
                                shutterSpeed = message.shutterSpeed
                                iso = message.iso
                                captureRate = message.captureRate
                                captureLimit = message.captureCount
                                currentProjectName = message.projectName
                                runOnUiThread { (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateUi() }
                                updatePreview()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    fun showLoading(message: String = "Processing...") {
        runOnUiThread {
            loadingStatus.text = message
            loadingIndicator.visibility = View.VISIBLE
        }
    }

    fun hideLoading() {
        runOnUiThread {
            loadingIndicator.visibility = View.GONE
        }
    }

    override fun onCameraReady() {
        Log.d(tag, "onCameraReady: Applying initial settings to preview")
        updatePreview()
        if (role == "PRIMARY") {
            runOnUiThread { (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.onCameraReady() }
        }
    }

    override fun onImageCaptured() {
        sessionCaptureCount++
        updateCaptureCounter()
    }

    override fun onCaptureLimitReached() {
        onStopCapture()
    }

    private fun updateCaptureCounter() {
        runOnUiThread {
            captureCounter.text = sessionCaptureCount.toString()
        }
    }

    override fun onHistogramUpdated(histogram: IntArray) {
        if (role == "PRIMARY") {
            runOnUiThread { (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateHistogram(histogram) }
        }
    }

    override fun onAutoIsoStateChanged(isAnalyzing: Boolean) {
        if (role == "PRIMARY") {
            runOnUiThread { (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateAutoIsoState(isAnalyzing) }
        }
    }

    fun updatePreview() {
        Log.d(tag, "updatePreview: ISO=$iso, Shutter Speed=$shutterSpeed")
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.updatePreview(iso, shutterSpeed)
    }

    private fun updateUi() {
        if (role == "PRIMARY") {
            runOnUiThread { (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateUi() }
        }
    }

    private fun setupNewProject(force: Boolean) {
        var pName = if (force) null else sharedPreferences.getString("projectName", null)
        if (pName == null) {
            val adjectives = resources.getStringArray(R.array.adjectives)
            val nouns = resources.getStringArray(R.array.nouns)
            pName = "${adjectives.random()}-${nouns.random()}"
            sharedPreferences.edit { putString("projectName", pName) }
        }
        currentProjectName = pName
        sessionCaptureCount = 0
        updateCaptureCounter()
    }

    private fun generateCameraName(force: Boolean) {
        var aName = sharedPreferences.getString("cameraName", null)
        if (aName == null || force) {
            val animals = resources.getStringArray(R.array.animals)
            aName = animals.random()
            sharedPreferences.edit { putString("cameraName", aName) }
        }
        cameraName = aName
    }

    private fun loadSettings() {
        iso = sharedPreferences.getInt("iso", 400)
        shutterSpeed = sharedPreferences.getLong("shutterSpeed", 3333333L)
        captureRate = sharedPreferences.getInt("captureRate", 5)
        captureLimit = sharedPreferences.getInt("captureLimit", 5)
    }

    fun getProjectName(): String = currentProjectName
    fun getCameraName(): String = cameraName
    fun getIsArmed(): Boolean = isArmed.get()
    fun getIso(): Int = iso
    fun getShutterSpeed(): Long = shutterSpeed
    fun getCaptureRate(): Int = captureRate
    fun getCaptureLimit(): Int = captureLimit

    override fun setIso(value: Int) {
        Log.d(tag, "setIso: value=$value")
        iso = value
        sharedPreferences.edit { putInt("iso", value) }
        broadcastSettings()
        updatePreview()
        updateUi()
    }

    override fun setShutterSpeed(value: Long) {
        Log.d(tag, "setShutterSpeed: value=$value")
        shutterSpeed = value
        sharedPreferences.edit { putLong("shutterSpeed", value) }
        broadcastSettings()
        updatePreview()
        updateUi()
    }

    override fun setCaptureRate(value: Int) {
        captureRate = value
        sharedPreferences.edit { putInt("captureRate", value) }
        broadcastSettings()
        updateUi()
    }

    override fun setCaptureLimit(value: Int) {
        captureLimit = value
        sharedPreferences.edit { putInt("captureLimit", value) }
        broadcastSettings()
        updateUi()
    }

    fun broadcastSettings() {
        if (role == "PRIMARY") {
            Log.d(tag, "broadcastSettings: ISO=$iso, Shutter Speed=$shutterSpeed")
            val commandId = UUID.randomUUID().toString()
            networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureRate, captureLimit, currentProjectName, commandId))
        }
    }

    override fun onNewProject() {
        setupNewProject(true)
        broadcastSettings()
        updateUi()
    }

    override fun onArmCapture(broadcast: Boolean) {
        isArmed.set(true)
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.ArmCapture(UUID.randomUUID().toString()))
        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(true)
    }

    override fun onDisarmCapture(broadcast: Boolean) {
        isArmed.set(false)
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.DisarmCapture(UUID.randomUUID().toString()))
        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(false)
    }

    override fun onStartCapture() {
        if (isArmed.get()) {
            runOnUiThread { captureInProgressBorder.visibility = View.VISIBLE }
            networkService?.broadcastMessage(NetworkService.Message.StartCapture(UUID.randomUUID().toString()))
            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startBurstCapture(iso, shutterSpeed, captureRate, captureLimit)
        }
    }

    override fun onStopCapture() {
        runOnUiThread { captureInProgressBorder.visibility = View.GONE }
        networkService?.broadcastMessage(NetworkService.Message.StopCapture(UUID.randomUUID().toString()))
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.stopBurstCapture()
    }

    override fun onAnalyzeScene() {
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.toggleAutoIso()
    }

    override fun onStopServer() {
        showLoading("Shutting down server...")
        if (role == "PRIMARY") {
            networkService?.unregisterService()
        }
        finish()
    }

    override fun onRegenerateCameraName() {
        generateCameraName(true)
        broadcastSettings()
        updateUi()
    }
    
    override fun onCloseApp() {
        if (role == "PRIMARY") {
            onStopServer()
        } else {
            onLeaveServer()
        }
        finishAffinity()
    }

    override fun onLeaveServer() {
        showLoading("Disconnecting from server...")
        if (role == "CLIENT") {
            networkService?.disconnectFromPrimary()
        }
        finish()
    }
}
