package com.example.rexray_vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.* 
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.Group
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var textureView: TextureView
    private lateinit var captureButton: Button
    private lateinit var newProjectButton: Button
    private lateinit var projectNameTextView: TextView
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

    // Networking UI
    private lateinit var roleRadioGroup: RadioGroup
    private lateinit var primaryRadioButton: RadioButton
    private lateinit var clientRadioButton: RadioButton
    private lateinit var discoverButton: Button
    private lateinit var serviceListView: ListView
    private lateinit var clientListView: ListView
    private lateinit var connectedClientsHeader: TextView
    private lateinit var primaryControls: Group

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var rawImageReader: ImageReader
    private lateinit var analysisImageReader: ImageReader
    @Volatile private var captureResult: TotalCaptureResult? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private val imageQueue = LinkedBlockingQueue<Image>()
    private lateinit var imageSaverExecutor: ThreadPoolExecutor

    private var isCapturing = false
    private lateinit var currentProjectName: String
    private lateinit var cameraName: String

    private var iso = 100
    private var shutterSpeed = 3333333L // 1/300s
    private var captureRate = 10
    private var captureLimit = 20
    private var isAnalyzing = false
    private var isArmed = false
    private val capturedImageCount = AtomicInteger(0)
    private lateinit var previewSize: Size
    private var exposureAnalysisStrategy: ExposureAnalysisStrategy = HistogramEttrAnalysisStrategy()
    private var analysisPasses = 0
    private val maxAnalysisPasses = 6
    private val targetBrightness = 240

    private lateinit var networkManager: NetworkManager
    private val discoveredServices = mutableListOf<NsdServiceInfo>()
    private lateinit var serviceListAdapter: ArrayAdapter<String>
    private val clientStatusMap = ConcurrentHashMap<String, ClientStatus>()
    private lateinit var clientStatusAdapter: ClientStatusAdapter

    data class ClientStatus(val socket: Socket, var imageCount: Int, var batteryLevel: Int, var storageSpace: Long, var isArmed: Boolean = false)

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val adjectives = arrayOf("Fuzzy", "Sleek", "Dapper", "Cosmic", "Vivid", "Majestic", "Grumpy", "Quirky", "Lunar", "Solar")
    private val nouns = arrayOf("Wombat", "Spoon", "Waffle", "Robot", "Panda", "Dragon", "Cactus", "Nebula", "Gizmo", "Gasket")

    private val imageSaverRunnable = Runnable {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val image = imageQueue.take()
                if (cameraDevice == null) {
                    image.close()
                    continue
                }
                val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
                captureResult?.let { result ->
                    val dngCreator = DngCreator(characteristics, result)
                    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        display?.rotation
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                    dngCreator.setOrientation(getExifOrientation(rotation!!, sensorOrientation))
                    saveImageWithMediaStore(dngCreator, image)
                }
                image.close()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "ImageSaver: Error saving image", e)
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
            cameraDevice = camera
            if (cameraDevice != null) {
                runOnUiThread { 
                    permissionsTextView.visibility = View.GONE
                    setupSettingsControls()
                }
                createCameraPreviewSession()
            }
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureButton)
        newProjectButton = findViewById(R.id.newProjectButton)
        projectNameTextView = findViewById(R.id.projectNameTextView)
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

        // Networking UI
        roleRadioGroup = findViewById(R.id.roleRadioGroup)
        primaryRadioButton = findViewById(R.id.primaryRadioButton)
        clientRadioButton = findViewById(R.id.clientRadioButton)
        discoverButton = findViewById(R.id.discoverButton)
        serviceListView = findViewById(R.id.serviceListView)
        clientListView = findViewById(R.id.clientListView)
        connectedClientsHeader = findViewById(R.id.connectedClientsHeader)
        primaryControls = findViewById(R.id.primaryControls)

        setupNewProject()
        generateCameraName()
        updateCaptureCount(0)

        newProjectButton.setOnClickListener { if (!isCapturing) setupNewProject() }
        captureButton.setOnClickListener { if (isCapturing) stopBurstCapture() else startBurstCapture() }
        analyzeSceneButton.setOnClickListener { if (cameraDevice != null) analyzeScene() }
        armButton.setOnClickListener { armCapture() }

        setupNetworking()
        updateArmingState() // Set initial button states
    }

    override fun onStart() {
        super.onStart()
        startBackgroundThread()
        startImageSaver()
    }

    override fun onStop() {
        super.onStop()
        if (isCapturing) stopBurstCapture()
        networkManager.unregisterService()
        networkManager.stopDiscovery()
        closeCamera()
        stopImageSaver()
        stopBackgroundThread()
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) checkPermissionsAndOpenCamera(textureView.width, textureView.height)
        else textureView.surfaceTextureListener = textureListener
    }

    override fun onPause() {
        super.onPause()
    }

    private fun setupNetworking() {
        networkManager = NetworkManager(this)
        serviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        serviceListView.adapter = serviceListAdapter
        clientStatusAdapter = ClientStatusAdapter(this, mutableListOf())
        clientListView.adapter = clientStatusAdapter

        roleRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.primaryRadioButton) {
                primaryControls.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                serviceListView.visibility = View.GONE
                connectedClientsHeader.visibility = View.VISIBLE
                clientListView.visibility = View.VISIBLE
                networkManager.registerService(8080)
                networkManager.stopDiscovery()
            } else {
                primaryControls.visibility = View.GONE
                discoverButton.visibility = View.VISIBLE
                serviceListView.visibility = View.VISIBLE
                connectedClientsHeader.visibility = View.GONE
                clientListView.visibility = View.GONE
                networkManager.unregisterService()
            }
            updateArmingState()
        }

        discoverButton.setOnClickListener {
            discoveredServices.clear()
            serviceListAdapter.clear()
            serviceListAdapter.notifyDataSetChanged()
            networkManager.discoverServices()
        }

        serviceListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val service = discoveredServices[position]
            networkManager.resolveService(service)
        }

        networkManager.onServiceFound = { serviceInfo: NsdServiceInfo ->
            if (!discoveredServices.any { it.serviceName == serviceInfo.serviceName }) {
                discoveredServices.add(serviceInfo)
                runOnUiThread {
                    serviceListAdapter.add(serviceInfo.serviceName)
                    serviceListAdapter.notifyDataSetChanged()
                }
            }
        }

        networkManager.onServiceLost = { serviceInfo: NsdServiceInfo ->
            discoveredServices.removeAll { it.serviceName == serviceInfo.serviceName }
            runOnUiThread {
                serviceListAdapter.remove(serviceInfo.serviceName)
                serviceListAdapter.notifyDataSetChanged()
            }
        }

        networkManager.onServiceResolved = { serviceInfo: NsdServiceInfo ->
            if (serviceInfo.hostAddresses.isNotEmpty()) {
                val host = serviceInfo.hostAddresses[0]
                val port = serviceInfo.port
                runOnUiThread {
                    Toast.makeText(this, "Connected to ${serviceInfo.serviceName} at ${host.hostAddress}:$port", Toast.LENGTH_SHORT).show()
                }
            }
        }

        networkManager.onClientConnected = { client ->
            val address = client.inetAddress.hostAddress
            val status = ClientStatus(client, 0, getBatteryLevel(), getAvailableStorage(), false)
            clientStatusMap[address] = status
            runOnUiThread {
                clientStatusAdapter.add(status)
                clientStatusAdapter.notifyDataSetChanged()
                updateArmingState() // A new client joined, re-evaluate arming state
            }
            // Send current params to new client
            val params = NetworkManager.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName)
            networkManager.sendMessage(client, params)

            if(isArmed) {
                networkManager.sendMessage(client, NetworkManager.Message.ArmCapture)
            }
        }

        networkManager.onClientDisconnected = { client ->
            val address = client.inetAddress.hostAddress
            val status = clientStatusMap.remove(address)
            runOnUiThread {
                if(status != null) {
                    clientStatusAdapter.remove(status)
                    clientStatusAdapter.notifyDataSetChanged()
                }
                updateArmingState() // A client left, re-evaluate arming state
            }
        }

        networkManager.onMessageReceived = { socket, message ->
            runOnUiThread {
                val address = socket.inetAddress.hostAddress
                when (message) {
                    is NetworkManager.Message.SetParams -> {
                        shutterSpeed = message.shutterSpeed
                        iso = message.iso
                        captureLimit = message.captureCount
                        currentProjectName = message.projectName
                        updateUIFromNetwork()
                    }
                    is NetworkManager.Message.ArmCapture -> armCapture()
                    is NetworkManager.Message.StartCapture -> startBurstCapture()
                    is NetworkManager.Message.StatusUpdate -> {
                        val status = clientStatusMap[address]
                        if (status != null) {
                            status.imageCount = message.imageCount
                            status.batteryLevel = message.batteryLevel
                            status.storageSpace = message.storageSpace
                            status.isArmed = message.isArmed
                            clientStatusAdapter.notifyDataSetChanged()
                            updateArmingState()
                        }
                    }
                    is NetworkManager.Message.JoinGroup -> { /* Already handled by onClientConnected */ }
                }
            }
        }
        
        // Set initial state
        roleRadioGroup.check(R.id.primaryRadioButton)
    }

    private fun broadcastMessage(message: NetworkManager.Message) {
        clientStatusMap.values.forEach { networkManager.sendMessage(it.socket, message) }
    }

    private fun updateUIFromNetwork() {
        shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, 1_000_000_000L / shutterSpeed)
        isoValueTextView.text = iso.toString()
        captureLimitValueTextView.text = captureLimit.toString()
        projectNameTextView.text = getString(R.string.project_name_format, currentProjectName)
    }

    private fun armCapture() {
        if (primaryRadioButton.isChecked) {
            isArmed = true
            broadcastMessage(NetworkManager.Message.ArmCapture)
        } else { // Client
            isArmed = true
            val batteryLevel = getBatteryLevel()
            val storageSpace = getAvailableStorage()
            val status = NetworkManager.Message.StatusUpdate(capturedImageCount.get(), batteryLevel, storageSpace, isArmed)
            networkManager.sendMessageToPrimary(status)
        }
        updateArmingState()
    }

    private fun updateArmingState() {
        val allClientsArmed = clientStatusMap.values.all { it.isArmed }
        val isPrimary = primaryRadioButton.isChecked

        runOnUiThread {
            if (isPrimary) {
                val areClientsConnected = clientStatusMap.isNotEmpty()
                if (isArmed) {
                    when {
                        !areClientsConnected -> { // No clients, can arm and capture locally
                            armButton.text = "Armed"
                            armButton.isEnabled = false
                            captureButton.isEnabled = true
                        }
                        allClientsArmed -> { // All clients connected and armed
                            armButton.text = "Armed"
                            armButton.isEnabled = false
                            captureButton.isEnabled = true
                        }
                        else -> { // Clients are connected but not all are armed
                            armButton.text = "Syncing..."
                            armButton.isEnabled = false
                            captureButton.isEnabled = false
                        }
                    }
                } else { // Not armed
                    armButton.text = "Arm"
                    armButton.isEnabled = true // Always allow arming in primary mode
                    captureButton.isEnabled = false
                }
            } else { // Client
                armButton.text = if (isArmed) "Armed" else "Arm"
                armButton.isEnabled = !isArmed
                captureButton.isEnabled = false
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
    }

    private fun getAvailableStorage(): Long {
        return try {
            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Rexray_vision")
            if (!path.exists()) {
                path.mkdirs()
            }
            val stat = StatFs(path.absolutePath)
            val availableBytes = stat.availableBytes
            availableBytes / (1024 * 1024) // Return in MB
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage stats", e)
            -1L
        }
    }

    private fun checkPermissionsAndOpenCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsTextView.visibility = View.VISIBLE
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            permissionsTextView.visibility = View.GONE
            openCamera(width, height)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        val cameraId = cameraManager.cameraIdList.getOrNull(0) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        if (characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) != true) return

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: return
        previewSize = chooseOptimalPreviewSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, rawSize)
        rawImageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 30)
        analysisImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)

        rawImageReader.setOnImageAvailableListener({ reader ->
            val image: Image? = try { reader.acquireNextImage() } catch (e: IllegalStateException) { null }
            image?.let { img ->
                if (isCapturing && capturedImageCount.get() < captureLimit) {
                    if (!imageQueue.offer(img)) {
                         img.close()
                    }
                } else {
                    if (isCapturing) runOnUiThread { stopBurstCapture() }
                    img.close()
                }
            }
        }, backgroundHandler)

        analysisImageReader.setOnImageAvailableListener({ reader ->
            val image: Image? = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null }
            image?.let {
                val histogram = exposureAnalysisStrategy.getHistogram(it)
                runOnUiThread { histogramView.updateHistogram(histogram) }

                if(isAnalyzing){
                    val percentileValue = exposureAnalysisStrategy.analyze(it)
                    if (percentileValue != -1 && analysisPasses < maxAnalysisPasses) {
                        val requiredAdjustment = targetBrightness.toDouble() / percentileValue.toDouble()
                        val newIso = (iso * requiredAdjustment).toInt().coerceIn(isoSeekBar.min, isoSeekBar.max)
                        
                        if (newIso != iso) {
                            iso = newIso
                            runOnUiThread {
                                isoSeekBar.progress = iso
                                isoValueTextView.text = iso.toString()
                            }
                            analysisPasses++
                            analyzeScene() // Recursive call to re-evaluate
                        } else {
                            isAnalyzing = false // Coalesced
                            runOnUiThread { Toast.makeText(this@MainActivity, "Auto ISO: Coalesced at $iso", Toast.LENGTH_SHORT).show() }
                        }
                    } else if (analysisPasses >= maxAnalysisPasses) {
                        isAnalyzing = false
                        runOnUiThread { Toast.makeText(this@MainActivity, "Auto ISO: Settled at $iso", Toast.LENGTH_SHORT).show() }
                    } else {
                        isAnalyzing = false
                        runOnUiThread { Toast.makeText(this@MainActivity, "Auto ISO: Failed", Toast.LENGTH_SHORT).show() }
                    }
                }
                it.close()
            }
        }, backgroundHandler)

        try { 
            configureTransform(width, height)
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler) 
        }
        catch (e: CameraAccessException) { Log.e(TAG, "Failed to open camera", e) }
    }

    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraDevice?.close()
        } catch (e: Exception) { }
        cameraDevice = null
        cameraCaptureSession = null
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)
            previewRequestBuilder.addTarget(analysisImageReader.surface)

            cameraDevice?.createCaptureSession(listOf(previewSurface, rawImageReader.surface, analysisImageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSession = session
                    updatePreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }
    
    private fun updatePreview() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    private fun startBurstCapture() {
        if (primaryRadioButton.isChecked && clientStatusMap.isNotEmpty() && !clientStatusMap.values.all { it.isArmed }) {
            runOnUiThread { Toast.makeText(this, "Not all clients are armed", Toast.LENGTH_SHORT).show() }
            return // Don't start capture if not all clients are armed
        }

        if (primaryRadioButton.isChecked) {
            broadcastMessage(NetworkManager.Message.StartCapture)
        }
        capturedImageCount.set(0)
        updateCaptureCount(0)
        runOnUiThread { captureBorder.visibility = View.VISIBLE }

        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(rawImageReader.surface)
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
            runOnUiThread { captureButton.text = getString(R.string.stop) }
        } catch(e: CameraAccessException) { }
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
        } catch (e: CameraAccessException) { }
        runOnUiThread { captureButton.text = getString(R.string.capture) }
    }

    private fun saveImageWithMediaStore(dngCreator: DngCreator, image: Image) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "${currentProjectName}_${cameraName}_$timeStamp.dng"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rexray_vision")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                resolver.openOutputStream(it).use { outputStream -> dngCreator.writeImage(outputStream!!, image) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
            } catch (e: Exception) { resolver.delete(it, null, null) }
        } 
    }

    private fun setupNewProject(){
        currentProjectName = "${adjectives.random()}-${nouns.random()}"
        runOnUiThread { 
            projectNameTextView.text = getString(R.string.project_name_format, currentProjectName)
            updateArmingState()
        }
        if (primaryRadioButton.isChecked) {
            broadcastMessage(NetworkManager.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
        }
    }

    @SuppressLint("HardwareIds")
    private fun generateCameraName() {
        cameraName = "Cam-${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.takeLast(4) ?: "0000"}"
    }

    private fun setupSettingsControls() {
        if (cameraDevice == null) return
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 800)
        val exposureNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(200000L, 16666666L)

        // ISO
        isoSeekBar.max = isoRange.upper.coerceAtMost(800)
        isoSeekBar.min = isoRange.lower.coerceAtLeast(50)
        isoSeekBar.progress = iso
        isoValueTextView.text = iso.toString()

        isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                isoValueTextView.text = p.toString(); iso = p; updatePreview()
                if (fromUser && primaryRadioButton.isChecked) {
                    broadcastMessage(NetworkManager.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        
        // Shutter Speed
        val shutterMin = (1_000_000_000L / exposureNs.upper).toInt()
        val shutterMax = (1_000_000_000L / exposureNs.lower).toInt()
        shutterSpeedSeekBar.max = log10(shutterMax.toDouble()).toInt() * 100
        val shutterProgress = log10((1_000_000_000L / shutterSpeed).toDouble()).toInt() * 100
        shutterSpeedSeekBar.progress = shutterProgress
        shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, (1_000_000_000L / shutterSpeed).toInt())

        shutterSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = (10.0.pow(progress / 100.0)).toLong().coerceIn(shutterMin.toLong(), shutterMax.toLong())
                shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, value)
                shutterSpeed = 1_000_000_000L / value
                updatePreview()
                 if (fromUser && primaryRadioButton.isChecked) {
                     broadcastMessage(NetworkManager.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                 }
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
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (p>0) { 
                val newShutterMax = (1_000_000_000L / (1_000_000_000L / p)).toInt()
                if((1_000_000_000L / shutterSpeed) > newShutterMax) {
                    shutterSpeedSeekBar.progress = log10(newShutterMax.toDouble()).toInt() * 100
                }
                captureRateValueTextView.text = getString(R.string.capture_rate_format, p); captureRate = p 
            } }
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
                    if (fromUser && primaryRadioButton.isChecked) {
                        broadcastMessage(NetworkManager.Message.SetParams(shutterSpeed, iso, captureLimit, currentProjectName))
                    }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        analyzeSceneButton.setOnClickListener { if (cameraDevice != null) analyzeScene() }
    }
    
    private fun analyzeScene() {
        if (!isAnalyzing) {
            analysisPasses = 0
            isAnalyzing = true
        }

        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureBuilder.addTarget(analysisImageReader.surface)
            cameraCaptureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch(e: CameraAccessException) { }
    }
    
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == textureView || null == previewSize) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } ?: return

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

    private fun chooseOptimalPreviewSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, aspectRatio: Size): Size {
        val bigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width && it.width >= textureViewWidth && it.height >= textureViewHeight }
        if (bigEnough.isNotEmpty()) return bigEnough.minByOrNull { it.width * it.height }!!
        val notBigEnough = choices.filter { it.height == it.width * aspectRatio.height / aspectRatio.width }
        if (notBigEnough.isNotEmpty()) return notBigEnough.maxByOrNull { it.width * it.height }!!
        return choices[0]
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
    private fun stopImageSaver() { imageSaverExecutor.shutdownNow(); try { imageSaverExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { }; imageQueue.clear() }
    private fun startBackgroundThread() { backgroundThread = HandlerThread("CameraBackground").also { it.start() }; backgroundHandler = Handler(backgroundThread.looper) }
    private fun stopBackgroundThread() { backgroundThread.quitSafely(); try { backgroundThread.join() } catch (e: InterruptedException) { } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionsTextView.visibility = View.GONE
            if (textureView.isAvailable) openCamera(textureView.width, textureView.height)
        } else {
            permissionsTextView.visibility = View.VISIBLE
        }
    }
    
    private fun updateCaptureCount(count: Int) { runOnUiThread { captureCountTextView.text = getString(R.string.capture_count_format, count) } }

    inner class ClientStatusAdapter(context: Context, private val dataSource: MutableList<ClientStatus>) : ArrayAdapter<ClientStatus>(context, R.layout.client_status_item, dataSource) {
        @SuppressLint("ViewHolder")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(R.layout.client_status_item, parent, false)
            val addressTextView = view.findViewById<TextView>(R.id.clientAddressTextView)
            val statusTextView = view.findViewById<TextView>(R.id.clientStatusTextView)
            val status = getItem(position)
            addressTextView.text = status?.socket?.inetAddress?.hostAddress
            val armedStatus = if(status?.isArmed == true) "Armed" else "Disarmed"
            statusTextView.text = "Images: ${status?.imageCount}, Bat: ${status?.batteryLevel}%, Space: ${status?.storageSpace}MB, $armedStatus"
            return view
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }
}