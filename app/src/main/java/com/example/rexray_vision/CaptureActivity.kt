package com.example.rexray_vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
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
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CaptureActivity : AppCompatActivity(), BaseCaptureFragment.CameraFragmentListener, PrimaryControlsFragment.PrimaryControlsListener, ClientControlsFragment.ClientControlsListener {

    private val tag = "CaptureActivity"
    private lateinit var role: String

    var networkService: NetworkService? = null
        private set
        
    private var migrationService: FileMigrationService? = null
    private var isBound = false
    private var isMigrationBound = false
    private lateinit var sharedPreferences: SharedPreferences
    private var networkStateJob: Job? = null
    private var migrationStateJob: Job? = null
    private var migrationTriggerJob: Job? = null

    private lateinit var currentProjectName: String
    private lateinit var cameraName: String
    
    private var dngWriterThreads = 4
    private var sessionCaptureCount = 0

    private lateinit var loadingIndicator: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var captureInProgressBorder: View

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            val ns = binder.getService()
            networkService = ns
            isBound = true
            
            if (role == "PRIMARY") {
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
            finish()
        }
    }

    private val migrationServiceConnection = object : ServiceConnection {
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
        captureInProgressBorder = findViewById(R.id.captureInProgressBorder)

        sharedPreferences = getSharedPreferences("RexrayVisionPrefs", Context.MODE_PRIVATE)
        
        setupNewProject(newProject)
        generateCameraName(false)

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
            bindService(intent, migrationServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        if (isMigrationBound) {
            unbindService(migrationServiceConnection)
            isMigrationBound = false
        }
    }

    private fun observeNetworkState() {
        networkStateJob?.cancel()
        networkStateJob = lifecycleScope.launch {
            val ns = networkService ?: return@launch
            
            launch {
                ns.isConnectedToPrimary.collect { isConnected ->
                    if (role == "CLIENT" && !isConnected) {
                        Log.w(tag, "Connection to Primary lost. Returning to setup.")
                        runOnUiThread {
                            Toast.makeText(this@CaptureActivity, "Server connection lost", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }

            launch {
                combine(
                    ns.iso,
                    ns.shutterSpeed,
                    ns.captureRate,
                    ns.captureLimit,
                    ns.projectName,
                    ns.captureMode,
                    ns.isFlashEnabled,
                    ns.flashIntensity
                ) { args ->
                    MasterState(
                        args[0] as Int,
                        args[1] as Long,
                        args[2] as Int,
                        args[3] as Int,
                        args[4] as String,
                        args[5] as NetworkService.CaptureMode,
                        args[6] as Boolean,
                        args[7] as Int
                    )
                }.collectLatest { state ->
                    currentProjectName = state.projectName
                    updatePreview()
                    runOnUiThread { updateUi() }
                }
            }

            launch {
                ns.isArmed.collect { armed ->
                    if (role == "PRIMARY") {
                        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(armed)
                    }
                }
            }

            launch {
                ns.connectedClients.collect { clients ->
                    if (role == "PRIMARY") {
                        val names = clients.values.map { it.cameraName.ifEmpty { it.socket.inetAddress.hostAddress ?: "Unknown" } }
                        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.setClientList(names)
                    }
                }
            }

            launch {
                ns.incomingMessages.collect { messagePair ->
                    val (_, message) = messagePair
                    when (message) {
                        is NetworkService.Message.ArmCapture -> onArmCapture(false)
                        is NetworkService.Message.DisarmCapture -> onDisarmCapture(false)
                        is NetworkService.Message.StartCapture -> executeStartCapture(true)
                        is NetworkService.Message.StopCapture -> executeStopCapture(true)
                        is NetworkService.Message.SyncTrigger -> {
                            if (role == "PRIMARY") broadcastSettings()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    data class MasterState(val iso: Int, val shutterSpeed: Long, val captureRate: Int, val captureLimit: Int, val projectName: String, val captureMode: NetworkService.CaptureMode, val isFlashEnabled: Boolean, val flashIntensity: Int)

    private fun observeMigrationState() {
        migrationStateJob?.cancel()
        migrationStateJob = lifecycleScope.launch {
            val service = migrationService ?: return@launch
            service.isMigrating.combine(service.migrationProgress) { isMigrating, progress ->
                isMigrating to progress
            }.collect { (isMigrating, progress) ->
                val primaryFrag = supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment
                val clientFrag = supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment
                
                if (role == "PRIMARY") {
                    primaryFrag?.updateMigrationProgress(isMigrating, progress)
                } else {
                    clientFrag?.updateMigrationProgress(isMigrating, progress)
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
        bindService(intent, migrationServiceConnection, Context.BIND_AUTO_CREATE)
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
        updatePreview()
        runOnUiThread { 
            val primaryFrag = supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment
            val clientFrag = supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment
            
            primaryFrag?.onCameraReady() 
            
            val baseFrag = supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment
            baseFrag?.let { bf ->
                migrationTriggerJob?.cancel()
                migrationTriggerJob = lifecycleScope.launch {
                    bf.getImageSaver().activeTaskCount.collect { count ->
                        if (bf.isAwaitingMigration()) {
                            Log.d(tag, "IO_TRACING: Pending Disk Tasks: $count")
                            primaryFrag?.updateDiskWriteProgress(count)
                            clientFrag?.updateDiskWriteProgress(count)
                            
                            if (count == 0) {
                                Log.i(tag, "IO_TRACING: ImageSaver idle. Handing off to FileMigrationService.")
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
        if (role == "CLIENT") {
            networkService?.currentImageCount = sessionCaptureCount
            networkService?.sendStatusUpdate()
        }
    }

    override fun onCaptureLimitReached() {
        onStopCapture()
    }

    private fun updateCaptureCounter() {
        runOnUiThread {
            if (role == "PRIMARY") {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureCount(sessionCaptureCount)
            } else {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateCaptureCount(sessionCaptureCount)
            }
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
        
        if (force) {
            clearCache()
        }
    }

    private fun clearCache() {
        val cacheDir = File(filesDir, "dng_cache")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(tag, "Cache cleared for new project.")
        }
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
    fun getCaptureMode(): NetworkService.CaptureMode = networkService?.captureMode?.value ?: NetworkService.CaptureMode.JPEG
    fun getIsFlashEnabled(): Boolean = networkService?.isFlashEnabled?.value ?: false
    fun getFlashIntensity(): Int = networkService?.flashIntensity?.value ?: 3
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

    override fun setCaptureMode(value: NetworkService.CaptureMode) {
        networkService?.updateCaptureMode(value)
        sharedPreferences.edit { putString("captureMode", value.name) }
        broadcastSettings()
    }

    override fun setFlashEnabled(value: Boolean) {
        networkService?.updateFlashEnabled(value)
        sharedPreferences.edit { putBoolean("isFlashEnabled", value) }
        broadcastSettings()
    }

    override fun setFlashIntensity(value: Int) {
        networkService?.updateFlashIntensity(value)
        sharedPreferences.edit { putInt("flashIntensity", value) }
        broadcastSettings()
    }

    fun broadcastSettings() {
        if (role == "PRIMARY") {
            val ns = networkService ?: return
            val commandId = UUID.randomUUID().toString()
            ns.broadcastMessage(NetworkService.Message.SetParams(
                ns.shutterSpeed.value, ns.iso.value, ns.captureRate.value, ns.captureLimit.value, ns.projectName.value, ns.captureMode.value, ns.isFlashEnabled.value, ns.flashIntensity.value, commandId
            ))
        }
    }

    override fun onNewProject() {
        setupNewProject(true)
        broadcastSettings()
    }

    override fun onArmCapture(broadcast: Boolean) {
        networkService?.updateArmingStatus(true)
        if (role == "CLIENT") networkService?.isClientArmed = true
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.ArmCapture(UUID.randomUUID().toString()))
    }

    override fun onDisarmCapture(broadcast: Boolean) {
        networkService?.updateArmingStatus(false)
        if (role == "CLIENT") networkService?.isClientArmed = false
        if (broadcast) networkService?.broadcastMessage(NetworkService.Message.DisarmCapture(UUID.randomUUID().toString()))
    }

    override fun onStartCapture() {
        executeStartCapture(false)
    }

    override fun onStopCapture() {
        executeStopCapture(false)
    }

    private fun executeStartCapture(isNetworkTriggered: Boolean) {
        if (getIsArmed() || isNetworkTriggered) {
            runOnUiThread { 
                captureInProgressBorder.visibility = View.VISIBLE 
                sessionCaptureCount = 0
                updateCaptureCounter()
                if (role == "PRIMARY") {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(true)
                } else {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateCaptureState(true)
                }
            }
            if (role == "PRIMARY" && !isNetworkTriggered) {
                networkService?.broadcastMessage(NetworkService.Message.StartCapture(UUID.randomUUID().toString()))
            }
            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startBurstCapture(getIso(), getShutterSpeed(), getCaptureRate(), getCaptureLimit())
        }
    }

    private fun executeStopCapture(isNetworkTriggered: Boolean) {
        runOnUiThread { 
            captureInProgressBorder.visibility = View.GONE 
            if (role == "PRIMARY") {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(false)
                if (!isNetworkTriggered) onDisarmCapture(true)
            } else {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateCaptureState(false)
            }
        }
        if (role == "PRIMARY" && !isNetworkTriggered) {
            networkService?.broadcastMessage(NetworkService.Message.StopCapture(UUID.randomUUID().toString()))
        }
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
