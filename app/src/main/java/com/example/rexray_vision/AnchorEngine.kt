package com.example.rexray_vision

import android.content.Context
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * AnchorEngine handles ARCore spatial mapping and real-time visualization.
 * Optimized for High-Density Raw Depth acquisition and visualization.
 * Point Cloud accumulation has been removed in favor of direct 16-bit depth stream.
 */
class AnchorEngine(private val context: Context) {
    private val TAG = "AnchorEngine"
    private var arSession: Session? = null
    private val anchors = mutableListOf<Anchor>()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var renderer: PointRenderer? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayRotation = 0

    // Capture Management
    enum class CloudMode { STREAMING, ACCUMULATING, REVIEW }
    private var currentMode = CloudMode.STREAMING
    
    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null

    // Warmup Tracking
    private var warmupStartTime: Long = 0
    private val WARMUP_DURATION_MS = 3000L

    // Depth Sampling
    private var frameCounter = 0
    private var samplingRatio = 2 // Default: 1 depth per 2 frames (15 FPS)

    // Dedicated Depth I/O
    private var depthIoThread: HandlerThread? = null
    private var depthIoHandler: Handler? = null
    private var outputDir: File? = null

    // Configuration Fallback
    private var consecutiveDepthErrors = 0
    private val MAX_DEPTH_ERRORS = 10
    private var useAutomaticDepthFallback = false // Strictly prioritizing RAW_DEPTH as requested

    fun onSurfaceChanged(width: Int, height: Int, rotation: Int = 0) {
        viewportWidth = width
        viewportHeight = height
        displayRotation = rotation
        arSession?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
    }

    fun setWarmupStart(time: Long) {
        Log.d(TAG, "Warmup window started at $time")
        warmupStartTime = time
    }

    fun isDepthWarmedUp(): Boolean {
        if (warmupStartTime == 0L) return false
        return (System.currentTimeMillis() - warmupStartTime) >= WARMUP_DURATION_MS
    }

    fun setSamplingRatio(ratio: Int) {
        this.samplingRatio = ratio
        Log.d(TAG, "Sampling ratio set to 1 depth per $ratio frames")
    }

    fun initializeSession(surfaceHolder: SurfaceHolder) {
        try {
            if (arSession != null) return
            
            initEGL(surfaceHolder)
            renderer = PointRenderer(context)
            renderer?.init()
            
            arSession = Session(context)
            configureSession()
            
            // Set only the camera texture. Depth will be uploaded manually.
            renderer?.let { 
                arSession?.setCameraTextureName(it.getTextureId())
            }
            
            if (viewportWidth > 0 && viewportHeight > 0) {
                arSession?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            }
            
            arSession?.resume()
            
            // Start Depth I/O Thread
            depthIoThread = HandlerThread("DepthIO").also { it.start() }
            depthIoHandler = Handler(depthIoThread!!.looper)
            outputDir = File(context.filesDir, "burst_capture")
            if (!outputDir!!.exists()) outputDir!!.mkdirs()
            
            Log.d(TAG, "ARCore Session initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ARCore session", e)
        }
    }

    private fun configureSession() {
        val session = arSession ?: return
        val config = Config(session)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.focusMode = Config.FocusMode.AUTO
        
        // Strictly prefer RAW_DEPTH_ONLY to avoid smoothing
        val isRawSupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        val isAutoSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        
        Log.i(TAG, "Depth Support: RAW=$isRawSupported, AUTO=$isAutoSupported")

        if (isRawSupported) {
            config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
            Log.i(TAG, "Configuring depth: RAW_DEPTH_ONLY (Unsmoothed)")
        } else if (isAutoSupported && useAutomaticDepthFallback) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            Log.i(TAG, "Configuring depth: AUTOMATIC (Fallback, Smoothed)")
        } else {
            config.depthMode = Config.DepthMode.DISABLED
            Log.e(TAG, "Requested depth mode not supported on this device")
        }
        
