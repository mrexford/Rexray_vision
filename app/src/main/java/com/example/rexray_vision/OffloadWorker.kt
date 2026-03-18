package com.example.rexray_vision

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OffloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val TAG = "OffloadWorker"

    override suspend fun doWork(): Result {
        val projectName = inputData.getString("PROJECT_NAME") ?: "DefaultProject"
        val storageDir = File(applicationContext.filesDir, "burst_capture")
        
        if (!storageDir.exists() || storageDir.listFiles().isNullOrEmpty()) {
            return Result.success()
        }

        val zipFile = File(applicationContext.cacheDir, "${projectName}_session.zip")
        
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                storageDir.listFiles()?.forEach { file ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            Log.d(TAG, "Session packaged: ${zipFile.absolutePath}")
            // In a real app, we would upload this here.
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to package session", e)
            Result.failure()
        }
    }
}
