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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CaptureActivity : AppCompatActivity(), BaseCaptureFragment.CameraFragmentListener, PrimaryControlsFragment.PrimaryControlsListener, ClientControlsFragment.ClientControlsListener {

    private val tag = "CaptureActivity"
    private lateinit var role: String

    private var networkService: NetworkService? = null
    private var migrationService: FileMigrationService? = null
    private var isBound = false
    private var isMigrationBound = false
    private lateinit var sharedPreferences: SharedPreferences
    private var networkStateJob: Job? = null
    private var migrationStateJob: Job? = null

    private lateinit var currentProjectName: String
    private lateinit var cameraName: String
    
    // Removed local state variables. NetworkService is now the source of truth.
    private var dngWriterThreads = 4
    private var sessionCaptureCount = 0

    private lateinit var loadingIndicator: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var captureCounter: TextView
    private lateinit var captureInProgressBorder: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            val ns = binder.getService()
            networkService = ns
            isBound = true
            
            if (role == "PRIMARY") {
                // Persistent Server Check: Don't restart if already running
                if (ns.serviceRole.value == NetworkService.ServiceRole.IDLE) {
                    ns.registerService(0, "$currentProjectName-$cameraName")
                } else {
                    Log.d(tag, "Resuming existing Primary server session.")
                }
            }
            
            ns.setCameraName(cameraName)
            observeNetworkState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(tag, "Service disconnected")
            isBound = false
            networkService = null
            Toast.makeText(this@CaptureActivity, "Network service connection lost", Toast.LENGTH_LONG).show()
        }
    }

    private val migrationConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FileMigrationService.MigrationBinder
            migrationService = binder.getService()
            isMigrationBound = true
            observeMigrationState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isMigrationBound = false
            migrationService = null
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
        Intent(this, FileMigrationService::class.java).also { intent ->
            bindService(intent, migrationConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        if (isMigrationBound) {
            unbindService(migrationConnection)
            isMigrationBound = false
        }
    }

    private fun observeNetworkState() {
        networkStateJob?.cancel()
        networkStateJob = lifecycleScope.launch {
            val ns = networkService ?: return@launch
            
            // Observe Role-specific health
            launch {
                if (role == "CLIENT") {
                    ns.isConnectedToPrimary.collect { isConnected ->
                        // Only finish if we were connected and then lost it
                        // (Wait for binding to finish)
                    }
                }
            }

            // Sync UI with Master Service State
            launch {
                combine(ns.iso, ns.shutterSpeed, ns.captureRate, ns.captureLimit, ns.projectName) { i, s, r, l, p ->
                    MasterState(i, s, r, l, p)
                }.collectLatest { state ->
                    currentProjectName = state.projectName
                    updatePreview()
                    runOnUiThread { updateUi() }
                }
            }

            // Sync Armed Status
            launch {
                ns.isArmed.collect { armed ->
                    if (armed) {
                        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(true)
                    } else {
                        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(false)
                    }
                }
            }

            // Handle Incoming Commands (Internal Commands)
            launch {
                ns.incomingMessages.collect { messagePair ->
                    messagePair?.let { (_, message) ->
                        when (message) {
                            is NetworkService.Message.ArmCapture -> onArmCapture(false)
                            is NetworkService.Message.DisarmCapture -> onDisarmCapture(false)
                            is NetworkService.Message.StartCapture -> onStartCapture()
                            is NetworkService.Message.StopCapture -> onStopCapture()
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    data class MasterState(val iso: Int, val shutterSpeed: Long, val captureRate: Int, val captureLimit: Int, val projectName: String)

    private fun observeMigrationState() {
        migrationStateJob?.cancel()
        migrationStateJob = lifecycleScope.launch {
            val service = migrationService ?: return@launch
            service.isMigrating.combine(service.migrationProgress) { isMigrating, progress ->
                isMigrating to progress
            }.collect { (isMigrating, progress) ->
                if (role == "PRIMARY") {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)
                        ?.updateMigrationProgress(isMigrating, progress)
                }
            }
        }
    }

    fun startMigrationService() {
        val intent = Intent(this, FileMigrationService::class.java).apply {
            action = FileMigrationService.ACTION_START_MIGRATION
            putExtra(FileMigrationService.EXTRA_CACHE_DIR, File(filesDir, "dng_cache").absolutePath)
        }
        startForegroundService(intent)
        bindService(intent, migrationConnection, Context.BIND_AUTO_CREATE)
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
        Log.d(tag, "onCameraReady: Applying Master Service settings to preview")
        updatePreview()
        if (role == "PRIMARY") {
            runOnUiThread { 
                val frag = supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment
                frag?.onCameraReady() 
                
                val baseFrag = supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment
                baseFrag?.let { bf ->
                    lifecycleScope.launch {
                        bf.getImageSaver().activeTaskCount.collect { count ->
                            if (bf.isAwaitingMigration()) {
                                frag?.updateDiskWriteProgress(count)
                            }
                            if (count == 0 && bf.isAwaitingMigration()) {
                                bf.markMigrationStarted()
                                startMigrationService()
                            }
                        }
                    }
                }
            }
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
        val ns = networkService ?: return
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.updatePreview(ns.iso.value, ns.shutterSpeed.value)
    }

    private fun updateUi() {
        if (role == "PRIMARY") {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateUi()
        } else {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateUi()
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
        networkService?.updateProjectName(pName)
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

    fun getProjectName(): String = networkService?.projectName?.value ?: currentProjectName
    fun getCameraName(): String = cameraName
    fun getIsArmed(): Boolean = networkService?.isArmed?.value ?: false
    fun getIso(): Int = networkService?.iso?.value ?: 400
    fun getShutterSpeed(): Long = networkService?.shutterSpeed?.value ?: 3333333L
    fun getCaptureRate(): Int = networkService?.captureRate?.value ?: 15
    fun getCaptureLimit(): Int = networkService?.captureLimit?.value ?: 30
    fun getDngWriterThreads(): Int = dngWriterThreads

    override fun setIso(value: Int) {
        networkService?.updateIso(value)
        sharedPreferences.edit { putInt("iso", value) }
        broadcastSettings()
    }

    override fun setShutterSpeed(value: Long) {
        networkService?.updateShutterSpeed(value)
        sharedPreferences.edit { putLong("shutterSpeed", value) }
        broadcastSettings()
    }

    override fun setCaptureRate(value: Int) {
        networkService?.updateCaptureRate(value)
        sharedPreferences.edit { putInt("captureRate", value) }
        broadcastSettings()
    }

    override fun setCaptureLimit(value: Int) {
        networkService?.updateCaptureLimit(value)
        sharedPreferences.edit { putInt("captureLimit", value) }
        broadcastSettings()
    }

    fun broadcastSettings() {
        if (role == "PRIMARY") {
            val ns = networkService ?: return
            val commandId = UUID.randomUUID().toString()
            ns.broadcastMessage(NetworkService.Message.SetParams(
                ns.shutterSpeed.value, ns.iso.value, ns.captureRate.value, ns.captureLimit.value, ns.projectName.value, commandId
            ))
        }
    }

    override fun onNewProject() {
        setupNewProject(true)
        broadcastSettings()
    }

    override fun onArmCapture(broadcast: Boolean) {
        networkService?.updateArmingStatus(true)
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.ArmCapture(UUID.randomUUID().toString()))
    }

    override fun onDisarmCapture(broadcast: Boolean) {
        networkService?.updateArmingStatus(false)
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.DisarmCapture(UUID.randomUUID().toString()))
    }

    override fun onStartCapture() {
        if (getIsArmed()) {
            runOnUiThread { captureInProgressBorder.visibility = View.VISIBLE }
            networkService?.broadcastMessage(NetworkService.Message.StartCapture(UUID.randomUUID().toString()))
            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startBurstCapture(getIso(), getShutterSpeed(), getCaptureRate(), getCaptureLimit())
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
