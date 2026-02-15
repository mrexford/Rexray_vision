package com.example.rexray_vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class NetworkService : Service() {

    private val TAG = "NetworkService"
    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val serviceType = "_rexrayvision._tcp."
    private var serviceName: String? = null
    private val executor = Executors.newCachedThreadPool()
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(MessageTypeAdapterFactory())
        .create()

    private val binder = NetworkBinder()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var cameraName: String = ""

    // State Flows
    private val _discoveredServices = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredServices = _discoveredServices.asStateFlow()

    private val _connectedClients = MutableStateFlow<Map<String, ClientStatus>>(emptyMap())
    val connectedClients = _connectedClients.asStateFlow()

    private val _incomingMessages = MutableStateFlow<Pair<Socket, Message>?>(null)
    val incomingMessages = _incomingMessages.asStateFlow()

    private val _isConnectedToPrimary = MutableStateFlow(false)
    val isConnectedToPrimary = _isConnectedToPrimary.asStateFlow()

    data class ClientStatus(
        val socket: Socket,
        var imageCount: Int,
        var batteryLevel: Int,
        var storageSpace: Long,
        var isArmed: Boolean = false,
        var cameraName: String = ""
    )

    sealed class Message {
        data class SetParams(val shutterSpeed: Long, val iso: Int, val captureCount: Int, val projectName: String) : Message()
        object ArmCapture : Message()
        object DisarmCapture : Message()
        object StartCapture : Message()
        data class StatusUpdate(val imageCount: Int, val batteryLevel: Int, val storageSpace: Long, val isArmed: Boolean, val cameraName: String) : Message()
        data class UpdateCameraName(val name: String) : Message()
        object JoinGroup : Message()
        object LeaveGroup : Message()
    }

    inner class NetworkBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        stopDiscovery()
        unregisterService()
        executor.shutdown()
    }

    fun setCameraName(name: String) {
        this.cameraName = name
        broadcastMessage(Message.UpdateCameraName(name))
    }

    fun updateClientCameraName(socket: Socket, name: String) {
        val address = socket.inetAddress.hostAddress
        if (address != null) {
            _connectedClients.update { clients ->
                val updatedClients = clients.toMutableMap()
                val clientStatus = updatedClients[address]
                if (clientStatus != null) {
                    updatedClients[address] = clientStatus.copy(cameraName = name)
                }
                updatedClients
            }
        }
    }

    fun registerService(port: Int, name: String) {
        val listeningPort = startServer(port)
        if (listeningPort == -1) {
            Log.e(TAG, "Failed to start server, aborting service registration")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = name
            this.serviceType = this@NetworkService.serviceType
            this.port = listeningPort
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                serviceName = nsdServiceInfo.serviceName
                Log.d(TAG, "Service registered: $serviceName on port $listeningPort")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "Service registration failed: $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { Log.d(TAG, "Service unregistered: ${serviceInfo.serviceName}") }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "Service unregistration failed: $errorCode") }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let { nsdManager.unregisterService(it) }
        registrationListener = null
        stopServer()
    }

    fun discoverServices() {
        _discoveredServices.value = emptyList()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) { Log.d(TAG, "Service discovery started") }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!_discoveredServices.value.any { it.serviceName == service.serviceName }) {
                     _discoveredServices.update { it + service }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredServices.update { it.filter { s -> s.serviceName != service.serviceName } }
            }
            override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "Service discovery stopped") }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e(TAG, "Discovery failed: Error code: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e(TAG, "Stop Discovery failed: Error code: $errorCode") }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nsdManager.resolveService(serviceInfo, executor, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "Resolve failed: $errorCode") }
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    connectToPrimary(resolvedInfo)
                }
            })
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "Resolve failed: $errorCode") }
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    connectToPrimary(resolvedInfo)
                }
            })
        }
    }

    fun disconnectFromPrimary() {
        Log.i(TAG, "Disconnecting from primary")
        sendMessageToPrimary(Message.LeaveGroup)
        try {
            clientSocket?.close()
            Log.i(TAG, "Primary client socket closed")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing client socket", e)
        }
        _isConnectedToPrimary.value = false
    }

    private fun startServer(port: Int): Int {
        val localServerSocket: ServerSocket
        try {
            localServerSocket = ServerSocket(port)
            serverSocket = localServerSocket
            Log.i(TAG, "Server started on port ${localServerSocket.localPort}")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting server", e)
            return -1
        }

        executor.execute {
            while (!localServerSocket.isClosed) {
                try {
                    val client = localServerSocket.accept()
                    Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                    handleClient(client)
                } catch (e: IOException) {
                    if (localServerSocket.isClosed) {
                        Log.i(TAG, "Server socket closed, stopping accept loop.")
                        break
                    }
                    Log.e(TAG, "Error accepting client connection", e)
                }
            }
        }
        return localServerSocket.localPort
    }

    private fun stopServer() {
        try {
            serverSocket?.close()
            _connectedClients.value.values.forEach { it.socket.close() }
            _connectedClients.value = emptyMap()
            Log.i(TAG, "Server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
    }

    private fun connectToPrimary(serviceInfo: NsdServiceInfo) {
        executor.execute {
            try {
                Log.i(TAG, "Connecting to primary at ${serviceInfo.host}:${serviceInfo.port}")
                clientSocket = Socket()
                clientSocket?.connect(InetSocketAddress(serviceInfo.host, serviceInfo.port), 5000)
                Log.i(TAG, "Connected to primary. Sending JoinGroup message.")
                sendMessageToPrimary(Message.JoinGroup)
                _isConnectedToPrimary.value = true
                listenForServerMessages(clientSocket!!)
            } catch (e: IOException) {
                Log.e(TAG, "Error connecting to primary", e)
                _isConnectedToPrimary.value = false
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Connection to primary timed out", e)
                _isConnectedToPrimary.value = false
            }
        }
    }

    private fun listenForServerMessages(socket: Socket) {
        executor.execute {
            val serverAddress = socket.inetAddress.hostAddress
            Log.d(TAG, "[$serverAddress] Starting to listen for server messages.")

            try {
                val reader = socket.getInputStream().bufferedReader()
                while (socket.isConnected) {
                    var line: String? = null
                    try {
                        line = reader.readLine()
                        if (line == null) {
                            Log.d(TAG, "[$serverAddress] Server disconnected (readLine returned null).")
                            break
                        }

                        Log.d(TAG, "[$serverAddress] Received line: $line")
                        val message = gson.fromJson(line, Message::class.java)
                        Log.i(TAG, "[$serverAddress] Parsed message: $message")

                        _incomingMessages.value = Pair(socket, message)

                    } catch (e: JsonSyntaxException) {
                        Log.e(TAG, "[$serverAddress] Malformed JSON received: $line", e)
                    } catch (e: IOException) {
                        Log.e(TAG, "[$serverAddress] IO Error while reading from socket", e)
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "[$serverAddress] Error getting input stream", e)
            } finally {
                Log.d(TAG, "[$serverAddress] Cleaning up server connection.")
                _isConnectedToPrimary.value = false
                try {
                    socket.close()
                    Log.i(TAG, "[$serverAddress] Final socket closure successful.")
                } catch (e: IOException) {
                    Log.e(TAG, "[$serverAddress] Error closing server socket in finally block", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        executor.execute {
            val clientAddress = socket.inetAddress.hostAddress
            Log.d(TAG, "[$clientAddress] Starting to handle client.")

            try {
                val reader = socket.getInputStream().bufferedReader()
                while (socket.isConnected) {
                    var line: String? = null
                    try {
                        line = reader.readLine()
                        if (line == null) {
                            Log.d(TAG, "[$clientAddress] Client disconnected (readLine returned null).")
                            break
                        }

                        Log.d(TAG, "[$clientAddress] Received line: $line")
                        val message = gson.fromJson(line, Message::class.java)
                        Log.i(TAG, "[$clientAddress] Parsed message: $message")

                        when (message) {
                            is Message.JoinGroup -> {
                                if (clientAddress != null) {
                                    Log.i(TAG, "[$clientAddress] Client joined group.")
                                    val status = ClientStatus(socket, 0, 0, 0, false)
                                    _connectedClients.update { it + (clientAddress to status) }
                                }
                            }
                            is Message.LeaveGroup -> {
                                Log.i(TAG, "[$clientAddress] Client is leaving group.")
                                if (clientAddress != null) {
                                    _connectedClients.update { it - clientAddress }
                                }
                                socket.close()
                                Log.i(TAG, "[$clientAddress] Socket closed for leaving client.")
                                break
                            }
                            else -> {
                                Log.d(TAG, "[$clientAddress] Relaying message to StateFlow.")
                                _incomingMessages.value = Pair(socket, message)
                            }
                        }
                    } catch (e: JsonSyntaxException) {
                        Log.e(TAG, "[$clientAddress] Malformed JSON received: $line", e)
                    } catch (e: IOException) {
                        Log.e(TAG, "[$clientAddress] IO Error while reading from socket", e)
                        break 
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "[$clientAddress] Error getting input stream", e)
            } finally {
                if (clientAddress != null) {
                    Log.d(TAG, "[$clientAddress] Cleaning up client connection.")
                    _connectedClients.update { it - clientAddress }
                }
                try {
                    socket.close()
                    Log.i(TAG, "[$clientAddress] Final socket closure successful.")
                } catch (e: IOException) {
                    Log.e(TAG, "[$clientAddress] Error closing client socket in finally block", e)
                }
            }
        }
    }

    fun sendMessage(socket: Socket, message: Message) {
        val clientAddress = socket.inetAddress.hostAddress
        executor.execute {
            try {
                val jsonMessage = gson.toJson(message)
                Log.d(TAG, "[$clientAddress] Sending message: $jsonMessage")
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(jsonMessage)
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "[$clientAddress] Error sending message", e)
            }
        }
    }

    fun broadcastMessage(message: Message) {
        Log.d(TAG, "Broadcasting message: $message")
        _connectedClients.value.values.forEach { sendMessage(it.socket, message) }
    }

    fun sendMessageToPrimary(message: Message) {
        clientSocket?.let { 
            Log.d(TAG, "Sending message to primary: $message")
            sendMessage(it, message) 
        }
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
