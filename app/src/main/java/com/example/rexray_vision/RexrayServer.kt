package com.example.rexray_vision

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService

class RexrayServer(
    private val executor: ExecutorService,
    private val gson: Gson,
    private val onMessageReceived: (Socket, NetworkService.Message) -> Unit,
    private val onClientConnected: (Socket) -> Unit = {}
) {
    private val TAG = "RexrayServer"
    private var serverSocket: ServerSocket? = null
    
    private val _connectedClients = MutableStateFlow<Map<Socket, NetworkService.ClientStatus>>(emptyMap())
    val connectedClients = _connectedClients.asStateFlow()

    fun start(port: Int): Int {
        return try {
            val socket = ServerSocket(port)
            serverSocket = socket
            Log.i(TAG, "Server started on port ${socket.localPort}")
            
            executor.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                        handleClient(client)
                    } catch (e: IOException) {
                        if (socket.isClosed) break
                        Log.e(TAG, "Error accepting client", e)
                    }
                }
            }
            socket.localPort
        } catch (e: IOException) {
            Log.e(TAG, "Error starting server", e)
            -1
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            _connectedClients.value.values.forEach { it.socket.close() }
            _connectedClients.value = emptyMap()
            Log.i(TAG, "Server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        executor.execute {
            val address = socket.inetAddress.hostAddress
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (socket.isConnected && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "Incoming from $address: $line")
                    val message = gson.fromJson(line, NetworkService.Message::class.java)
                    
                    when (message) {
                        is NetworkService.Message.JoinGroup -> {
                            Log.i(TAG, "Client $address joined group")
                            val status = NetworkService.ClientStatus(socket, 0, false)
                            _connectedClients.update { it + (socket to status) }
                            onClientConnected(socket)
                        }
                        is NetworkService.Message.LeaveGroup -> {
                            Log.i(TAG, "Client $address requested to leave")
                            _connectedClients.update { it - socket }
                            socket.close()
                            break
                        }
                        is NetworkService.Message.StatusUpdate -> {
                            _connectedClients.update { clients ->
                                val updated = clients.toMutableMap()
                                updated[socket]?.let {
                                    updated[socket] = it.copy(
                                        imageCount = message.imageCount,
                                        isArmed = message.isArmed,
                                        cameraName = message.cameraName
                                    )
                                }
                                updated
                            }
                        }
                        is NetworkService.Message.UpdateCameraName -> {
                            _connectedClients.update { clients ->
                                val updated = clients.toMutableMap()
                                updated[socket]?.let {
                                    updated[socket] = it.copy(cameraName = message.name)
                                }
                                updated
                            }
                        }
                        else -> onMessageReceived(socket, message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client $address", e)
            } finally {
                _connectedClients.update { it - socket }
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun broadcast(message: NetworkService.Message) {
        val json = gson.toJson(message)
        val clients = _connectedClients.value.values
        Log.d(TAG, "Broadcasting to ${clients.size} clients: $json")
        
        clients.forEach { client ->
            executor.execute {
                try {
                    val writer = client.socket.getOutputStream().bufferedWriter()
                    writer.write(json)
                    writer.newLine()
                    writer.flush()
                    Log.d(TAG, "Successfully sent to ${client.socket.inetAddress}")
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending to ${client.socket.inetAddress}", e)
                }
            }
        }
    }
}
