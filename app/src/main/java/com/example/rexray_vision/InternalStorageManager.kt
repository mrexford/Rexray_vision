package com.example.rexray_vision

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class InternalStorageManager(private val context: Context) {
    private val TAG = "InternalStorageManager"
    private val BURST_DIR = "burst_capture"

    fun prepareStorageHandles(): File {
        val dir = File(context.filesDir, BURST_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveImageToInternal(data: ByteArray, filename: String): Boolean {
        val dir = prepareStorageHandles()
        val file = File(dir, filename)
        return try {
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save image: $filename", e)
            false
        }
    }

    fun clearBurstStorage() {
        val dir = File(context.filesDir, BURST_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
    
    fun getBurstFiles(): List<File> {
        val dir = File(context.filesDir, BURST_DIR)
        return dir.listFiles()?.toList() ?: emptyList()
    }
}
