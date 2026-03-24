package com.example.rexray_vision

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Handles rendering the ARCore camera feed and Depth Map visualization.
 * Transitioned from Point Cloud to 16-bit Raw Depth visualization with Confidence filtering.
 */
class PointRenderer(context: Context) {
    private val TAG = "PointRenderer"

    private val depthVertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val depthFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture; 
        uniform sampler2D sConfidence;
        
        void main() {
            // Check confidence if available. 
            // 60% threshold: 0.6 * 255.0 = 153.0
            float confidence = texture2D(sConfidence, vTexCoord).r * 255.0;
            if (confidence < 153.0) {
                discard;
            }

            // Sample from the 16-bit depth texture uploaded as GL_RG8.
            // R contains the low byte, G contains the high byte.
            vec4 rawDepth = texture2D(sTexture, vTexCoord);
            
            // Reconstruct 16-bit millimeter value.
            float depthMm = (rawDepth.r * 255.0 + rawDepth.g * 255.0 * 256.0);
            
            // No data (0) or invalid should be transparent
            if (depthMm <= 0.0 || depthMm >= 65530.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
                return;
            }
            
            // Map 200mm (0.2m) to 5000mm (5m) to 0.0 - 1.0 range
            float normalizedDepth = clamp((depthMm - 200.0) / 4800.0, 0.0, 1.0);
            
            // Turbo-like colormap approximation
            // Red (Near) -> Green -> Blue (Far)
            vec3 color;
            color.r = clamp(1.5 - abs(4.0 * normalizedDepth - 1.0), 0.0, 1.0);
            color.g = clamp(1.5 - abs(4.0 * normalizedDepth - 2.0), 0.0, 1.0);
            color.b = clamp(1.5 - abs(4.0 * normalizedDepth - 3.0), 0.0, 1.0);
            
            gl_FragColor = vec4(color, 0.75);
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

    private var cameraProgram: Int = 0
    private var depthProgram: Int = 0
    private var textureId: Int = -1
    private var depthTextureId: Int = -1
    private var confidenceTextureId: Int = -1

    // Quad for rendering (Triangle Strip order)
    private val quadCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    )
    
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
        cameraProgram = createProgram(cameraVertexShaderCode, cameraFragmentShaderCode)
        depthProgram = createProgram(depthVertexShaderCode, depthFragmentShaderCode)

        val textures = IntArray(3)
        GLES20.glGenTextures(3, textures, 0)
        textureId = textures[0]
        depthTextureId = textures[1]
        confidenceTextureId = textures[2]

        setupExternalTexture(textureId)
        
        // Depth Texture (Regular 2D)
        setupDepthTexture(depthTextureId)
        // Confidence Texture (Regular 2D)
        setupDepthTexture(confidenceTextureId)

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

    private fun setupDepthTexture(id: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun setupExternalTexture(id: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun getTextureId() = textureId
    fun getDepthTextureId() = depthTextureId

    fun updateDepthTextures(width: Int, height: Int, depthBuffer: ByteBuffer, confidenceBuffer: ByteBuffer?) {
        // Upload Depth Map
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG8, width, height, 0,
            GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, depthBuffer
        )

        // Upload Confidence Map if available
        confidenceBuffer?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, confidenceTextureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, it
            )
        }
    }

    fun drawCamera(frame: Frame) {
        if (cameraProgram == 0 || textureId == -1) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

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
    }

    fun drawDepth(frame: Frame) {
        if (depthProgram == 0 || depthTextureId == -1) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        frame.transformDisplayUvCoords(quadTexBuffer, transformedTexBuffer)

        GLES20.glUseProgram(depthProgram)
        val positionHandle = GLES20.glGetAttribLocation(depthProgram, "vPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(depthProgram, "aTexCoord")
        val texSamplerHandle = GLES20.glGetUniformLocation(depthProgram, "sTexture")
        val confSamplerHandle = GLES20.glGetUniformLocation(depthProgram, "sConfidence")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, quadBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        transformedTexBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES20.glUniform1i(texSamplerHandle, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, confidenceTextureId)
        GLES20.glUniform1i(confSamplerHandle, 2)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun createProgram(vShader: String, fShader: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vShader)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShader)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Error compiling shader: $log")
            GLES20.glDeleteShader(shader)
            return 0
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
