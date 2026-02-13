package com.example.rexray_vision

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class NetworkManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_rexrayvision._tcp."
    private var serviceName = "RexrayVision-Primary"
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(MessageTypeAdapterFactory())
        .create()

    var onServiceFound: ((NsdServiceInfo) -> Unit)? = null
    var onServiceLost: ((NsdServiceInfo) -> Unit)? = null
    var onServiceResolved: ((NsdServiceInfo) -> Unit)? = null
    var onClientConnected: ((Socket) -> Unit)? = null
    var onClientDisconnected: ((Socket) -> Unit)? = null
    var onMessageReceived: ((Socket, Message) -> Unit)? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    sealed class Message {
        data class SetParams(val shutterSpeed: Long, val iso: Int, val captureCount: Int, val projectName: String) : Message()
        object ArmCapture : Message()
        object StartCapture : Message()
        data class StatusUpdate(val imageCount: Int, val batteryLevel: Int, val storageSpace: Long, val isArmed: Boolean) : Message()
        object JoinGroup : Message()
    }

    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@NetworkManager.serviceName
            this.serviceType = this@NetworkManager.serviceType
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                serviceName = nsdServiceInfo.serviceName
                Log.d("NetworkManager", "Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NetworkManager", "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d("NetworkManager", "Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NetworkManager", "Service unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        startServer(port)
    }

    fun unregisterService() {
        registrationListener?.let { nsdManager.unregisterService(it) }
        registrationListener = null
        stopServer()
    }

    fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NetworkManager", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("NetworkManager", "Service found: $service")
                onServiceFound?.invoke(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("NetworkManager", "Service lost: $service")
                onServiceLost?.invoke(service)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("NetworkManager", "Service discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NetworkManager", "Discovery failed: Error code: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NetworkManager", "Stop Discovery failed: Error code: $errorCode")
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NetworkManager", "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d("NetworkManager", "Resolve Succeeded. $serviceInfo")
                onServiceResolved?.invoke(serviceInfo)
                connectToPrimary(serviceInfo)
            }
        }
        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    private fun startServer(port: Int) {
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                while (!serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    onClientConnected?.invoke(client)
                    handleClient(client)
                }
            } catch (e: IOException) {
                Log.e("NetworkManager", "Error starting server", e)
            }
        }
    }

    private fun stopServer() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("NetworkManager", "Error closing server socket", e)
        }
    }

    private fun connectToPrimary(serviceInfo: NsdServiceInfo) {
        executor.execute {
            try {
                clientSocket = Socket(serviceInfo.host, serviceInfo.port)
                handleClient(clientSocket!!)
            } catch (e: IOException) {
                Log.e("NetworkManager", "Error connecting to primary", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        executor.execute {
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (socket.isConnected) {
                    try {
                        val line = reader.readLine() ?: break
                        val message = gson.fromJson(line, Message::class.java)
                        onMessageReceived?.invoke(socket, message)
                    } catch (e: JsonSyntaxException) {
                        Log.e("NetworkManager", "Malformed JSON received: ", e)
                    }
                }
            } catch (e: IOException) {
                Log.e("NetworkManager", "Error handling client", e)
            } finally {
                onClientDisconnected?.invoke(socket)
                try {
                    socket.close()
                } catch (e: IOException) {
                    Log.e("NetworkManager", "Error closing client socket", e)
                }
            }
        }
    }

    fun sendMessage(socket: Socket, message: Message) {
        executor.execute {
            try {
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(gson.toJson(message))
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                Log.e("NetworkManager", "Error sending message", e)
            }
        }
    }

    fun sendMessageToPrimary(message: Message) {
        clientSocket?.let { sendMessage(it, message) }
    }
}