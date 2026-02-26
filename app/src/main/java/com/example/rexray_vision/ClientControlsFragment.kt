package com.example.rexray_vision

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
