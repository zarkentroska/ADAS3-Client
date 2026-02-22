package com.github.digitallyrefined.androidipcamera.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class LanDiscoveryBeaconHelper(
    private val beaconIntervalMs: Long = 2000L,
    private val discoveryPort: Int = 39000,
    private val onLog: (String) -> Unit = {}
) {
    private var beaconJob: Job? = null

    fun start(getBindIp: () -> String, getStreamPort: () -> Int) {
        if (beaconJob?.isActive == true) return

        beaconJob = CoroutineScope(Dispatchers.IO).launch {
            onLog("LAN discovery beacon started on UDP/$discoveryPort")
            while (isActive) {
                try {
                    val bindIp = getBindIp()
                    val streamPort = getStreamPort()
                    val payload = buildPayload(bindIp, streamPort)
                    sendBroadcast(payload)
                } catch (e: Exception) {
                    onLog("LAN discovery beacon error: ${e.message}")
                }
                delay(beaconIntervalMs)
            }
        }
    }

    fun stop() {
        beaconJob?.cancel()
        beaconJob = null
        onLog("LAN discovery beacon stopped")
    }

    private fun buildPayload(bindIp: String, streamPort: Int): String {
        val ts = System.currentTimeMillis()
        return """{"type":"adas3-client-discovery","ip":"$bindIp","port":$streamPort,"ts":$ts}"""
    }

    private fun sendBroadcast(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = collectBroadcastTargets().toMutableSet()
            targets.add(InetAddress.getByName("255.255.255.255"))

            targets.forEach { address ->
                try {
                    val packet = DatagramPacket(bytes, bytes.size, address, discoveryPort)
                    socket.send(packet)
                } catch (_: Exception) {
                    // Ignore per-target errors and continue with others.
                }
            }
        }
    }

    private fun collectBroadcastTargets(): Set<InetAddress> {
        val addresses = mutableSetOf<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast is Inet4Address) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore discovery target errors and fall back to global broadcast.
        }
        return addresses
    }
}
