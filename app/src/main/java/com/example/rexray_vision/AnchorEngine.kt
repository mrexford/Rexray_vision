package com.example.rexray_vision

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.File
import java.io.FileOutputStream

/**
 * AnchorEngine handles ARCore spatial mapping.
 * To avoid AR_ERROR_MISSING_GL_CONTEXT, we maintain a minimal offscreen EGL context.
 */
class AnchorEngine(private val context: Context) {
    private val TAG = "AnchorEngine"
    private var arSession: Session? = null
    private val anchors = mutableListOf<Anchor>()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var dummyTextureId: Int = -1

    fun initializeSession() {
        try {
            if (arSession != null) return
            
            initEGL()
            
            arSession = Session(context)
            val config = Config(arSession)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            
            // Enable depth if supported for better mapping
            if (arSession?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            
            arSession?.configure(config)
            
            if (dummyTextureId != -1) {
                arSession?.setCameraTextureName(dummyTextureId)
            }
            
            arSession?.resume()
            Log.d(TAG, "ARCore Session initialized and resumed with dummy GL context.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ARCore session", e)
        }
    }

    private fun initEGL() {
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

        val contextAttr = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttr, 0)

        val surfaceAttr = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttr, 0)
        
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL context current")
            return
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        dummyTextureId = textures[0]
        Log.d(TAG, "Dummy GL Context and texture ($dummyTextureId) initialized.")
    }

    private fun ensureGlContext() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
    }

    fun getTrackingStatus(): String {
        val session = arSession ?: return "NOT_INITIALIZED"
        return try {
            ensureGlContext()
            val frame = session.update()
            frame.camera.trackingState.name
        } catch (e: Exception) {
            Log.e(TAG, "Error checking tracking status: ${e.message}")
            "OFFLINE"
        }
    }

    fun pauseSession() {
        try {
            arSession?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing ARCore session", e)
        }
    }

    fun closeSession() {
        try {
            arSession?.pause()
            arSession?.close()
            arSession = null
            anchors.clear()
            
            if (dummyTextureId != -1) {
                ensureGlContext()
                GLES20.glDeleteTextures(1, intArrayOf(dummyTextureId), 0)
                dummyTextureId = -1
            }
            
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            
            Log.d(TAG, "ARCore Session and GL resources closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ARCore session", e)
        }
    }

    fun createAnchorAtCurrentPose(): Anchor? {
        val session = arSession ?: return null
        return try {
            ensureGlContext()
            val frame = session.update()
            if (frame.camera.trackingState == TrackingState.TRACKING) {
                val anchor = session.createAnchor(frame.camera.pose)
                anchors.add(anchor)
                Log.d(TAG, "Anchor created at pose: ${frame.camera.pose}")
                anchor
            } else {
                Log.w(TAG, "Failed to create anchor: Camera is not tracking (State: ${frame.camera.trackingState})")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor", e)
            null
        }
    }

    fun exportPointCloudToPly(projectName: String): File? {
        val session = arSession ?: return null
        return try {
            ensureGlContext()
            val frame = session.update()
            val pointCloud = frame.acquirePointCloud()
            val file = File(context.filesDir, "${projectName}_cloud.ply")
            
            FileOutputStream(file).use { fos ->
                val points = pointCloud.points
                val numPoints = points.remaining() / 4
                
                val header = "ply\n" +
                        "format ascii 1.0\n" +
                        "element vertex $numPoints\n" +
                        "property float x\n" +
                        "property float y\n" +
                        "property float z\n" +
                        "property float confidence\n" +
                        "end_header\n"
                
                fos.write(header.toByteArray())
                
                while (points.hasRemaining()) {
                    val x = points.get()
                    val y = points.get()
                    val z = points.get()
                    val confidence = points.get()
                    fos.write("$x $y $z $confidence\n".toByteArray())
                }
            }
            pointCloud.release()
            Log.d(TAG, "Exported point cloud to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export point cloud", e)
            null
        }
    }
}
