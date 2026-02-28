package com.example.rexray_vision

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService

class RexrayClient(
    private val executor: ExecutorService,
    private val gson: Gson,
    private val onMessageReceived: (NetworkService.Message) -> Unit,
    private val onConnectionLost: () -> Unit
) {
    private val TAG = "RexrayClient"
    private var clientSocket: Socket? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun connect(host: String, port: Int) {
        executor.execute {
            try {
                Log.i(TAG, "Connecting to $host:$port")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                clientSocket = socket
                _isConnected.value = true
                
                // Immediately register with the server to be added to the broadcast list
                send(NetworkService.Message.JoinGroup(""))
                
                listen(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _isConnected.value = false
                onConnectionLost()
            }
        }
    }

    private fun listen(socket: Socket) {
        executor.execute {
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (socket.isConnected && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "RAW JSON Received: $line")
                    val message = gson.fromJson(line, NetworkService.Message::class.java)
                    onMessageReceived(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listen error", e)
            } finally {
                Log.w(TAG, "Socket closed or stream ended.")
                _isConnected.value = false
                try { socket.close() } catch (_: Exception) {}
                onConnectionLost()
            }
        }
    }

    fun send(message: NetworkService.Message) {
        val json = gson.toJson(message)
        executor.execute {
            try {
                clientSocket?.let {
                    val writer = it.getOutputStream().bufferedWriter()
                    writer.write(json)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Send error", e)
            }
        }
    }

    fun disconnect() {
        try {
            // Try to notify the server before closing
            send(NetworkService.Message.LeaveGroup)

            clientSocket?.close()
            clientSocket = null
            _isConnected.value = false
        } catch (e: IOException) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
