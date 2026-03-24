package com.example.rexray_vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.rexray_vision.databinding.ActivityCaptureBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class CaptureActivity : AppCompatActivity(), BaseCaptureFragment.CameraFragmentListener, PrimaryControlsFragment.PrimaryControlsListener, ClientControlsFragment.ClientControlsListener {

    private val tag = "CaptureActivity"
    private var role: String = "CLIENT"
    private var isBound = false
    var networkService: NetworkService? = null
    
    private var isMigrationBound = false
    private var migrationService: FileMigrationService? = null

    private lateinit var workflowManager: WorkflowManager
    private lateinit var storageManager: InternalStorageManager
    private lateinit var imuPacketizer: ImuPacketizer
    private var anchorEngine: AnchorEngine? = null
    private var dualCameraManager: DualCameraManager? = null

    private lateinit var binding: ActivityCaptureBinding

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var networkStateJob: Job? = null
    private var renderJob: Job? = null
    private var migrationStateJob: Job? = null
    private var captureTimerJob: Job? = null

    private var isSurfaceAvailable = false
    private var currentProjectName: String = ""
    private var sessionCaptureCount = 0

    private lateinit var sharedPreferences: SharedPreferences

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            networkService = binder.getService()
            isBound = true
            observeNetworkState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            networkService = null
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
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        role = intent.getStringExtra("ROLE") ?: "CLIENT"
        val newProject = intent.getBooleanExtra("NEW_PROJECT", false)

        // Robust Inset Handling to protect from notification bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(binding.statusOverlayContainer) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
            }
            insets
        }

        sharedPreferences = getSharedPreferences("RexrayVisionPrefs", Context.MODE_PRIVATE)
        
        workflowManager = WorkflowManager(this)
        storageManager = InternalStorageManager(this)
        imuPacketizer = ImuPacketizer(this)
        
        setupNewProject(newProject)
        generateCameraName(false)

        if (role == "PRIMARY") {
            anchorEngine = AnchorEngine(this)
        }
        
        startBackgroundThread()

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
        
        binding.arcoreSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(tag, "ARCore Surface Created")
                isSurfaceAvailable = true
                
                val rotation = windowManager.defaultDisplay.rotation
                val w = if (binding.arcoreSurfaceView.width > 0) binding.arcoreSurfaceView.width else 1080
                val h = if (binding.arcoreSurfaceView.height > 0) binding.arcoreSurfaceView.height else 2400
                anchorEngine?.onSurfaceChanged(w, h, rotation)
                
                if (getIsArmed() && role == "PRIMARY") {
                    anchorEngine?.setWarmupStart(System.currentTimeMillis())
                    
                    val fps = getCaptureRate()
                    val ratio = if (fps > 0) 30 / fps else 2
                    anchorEngine?.setSamplingRatio(ratio)
                    
                    anchorEngine?.initializeSession(holder)
                    startRenderLoop()
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    Log.d(tag, "ARCore Surface Changed: $width x $height")
                    val rotation = windowManager.defaultDisplay.rotation
                    anchorEngine?.onSurfaceChanged(width, height, rotation)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(tag, "ARCore Surface Destroyed")
                isSurfaceAvailable = false
                stopRenderLoop()
            }
        })
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(tag, "Interrupted stopping background thread", e)
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
        if (isBound) unbindService(connection)
        if (isMigrationBound) unbindService(migrationServiceConnection)
        
        dualCameraManager?.closeAll()
        anchorEngine?.closeSession()
        imuPacketizer.stopLogging()
        stopBackgroundThread()
    }

    private fun observeNetworkState() {
        networkStateJob?.cancel()
        networkStateJob = lifecycleScope.launch {
            val ns = networkService ?: return@launch
            
            launch {
                ns.isConnectedToPrimary.collect { isConnected ->
                    if (role == "CLIENT" && !isConnected) finish()
                }
            }

            launch {
                combine(
                    ns.iso,
                    ns.shutterSpeed,
                    ns.captureRate,
                    ns.captureLimit,
                    ns.projectName,
                    ns.isArmed
                ) { params ->
                    params
                }.collectLatest {
                    currentProjectName = ns.projectName.value
                    if (!ns.isArmed.value) {
                        updatePreview()
                    }
                    runOnUiThread { updateUi() }
                }
            }

            launch {
                ns.isArmed.collect { armed ->
                    if (armed) {
                        workflowManager.transitionToArmed {
                            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.stopPreviewAndReleaseCamera()
                            imuPacketizer.startHighFreqLogging()
                            
                            if (role == "PRIMARY") {
                                runOnUiThread {
                                    binding.arcoreSurfaceView.visibility = View.VISIBLE
                                    binding.arStatusText.visibility = View.VISIBLE
                                }
                                if (isSurfaceAvailable) {
                                    anchorEngine?.setMode(AnchorEngine.CloudMode.STREAMING)
                                    anchorEngine?.initializeSession(binding.arcoreSurfaceView.holder)
                                    startRenderLoop()
                                }
                            } else if (role == "CLIENT") {
                                val targets = getMappedPhysicalIds()
                                dualCameraManager?.openAndSetupBurst(targets)
                            }
                        }
                    } else {
                        workflowManager.transitionToDisarmed {
                            // Phase 2: Increased teardown delay and stricter sequence
                            stopRenderLoop()
                            imuPacketizer.stopLogging()
                            if (role == "PRIMARY") {
                                anchorEngine?.closeSession()
                                runOnUiThread {
                                    binding.arcoreSurfaceView.visibility = View.GONE
                                    binding.arStatusText.visibility = View.GONE
                                }
                                delay(500) // Increased delay to ensure surface is cleared
                            } else if (role == "CLIENT") {
                                dualCameraManager?.closeAll()
                            }
                            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startPreview()
                        }
                    }
                }
            }

            launch {
                ns.incomingMessages.collect { (_, msg) ->
                    when (msg) {
                        is NetworkService.Message.ArmCapture -> onArmCapture(false)
                        is NetworkService.Message.DisarmCapture -> onDisarmCapture(false)
                        is NetworkService.Message.StartCapture -> executeStartCapture(true)
                        is NetworkService.Message.StopCapture -> executeStopCapture(true)
                        else -> {}
                    }
                }
            }
            
            if (role == "PRIMARY") {
                launch {
                    ns.connectedClients.collect { clients ->
                        val clientInfo = clients.values.map { 
                            "${it.cameraName}: [${it.imageCount}/${getCaptureLimit()}] ${if(it.isArmed) "ARMED" else "WAITING"}"
                        }
                        runOnUiThread {
                            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.setClientList(clientInfo)
                        }
                    }
                }
            }
        }
    }

    private fun startRenderLoop() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            while (isActive) {
                val stats = anchorEngine?.updateAndRender() ?: "OFF"
                val warmedUp = anchorEngine?.isDepthWarmedUp() ?: false
                
                runOnUiThread { 
                    val statusPrefix = if (warmedUp) "AR READY" else "WARMING UP"
                    binding.arStatusText.text = "$statusPrefix: $stats"
                }
                delay(33) // ~30 FPS for better stability
            }
        }
    }

    private fun stopRenderLoop() {
        renderJob?.cancel()
        renderJob = null
    }

    fun getProjectName(): String = networkService?.projectName?.value ?: ""
    fun getCameraName(): String = networkService?.projectName?.value ?: "" // Placeholder, should be distinct

    private fun getMappedPhysicalIds(): List<Pair<String, String?>> {
        val prefs = getSharedPreferences("HardwareMapping", Context.MODE_PRIVATE)
        val mainOpenId = prefs.getString("main_open_id", null)
        val mainPhysId = prefs.getString("main_phys_id", null)
        val secOpenId = prefs.getString("secondary_open_id", null)
        val secPhysId = prefs.getString("secondary_phys_id", null)
        
        val list = mutableListOf<Pair<String, String?>>()
        mainOpenId?.let { list.add(Pair(it, mainPhysId)) }
        secOpenId?.let { list.add(Pair(it, secPhysId)) }
        return list
    }

    private fun observeMigrationState() {
        migrationStateJob?.cancel()
        migrationStateJob = lifecycleScope.launch {
            val service = migrationService ?: return@launch
            service.isMigrating.combine(service.migrationProgress) { isMigrating, progress ->
                isMigrating to progress
            }.collect { (isMigrating, progress) ->
                if (role == "PRIMARY") {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateMigrationProgress(isMigrating, progress)
                } else {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateMigrationProgress(isMigrating, progress)
                }
                
                if (!isMigrating && progress == 100) {
                    triggerOffloadWorker()
                }
            }
        }
    }

    private fun triggerOffloadWorker() {
        val workRequest = OneTimeWorkRequestBuilder<OffloadWorker>()
            .setInputData(workDataOf("PROJECT_NAME" to currentProjectName))
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    fun startMigrationService() {
        val intent = Intent(this, FileMigrationService::class.java).apply {
            action = FileMigrationService.ACTION_START_MIGRATION
            putExtra(FileMigrationService.EXTRA_CACHE_DIR, storageManager.prepareStorageHandles().absolutePath)
        }
        startForegroundService(intent)
    }

    override fun onCameraReady() { updatePreview() }
    override fun onImageCaptured() { sessionCaptureCount++; updateCaptureCounter() }
    override fun onCaptureLimitReached() { onStopCapture() }
    override fun onHistogramUpdated(histogram: IntArray) { 
        if (role == "PRIMARY") {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateHistogram(histogram)
        }
    }
    override fun onAutoIsoStateChanged(isAnalyzing: Boolean) { 
        if (role == "PRIMARY") {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateAutoIsoState(isAnalyzing)
        }
    }

    override fun setIso(value: Int) { networkService?.updateIso(value); updatePreview() }
    override fun setShutterSpeed(value: Long) { networkService?.updateShutterSpeed(value); updatePreview() }
    override fun setCaptureRate(value: Int) { networkService?.updateCaptureRate(value) }
    override fun setCaptureLimit(value: Int) { networkService?.updateCaptureLimit(value) }
    
    override fun setCaptureMode(value: NetworkService.CaptureMode) { networkService?.updateCaptureMode(value) }
    
    fun onDngWriterThreadsChanged(count: Int) { 
        val editor = sharedPreferences.edit()
        editor.putInt("DNG_WRITER_THREADS", count)
        editor.apply()
    }

    override fun onProjectNameChanged(name: String) { networkService?.updateProjectName(name) }

    override fun onNewProject() { setupNewProject(true); broadcastSettings() }

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
        if (role == "PRIMARY" && anchorEngine?.isDepthWarmedUp() == false) {
            Toast.makeText(this, "Depth cache warming up... please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        executeStartCapture(false) 
    }
    override fun onStopCapture() { executeStopCapture(false) }

    // Implement missing PrimaryControlsListener / ClientControlsListener methods
    override fun onAnalyzeScene() { (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.toggleAutoIso() }
    override fun onStopServer() { networkService?.unregisterService(); finish() }
    override fun onRegenerateCameraName() { generateCameraName(true) }
    override fun onSaveAndReset() { /* logic if needed */ }
    override fun setFlashEnabled(value: Boolean) { networkService?.updateFlashEnabled(value); updatePreview() }
    override fun setFlashIntensity(value: Int) { networkService?.updateFlashIntensity(value); updatePreview() }
    override fun onCloseApp() { networkService?.unregisterService(); finishAffinity() }
    override fun onLeaveServer() { networkService?.disconnectFromPrimary(); finish() }

    fun getIsFlashEnabled(): Boolean = networkService?.isFlashEnabled?.value ?: false
    fun getFlashIntensity(): Int = networkService?.flashIntensity?.value ?: 1

    private fun executeStartCapture(isNetworkTriggered: Boolean) {
        if (getIsArmed() || isNetworkTriggered) {
            sessionCaptureCount = 0
            updateCaptureCounter()
            
            runOnUiThread { 
                binding.captureInProgressBorder.visibility = View.VISIBLE 
                if (role == "PRIMARY") {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(true)
                }
            }

            if (!isNetworkTriggered) {
                networkService?.broadcastMessage(NetworkService.Message.StartCapture(UUID.randomUUID().toString()))
                
                // Primary specific coordination
                if (role == "PRIMARY") {
                    anchorEngine?.setMode(AnchorEngine.CloudMode.ACCUMULATING)
                    
                    // Start automatic stop timer based on capture settings
                    val limit = getCaptureLimit()
                    val rate = getCaptureRate()
                    val durationMs = ((limit.toDouble() / rate.toDouble()) * 1000).toLong() + 500 // 500ms buffer
                    
                    captureTimerJob?.cancel()
                    captureTimerJob = lifecycleScope.launch {
                        delay(durationMs)
                        Log.d(tag, "Primary automatic capture stop triggered after $durationMs ms")
                        onStopCapture()
                    }
                }
            }

            if (role == "CLIENT") {
                (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startBurstCapture(
                    getIso(), getShutterSpeed(), getCaptureRate(), getCaptureLimit()
                )
            }
        }
    }

    private fun executeStopCapture(isNetworkTriggered: Boolean) {
        captureTimerJob?.cancel()
        captureTimerJob = null
        
        if (role == "PRIMARY") {
            anchorEngine?.setMode(AnchorEngine.CloudMode.STREAMING)
        }

        runOnUiThread { 
            binding.captureInProgressBorder.visibility = View.GONE
            if (role == "PRIMARY") {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(false)
            }
        }

        if (!isNetworkTriggered) {
            networkService?.broadcastMessage(NetworkService.Message.StopCapture(UUID.randomUUID().toString()))
        }

        if (role == "CLIENT") {
            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.stopBurstCapture()
        }
    }

    fun updatePreview() {
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.updatePreview(getIso(), getShutterSpeed())
    }

    private fun updateUi() {
        if (role == "PRIMARY") {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateUi()
        } else {
            (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateUi()
        }
    }

    private fun updateCaptureCounter() {
        runOnUiThread {
            if (role == "PRIMARY") {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureCount(sessionCaptureCount)
            } else {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? ClientControlsFragment)?.updateCaptureCount(sessionCaptureCount)
                networkService?.currentImageCount = sessionCaptureCount
            }
        }
    }

    fun getIso(): Int = networkService?.iso?.value ?: 100
    fun getShutterSpeed(): Long = networkService?.shutterSpeed?.value ?: 10000000L
    fun getCaptureRate(): Int = networkService?.captureRate?.value ?: 10
    fun getCaptureLimit(): Int = networkService?.captureLimit?.value ?: 100
    fun getCaptureMode(): NetworkService.CaptureMode = networkService?.captureMode?.value ?: NetworkService.CaptureMode.RAW
    fun getIsArmed(): Boolean = networkService?.isArmed?.value ?: false
    fun getDngWriterThreads(): Int = sharedPreferences.getInt("DNG_WRITER_THREADS", 1)

    fun broadcastSettings() {
        networkService?.broadcastMessage(NetworkService.Message.SetParams(
            getShutterSpeed(), getIso(), getCaptureRate(), getCaptureLimit(), currentProjectName, getCaptureMode(), getIsFlashEnabled(), getFlashIntensity(), UUID.randomUUID().toString()
        ))
    }

    private fun setupNewProject(isNew: Boolean) {
        if (isNew) {
            val name = "Project_${System.currentTimeMillis()}"
            networkService?.updateProjectName(name)
            currentProjectName = name
        } else {
            currentProjectName = networkService?.projectName?.value ?: ""
        }
    }

    private fun generateCameraName(force: Boolean) {
        val prefs = getSharedPreferences("RexrayVisionPrefs", Context.MODE_PRIVATE)
        var name = prefs.getString("CAMERA_NAME", null)
        if (name == null || force) {
            name = "Cam_${UUID.randomUUID().toString().take(4)}"
            prefs.edit().putString("CAMERA_NAME", name).apply()
        }
        networkService?.setCameraName(name)
    }
}
