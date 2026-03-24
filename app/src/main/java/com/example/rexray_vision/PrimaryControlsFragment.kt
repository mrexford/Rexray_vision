package com.example.rexray_vision

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class PrimaryControlsFragment : Fragment() {

    interface PrimaryControlsListener {
        fun onNewProject()
        fun onArmCapture(broadcast: Boolean)
        fun onDisarmCapture(broadcast: Boolean)
        fun onStartCapture()
        fun onStopCapture()
        fun onAnalyzeScene()
        fun onStopServer()
        fun onRegenerateCameraName()
        fun onSaveAndReset()
        fun onProjectNameChanged(name: String)
        fun setIso(value: Int)
        fun setShutterSpeed(value: Long)
        fun setCaptureRate(value: Int)
        fun setCaptureLimit(value: Int)
        fun setCaptureMode(value: NetworkService.CaptureMode)
        fun setFlashEnabled(value: Boolean)
        fun setFlashIntensity(value: Int)
        fun onCloseApp()
    }

    private var listener: PrimaryControlsListener? = null

    private lateinit var newProjectButton: Button
    private lateinit var armButton: Button
    private lateinit var captureButton: Button
    private lateinit var saveResetButton: Button
    private lateinit var analyzeSceneButton: Button
    private lateinit var stopServerButton: Button
    private lateinit var isoSeekBar: SeekBar
    private lateinit var shutterSpeedSeekBar: SeekBar
    private lateinit var captureRateSpinner: Spinner
    private lateinit var captureLimitSeekBar: SeekBar
    private lateinit var isoValueTextView: TextView
    private lateinit var shutterSpeedValueTextView: TextView
    private lateinit var captureLimitValueTextView: TextView
    private lateinit var projectNameTextView: TextView
    private lateinit var cameraNameTextView: TextView
    private lateinit var settingsDisplayTextView: TextView
    private lateinit var clientListView: ListView
    private lateinit var histogramView: HistogramView
    private lateinit var closeAppButton: Button
    private lateinit var autoIsoIndicator: TextView
    private lateinit var captureCounter: TextView

    // MIGRATION UI
    private lateinit var migrationOverlay: ConstraintLayout
    private lateinit var migrationStatusTextView: TextView
    private lateinit var migrationProgressBar: ProgressBar
    private lateinit var migrationProgressTextView: TextView

    private var isCapturing = false

    private val shutterSpeeds = (200..1200 step 50).map { 1_000_000_000L / it }.toTypedArray()
    private val fpsOptions = arrayOf(3, 6, 10, 15)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is PrimaryControlsListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement PrimaryControlsListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_primary_controls, container, false)

        newProjectButton = view.findViewById(R.id.newProjectButton)
        armButton = view.findViewById(R.id.armButton)
        captureButton = view.findViewById(R.id.captureButton)
        saveResetButton = view.findViewById(R.id.saveResetButton)
        analyzeSceneButton = view.findViewById(R.id.analyzeSceneButton)
        stopServerButton = view.findViewById(R.id.stopServerButton)
        isoSeekBar = view.findViewById(R.id.isoSeekBar)
        shutterSpeedSeekBar = view.findViewById(R.id.shutterSpeedSeekBar)
        captureRateSpinner = view.findViewById(R.id.captureRateSpinner)
        captureLimitSeekBar = view.findViewById(R.id.captureLimitSeekBar)
        isoValueTextView = view.findViewById(R.id.isoValueTextView)
        shutterSpeedValueTextView = view.findViewById(R.id.shutterSpeedValueTextView)
        captureLimitValueTextView = view.findViewById(R.id.captureLimitValueTextView)
        projectNameTextView = view.findViewById(R.id.projectNameTextView)
        cameraNameTextView = view.findViewById(R.id.cameraNameTextView)
        settingsDisplayTextView = view.findViewById(R.id.settingsDisplayTextView)
        clientListView = view.findViewById(R.id.clientListView)
        histogramView = view.findViewById(R.id.histogramView)
        closeAppButton = view.findViewById(R.id.closeAppButton)
        autoIsoIndicator = view.findViewById(R.id.autoIsoIndicator)
        captureCounter = view.findViewById(R.id.captureCounter)

        migrationOverlay = view.findViewById(R.id.migrationOverlay)
        migrationStatusTextView = view.findViewById(R.id.migrationStatusTextView)
        migrationProgressBar = view.findViewById(R.id.migrationProgressBar)
        migrationProgressTextView = view.findViewById(R.id.migrationProgressTextView)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        setupListeners()

        return view
    }

    private fun setupViews() {
        isoSeekBar.min = 50
        isoSeekBar.max = 1000
        shutterSpeedSeekBar.max = shutterSpeeds.size - 1
        
        captureLimitSeekBar.min = 1
        captureLimitSeekBar.max = 300
    }

    private fun setupListeners() {
        newProjectButton.setOnClickListener { listener?.onNewProject() }
        armButton.setOnClickListener {
            val isArmed = (activity as? CaptureActivity)?.getIsArmed() ?: false
            if (isArmed) {
                listener?.onDisarmCapture(true)
            } else {
                listener?.onArmCapture(true)
            }
        }
        captureButton.setOnClickListener {
            if (isCapturing) {
                listener?.onStopCapture()
            } else {
                listener?.onStartCapture()
            }
        }
        saveResetButton.setOnClickListener { listener?.onSaveAndReset() }
        analyzeSceneButton.setOnClickListener { listener?.onAnalyzeScene() }
        stopServerButton.setOnClickListener { listener?.onStopServer() }
        closeAppButton.setOnClickListener { listener?.onCloseApp() }

        isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isoValueTextView.text = getString(R.string.iso_value_format, progress)
                    listener?.setIso(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        shutterSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val inv = 1_000_000_000.0 / shutterSpeeds[progress]
                    shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, inv.toLong())
                    listener?.setShutterSpeed(shutterSpeeds[progress])
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        captureRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fps = fpsOptions[position]
                listener?.setCaptureRate(fps)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        captureLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    captureLimitValueTextView.text = getString(R.string.capture_limit_format, progress)
                    listener?.setCaptureLimit(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    fun updateDiskWriteProgress(pendingCount: Int) {
        activity?.runOnUiThread {
            if (pendingCount > 0) {
                migrationOverlay.visibility = View.VISIBLE
                migrationStatusTextView.text = getString(R.string.migration_finalizing_writes)
                migrationProgressBar.isIndeterminate = true
                migrationProgressTextView.text = getString(R.string.migration_remaining_format, pendingCount)
                
                armButton.isEnabled = false
                captureButton.isEnabled = false
            } else {
                if (migrationStatusTextView.text != getString(R.string.migration_moving_to_gallery)) {
                    migrationOverlay.visibility = View.GONE
                    updateArmingState((activity as? CaptureActivity)?.getIsArmed() ?: false)
                }
            }
        }
    }

    fun updateMigrationProgress(isMigrating: Boolean, progress: Int) {
        activity?.runOnUiThread {
            if (isMigrating) {
                migrationOverlay.visibility = View.VISIBLE
                migrationStatusTextView.text = getString(R.string.migration_moving_to_gallery)
                migrationProgressBar.isIndeterminate = false
                migrationProgressBar.progress = progress
                migrationProgressTextView.text = getString(R.string.migration_percentage_format, progress)
                
                armButton.isEnabled = false
                captureButton.isEnabled = false
            } else {
                migrationOverlay.visibility = View.GONE
                updateArmingState((activity as? CaptureActivity)?.getIsArmed() ?: false)
            }
        }
    }

    fun onCameraReady() {
        val activity = activity as? CaptureActivity
        activity?.let {
            updateUi()
            updateArmingState(it.getIsArmed())
        }
    }

    fun updateUi() {
        val activity = activity as? CaptureActivity
        activity?.let {
            isoValueTextView.text = getString(R.string.iso_value_format, it.getIso())
            isoSeekBar.progress = it.getIso()

            val shutterInv = 1_000_000_000.0 / it.getShutterSpeed()
            shutterSpeedValueTextView.text = getString(R.string.shutter_speed_value_format, shutterInv.toLong())
            val shutterSpeedIndex = shutterSpeeds.indexOfFirst { speed -> speed <= it.getShutterSpeed() }
            shutterSpeedSeekBar.progress = if (shutterSpeedIndex != -1) shutterSpeedIndex else 0

            val fpsIndex = fpsOptions.indexOf(it.getCaptureRate())
            if (fpsIndex != -1) captureRateSpinner.setSelection(fpsIndex)

            captureLimitValueTextView.text = getString(R.string.capture_limit_format, it.getCaptureLimit())
            captureLimitSeekBar.progress = it.getCaptureLimit()

            val currentMode = it.getCaptureMode()
            val shutterSpeedString = "1/${shutterInv.toLong()}"
            val settingsText = "ISO: ${it.getIso()}\nS: $shutterSpeedString\nFPS: ${it.getCaptureRate()}\nLimit: ${it.getCaptureLimit()}\nMode: $currentMode"
            settingsDisplayTextView.text = settingsText

            projectNameTextView.text = getString(R.string.project_name_prefix, it.getProjectName())
            cameraNameTextView.text = getString(R.string.camera_name_prefix, it.getCameraName())
            
            updateArmingState(it.getIsArmed())
        }
    }

    fun updateArmingState(isArmed: Boolean) {
        activity?.runOnUiThread {
            armButton.isEnabled = true
            armButton.text = if (isArmed) "Disarm" else "Arm"
            captureButton.isEnabled = isArmed
            captureButton.alpha = if(isArmed) 1.0f else 0.5f
            
            // New logic: disable settings seekbars when armed to prevent race conditions
            isoSeekBar.isEnabled = !isArmed
            shutterSpeedSeekBar.isEnabled = !isArmed
            captureRateSpinner.isEnabled = !isArmed
            captureLimitSeekBar.isEnabled = !isArmed
            newProjectButton.isEnabled = !isArmed
            analyzeSceneButton.isEnabled = !isArmed
        }
    }

    fun updateCaptureState(isCapturing: Boolean) {
        this.isCapturing = isCapturing
        activity?.runOnUiThread {
            captureButton.text = if (isCapturing) "Stop" else "Capture"
            captureCounter.visibility = if (isCapturing || captureCounter.text.toString().toIntOrNull() ?: 0 > 0) View.VISIBLE else View.INVISIBLE
            armButton.isEnabled = !isCapturing
        }
    }
    
    fun showReviewUI(visible: Boolean) {
        activity?.runOnUiThread {
            saveResetButton.visibility = if (visible) View.VISIBLE else View.GONE
            captureButton.isEnabled = !visible
            armButton.isEnabled = !visible
        }
    }

    fun updateAutoIsoState(isAnalyzing: Boolean) {
        if (isAnalyzing) {
            analyzeSceneButton.text = "Cancel"
            autoIsoIndicator.visibility = View.VISIBLE
            autoIsoIndicator.animate().alpha(0f).setDuration(500).withEndAction {
                autoIsoIndicator.animate().alpha(1f).setDuration(500).start()
            }.start()
        } else {
            analyzeSceneButton.text = "Auto-ISO"
            autoIsoIndicator.clearAnimation()
            autoIsoIndicator.visibility = View.GONE
        }
    }

    fun updateCaptureCount(count: Int) {
        activity?.runOnUiThread {
            captureCounter.text = count.toString()
            if (count > 0) captureCounter.visibility = View.VISIBLE
        }
    }

    fun setClientList(clients: List<String>) {
        activity?.runOnUiThread {
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, clients)
            clientListView.adapter = adapter
        }
    }

    fun updateHistogram(histogram: IntArray) {
        histogramView.updateHistogram(histogram)
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
