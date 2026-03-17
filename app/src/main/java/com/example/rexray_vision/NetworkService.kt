package com.example.rexray_vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.GsonBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkService : Service() {

    private val TAG = "NetworkService"
    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val wifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var multicastLock: WifiManager.MulticastLock? = null

    private val serviceType = "_rexrayvision._tcp"
    var serviceName: String? = null
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(MessageTypeAdapterFactory())
        .create()

    private val binder = NetworkBinder()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // Decomposed Helpers
    private var server: RexrayServer? = null
    private var client: RexrayClient? = null

    private var cameraName: String = ""

    // Session State Master Variables
    enum class ServiceRole { IDLE, PRIMARY, CLIENT }
    enum class CaptureMode { RAW, JPEG }

    private val _serviceRole = MutableStateFlow(ServiceRole.IDLE)
    val serviceRole = _serviceRole.asStateFlow()

    private val _isArmed = MutableStateFlow(false)
    val isArmed = _isArmed.asStateFlow()

    private val _iso = MutableStateFlow(400)
    val iso = _iso.asStateFlow()

    private val _shutterSpeed = MutableStateFlow(3333333L)
    val shutterSpeed = _shutterSpeed.asStateFlow()

    private val _captureRate = MutableStateFlow(15)
    val captureRate = _captureRate.asStateFlow()

    private val _captureLimit = MutableStateFlow(30)
    val captureLimit = _captureLimit.asStateFlow()

    private val _projectName = MutableStateFlow("DefaultProject")
    val projectName = _projectName.asStateFlow()

    private val _captureMode = MutableStateFlow(CaptureMode.JPEG)
    val captureMode = _captureMode.asStateFlow()

    private val _isFlashEnabled = MutableStateFlow(false)
    val isFlashEnabled = _isFlashEnabled.asStateFlow()

    private val _flashIntensity = MutableStateFlow(3) // 1, 2, or 3
    val flashIntensity = _flashIntensity.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    // Command Queue for early calls
    private val commandQueue = mutableListOf<() -> Unit>()

    var currentImageCount: Int = 0
    var isClientArmed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateStatusUpdateSpeed()
            }
        }

    // State Flows
    private val _discoveredServices = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredServices = _discoveredServices.asStateFlow()

    // Combined flows for Activity observation - Unified with helpers
    val connectedClients = _isReady.flatMapLatest { ready ->
        if (ready) server?.connectedClients ?: flowOf(emptyMap())
        else flowOf(emptyMap())
    }
    
    // Event-based Flow for commands to ensure rapid sequential events (e.g. Arm -> Start) are not dropped.
    private val _incomingMessages = MutableSharedFlow<Pair<Socket?, Message>>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    val isConnectedToPrimary = _isReady.flatMapLatest { ready ->
        if (ready) client?.isConnected ?: flowOf(false)
        else flowOf(false)
    }

    data class ClientStatus(
        val socket: Socket,
        var imageCount: Int,
        var isArmed: Boolean = false,
        var cameraName: String = "",
        var lastCommandAck: Boolean = true
    )

    sealed class Message {
        data class SetParams(
            val shutterSpeed: Long,
            val iso: Int,
            val captureRate: Int,
            val captureCount: Int,
            val projectName: String,
            val captureMode: CaptureMode,
            val isFlashEnabled: Boolean,
            val flashIntensity: Int,
            val commandId: String
        ) : Message()
        data class ArmCapture(val commandId: String) : Message()
        data class DisarmCapture(val commandId: String) : Message()
        data class StartCapture(val commandId: String) : Message()
        data class StopCapture(val commandId: String) : Message()
        data class StatusUpdate(val imageCount: Int, val isArmed: Boolean, val cameraName: String) : Message()
        data class UpdateCameraName(val name: String) : Message()
        data class JoinGroup(val projectName: String) : Message()
        object LeaveGroup : Message()
        data class CommandAck(val commandId: String) : Message()
        object ConnectionRejected : Message()
        object SyncTrigger : Message() // Internal use
    }

    inner class NetworkBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Bootstrapping service asynchronously.")
        
        createNotificationChannel()
        
        executor.execute {
            try {
                val s = RexrayServer(executor, gson, { socket, message -> 
                    _incomingMessages.tryEmit(socket to message)
                }, { socket ->
                    _incomingMessages.tryEmit(socket to Message.SyncTrigger)
                })
                val c = RexrayClient(executor, gson, { message ->
                    handlePrimaryMessage(message)
                }, {
                    _serviceRole.value = ServiceRole.IDLE
                })
                
                server = s
                client = c
                
                _isReady.value = true
                Log.d(TAG, "Service bootstrap complete. Executing queued commands: ${commandQueue.size}")
                
                synchronized(commandQueue) {
                    commandQueue.forEach { it.invoke() }
                    commandQueue.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bootstrap service", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        client?.disconnect()
        stopDiscovery()
        unregisterService()
        releaseMulticastLock()
        executor.shutdown()
        scheduler.shutdown()
    }

    private fun handlePrimaryMessage(message: Message) {
        when (message) {
            is Message.SetParams -> {
                _iso.value = message.iso
                _shutterSpeed.value = message.shutterSpeed
                _captureRate.value = message.captureRate
                _captureLimit.value = message.captureCount
                _projectName.value = message.projectName
                _captureMode.value = message.captureMode
                _isFlashEnabled.value = message.isFlashEnabled
                _flashIntensity.value = message.flashIntensity
            }
            is Message.ArmCapture -> _isArmed.value = true
            is Message.DisarmCapture -> _isArmed.value = false
            else -> {}
        }
        _incomingMessages.tryEmit(null to message)
    }

    fun setCameraName(name: String) {
        this.cameraName = name
        broadcastMessage(Message.UpdateCameraName(name))
    }

    // State Update Methods
    fun updateProjectName(name: String) { _projectName.value = name }
    fun updateIso(value: Int) { _iso.value = value }
    fun updateShutterSpeed(value: Long) { _shutterSpeed.value = value }
    fun updateCaptureRate(value: Int) { _captureRate.value = value }
    fun updateCaptureLimit(value: Int) { _captureLimit.value = value }
    fun updateCaptureMode(mode: CaptureMode) { _captureMode.value = mode }
    fun updateFlashEnabled(enabled: Boolean) { _isFlashEnabled.value = enabled }
    fun updateFlashIntensity(intensity: Int) { _flashIntensity.value = intensity }
    fun updateArmingStatus(armed: Boolean) { _isArmed.value = armed }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("RexrayVisionMulticastLock").apply {
                setReferenceCounted(true)
            }
        }
        multicastLock?.let {
            if (!it.isHeld) {
                Log.d(TAG, "Acquiring MulticastLock")
                it.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                Log.d(TAG, "Releasing MulticastLock")
                it.release()
            }
        }
    }

    fun registerService(port: Int, name: String) {
        if (!_isReady.value) {
            Log.d(TAG, "Queuing registerService for $name")
            synchronized(commandQueue) {
                commandQueue.add { registerService(port, name) }
            }
            return
        }
        if (_serviceRole.value == ServiceRole.PRIMARY) return

        executor.execute {
            val listeningPort = server?.start(port) ?: -1
            if (listeningPort == -1) return@execute

            _serviceRole.value = ServiceRole.PRIMARY
            _projectName.value = name

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = name
                this.serviceType = this@NetworkService.serviceType
                this.port = listeningPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    this@NetworkService.serviceName = nsdServiceInfo.serviceName
                    Log.d(TAG, "Service registered: ${this@NetworkService.serviceName}")
                }
                override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {
                    Log.e(TAG, "Registration failed: $e")
                }
                override fun onServiceUnregistered(s: NsdServiceInfo) {
                    Log.d(TAG, "Service unregistered")
                }
                override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {
                    Log.e(TAG, "Unregistration failed: $e")
                }
            }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    fun unregisterService() {
        registrationListener?.let { nsdManager.unregisterService(it) }
        registrationListener = null
        server?.stop()
        _serviceRole.value = ServiceRole.IDLE
    }

    fun discoverServices() {
        Log.d(TAG, "Starting service discovery for type: $serviceType")
        acquireMulticastLock()
        _discoveredServices.value = emptyList()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started: $regType")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                if (!_discoveredServices.value.any { it.serviceName == service.serviceName }) {
                     val list = _discoveredServices.value.toMutableList()
                     list.add(service)
                     _discoveredServices.value = list
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                val list = _discoveredServices.value.toMutableList()
                list.removeAll { it.serviceName == service.serviceName }
                _discoveredServices.value = list
            }
            override fun onDiscoveryStopped(s: String) {
                Log.d(TAG, "Discovery stopped: $s")
                releaseMulticastLock()
            }
            override fun onStartDiscoveryFailed(s: String, e: Int) {
                Log.e(TAG, "Start discovery failed: $e")
                releaseMulticastLock()
            }
            override fun onStopDiscoveryFailed(s: String, e: Int) {
                Log.e(TAG, "Stop discovery failed: $e")
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stopping service discovery")
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    fun resolveService(serviceInfo: NsdServiceInfo) {
        Log.d(TAG, "Resolving service: ${serviceInfo.serviceName}")
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                Log.e(TAG, "Resolve failed for ${s.serviceName}: $e")
            }
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
                connectToPrimary(resolvedInfo)
            }
        })
    }

    fun connectToPrimary(serviceInfo: NsdServiceInfo) {
        if (!_isReady.value) {
            Log.d(TAG, "Queuing connectToPrimary for ${serviceInfo.serviceName}")
            synchronized(commandQueue) {
                commandQueue.add { connectToPrimary(serviceInfo) }
            }
            return
        }
        if (_serviceRole.value == ServiceRole.CLIENT) return
        
        Log.d(TAG, "Connecting to Primary: ${serviceInfo.host.hostAddress}:${serviceInfo.port}")
        client?.connect(serviceInfo.host.hostAddress!!, serviceInfo.port)
        _serviceRole.value = ServiceRole.CLIENT
    }

    fun disconnectFromPrimary() {
        Log.d(TAG, "Disconnecting from Primary")
        stopSendingStatusUpdates()
        client?.send(Message.LeaveGroup)
        client?.disconnect()
        _serviceRole.value = ServiceRole.IDLE
    }

    fun broadcastMessage(message: Message) {
        if (_serviceRole.value == ServiceRole.PRIMARY) {
            Log.d(TAG, "Attempting to broadcast message: $message")
            server?.broadcast(message)
        }
    }

    fun sendMessageToPrimary(message: Message) {
        if (_serviceRole.value == ServiceRole.CLIENT) {
            client?.send(message)
        }
    }

    private var statusUpdateJob: java.util.concurrent.ScheduledFuture<*>? = null

    private fun startSendingStatusUpdates() {
        updateStatusUpdateSpeed()
    }

    private fun stopSendingStatusUpdates() {
        statusUpdateJob?.cancel(true)
    }

    private fun updateStatusUpdateSpeed() {
        statusUpdateJob?.cancel(true)
        val period = if (isClientArmed) 250L else 2000L
        statusUpdateJob = scheduler.scheduleWithFixedDelay({ sendStatusUpdate() }, 0, period, TimeUnit.MILLISECONDS)
    }

    fun sendStatusUpdate() {
        val message = Message.StatusUpdate(currentImageCount, isClientArmed, cameraName)
        sendMessageToPrimary(message)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Network Service", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rexray Vision")
            .setContentText("Network service is running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "NetworkServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
