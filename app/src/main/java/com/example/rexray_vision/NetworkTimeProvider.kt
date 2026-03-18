package com.example.rexray_vision

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

class NetworkTimeProvider(
    private val executor: ExecutorService,
    private val syncEngine: TimeSyncEngine
) {
    private val TAG = "NetworkTimeProvider"
    private val UDP_PORT = 8889
    private var socket: DatagramSocket? = null
    private var isRunning = false

    private val MSG_TYPE_PULSE = 1
    private val MSG_TYPE_RESPONSE = 2
    private val MSG_TYPE_OFFSET = 3

    fun start(isMaster: Boolean) {
        isRunning = true
        executor.execute {
            try {
                socket = DatagramSocket(UDP_PORT)
                socket?.broadcast = true
                Log.d(TAG, "UDP Time Provider started on port $UDP_PORT (Master: $isMaster)")
                
                val buffer = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    handlePacket(packet, isMaster)
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "UDP Error", e)
            } finally {
                socket?.close()
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
    }

    private fun handlePacket(packet: DatagramPacket, isMaster: Boolean) {
        val data = packet.data
        val type = ByteBuffer.wrap(data).int
        
        if (isMaster) {
            if (type == MSG_TYPE_RESPONSE) {
                // Client responded. Calculate RTT.
                val t1 = ByteBuffer.wrap(data, 4, 8).long // Master Sent
                val t2 = ByteBuffer.wrap(data, 12, 8).long // Node Received
                val t3 = SystemClock.elapsedRealtimeNanos() // Master Received
                
                val rtt = t3 - t1
                sendOffset(packet.address, t1, rtt)
            }
        } else {
            if (type == MSG_TYPE_PULSE) {
                // Received pulse from Master. Respond immediately.
                val t1 = ByteBuffer.wrap(data, 4, 8).long // Master Sent
                val t2 = SystemClock.elapsedRealtimeNanos() // Node Received
                sendResponse(packet.address, t1, t2)
            } else if (type == MSG_TYPE_OFFSET) {
                // Master sent RTT. Update local engine.
                val t1 = ByteBuffer.wrap(data, 4, 8).long // Master Sent
                val rtt = ByteBuffer.wrap(data, 12, 8).long
                val t2 = SystemClock.elapsedRealtimeNanos() // Approximate, but better to use T2 from earlier
                // Note: To be perfectly accurate, we should store T2 keyed by T1.
                syncEngine.updateOffset(t1, t2, rtt)
            }
        }
    }

    // Master: Periodically broadcast pulses
    fun broadcastPulse() {
        executor.execute {
            try {
                val t1 = SystemClock.elapsedRealtimeNanos()
                val buffer = ByteBuffer.allocate(12)
                buffer.putInt(MSG_TYPE_PULSE)
                buffer.putLong(t1)
                
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer.array(), buffer.capacity(), address, UDP_PORT)
                socket?.send(packet)
                Log.d(TAG, "Broadcasted Pulse: T1=$t1")
            } catch (e: Exception) {
                Log.e(TAG, "Pulse Broadcast Error", e)
            }
        }
    }

    private fun sendResponse(address: InetAddress, t1: Long, t2: Long) {
        val buffer = ByteBuffer.allocate(20)
        buffer.putInt(MSG_TYPE_RESPONSE)
        buffer.putLong(t1)
        buffer.putLong(t2)
        val packet = DatagramPacket(buffer.array(), buffer.capacity(), address, UDP_PORT)
        socket?.send(packet)
    }

    private fun sendOffset(address: InetAddress, t1: Long, rtt: Long) {
        val buffer = ByteBuffer.allocate(20)
        buffer.putInt(MSG_TYPE_OFFSET)
        buffer.putLong(t1)
        buffer.putLong(rtt)
        val packet = DatagramPacket(buffer.array(), buffer.capacity(), address, UDP_PORT)
        socket?.send(packet)
    }
}