        session.configure(config)
    }

    private fun initEGL(surfaceHolder: SurfaceHolder) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040, // ES3 bit
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0]

        // Request GLES 3.0 context for GL_RG8 support
        val contextAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttr, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surfaceHolder, null, 0)
        
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL context current")
        }
    }

    fun setMode(mode: CloudMode) {
        Log.d(TAG, "Mode Change -> $mode")
        currentMode = mode
        if (mode == CloudMode.ACCUMULATING) {
            frameCounter = 0
            consecutiveDepthErrors = 0
        }
    }

    fun updateAndRender(): String {
        val session = arSession ?: return "NOT_INITIALIZED"
        if (viewportWidth <= 0 || viewportHeight <= 0) return "WAITING_GEOMETRY"

        return try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            
            val frame = session.update()
            val trackingState = frame.camera.trackingState
            val failureReason = frame.camera.trackingFailureReason
            
            if (trackingState != lastTrackingState || failureReason != lastFailureReason) {
                lastTrackingState = trackingState
                lastFailureReason = failureReason
            }
            
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Render standard camera background
            renderer?.drawCamera(frame)

            var status = trackingState.name

            if (trackingState == TrackingState.TRACKING) {
                // Manual depth acquisition and upload for visualization
                try {
                    val depthImage = if (session.config.depthMode == Config.DepthMode.AUTOMATIC) {
                        frame.acquireDepthImage16Bits()
                    } else {
                        frame.acquireRawDepthImage16Bits()
                    }
                    
                    // Acquire confidence for visualization filtering if in RAW mode
                    var confidenceImage: Image? = null
                    if (session.config.depthMode == Config.DepthMode.RAW_DEPTH_ONLY) {
                        try {
                            confidenceImage = frame.acquireRawDepthConfidenceImage()
                        } catch (e: NotYetAvailableException) { /* Not available yet */ }
                    }
                    
                    val depthBuffer = depthImage.planes[0].buffer
                    val confidenceBuffer = confidenceImage?.planes?.get(0)?.buffer
                    
                    renderer?.updateDepthTextures(
                        depthImage.width, depthImage.height, depthBuffer,
                        confidenceBuffer
                    )
                    renderer?.drawDepth(frame)
                    
                    if (currentMode == CloudMode.ACCUMULATING && frameCounter % samplingRatio == 0) {
                        // Ensure we have confidence if in RAW mode for saving
                        val confToSave = if (session.config.depthMode == Config.DepthMode.RAW_DEPTH_ONLY) {
                            confidenceImage ?: frame.acquireRawDepthConfidenceImage()
                        } else {
                            null
                        }
                        saveDepthFrameAsync(depthImage, confToSave, frame.camera.displayOrientedPose, frame.timestamp)
                    } else {
                        depthImage.close()
                        confidenceImage?.close()
                    }
                    consecutiveDepthErrors = 0 
                } catch (e: NotYetAvailableException) {
                    // Normal hardware lag
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acquire depth image: ${e.message}")
                    consecutiveDepthErrors++
                    if (consecutiveDepthErrors >= MAX_DEPTH_ERRORS && !useAutomaticDepthFallback) {
                        Log.e(TAG, "Persistent depth errors. Forcing AUTOMATIC fallback.")
                        useAutomaticDepthFallback = true
                        configureSession()
                    }
                }
                
                if (currentMode == CloudMode.ACCUMULATING) {
                    frameCounter++
                }
            } else {
                status = "PAUSED: $failureReason"
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            status
        } catch (e: Exception) {
            Log.e(TAG, "Render Error", e)
            "ERROR: ${e.javaClass.simpleName}"
        }
    }

    private fun saveDepthFrameAsync(depthImage: Image, confidenceImage: Image?, pose: com.google.ar.core.Pose, timestamp: Long) {
        val depthBuffer = depthImage.planes[0].buffer
        val depthClone = ByteBuffer.allocateDirect(depthBuffer.remaining()).put(depthBuffer)
        depthClone.flip()

        var confidenceClone: ByteBuffer? = null
        if (confidenceImage != null) {
            val confBuffer = confidenceImage.planes[0].buffer
            confidenceClone = ByteBuffer.allocateDirect(confBuffer.remaining()).put(confBuffer)
            confidenceClone.flip()
            confidenceImage.close()
        }
        depthImage.close()

        depthIoHandler?.post {
            try {
                // Apply confidence filter to depth buffer to "remove" low-accuracy points
                // 60% threshold = 153
                if (confidenceClone != null) {
                    val pixelCount = confidenceClone.remaining()
                    for (i in 0 until pixelCount) {
                        val conf = confidenceClone.get(i).toInt() and 0xFF
                        if (conf < 153) {
                            // Zero out the 16-bit depth value (2 bytes per pixel)
                            depthClone.put(i * 2, 0)
                            depthClone.put(i * 2 + 1, 0)
                        }
                    }
                    depthClone.rewind()
                }

                val depthFile = File(outputDir, "depth_${timestamp}.raw")
                val poseFile = File(outputDir, "pose_${timestamp}.txt")

                FileOutputStream(depthFile).channel.use { it.write(depthClone) }
                
                if (confidenceClone != null) {
                    val confFile = File(outputDir, "conf_${timestamp}.raw")
                    confidenceClone.rewind()
                    FileOutputStream(confFile).channel.use { it.write(confidenceClone) }
                }
                
                FileOutputStream(poseFile).use { fos ->
                    val poseData = "timestamp: $timestamp\n" +
                                   "position: ${pose.tx()}, ${pose.ty()}, ${pose.tz()}\n" +
                                   "rotation: ${pose.qx()}, ${pose.qy()}, ${pose.qz()}, ${pose.qw()}\n"
                    fos.write(poseData.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Depth IO Error", e)
            }
        }
    }

    fun closeSession() {
        try {
            arSession?.pause()
            arSession?.close()
            arSession = null
            anchors.clear()
            
            depthIoThread?.quitSafely()
            depthIoThread = null
            depthIoHandler = null

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglDestroySurfaceSafely()
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Exception) { Log.e(TAG, "Cleanup Error", e) }
    }

    private fun eglDestroySurfaceSafely() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun getTrackingStatus(): String = arSession?.update()?.camera?.trackingState?.name ?: "OFF"
}
