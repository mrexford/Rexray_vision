package com.example.rexray_vision

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Handles rendering the ARCore camera feed and the accumulated point cloud.
 * Updated to fix inversion issues and improve UV transformation.
 */
class PointRenderer(context: Context) {
    private val TAG = "PointRenderer"

    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute float aConfidence;
        uniform mat4 uMVPMatrix;
        uniform float uPointSize;
        varying float vConfidence;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = uPointSize;
            vConfidence = aConfidence;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying float vConfidence;
        void main() {
            // Bright Yellow for high visibility
            gl_FragColor = vec4(1.0, 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private val cameraVertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val cameraFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private var program: Int = 0
    private var cameraProgram: Int = 0
    private var textureId: Int = -1

    // Quad for camera rendering (Triangle Strip order)
    private val quadCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    )
    
    // Initial UVs (Top-left origin, standard for many camera APIs)
    private val quadTexCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )
    
    private lateinit var quadBuffer: FloatBuffer
    private lateinit var quadTexBuffer: FloatBuffer
    private lateinit var transformedTexBuffer: FloatBuffer

    fun init() {
        program = createProgram(vertexShaderCode, fragmentShaderCode)
        cameraProgram = createProgram(cameraVertexShaderCode, cameraFragmentShaderCode)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        quadBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(quadCoords); position(0) }
        }
        quadTexBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(quadTexCoords); position(0) }
        }
        transformedTexBuffer = ByteBuffer.allocateDirect(8 * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer()
        }
        
        checkGlError("init")
    }

    fun getTextureId() = textureId

    fun drawCamera(frame: Frame) {
        if (cameraProgram == 0 || textureId == -1) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // ARCore transforms the UVs to handle rotation and aspect ratio correctly
        frame.transformDisplayUvCoords(quadTexBuffer, transformedTexBuffer)

        GLES20.glUseProgram(cameraProgram)
        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "vPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        val texSamplerHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, quadBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        transformedTexBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texSamplerHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        
        checkGlError("drawCamera")
    }

    fun drawPoints(pointBuffer: FloatBuffer, mvpMatrix: FloatArray, pointSize: Float) {
        val limit = pointBuffer.limit()
        if (program == 0 || limit == 0) return

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)

        GLES20.glUseProgram(program)
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, pointSize)

        val posBuf = pointBuffer.duplicate()
        posBuf.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 16, posBuf)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, limit / 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        
        checkGlError("drawPoints")
    }

    private fun createProgram(vShader: String, fShader: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vShader)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShader)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Link error: " + GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader != 0) {
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Shader error ($type): " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
        }
        return shader
    }

    private fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError $error")
        }
    }
}
