package com.example.rexray_vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class FileMigrationService : LifecycleService() {

    private val tag = "FileMigrationService"
    private val notificationId = 1001
    private val channelId = "migration_channel"

    private val _migrationProgress = MutableStateFlow(0)
    val migrationProgress = _migrationProgress.asStateFlow()

    private val _isMigrating = MutableStateFlow(false)
    val isMigrating = _isMigrating.asStateFlow()

    private val binder = MigrationBinder()

    inner class MigrationBinder : Binder() {
        fun getService(): FileMigrationService = this@FileMigrationService
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_START_MIGRATION) {
            val cacheDirPath = intent.getStringExtra(EXTRA_CACHE_DIR)
            if (cacheDirPath != null && !_isMigrating.value) {
                startForeground(notificationId, createNotification(0, 0))
                startMigration(cacheDirPath)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMigration(cacheDirPath: String) {
        lifecycleScope.launch {
            _isMigrating.value = true
            val cacheDir = File(cacheDirPath)
            
            // Optimization: Sort files by name (which contains timestamp) to ensure chronological migration
            val files = cacheDir.listFiles()
                ?.filter { it.isFile && (it.extension == "dng" || it.extension == "jpg") }
                ?.sortedBy { it.name } ?: emptyList()

            val totalFiles = files.size

            if (totalFiles == 0) {
                Log.d(tag, "No files to migrate.")
                _isMigrating.value = false
                stopSelf()
                return@launch
            }

            Log.d(tag, "Starting migration of $totalFiles files.")

            withContext(Dispatchers.IO) {
                files.forEachIndexed { index, file ->
                    val progress = ((index.toFloat() / totalFiles) * 100).toInt()
                    _migrationProgress.value = progress
                    updateNotification(index + 1, totalFiles)

                    migrateFile(file)
                }
            }

            Log.d(tag, "Migration finished.")
            _migrationProgress.value = 100
            _isMigrating.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun migrateFile(file: File) {
        try {
            val isDng = file.extension == "dng"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isDng) "image/x-adobe-dng" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rexray_vision")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
                file.delete()
                Log.i(tag, "Successfully migrated ${file.name}")
            } ?: Log.e(tag, "Failed to create MediaStore entry for ${file.name}")

        } catch (e: Exception) {
            Log.e(tag, "Failed to migrate ${file.name}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "File Migration Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(current: Int, total: Int): Notification {
        val progressText = if (total > 0) "Moving $current of $total files..." else "Preparing migration..."
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Migrating Images")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(current: Int, total: Int) {
        val notification = createNotification(current, total)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    companion object {
        const val ACTION_START_MIGRATION = "com.example.rexray_vision.START_MIGRATION"
        const val EXTRA_CACHE_DIR = "extra_cache_dir"
    }
}
