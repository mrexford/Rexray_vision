package com.example.rexray_vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class CaptureActivity : AppCompatActivity() {

    private val tag = "CaptureActivity"
    private var isClient = false

    private lateinit var textureView: TextureView
    private lateinit var captureButton: Button
    private lateinit var newProjectButton: Button
    private lateinit var projectNameTextView: TextView
    private lateinit var cameraNameTextView: TextView
    private lateinit var captureCountTextView: TextView
    private lateinit var permissionsTextView: TextView
    private lateinit var captureBorder: View
    private lateinit var isoSeekBar: SeekBar
    private lateinit var shutterSpeedSeekBar: SeekBar
    private lateinit var captureRateSeekBar: SeekBar
    private lateinit var captureLimitSeekBar: SeekBar
    private lateinit var isoValueTextView: TextView
    private lateinit var shutterSpeedValueTextView: TextView
    private lateinit var captureRateValueTextView: TextView
    private lateinit var captureLimitValueTextView: TextView
    private lateinit var analyzeSceneButton: Button
    private lateinit var histogramView: HistogramView
    private lateinit var armButton: Button
    private lateinit var clientListView: ListView
    private lateinit var connectedClientsHeader: TextView
    private lateinit var switchModeButton: Button
    private lateinit var regenerateCameraNameButton: ImageButton
    private lateinit var settingsDisplayTextView: TextView

    private var cameraCaptureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    @Volatile private var captureResult: TotalCaptureResult? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var rexrayCameraManager: RexrayCameraManager

    private val imageQueue = LinkedBlockingQueue<Image>()
    private lateinit var imageSaverExecutor: ThreadPoolExecutor

    private var isCapturing = false
    private lateinit var currentProjectName: String
    private lateinit var cameraName: String

    private var iso = 100
    private var shutterSpeed = 3333333L // 1/300s
    private var captureRate = 10
    private var captureLimit = 20
    private var isArmed = false
    private var isAnalyzing = false
    private val capturedImageCount = AtomicInteger(0)
    private var analysisPasses = 0
    private var exposureAnalysisStrategy: ExposureAnalysisStrategy = HistogramEttrAnalysisStrategy()
    private val maxAnalysisPasses = 8
    private val targetBrightness = 240

    private val cameraManager by lazy {
        getSystemService(CAMERA_SERVICE) as CameraManager
    }

    private lateinit var adjectives: Array<String>
    private lateinit var nouns: Array<String>
    private lateinit var animals: Array<String>

    private var networkService: NetworkService? = null
    private var isBound = false
    private lateinit var clientStatusAdapter: ClientStatusAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NetworkService.NetworkBinder
            networkService = binder.getService()
            isBound = true
            if (!isClient) {
                networkService?.registerService(0, "$currentProjectName-$cameraName")
            }
            networkService?.setCameraName(cameraName)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(tag, "Service disconnected")
            isBound = false
            networkService = null
            Toast.makeText(this@CaptureActivity, "Network service connection lost", Toast.LENGTH_LONG).show()
        }
    }

    private val imageSaverRunnable = Runnable {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val image = imageQueue.take()
                if (rexrayCameraManager.cameraDevice == null) {
                    image.close()
                    continue
                }
                val characteristics = cameraManager.getCameraCharacteristics(rexrayCameraManager.cameraDevice!!.id)
                captureResult?.let { result ->
                    val dngCreator = DngCreator(characteristics, result)
                    val rotation = display?.rotation ?: Surface.ROTATION_0
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                    dngCreator.setOrientation(getExifOrientation(rotation, sensorOrientation))
                    saveImageWithMediaStore(dngCreator, image)
                }
                image.close()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(tag, "ImageSaver: Error saving image", e)
            }
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            checkPermissionsAndOpenCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            rexrayCameraManager.cameraDevice = camera
            runOnUiThread {
                permissionsTextView.visibility = View.GONE
                setupSettingsControls()
                observeNetworkState()
            }
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            rexrayCameraManager.cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            rexrayCameraManager.cameraDevice = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_capture)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        isClient = intent.getBooleanExtra("isClient", false)

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureButton)
        newProjectButton = findViewById(R.id.newProjectButton)
        projectNameTextView = findViewById(R.id.projectNameTextView)
        cameraNameTextView = findViewById(R.id.cameraNameTextView)
        captureCountTextView = findViewById(R.id.captureCountTextView)
        permissionsTextView = findViewById(R.id.permissionsTextView)
        captureBorder = findViewById(R.id.captureBorder)
        isoSeekBar = findViewById(R.id.isoSeekBar)
        shutterSpeedSeekBar = findViewById(R.id.shutterSpeedSeekBar)
        captureRateSeekBar = findViewById(R.id.captureRateSeekBar)
        captureLimitSeekBar = findViewById(R.id.captureLimitSeekBar)
        isoValueTextView = findViewById(R.id.isoValueTextView)
        shutterSpeedValueTextView = findViewById(R.id.shutterSpeedValueTextView)
        captureRateValueTextView = findViewById(R.id.captureRateValueTextView)
        captureLimitValueTextView = findViewById(R.id.captureLimitValueTextView)
        analyzeSceneButton = findViewById(R.id.analyzeSceneButton)
        histogramView = findViewById(R.id.histogramView)
        armButton = findViewById(R.id.armButton)
        armButton.contentDescription = getString(R.string.arm_button_description)
        clientListView = findViewById(R.id.clientListView)
        connectedClientsHeader = findViewById(R.id.connectedClientsHeader)
        switchModeButton = findViewById(R.id.switchModeButton)
        regenerateCameraNameButton = findViewById(R.id.regenerateCameraNameButton)
        settingsDisplayTextView = findViewById(R.id.settingsDisplayTextView)

        adjectives = resources.getStringArray(R.array.adjectives)
        nouns = resources.getStringArray(R.array.nouns)
        animals = resources.getStringArray(R.array.animals)

        sharedPreferences = getSharedPreferences("RexrayVisionPrefs", MODE_PRIVATE)

        clientStatusAdapter = ClientStatusAdapter(this, mutableListOf())
        clientListView.adapter = clientStatusAdapter

        setupNewProject(false)
        generateCameraName(false)
        updateCaptureCount(0)

        setupUIForRole()
    }
    private fun setupUIForRole() {
        if (isClient) {
            // Client-specific UI setup
            switchModeButton.text = "Disconnect"
            switchModeButton.setOnClickListener { networkService?.disconnectFromPrimary() }
            captureButton.visibility = View.GONE
            newProjectButton.visibility = View.GONE
            regenerateCameraNameButton.visibility = View.GONE
            clientListView.visibility = View.GONE
            connectedClientsHeader.visibility = View.GONE
            armButton.visibility = View.GONE
            findViewById<LinearLayout>(R.id.settingsLayout).visibility = View.GONE

        } else {
            // Primary-specific UI setup
            switchModeButton.text = "Switch to Client"
            switchModeButton.setOnClickListener { showSwitchModeDialog() }
            regenerateCameraNameButton.setOnClickListener { generateCameraName(true) }
            newProjectButton.setOnClickListener { if (!isCapturing) setupNewProject(true) }
            captureButton.setOnClickListener {
                if (isCapturing) {
                    stopBurstCapture()
                } else {
                    startBurstCapture()
                    networkService?.broadcastMessage(NetworkService.Message.StartCapture)
                }
            }
            analyzeSceneButton.setOnClickListener { if (rexrayCameraManager.cameraDevice != null) analyzeScene(false) }
            armButton.setOnClickListener {
                isArmed = !isArmed
                if (isArmed) {
                    networkService?.broadcastMessage(NetworkService.Message.ArmCapture)
                } else {
                    networkService?.broadcastMessage(NetworkService.Message.DisarmCapture)
                }
                updateArmingState()
            }
        }
        updateArmingState()
    }

    override fun onStart() {
        super.onStart()
        startBackgroundThread()
        rexrayCameraManager = RexrayCameraManager(this, backgroundHandler)
        startImageSaver()
        Intent(this, NetworkService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        saveSettings()
        rexrayCameraManager.closeCamera()
        stopImageSaver()
        stopBackgroundThread()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) checkPermissionsAndOpenCamera(textureView.width, textureView.height)
        else textureView.surfaceTextureListener = textureListener
    }

    private fun observeNetworkState() {
        if (isClient) {
            lifecycleScope.launch {
                networkService?.isConnectedToPrimary?.drop(1)?.collect { isConnected ->
                    if (!isConnected) {
                        val intent = Intent(this@CaptureActivity, SetupActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }
        lifecycleScope.launch {
            networkService?.connectedClients?.collect { clients ->
                clientStatusAdapter.clear()
                clientStatusAdapter.addAll(clients.values)
                clientStatusAdapter.notifyDataSetChanged()
                updateArmingState()
            }
        }
        lifecycleScope.launch {
            networkService?.incomingMessages?.collect { messagePair ->
                messagePair?.let { (socket, message) ->
                    when (message) {
                        is NetworkService.Message.ArmCapture -> armCapture()
                        is NetworkService.Message.DisarmCapture -> disarmCapture()
                        is NetworkService.Message.StartCapture -> startBurstCapture()
                        is NetworkService.Message.SetParams -> {
                            shutterSpeed = message.shutterSpeed
                            iso = message.iso
                            captureLimit = message.captureCount
                            currentProjectName = message.projectName
                            updateUIFromNetwork()
                        }
                        is NetworkService.Message.UpdateCameraName -> {
                            networkService?.updateClientCameraName(socket, message.name)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun armCapture() {
        isArmed = true
        updateArmingState()
    }

    private fun disarmCapture() {
        isArmed = false
        updateArmingState()
    }

    private fun updateArmingState() {
        runOnUiThread {
            if(!isClient) {
                armButton.text = if (isArmed) "Disarm" else "Arm"
                armButton.isEnabled = true
                captureButton.isEnabled = isArmed
                captureButton.alpha = if(isArmed) 1.0f else 0.5f
            }
        }
    }

    private fun checkPermissionsAndOpenCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsTextView.visibility = View.VISIBLE
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            permissionsTextView.visibility = View.GONE
            rexrayCameraManager.openCamera(width, height, cameraStateCallback)
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            rexrayCameraManager.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
            val previewSurface = Surface(texture)

            rexrayCameraManager.rawImageReader.setOnImageAvailableListener({ reader ->
                val image: Image? = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
                image?.let { img ->
                    if (capturedImageCount.get() < captureLimit && isCapturing) {
                        if (!imageQueue.offer(img)) {
                            img.close()
                        }
                    } else {
                        if (isCapturing) runOnUiThread { stopBurstCapture() }
                        img.close()
                    }
                }
            }, backgroundHandler)

            rexrayCameraManager.analysisImageReader.setOnImageAvailableListener({ reader ->
                val image: Image? = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null }
                image?.let {
                    val histogram = exposureAnalysisStrategy.getHistogram(it)
                    runOnUiThread { histogramView.updateHistogram(histogram) }

                    if(isAnalyzing) {
                        if (analysisPasses >= maxAnalysisPasses) {
                            isAnalyzing = false
                            runOnUiThread { Toast.makeText(this@CaptureActivity, "Auto ISO: Settled at $iso", Toast.LENGTH_SHORT).show() }
                        } else {
                            val percentileValue = exposureAnalysisStrategy.analyze(it)
                            Log.d(tag, "Analysis Pass: $analysisPasses, 95th Percentile: $percentileValue, Current ISO: $iso")

                            if (percentileValue != -1) {
                                val requiredAdjustment = targetBrightness.toDouble() / percentileValue.toDouble()
                                val newIso = (iso * requiredAdjustment).toInt().coerceIn(isoSeekBar.min, isoSeekBar.max)

                                if (abs(newIso - iso) > 5) {
                                    iso = newIso
                                    Log.d(tag, "Adjusting ISO to: $iso")
                                    runOnUiThread {
                                        isoSeekBar.progress = iso
                                        isoValueTextView.text = iso.toString()
                                        updatePreview()
                                    }
                                    if (!isClient) {
                                        networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                                    }
                                    backgroundHandler.postDelayed({ analyzeScene(true) }, 300)
                                } else {
                                    isAnalyzing = false // Coalesced
                                    runOnUiThread { Toast.makeText(this@CaptureActivity, "Auto ISO: Coalesced at $iso", Toast.LENGTH_SHORT).show() }
                                    if (!isClient) {
                                        networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                                    }
                                }
                            } else {
                                isAnalyzing = false
                                runOnUiThread { Toast.makeText(this@CaptureActivity, "Auto ISO: Failed", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        analysisPasses++
                    }
                    it.close()
                }
            }, backgroundHandler)

            previewRequestBuilder = rexrayCameraManager.cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)
            previewRequestBuilder.addTarget(rexrayCameraManager.analysisImageReader.surface)

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    android.hardware.camera2.params.OutputConfiguration(previewSurface),
                    android.hardware.camera2.params.OutputConfiguration(rexrayCameraManager.rawImageReader.surface),
                    android.hardware.camera2.params.OutputConfiguration(rexrayCameraManager.analysisImageReader.surface)
                ),
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (rexrayCameraManager.cameraDevice == null) return
                        cameraCaptureSession = session
                        updatePreview()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { }
                }
            )
            rexrayCameraManager.cameraDevice?.createCaptureSession(sessionConfig)
        } catch (_: CameraAccessException) { }
    }

    private fun updatePreview() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
        } catch (_: CameraAccessException) { }
    }

    private fun startBurstCapture() {
        capturedImageCount.set(0)
        updateCaptureCount(0)
        runOnUiThread { captureBorder.visibility = View.VISIBLE }

        try {
            val texture = textureView.surfaceTexture!!
            rexrayCameraManager.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
            val previewSurface = Surface(texture)

            val captureBuilder = rexrayCameraManager.cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(rexrayCameraManager.rawImageReader.surface)
            captureBuilder.addTarget(previewSurface)

            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(captureRate, captureRate))

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    captureResult = result
                    updateCaptureCount(capturedImageCount.incrementAndGet())
                }
            }
            cameraCaptureSession?.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler)
            isCapturing = true
            if(!isClient) {
                runOnUiThread { captureButton.text = getString(R.string.stop) }
            }
        } catch(_: CameraAccessException) { }
    }

    private fun stopBurstCapture() {
        isCapturing = false
        isArmed = false
        runOnUiThread {
            captureBorder.visibility = View.GONE
            updateArmingState()
        }
        try {
            cameraCaptureSession?.abortCaptures()
            cameraCaptureSession?.stopRepeating()
            updatePreview()
        } catch (_: CameraAccessException) { }
        if(!isClient) {
            runOnUiThread { captureButton.text = getString(R.string.capture) }
        }
    }

    private fun saveImageWithMediaStore(dngCreator: DngCreator, image: Image) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "${currentProjectName}_${cameraName}_$timeStamp.dng"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rexray_vision")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                resolver.openOutputStream(it).use { outputStream -> dngCreator.writeImage(outputStream!!, image) }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            } catch (_: Exception) { resolver.delete(it, null, null) }
        }
    }

    private fun setupNewProject(force: Boolean){
        var pName = if (force) null else sharedPreferences.getString("projectName", null)
        if (pName == null) {
            pName = "${adjectives.random()}-${nouns.random()}"
            sharedPreferences.edit { putString("projectName", pName) }
        }
        currentProjectName = pName

        runOnUiThread {
            projectNameTextView.text = currentProjectName
            updateArmingState()
            if (!isClient) {
                networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
            }
        }
    }

    private fun generateCameraName(force: Boolean) {
        var aName = sharedPreferences.getString("cameraName", null)
        if (aName == null || force) {
            aName = animals.random()
            sharedPreferences.edit { putString("cameraName", aName) }
        }
        cameraName = aName
        runOnUiThread {
            cameraNameTextView.text = cameraName
        }
        if (!isClient) {
            networkService?.setCameraName(cameraName)
        }
    }

    private fun setupSettingsControls() {
        loadSettings()
        if (rexrayCameraManager.cameraDevice == null) return
        val characteristics = cameraManager.getCameraCharacteristics(rexrayCameraManager.cameraDevice!!.id)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 800)

        // ISO
        isoSeekBar.max = isoRange.upper.coerceAtMost(800)
        isoSeekBar.min = isoRange.lower.coerceAtLeast(50)
        isoSeekBar.progress = iso
        isoValueTextView.text = iso.toString()

        isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                isoValueTextView.text = p.toString(); iso = p; updatePreview()
                if (fromUser && !isClient) {
                    networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                }
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Shutter Speed
        val shutterMinInv = 60.0 // 1/60s
        val shutterMaxInv = 2000.0 // 1/2000s
        val logMin = log10(shutterMinInv)
        val logMax = log10(shutterMaxInv)

        shutterSpeedSeekBar.max = 100
        val currentShutterInv = 1_000_000_000.0 / shutterSpeed
        val progress = (100 * (log10(currentShutterInv) - logMin) / (logMax - logMin)).toInt()
        shutterSpeedSeekBar.progress = progress
        shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, currentShutterInv.toLong())

        shutterSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                val logVal = logMin + (p / 100.0) * (logMax - logMin)
                val shutterInv = 10.0.pow(logVal).toLong()
                shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, shutterInv)
                shutterSpeed = 1_000_000_000L / shutterInv
                updatePreview()
                if (fromUser && !isClient) {
                    networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                }
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Capture Rate
        captureRateSeekBar.max = 60
        captureRateSeekBar.min = 3
        captureRateSeekBar.progress = captureRate
        captureRateValueTextView.text = getString(R.string.capture_rate_format, captureRate)

        captureRateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (p > 0) {
                    captureRate = p
                    captureRateValueTextView.text = getString(R.string.capture_rate_format, p)
                    val maxShutterSpeedNs = 1_000_000_000L / captureRate
                    if (shutterSpeed > maxShutterSpeedNs) {
                        shutterSpeed = maxShutterSpeedNs
                        val shutterAsSpeed = 1_000_000_000L / shutterSpeed
                        shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, shutterAsSpeed)
                        shutterSpeedSeekBar.progress = (100 * (log10(shutterAsSpeed.toDouble()) - logMin) / (logMax - logMin)).toInt()
                        updatePreview()
                    }
                    if(fromUser && !isClient) {
                        networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                    }
                }
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Capture Limit
        captureLimitSeekBar.max = 500
        captureLimitSeekBar.min = 1
        captureLimitSeekBar.progress = captureLimit
        captureLimitValueTextView.text = captureLimit.toString()

        captureLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (p>0) {
                    captureLimitValueTextView.text = p.toString(); captureLimit = p
                    if (fromUser && !isClient) {
                        networkService?.broadcastMessage(NetworkService.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                    }
                }
                updateSettingsDisplay()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        analyzeSceneButton.setOnClickListener { analyzeScene(false) }
        updateSettingsDisplay()
    }

    private fun saveSettings() {
        sharedPreferences.edit {
            putInt("iso", iso)
            putLong("shutterSpeed", shutterSpeed)
            putInt("captureRate", captureRate)
            putInt("captureLimit", captureLimit)
        }
    }

    private fun loadSettings() {
        iso = sharedPreferences.getInt("iso", 100)
        shutterSpeed = sharedPreferences.getLong("shutterSpeed", 3333333L)
        captureRate = sharedPreferences.getInt("captureRate", 10)
        captureLimit = sharedPreferences.getInt("captureLimit", 20)
    }

    private fun showSwitchModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Switch Mode")
            .setMessage("Are you sure you want to switch to client mode? Settings will be saved.")
            .setPositiveButton("Switch") { _, _ ->
                networkService?.unregisterService()
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUIFromNetwork() {
        runOnUiThread {
            val shutterMinInv = 60.0 // 1/60s
            val shutterMaxInv = 2000.0 // 1/2000s
            val logMin = log10(shutterMinInv)
            val logMax = log10(shutterMaxInv)

            isoValueTextView.text = iso.toString()
            isoSeekBar.progress = iso

            val currentShutterInv = 1_000_000_000.0 / shutterSpeed
            shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, currentShutterInv.toLong())
            shutterSpeedSeekBar.progress = (100 * (log10(currentShutterInv) - logMin) / (logMax - logMin)).toInt()

            captureRateValueTextView.text = getString(R.string.capture_rate_format, captureRate)
            captureRateSeekBar.progress = captureRate

            captureLimitValueTextView.text = captureLimit.toString()
            captureLimitSeekBar.progress = captureLimit

            projectNameTextView.text = currentProjectName
            updateSettingsDisplay()
        }
    }

    private fun analyzeScene(isContinuing: Boolean) {
        if (!isContinuing) {
            analysisPasses = 0
            isAnalyzing = true
        }
        try {
            val captureBuilder = rexrayCameraManager.cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureBuilder.addTarget(rexrayCameraManager.analysisImageReader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            cameraCaptureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch(_: CameraAccessException) { }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        rexrayCameraManager.previewSize?.let { previewSize ->
            val rotation = display?.rotation ?: Surface.ROTATION_0

            val matrix = Matrix()
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = (viewHeight.toFloat() / previewSize.height).coerceAtLeast(viewWidth.toFloat() / previewSize.width)
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
            textureView.setTransform(matrix)
        }
    }

    private fun getExifOrientation(rotation: Int, sensorOrientation: Int): Int {
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val result = (sensorOrientation - degrees + 360) % 360
        return when (result) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun startImageSaver() {
        val numCores = Runtime.getRuntime().availableProcessors()
        imageSaverExecutor = ThreadPoolExecutor(numCores * 2, numCores * 2, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
        imageSaverExecutor.execute(imageSaverRunnable)
    }
    private fun stopImageSaver() { imageSaverExecutor.shutdownNow(); try { imageSaverExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }; imageQueue.clear() }
    private fun startBackgroundThread() { backgroundThread = HandlerThread("CameraBackground").also { it.start() }; backgroundHandler = Handler(backgroundThread.looper) }
    private fun stopBackgroundThread() { backgroundThread.quitSafely(); try { backgroundThread.join() } catch (_: InterruptedException) { } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionsTextView.visibility = View.GONE
            if (textureView.isAvailable) rexrayCameraManager.openCamera(textureView.width, textureView.height, cameraStateCallback)
        } else {
            permissionsTextView.visibility = View.VISIBLE
        }
    }

    private fun updateCaptureCount(count: Int) { runOnUiThread { captureCountTextView.text = getString(R.string.capture_count_format, count) } }

    private fun updateSettingsDisplay() {
        val shutterSpeedString = "1/${1_000_000_000L / shutterSpeed}"
        val settingsText = "ISO: $iso, S: $shutterSpeedString, FPS: $captureRate, Limit: $captureLimit"
        settingsDisplayTextView.text = settingsText
    }

    class ClientStatusAdapter(context: Context, dataSource: MutableList<NetworkService.ClientStatus>) : ArrayAdapter<NetworkService.ClientStatus>(context, R.layout.client_status_item, dataSource) {
        @SuppressLint("ViewHolder", "SetTextI18n")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(R.layout.client_status_item, parent, false)
            val addressTextView = view.findViewById<TextView>(R.id.clientAddressTextView)
            val statusTextView = view.findViewById<TextView>(R.id.clientStatusTextView)
            val status = getItem(position)
            addressTextView.text = "${status?.cameraName} (${status?.socket?.inetAddress?.hostAddress})"
            val armedStatus = if(status?.isArmed == true) "Armed" else "Disarmed"
            statusTextView.text = "Images: ${status?.imageCount}, Bat: ${status?.batteryLevel}%, Space: ${status?.storageSpace}MB, $armedStatus"
            return view
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }
}
