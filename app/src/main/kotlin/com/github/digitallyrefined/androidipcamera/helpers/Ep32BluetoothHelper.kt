package com.github.digitallyrefined.androidipcamera.helpers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

class Ep32BluetoothHelper(
    context: Context,
    private val prefs: SharedPreferences,
    private val onStateChanged: (State, String?) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
    private val onHeartbeat: (Heartbeat) -> Unit = {},
    private val onAcoustic: (Acoustic) -> Unit = {},
    private val onUnknownPayload: (String) -> Unit = {}
) {
    enum class State {
        OFF, SCANNING, CONNECTING, CONNECTED, ERROR
    }

    // JSONL payloads emitted by ESP32 over Bluetooth SPP. The ESP32 processes
    // I2S mic-array beamforming/GCC-PHAT locally and pushes events here; the
    // Android side never receives raw audio over Bluetooth.
    data class Heartbeat(
        val micCount: Int,
        val firmware: String?
    )

    data class Acoustic(
        val detected: Boolean,
        val doaDeg: Double?,
        val energy: Double?,
        val confidence: Double?,
        val micCount: Int?
    )

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var receiverRegistered = false
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var readerJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var active = false
    private var triedAddresses = mutableSetOf<String>()
    private var discoveryCycleCount = 0

    @Volatile private var lastInboundActivityMs: Long = 0L
    @Volatile private var lastHeartbeat: Heartbeat? = null
    @Volatile private var lastAcoustic: Acoustic? = null

    fun getLastHeartbeat(): Heartbeat? = lastHeartbeat
    fun getLastAcoustic(): Acoustic? = lastAcoustic

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                    val address = device.address ?: return
                    if (triedAddresses.contains(address)) return

                    if (isEp32Candidate(device)) {
                        triedAddresses.add(address)
                        onLog("EP32 candidate found by name: ${device.name ?: "Unknown"} ($address)")
                        cancelDiscoverySafe()
                        saveLastMac(address)
                        connectToDevice(device)
                        return
                    }

                    val savedMac = getLastMac()
                    if (savedMac != null && address.equals(savedMac, ignoreCase = true)) {
                        triedAddresses.add(address)
                        onLog("EP32 candidate found by saved MAC: $address")
                        cancelDiscoverySafe()
                        connectToDevice(device)
                        return
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!active || isConnected()) return
                    discoveryCycleCount++
                    onLog("Discovery cycle $discoveryCycleCount finished, no EP32 found yet")

                    if (discoveryCycleCount >= MAX_DISCOVERY_CYCLES_BEFORE_BONDED_FALLBACK) {
                        tryAllBondedDevicesWithSpp()
                    } else {
                        scheduleDiscoveryRestart()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAutoConnect() {
        active = true
        triedAddresses.clear()
        discoveryCycleCount = 0

        if (adapter == null) {
            emitState(State.ERROR, "Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            emitState(State.ERROR, "Bluetooth is disabled")
            return
        }

        registerReceiverIfNeeded()
        emitState(State.SCANNING, null)

        val savedMac = getLastMac()
        if (savedMac != null) {
            val savedDevice = adapter.bondedDevices?.firstOrNull {
                it.address.equals(savedMac, ignoreCase = true)
            }
            if (savedDevice != null) {
                onLog("Trying saved MAC device: ${savedDevice.name ?: "unknown"} ($savedMac)")
                connectToDevice(savedDevice)
                return
            }
            onLog("Saved MAC $savedMac not in bonded devices, will try direct connect")
            val remoteDevice = runCatching { adapter?.getRemoteDevice(savedMac) }.getOrNull()
            if (remoteDevice != null) {
                onLog("Trying direct connect to saved MAC: $savedMac")
                connectToDevice(remoteDevice)
                return
            }
        }

        val bondedByName = adapter.bondedDevices?.firstOrNull { isEp32Candidate(it) }
        if (bondedByName != null) {
            onLog("Trying bonded EP32 device: ${bondedByName.name} (${bondedByName.address})")
            saveLastMac(bondedByName.address)
            connectToDevice(bondedByName)
        } else {
            startDiscovery()
        }
    }

    fun stop() {
        active = false
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        cancelDiscoverySafe()
        unregisterReceiverIfNeeded()
        closeSocket()
        emitState(State.OFF, null)
    }

    fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && outputStream != null
    }

    fun sendCommand(command: String): Boolean {
        if (!isConnected()) {
            emitState(State.ERROR, "EP32 not connected")
            return false
        }
        ioScope.launch {
            try {
                outputStream?.write("$command\n".toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                onLog("EP32 command sent: $command")
            } catch (e: Exception) {
                onLog("EP32 command send error: ${e.message}")
                handleConnectionLost()
            }
        }
        return true
    }

    fun sendSequence(commands: List<String>, delayMs: Long = 180L): Boolean {
        if (!isConnected()) {
            emitState(State.ERROR, "EP32 not connected")
            return false
        }
        ioScope.launch {
            for ((index, cmd) in commands.withIndex()) {
                sendCommand(cmd)
                if (index < commands.lastIndex) {
                    delay(delayMs)
                }
            }
        }
        return true
    }

    private fun handleConnectionLost() {
        onLog("EP32 connection lost, closing socket and retrying...")
        closeSocket()
        emitState(State.ERROR, "EP32 connection lost")
        if (active) {
            reconnectJob?.cancel()
            reconnectJob = ioScope.launch {
                delay(RECONNECT_DELAY_MS)
                if (active && !isConnected()) {
                    mainHandler.post { startAutoConnect() }
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = ioScope.launch {
            while (isActive && active) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!active) break
                val s = socket
                if (s == null || !s.isConnected) {
                    onLog("Watchdog: socket dead, triggering reconnect")
                    handleConnectionLost()
                    break
                }
                val sinceLastInbound = System.currentTimeMillis() - lastInboundActivityMs
                if (lastInboundActivityMs > 0L && sinceLastInbound > INBOUND_SILENCE_TIMEOUT_MS) {
                    onLog("Watchdog: no JSONL data from ESP32 in ${sinceLastInbound}ms, reconnecting")
                    handleConnectionLost()
                    break
                }
            }
        }
    }

    private fun startReader() {
        readerJob?.cancel()
        val stream = inputStream ?: return
        lastInboundActivityMs = System.currentTimeMillis()
        readerJob = ioScope.launch {
            val reader = try {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            } catch (e: Exception) {
                onLog("EP32 reader init error: ${e.message}")
                return@launch
            }
            try {
                while (isActive && active) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        if (active) {
                            onLog("EP32 reader I/O error: ${e.message}")
                            handleConnectionLost()
                        }
                        return@launch
                    }
                    if (line == null) {
                        if (active) {
                            onLog("EP32 reader: stream closed by peer")
                            handleConnectionLost()
                        }
                        return@launch
                    }
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    lastInboundActivityMs = System.currentTimeMillis()
                    dispatchPayload(trimmed)
                }
            } catch (_: Exception) {
                // swallow; reconnect handled above
            }
        }
    }

    private fun dispatchPayload(line: String) {
        if (!line.startsWith("{")) {
            // Non-JSON line (e.g. boot banner). Surface to log but do not error.
            onLog("EP32 ignored non-JSON line: ${line.take(120)}")
            return
        }
        val json = try {
            JSONObject(line)
        } catch (e: JSONException) {
            onLog("EP32 JSON parse error: ${e.message}")
            onUnknownPayload(line)
            return
        }
        when (json.optString("type", "").lowercase()) {
            "heartbeat" -> {
                val hb = Heartbeat(
                    micCount = json.optInt("mic_count", 0),
                    firmware = json.optString("firmware", "").ifBlank { null }
                )
                lastHeartbeat = hb
                onLog("EP32 heartbeat: mics=${hb.micCount} fw=${hb.firmware ?: "?"}")
                onHeartbeat(hb)
            }
            "acoustic" -> {
                val ac = Acoustic(
                    detected = json.optBoolean("detected", false),
                    doaDeg = if (json.has("doa_deg")) json.optDouble("doa_deg").takeUnless { it.isNaN() } else null,
                    energy = if (json.has("energy")) json.optDouble("energy").takeUnless { it.isNaN() } else null,
                    confidence = if (json.has("confidence")) json.optDouble("confidence").takeUnless { it.isNaN() } else null,
                    micCount = if (json.has("mic_count")) json.optInt("mic_count") else null
                )
                lastAcoustic = ac
                onAcoustic(ac)
            }
            else -> {
                onUnknownPayload(line)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!active) return
        emitState(State.SCANNING, null)
        triedAddresses.clear()
        cancelDiscoverySafe()
        try {
            val started = adapter?.startDiscovery() == true
            onLog("EP32 discovery started: $started (cycle ${discoveryCycleCount + 1})")
            if (!started) {
                scheduleDiscoveryRestart()
            }
        } catch (e: Exception) {
            onLog("EP32 discovery error: ${e.message}")
            emitState(State.ERROR, "EP32 discovery failed")
            scheduleDiscoveryRestart()
        }
    }

    private fun scheduleDiscoveryRestart() {
        reconnectJob?.cancel()
        reconnectJob = ioScope.launch {
            delay(DISCOVERY_RESTART_DELAY_MS)
            if (active && !isConnected()) {
                startDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryAllBondedDevicesWithSpp() {
        if (!active || isConnected()) return
        val bonded = adapter?.bondedDevices ?: emptySet()
        onLog("Fallback: trying all ${bonded.size} bonded devices for SPP...")

        ioScope.launch {
            for (device in bonded) {
                if (!active || isConnected()) return@launch
                val name = device.name ?: "unknown"
                onLog("Fallback: trying bonded device $name (${device.address})")
                connectToDeviceSuspend(device)
                if (isConnected()) {
                    onLog("Fallback: connected to $name (${device.address})")
                    saveLastMac(device.address)
                    return@launch
                }
                delay(500)
            }
            if (active && !isConnected()) {
                onLog("Fallback: no bonded device worked, restarting discovery")
                discoveryCycleCount = 0
                mainHandler.post { startDiscovery() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!active) return
        emitState(State.CONNECTING, device.name ?: device.address)

        ioScope.launch {
            closeSocket()
            cancelDiscoverySafe()
            connectToDeviceSuspend(device)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDeviceSuspend(device: BluetoothDevice) {
        val sppUuid = UUID.fromString(SPP_UUID)

        val primarySocket = runCatching { device.createRfcommSocketToServiceRecord(sppUuid) }.getOrNull()
        if (primarySocket == null) {
            onLog("EP32 socket create failed for ${device.address}")
            if (active && !isConnected()) scheduleDiscoveryRestart()
            return
        }

        try {
            primarySocket.connect()
            onConnected(primarySocket, device)
            return
        } catch (firstError: Exception) {
            onLog("EP32 connect primary failed (${device.address}): ${firstError.message}")
            runCatching { primarySocket.close() }
        }

        val fallbackSocket = runCatching {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }.getOrNull()

        if (fallbackSocket == null) {
            onLog("EP32 fallback socket create failed for ${device.address}")
            if (active && !isConnected()) scheduleDiscoveryRestart()
            return
        }

        try {
            fallbackSocket.connect()
            onConnected(fallbackSocket, device)
        } catch (secondError: Exception) {
            onLog("EP32 connect fallback failed (${device.address}): ${secondError.message}")
            runCatching { fallbackSocket.close() }
            if (active && !isConnected()) scheduleDiscoveryRestart()
        }
    }

    private fun onConnected(connectedSocket: BluetoothSocket, device: BluetoothDevice) {
        socket = connectedSocket
        outputStream = connectedSocket.outputStream
        inputStream = connectedSocket.inputStream
        saveLastMac(device.address)
        emitState(State.CONNECTED, device.name ?: device.address)
        onLog("EP32 connected: ${device.name ?: "?"} (${device.address})")
        startReader()
        startWatchdog()
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscoverySafe() {
        runCatching {
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        }
    }

    private fun closeSocket() {
        watchdogJob?.cancel()
        watchdogJob = null
        readerJob?.cancel()
        readerJob = null
        lastInboundActivityMs = 0L
        runCatching { inputStream?.close() }
        inputStream = null
        runCatching { outputStream?.close() }
        outputStream = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun emitState(state: State, detail: String?) {
        mainHandler.post {
            onStateChanged(state, detail)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isEp32Candidate(device: BluetoothDevice): Boolean {
        val name = device.name?.lowercase() ?: return false
        return name.contains("esp32") || name.contains("ep32") || name.contains("adas3")
    }

    private fun saveLastMac(mac: String) {
        prefs.edit().putString(PREF_EP32_LAST_MAC, mac).apply()
        onLog("Saved EP32 MAC: $mac")
    }

    private fun getLastMac(): String? {
        return prefs.getString(PREF_EP32_LAST_MAC, null)
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        appContext.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    companion object {
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val PREF_EP32_LAST_MAC = "ep32_last_connected_mac"
        private const val DISCOVERY_RESTART_DELAY_MS = 3000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val WATCHDOG_INTERVAL_MS = 5000L
        private const val MAX_DISCOVERY_CYCLES_BEFORE_BONDED_FALLBACK = 3
        // ESP32 firmware should emit a heartbeat at least every ~10s, so the
        // watchdog gives generous margin before considering the link silent.
        private const val INBOUND_SILENCE_TIMEOUT_MS = 30_000L
    }
}
