package com.example.rexray_vision

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class ClientControlsFragment : Fragment() {

    interface ClientControlsListener {
        fun onLeaveServer()
    }

    private var listener: ClientControlsListener? = null

    private lateinit var leaveServerButton: Button
    private lateinit var projectNameTextView: TextView
    private lateinit var cameraNameTextView: TextView
    private lateinit var captureCounter: TextView
    private lateinit var settingsDisplayTextView: TextView

    // MIGRATION UI
    private lateinit var migrationOverlay: ConstraintLayout
    private lateinit var migrationStatusTextView: TextView
    private lateinit var migrationProgressBar: ProgressBar
    private lateinit var migrationProgressTextView: TextView

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ClientControlsListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement ClientControlsListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_client_controls, container, false)

        leaveServerButton = view.findViewById(R.id.leaveServerButton)
        projectNameTextView = view.findViewById(R.id.projectNameTextView)
        cameraNameTextView = view.findViewById(R.id.cameraNameTextView)
        captureCounter = view.findViewById(R.id.captureCounter)
        settingsDisplayTextView = view.findViewById(R.id.settingsDisplayTextView)

        migrationOverlay = view.findViewById(R.id.migrationOverlay)
        migrationStatusTextView = view.findViewById(R.id.migrationStatusTextView)
        migrationProgressBar = view.findViewById(R.id.migrationProgressBar)
        migrationProgressTextView = view.findViewById(R.id.migrationProgressTextView)

        leaveServerButton.setOnClickListener { listener?.onLeaveServer() }

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        return view
    }

    fun updateUi() {
        val activity = activity as? CaptureActivity
        activity?.let {
            projectNameTextView.text = getString(R.string.project_name_client_format, it.getProjectName())
            cameraNameTextView.text = getString(R.string.camera_name_client_format, it.getCameraName())

            val shutterInv = 1_000_000_000.0 / it.getShutterSpeed()
            val shutterSpeedString = "1/${shutterInv.toLong()}"
            val settingsText = "ISO: ${it.getIso()}\nS: $shutterSpeedString\nFPS: ${it.getCaptureRate()}\nLimit: ${it.getCaptureLimit()}"
            settingsDisplayTextView.text = settingsText
        }
    }

    fun updateCaptureCount(count: Int) {
        activity?.runOnUiThread {
            if (::captureCounter.isInitialized) {
                captureCounter.text = count.toString()
                if (count > 0) captureCounter.visibility = View.VISIBLE
            }
        }
    }

    fun updateCaptureState(isCapturing: Boolean) {
        activity?.runOnUiThread {
            if (::captureCounter.isInitialized) {
                captureCounter.visibility = if (isCapturing || captureCounter.text.toString().toIntOrNull() ?: 0 > 0) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    fun updateDiskWriteProgress(pendingCount: Int) {
        activity?.runOnUiThread {
            if (!::migrationOverlay.isInitialized) return@runOnUiThread
            
            if (pendingCount > 0) {
                migrationOverlay.visibility = View.VISIBLE
                migrationStatusTextView.text = getString(R.string.migration_finalizing_writes)
                migrationProgressBar.isIndeterminate = true
                migrationProgressTextView.text = getString(R.string.migration_remaining_format, pendingCount)
            } else {
                if (migrationStatusTextView.text != getString(R.string.migration_moving_to_gallery)) {
                    migrationOverlay.visibility = View.GONE
                }
            }
        }
    }

    fun updateMigrationProgress(isMigrating: Boolean, progress: Int) {
        activity?.runOnUiThread {
            if (!::migrationOverlay.isInitialized) return@runOnUiThread

            if (isMigrating) {
                migrationOverlay.visibility = View.VISIBLE
                migrationStatusTextView.text = getString(R.string.migration_moving_to_gallery)
                migrationProgressBar.isIndeterminate = false
                migrationProgressBar.progress = progress
                migrationProgressTextView.text = getString(R.string.migration_percentage_format, progress)
            } else {
                migrationOverlay.visibility = View.GONE
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
