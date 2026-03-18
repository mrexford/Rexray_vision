package com.example.rexray_vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class TestingActivity : AppCompatActivity(), DualCameraManager.DualCameraListener {

    private val TAG = "TestingActivity"
    private val PERMISSION_REQUEST_CODE = 1001

    private lateinit var lensScanner: LensScanner
    private lateinit var lensInfoText: TextView
    private lateinit var testLogText: TextView
    private lateinit var mainCameraSpinner: Spinner
    private lateinit var secondaryCameraSpinner: Spinner
    private lateinit var lastFramePreview: ImageView
    private lateinit var captureProgressBar: ProgressBar
    
    private var detectedLenses: List<LensSpecs> = emptyList()
    
    private lateinit var dualCameraManager: DualCameraManager
    private lateinit var storageManager: InternalStorageManager
    private val timeSyncEngine = TimeSyncEngine() 
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_testing)

        lensScanner = LensScanner(this)
        storageManager = InternalStorageManager(this)
        
        lensInfoText = findViewById(R.id.lensInfoText)
        testLogText = findViewById(R.id.testLogText)
        mainCameraSpinner = findViewById(R.id.mainCameraSpinner)
        secondaryCameraSpinner = findViewById(R.id.sceneCameraSpinner)
        lastFramePreview = findViewById(R.id.lastFramePreview)
        captureProgressBar = findViewById(R.id.captureProgressBar)

        findViewById<Button>(R.id.returnHomeButton).setOnClickListener {
            finish()
        }

        startBackgroundThread()
        dualCameraManager = DualCameraManager(this, backgroundHandler!!, storageManager, timeSyncEngine, this)

        findViewById<Button>(R.id.scanLensesButton).setOnClickListener {
            checkPermissionsAndScan()
        }

        findViewById<Button>(R.id.saveMappingButton).setOnClickListener {
            saveMapping()
        }

        findViewById<Button>(R.id.testBurstButton).setOnClickListener {
            startTestBurst()
        }

        findViewById<Button>(R.id.stopBurstButton).setOnClickListener {
            stopTestBurst()
        }
        
        checkPermissionsAndScan()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("TestingBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread error", e)
        }
    }

    private fun checkPermissionsAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
        } else {
            scanLenses()
        }
    }

    private fun scanLenses() {
        detectedLenses = lensScanner.enumeratePhysicalCameras()
        logToUi("Scanning complete. Found ${detectedLenses.size} sensors.")
        
        lensInfoText.text = detectedLenses.joinToString("\n\n") { lens ->
            lens.label
        }
        populateSpinners()
    }

    private fun populateSpinners() {
        val lensLabels = detectedLenses.map { it.label }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lensLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mainCameraSpinner.adapter = adapter

        val secondaryLabels = mutableListOf("None (Disabled)")
        secondaryLabels.addAll(lensLabels)
        val secAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, secondaryLabels)
        secAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        secondaryCameraSpinner.adapter = secAdapter
        
        val prefs = getSharedPreferences("HardwareMapping", Context.MODE_PRIVATE)
        val savedMainOpen = prefs.getString("main_open_id", null)
        val savedMainPhys = prefs.getString("main_phys_id", null)
        val savedSecOpen = prefs.getString("secondary_open_id", null)
        val savedSecPhys = prefs.getString("secondary_phys_id", null)
        
        savedMainOpen?.let { openId ->
            val idx = detectedLenses.indexOfFirst { it.openId == openId && it.physicalId == savedMainPhys }
            if (idx != -1) mainCameraSpinner.setSelection(idx)
        }
        savedSecOpen?.let { openId ->
            val idx = detectedLenses.indexOfFirst { it.openId == openId && it.physicalId == savedSecPhys }
            if (idx != -1) secondaryCameraSpinner.setSelection(idx + 1)
        }
    }

    private fun saveMapping() {
        val mainLens = detectedLenses.getOrNull(mainCameraSpinner.selectedItemPosition)
        val secIndex = secondaryCameraSpinner.selectedItemPosition
        val secLens = if (secIndex > 0) detectedLenses.getOrNull(secIndex - 1) else null
        
        if (mainLens == null) return

        getSharedPreferences("HardwareMapping", Context.MODE_PRIVATE).edit().apply {
            putString("main_open_id", mainLens.openId)
            putString("main_phys_id", mainLens.physicalId)
            putString("secondary_open_id", secLens?.openId)
            putString("secondary_phys_id", secLens?.physicalId)
            apply()
        }
        
        logToUi("Mapping Saved:\nMain -> ${mainLens.openId}:${mainLens.physicalId ?: "None"}\nSecondary -> ${secLens?.openId ?: "None"}:${secLens?.physicalId ?: "None"}")
        Toast.makeText(this, "Hardware mapping saved", Toast.LENGTH_SHORT).show()
    }

    private fun startTestBurst() {
        val prefs = getSharedPreferences("HardwareMapping", Context.MODE_PRIVATE)
        val mainOpenId = prefs.getString("main_open_id", null)
        val mainPhysId = prefs.getString("main_phys_id", null)
        val secOpenId = prefs.getString("secondary_open_id", null)
        val secPhysId = prefs.getString("secondary_phys_id", null)
        
        if (mainOpenId == null) {
            logToUi("Error: No mapping saved. Press 'Save Mapping' first.")
            return
        }
        
        val targets = mutableListOf(Pair(mainOpenId, mainPhysId))
        if (secOpenId != null) {
            targets.add(Pair(secOpenId, secPhysId))
        }
        
        logToUi("Initializing Hardware: ${targets.joinToString { "${it.first}:${it.second ?: "None"}" }}")
        captureProgressBar.progress = 0
        
        backgroundHandler?.post {
            try {
                dualCameraManager.openAndSetupBurst(targets)
            } catch (e: Exception) {
                runOnUiThread { logToUi("Initialization Failure: ${e.message}") }
            }
        }
    }

    private fun stopTestBurst() {
        logToUi("Stopping hardware test...")
        backgroundHandler?.post {
            dualCameraManager.stopBurst()
            dualCameraManager.closeAll()
            runOnUiThread { logToUi("Test Hardware Released.") }
        }
    }

    override fun onImageCaptured(data: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        runOnUiThread {
            lastFramePreview.setImageBitmap(bitmap)
            captureProgressBar.incrementProgressBy(1)
        }
    }

    override fun onCaptureLimitReached() {
        runOnUiThread {
            logToUi("Capture limit reached. Stopping...")
            stopTestBurst()
        }
    }

    override fun onSessionConfigured(logicalId: String) {
        runOnUiThread {
            logToUi("Session Ready for $logicalId. Starting burst...")
        }
        // Small delay to ensure all sessions are ready if dual
        backgroundHandler?.postDelayed({
            dualCameraManager.startBurst(30)
        }, 500)
    }

    private fun logToUi(msg: String) {
        testLogText.append("\n> $msg")
        Log.d(TAG, msg)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanLenses()
        } else {
            Toast.makeText(this, "Camera permission required for testing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dualCameraManager.closeAll()
        stopBackgroundThread()
    }
}
