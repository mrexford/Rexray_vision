package com.example.rexray_vision

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.SurfaceHolder
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * AnchorEngine handles ARCore spatial mapping and real-time visualization.
 * Robust against buffer overflows and viewport geometry race conditions.
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

    // Point Cloud Management
    enum class CloudMode { STREAMING, ACCUMULATING, REVIEW }
    private var currentMode = CloudMode.STREAMING
    
    private var pointBuffer: FloatBuffer? = null
    private val maxPoints = 150000 
    private var currentPointCount = 0

    fun onSurfaceChanged(width: Int, height: Int, rotation: Int = 0) {
        viewportWidth = width
        viewportHeight = height
        displayRotation = rotation
        // Set geometry on session if it exists
        arSession?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
        Log.d(TAG, "Dimensions cached: $width x $height, rot: $rotation")
    }

    fun initializeSession(surfaceHolder: SurfaceHolder) {
        try {
            if (arSession != null) return
            
            initEGL(surfaceHolder)
            renderer = PointRenderer(context)
            renderer?.init()
            
            arSession = Session(context)
            val config = Config(arSession)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            
            if (arSession?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            
            arSession?.configure(config)
            renderer?.getTextureId()?.let { arSession?.setCameraTextureName(it) }
            
            // Ensure geometry is set before resume
            if (viewportWidth > 0 && viewportHeight > 0) {
                arSession?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            }
            
            arSession?.resume()
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
            
            pointBuffer = ByteBuffer.allocateDirect(maxPoints * 4 * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer()
            }
            
            Log.d(TAG, "ARCore Session initialized and resumed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ARCore session", e)
        }
    }

    private fun initEGL(surfaceHolder: SurfaceHolder) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
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

        val contextAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
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
            currentPointCount = 0
            pointBuffer?.clear() 
            pointBuffer?.limit(0)
        }
    }

    fun getPointCount(): Int = currentPointCount

    fun updateAndRender(): String {
        val session = arSession ?: return "NOT_INITIALIZED"
        if (viewportWidth <= 0 || viewportHeight <= 0) return "WAITING_GEOMETRY"

        return try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            
            // Re-assert geometry to fix ARCore internal width:0 warnings
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val frame = session.update()
            val trackingState = frame.camera.trackingState
            
            // Pass the whole frame for proper UV transformation
            renderer?.drawCamera(frame)

            var status = trackingState.name

            if (trackingState == TrackingState.TRACKING) {
                val pointCloud = frame.acquirePointCloud()
                val points = pointCloud.points
                val incomingCount = points.remaining() / 4
                
                if (incomingCount < 10 && currentMode != CloudMode.REVIEW) status = "LOW_FEATURES"

                when (currentMode) {
                    CloudMode.STREAMING -> {
                        pointBuffer?.clear()
                        val toPut = minOf(incomingCount, maxPoints)
                        points.limit(toPut * 4)
                        pointBuffer?.put(points)
                        pointBuffer?.flip()
                    }
                    CloudMode.ACCUMULATING -> {
                        accumulatePoints(points)
                    }
                    CloudMode.REVIEW -> {}
                }
                
                val mvp = FloatArray(16)
                val proj = FloatArray(16)
                val view = FloatArray(16)
                frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
                frame.camera.getViewMatrix(view, 0)
                android.opengl.Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

                val drawLimit = pointBuffer?.limit() ?: 0
                if (drawLimit > 0) {
                    renderer?.drawPoints(pointBuffer!!, mvp, 40.0f)
                }
                pointCloud.release()
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            status
        } catch (e: Exception) {
            Log.e(TAG, "Render Error", e)
            "ERROR: ${e.javaClass.simpleName}"
        }
    }

    private fun accumulatePoints(incoming: FloatBuffer) {
        val incomingCount = incoming.remaining() / 4
        pointBuffer?.let { buf ->
            buf.limit(buf.capacity()) // Reset limit to capacity for writing
            val remainingSpace = maxPoints - currentPointCount
            if (remainingSpace > 0) {
                val toAdd = minOf(incomingCount, remainingSpace)
                buf.position(currentPointCount * 4)
                
                // Use a duplicate to avoid modifying incoming buffer state
                val dup = incoming.duplicate()
                dup.limit(dup.position() + toAdd * 4)
                buf.put(dup)
                
                currentPointCount += toAdd
                buf.limit(currentPointCount * 4)
            }
        }
    }

    fun clearBuffer() {
        currentPointCount = 0
        pointBuffer?.clear()
        pointBuffer?.limit(0)
    }

    fun closeSession() {
        try {
            arSession?.pause()
            arSession?.close()
            arSession = null
            anchors.clear()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Exception) { Log.e(TAG, "Cleanup Error", e) }
    }

    fun getTrackingStatus(): String = arSession?.update()?.camera?.trackingState?.name ?: "OFF"

    fun exportPointCloudToPly(projectName: String): File? {
        val file = File(context.filesDir, "${projectName}_cloud.ply")
        try {
            FileOutputStream(file).use { fos ->
                fos.write("ply\nformat ascii 1.0\nelement vertex $currentPointCount\nproperty float x\nproperty float y\nproperty float z\nproperty float confidence\nend_header\n".toByteArray())
                val buf = pointBuffer?.duplicate() ?: return null
                buf.position(0)
                for (i in 0 until currentPointCount) {
                    fos.write("${buf.get()} ${buf.get()} ${buf.get()} ${buf.get()}\n".toByteArray())
                }
            }
            return file
        } catch (e: Exception) { return null }
    }
}
