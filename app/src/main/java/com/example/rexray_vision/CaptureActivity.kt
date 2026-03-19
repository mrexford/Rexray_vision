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
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

class CaptureActivity : AppCompatActivity(), 
    BaseCaptureFragment.CameraFragmentListener, 
    PrimaryControlsFragment.PrimaryControlsListener, 
    ClientControlsFragment.ClientControlsListener,
    DualCameraManager.DualCameraListener {

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
    private var captureSafetyJob: Job? = null
    private var renderJob: Job? = null

    private lateinit var currentProjectName: String
    private lateinit var cameraName: String
    
    private var sessionCaptureCount = 0
    private var dngWriterThreads = 4

    private lateinit var loadingIndicator: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var captureInProgressBorder: View
    
    private lateinit var arcoreSurfaceView: SurfaceView
    private lateinit var arStatusText: TextView

    private var isSurfaceAvailable = false

    // Core Components
    private lateinit var workflowManager: WorkflowManager
    private lateinit var storageManager: InternalStorageManager
    private lateinit var imuPacketizer: ImuPacketizer 
    
    // Role-Specific Components
    private var dualCameraManager: DualCameraManager? = null // Node Only
    private var anchorEngine: AnchorEngine? = null // Brain Only
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            val ns = binder.getService()
            networkService = ns
            isBound = true
            
            if (role == "PRIMARY") {
                if (ns.serviceRole.value == NetworkService.ServiceRole.IDLE) {
                    ns.registerService(0, "$currentProjectName-$cameraName")
                }
            } else if (role == "CLIENT") {
                dualCameraManager = DualCameraManager(this@CaptureActivity, backgroundHandler!!, storageManager, ns.getSyncEngine(), this@CaptureActivity)
            }
            
            ns.setCameraName(cameraName)
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
        setContentView(R.layout.activity_capture)

        role = intent.getStringExtra("ROLE") ?: "CLIENT"
        val newProject = intent.getBooleanExtra("NEW_PROJECT", false)

        loadingIndicator = findViewById(R.id.loadingIndicator)
        loadingStatus = findViewById(R.id.loadingStatus)
        captureInProgressBorder = findViewById(R.id.captureInProgressBorder)
        arcoreSurfaceView = findViewById(R.id.arcoreSurfaceView)
        arStatusText = findViewById(R.id.arStatusText)

        // Robust Inset Handling to protect from notification bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.statusOverlayContainer)) { view, insets ->
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
        
        arcoreSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(tag, "ARCore Surface Created")
                isSurfaceAvailable = true
                
                val rotation = windowManager.defaultDisplay.rotation
                val w = if (arcoreSurfaceView.width > 0) arcoreSurfaceView.width else 1080
                val h = if (arcoreSurfaceView.height > 0) arcoreSurfaceView.height else 2400
                anchorEngine?.onSurfaceChanged(w, h, rotation)
                
                if (getIsArmed() && role == "PRIMARY") {
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
                combine(ns.iso, ns.shutterSpeed, ns.captureRate, ns.captureLimit, ns.projectName) { _, _, _, _, _ ->
                    Unit
                }.collectLatest {
                    currentProjectName = ns.projectName.value
                    updatePreview()
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
                                    arcoreSurfaceView.visibility = View.VISIBLE
                                    arStatusText.visibility = View.VISIBLE
                                }
                                if (isSurfaceAvailable) {
                                    anchorEngine?.setMode(AnchorEngine.CloudMode.STREAMING)
                                    anchorEngine?.initializeSession(arcoreSurfaceView.holder)
                                    startRenderLoop()
                                }
                            } else if (role == "CLIENT") {
                                val targets = getMappedPhysicalIds()
                                dualCameraManager?.openAndSetupBurst(targets)
                            }
                        }
                    } else {
                        workflowManager.transitionToDisarmed {
                            stopRenderLoop()
                            imuPacketizer.stopLogging()
                            if (role == "PRIMARY") {
                                runOnUiThread {
                                    arcoreSurfaceView.visibility = View.GONE
                                    arStatusText.visibility = View.GONE
                                }
                                anchorEngine?.closeSession()
                            } else if (role == "CLIENT") {
                                dualCameraManager?.closeAll()
                            }
                            (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startPreview()
                        }
                    }
                    if (role == "PRIMARY") {
                        (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateArmingState(armed)
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
                val points = anchorEngine?.getPointCount() ?: 0
                runOnUiThread { 
                    arStatusText.text = "AR SCANNING: $stats | Pts: $points" 
                }
                delay(16)
            }
        }
    }

    private fun stopRenderLoop() {
        renderJob?.cancel()
        renderJob = null
    }

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

    override fun onImageCaptured(data: ByteArray) {
        onImageCaptured()
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
        runOnUiThread { onStopCapture() } 
    }

    override fun onSessionConfigured(logicalId: String) {
        Log.d(tag, "Node session configured: $logicalId")
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
        val nm = NamingManager(sharedPreferences)
        currentProjectName = nm.setupNewProject(force)
        networkService?.updateProjectName(currentProjectName)
        sessionCaptureCount = 0
        updateCaptureCounter()
        if (force) storageManager.clearBurstStorage()
    }

    private fun generateCameraName(force: Boolean) {
        val nm = NamingManager(sharedPreferences)
        cameraName = nm.generateCameraName(force)
    }

    fun getProjectName(): String = currentProjectName
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

    override fun setIso(value: Int) { networkService?.updateIso(value); broadcastSettings() }
    override fun setShutterSpeed(value: Long) { networkService?.updateShutterSpeed(value); broadcastSettings() }
    override fun setCaptureRate(value: Int) { networkService?.updateCaptureRate(value); broadcastSettings() }
    override fun setCaptureLimit(value: Int) { networkService?.updateCaptureLimit(value); broadcastSettings() }
    override fun setCaptureMode(value: NetworkService.CaptureMode) { networkService?.updateCaptureMode(value); broadcastSettings() }
    override fun setFlashEnabled(value: Boolean) { networkService?.updateFlashEnabled(value); broadcastSettings() }
    override fun setFlashIntensity(value: Int) { networkService?.updateFlashIntensity(value); broadcastSettings() }

    fun broadcastSettings() {
        if (role == "PRIMARY") {
            val ns = networkService ?: return
            ns.broadcastMessage(NetworkService.Message.SetParams(
                ns.shutterSpeed.value, ns.iso.value, ns.captureRate.value, ns.captureLimit.value, ns.projectName.value, ns.captureMode.value, ns.isFlashEnabled.value, ns.flashIntensity.value, UUID.randomUUID().toString()
            ))
        }
    }

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

    override fun onStartCapture() { executeStartCapture(false) }
    override fun onStopCapture() { executeStopCapture(false) }

    private fun executeStartCapture(isNetworkTriggered: Boolean) {
        if (getIsArmed() || isNetworkTriggered) {
            sessionCaptureCount = 0
            updateCaptureCounter()
            
            runOnUiThread { 
                captureInProgressBorder.visibility = View.VISIBLE 
                if (role == "PRIMARY") {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(true)
                }
            }

            captureSafetyJob?.cancel()
            captureSafetyJob = lifecycleScope.launch {
                val expectedSeconds = (getCaptureLimit() / getCaptureRate())
                val safetyBuffer = 5000L 
                delay((expectedSeconds * 1000L) + safetyBuffer)
                Log.w(tag, "Capture safety timeout reached. Aborting capture.")
                executeStopCapture(false)
            }

            if (role == "PRIMARY") {
                anchorEngine?.setMode(AnchorEngine.CloudMode.ACCUMULATING)
                if (!isNetworkTriggered) {
                    networkService?.broadcastMessage(NetworkService.Message.StartCapture(UUID.randomUUID().toString()))
                }
                (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.startBurstCapture(
                    getIso(), getShutterSpeed(), getCaptureRate(), getCaptureLimit()
                )
            } else if (role == "CLIENT") {
                dualCameraManager?.startBurst(getCaptureLimit())
            }
        }
    }

    private fun executeStopCapture(isNetworkTriggered: Boolean) {
        captureSafetyJob?.cancel()
        
        runOnUiThread { 
            captureInProgressBorder.visibility = View.GONE 
            if (role == "PRIMARY") {
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.updateCaptureState(false)
                (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.showReviewUI(true)
            }
            showLoading("Finalizing Scan...")
        }
        
        lifecycleScope.launch {
            if (role == "PRIMARY") {
                anchorEngine?.setMode(AnchorEngine.CloudMode.REVIEW)
                if (!isNetworkTriggered) {
                    networkService?.broadcastMessage(NetworkService.Message.StopCapture(UUID.randomUUID().toString()))
                }
                (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.stopBurstCapture()
                
                imuPacketizer.exportToCsv(currentProjectName)
            } else if (role == "CLIENT") {
                dualCameraManager?.stopBurst()
                imuPacketizer.exportToCsv(currentProjectName)
                generateXmpSidecars()
                startMigrationService()
            }
            runOnUiThread { hideLoading() }
        }
    }

    override fun onSaveAndReset() {
        showLoading("Saving data...")
        lifecycleScope.launch {
            if (role == "PRIMARY") {
                anchorEngine?.exportPointCloudToPly(currentProjectName)
                startMigrationService()
                anchorEngine?.clearBuffer()
                anchorEngine?.setMode(AnchorEngine.CloudMode.STREAMING)
                runOnUiThread {
                    (supportFragmentManager.findFragmentById(R.id.controlsFragmentContainer) as? PrimaryControlsFragment)?.showReviewUI(false)
                }
                onDisarmCapture(true)
            }
            runOnUiThread { hideLoading() }
        }
    }

    private fun generateXmpSidecars() {
        val dir = storageManager.prepareStorageHandles()
        val files = dir.listFiles { f -> f.extension.lowercase() == "jpg" } ?: return
        val packer = MetadataPacker()
        val offset = networkService?.getSyncEngine()?.getOffsetNanos() ?: 0L
        
        files.forEach { file ->
            val parts = file.nameWithoutExtension.split("_")
            if (parts.size >= 2) {
                val sensorTs = parts[1].toLongOrNull() ?: 0L
                val brainTs = sensorTs + offset
                val imuSample = imuPacketizer.getClosestSample(sensorTs)
                packer.writeXmpSidecar(file, brainTs, imuSample)
            }
        }
    }

    fun showLoading(message: String) {
        runOnUiThread {
            loadingStatus.text = message
            loadingIndicator.visibility = View.VISIBLE
        }
    }

    fun hideLoading() {
        runOnUiThread { loadingIndicator.visibility = View.GONE }
    }

    override fun onAnalyzeScene() {
        (supportFragmentManager.findFragmentById(R.id.baseCaptureFragmentContainer) as? BaseCaptureFragment)?.toggleAutoIso()
    }
    override fun onStopServer() { 
        networkService?.unregisterService()
        finish() 
    }
    override fun onRegenerateCameraName() { generateCameraName(true); broadcastSettings() }
    override fun onCloseApp() { 
        stopService(Intent(this, NetworkService::class.java))
        finishAffinity() 
    }
    override fun onLeaveServer() { 
        networkService?.disconnectFromPrimary()
        finish() 
    }
}
