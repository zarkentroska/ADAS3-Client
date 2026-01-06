package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Helper para comunicación con TinySA Ultra vía USB Serial
 * 
 * Protocolo:
 * - Comandos: "scanraw {start} {stop} {points}\r", "abort\r"
 * - Respuestas: datos binarios entre { y }
 * - Formato: cada punto = 3 bytes (skip 1 byte, luego 2 bytes little-endian unsigned short)
 * - Conversión: levels = (value / 32.0) - 174.0 (dBm)
 */
class TinySAHelper(
    private val context: Context,
    private val onDataReady: (FloatArray, FloatArray) -> Unit, // (freqs, levels)
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "TinySAHelper"
        private const val TINYSA_VID = 0x0483
        private const val TINYSA_PID = 0x5740
        private const val BAUD_RATE = 921600
        private const val TIMEOUT_MS = 8000
    }

    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    @Volatile private var isRunning = false
    fun isConnected(): Boolean = usbSerialPort != null
    fun isScanning(): Boolean = isRunning
    private var hardwareJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    private val currentSequence = AtomicReference<List<ScanConfig>>(emptyList())
    private var sequenceIndex = 0
    private val sequenceLock = Any()
    
    data class ScanConfig(
        val start: Long,      // Hz
        val stop: Long,       // Hz
        val points: Int,
        val sweeps: Int = 5,
        val label: String = ""
    )

    /**
     * Busca el dispositivo TinySA conectado vía USB
     */
    fun findTinySADevice(): UsbSerialDriver? {
        try {
            usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                onLog("UsbManager no disponible")
                return null
            }
            
            // Primero buscar directamente en la lista de dispositivos USB
            try {
                usbManager?.deviceList?.values?.forEach { device ->
                    if (device.vendorId == TINYSA_VID && device.productId == TINYSA_PID) {
                        onLog("TinySA encontrado en deviceList: ${device.deviceName}")
                        // Verificar si tiene permiso
                        if (usbManager?.hasPermission(device) == true) {
                            // Buscar el driver correspondiente
                            try {
                                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                                availableDrivers.forEach { driver ->
                                    if (driver.device.deviceId == device.deviceId) {
                                        onLog("Driver encontrado para TinySA")
                                        return driver
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error buscando driver: ${e.message}", e)
                                // Continuar con otro método
                            }
                        } else {
                            onLog("TinySA encontrado pero sin permiso USB")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accediendo a deviceList: ${e.message}", e)
            }
            
            // También buscar con UsbSerialProber
            try {
                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                availableDrivers.forEach { driver ->
                    val device = driver.device
                    if (device.vendorId == TINYSA_VID && device.productId == TINYSA_PID) {
                        if (usbManager?.hasPermission(device) == true) {
                            onLog("TinySA encontrado con Prober: ${device.deviceName}")
                            return driver
                        } else {
                            onLog("TinySA encontrado con Prober pero sin permiso")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error usando UsbSerialProber: ${e.message}", e)
                // No es crítico, simplemente no hay TinySA conectado
            }
            
            onLog("TinySA no encontrado en ningún método")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando TinySA: ${e.message}", e)
            onLog("Error buscando: ${e.message}")
            return null
        }
    }

    /**
     * Abre la conexión USB Serial con TinySA
     */
    fun openConnection(): Boolean {
        try {
            val driver = findTinySADevice()
            if (driver == null) {
                onLog("TinySA no encontrado")
                return false
            }

            usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbConnection = usbManager?.openDevice(driver.device)
            
            if (usbConnection == null) {
                onLog("No se pudo abrir conexión USB (puede requerir permiso)")
                return false
            }

            val port = driver.ports[0] // TinySA generalmente usa el primer puerto
            port.open(usbConnection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            usbSerialPort = port
            onLog("Conexión TinySA establecida")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo conexión TinySA: ${e.message}", e)
            onLog("Error: ${e.message}")
            return false
        }
    }

    /**
     * Cierra la conexión USB
     */
    fun closeConnection() {
        try {
            isRunning = false
            hardwareJob?.cancel()
            
            usbSerialPort?.close()
            usbConnection?.close()
            usbSerialPort = null
            usbConnection = null
            
            onLog("Conexión TinySA cerrada")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando conexión: ${e.message}", e)
        }
    }

    /**
     * Configura la secuencia de barridos
     */
    fun setSequence(configs: List<ScanConfig>) {
        synchronized(sequenceLock) {
            currentSequence.set(configs)
            sequenceIndex = 0
        }
    }

    /**
     * Inicia el worker de hardware que ejecuta los barridos
     */
    fun startScanning() {
        if (isRunning) {
            return
        }

        if (usbSerialPort == null) {
            if (!openConnection()) {
                onLog("No se pudo iniciar: conexión no disponible")
                return
            }
        }

        isRunning = true
        hardwareJob = coroutineScope.launch {
            hardwareWorker()
        }
        onLog("TinySA scanning iniciado")
    }

    /**
     * Detiene el scanning
     */
    fun stopScanning() {
        isRunning = false
        hardwareJob?.cancel()
        sendCommand("abort\r")
        onLog("TinySA scanning detenido")
    }

    /**
     * Envía un comando al TinySA
     */
    private fun sendCommand(command: String) {
        try {
            val bytes = command.toByteArray(Charsets.US_ASCII)
            usbSerialPort?.write(bytes, TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando comando: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Drena cualquier dato pendiente en el buffer USB (rápido)
     */
    private fun drainBuffer() {
        try {
            val drainBuffer = ByteArray(2048)
            var attempts = 0
            // Máximo 5 intentos para ser rápido
            while (attempts < 5) {
                val bytesRead = usbSerialPort?.read(drainBuffer, 20) ?: -1
                if (bytesRead <= 0) break
                attempts++
            }
        } catch (_: Exception) {}
    }
    
    private fun recoverUsbPort(): Boolean {
        onLog("Intentando recuperar conexión USB...")
        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        try {
            usbConnection?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
        usbConnection = null
        
        // Pequeña pausa para que el dispositivo se estabilice
        Thread.sleep(100)
        
        val success = openConnection()
        if (success) {
            try {
                // Drenar cualquier dato residual
                drainBuffer()
                // Enviar abort para asegurar estado limpio
                sendCommand("abort\r")
                // Drenar respuesta del abort
                drainBuffer()
            } catch (_: Exception) {
            }
        } else {
            onLog("No se pudo reabrir TinySA")
        }
        return success
    }
    
    private fun handleUsbException(e: Exception): Boolean {
        Log.e(TAG, "USB error: ${e.message}", e)
        return if (recoverUsbPort()) {
            onLog("Conexión USB recuperada")
            true
        } else {
            onLog("Error crítico USB: ${e.message}")
            isRunning = false
            false
        }
    }

    /**
     * Lee datos del TinySA hasta encontrar un delimitador (para prompts)
     */
    private suspend fun readUntil(delimiter: ByteArray, timeoutMs: Long = 3000L): ByteArray? {
        val buffer = mutableListOf<Byte>()
        var delimiterIndex = 0
        
        try {
            val readBuffer = ByteArray(512)
            val startTime = System.currentTimeMillis()
            
            while (isRunning && (System.currentTimeMillis() - startTime) < timeoutMs) {
                val bytesRead = usbSerialPort?.read(readBuffer, 100) ?: -1
                
                if (bytesRead <= 0) {
                    kotlinx.coroutines.delay(5)
                    continue
                }
                
                for (i in 0 until bytesRead) {
                    buffer.add(readBuffer[i])
                    
                    if (readBuffer[i] == delimiter[delimiterIndex]) {
                        delimiterIndex++
                        if (delimiterIndex >= delimiter.size) {
                            return buffer.toByteArray()
                        }
                    } else {
                        delimiterIndex = 0
                        if (readBuffer[i] == delimiter[0]) {
                            delimiterIndex = 1
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en readUntil: ${e.message}", e)
        }
        
        return if (buffer.isNotEmpty()) buffer.toByteArray() else null
    }

    /**
     * Lee los datos de scanraw: busca '{', luego lee exactamente expectedDataBytes bytes
     * Esto evita el problema de que '}' aparezca en los datos binarios
     */
    private suspend fun readScanrawData(expectedPoints: Int, timeoutMs: Long = 5000L): ByteArray? {
        val expectedDataBytes = expectedPoints * 3  // 290 * 3 = 870 bytes
        val buffer = mutableListOf<Byte>()
        var foundStart = false
        var dataReceived = 0
        
        try {
            val readBuffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()
            
            while (isRunning && (System.currentTimeMillis() - startTime) < timeoutMs) {
                val bytesRead = usbSerialPort?.read(readBuffer, 200) ?: -1
                
                if (bytesRead <= 0) {
                    kotlinx.coroutines.delay(10)
                    continue
                }
                
                for (i in 0 until bytesRead) {
                    if (!foundStart) {
                        // Buscar el byte '{'
                        if (readBuffer[i] == '{'.code.toByte()) {
                            foundStart = true
                            buffer.add(readBuffer[i])
                        }
                    } else {
                        // Ya encontramos '{', ahora leer exactamente expectedDataBytes
                        if (dataReceived < expectedDataBytes) {
                            buffer.add(readBuffer[i])
                            dataReceived++
                        } else {
                            // Ya tenemos todos los datos, el siguiente debería ser '}'
                            buffer.add(readBuffer[i])
                            Log.d(TAG, "Scanraw completo: ${buffer.size} bytes, $dataReceived datos")
                            return buffer.toByteArray()
                        }
                    }
                }
            }
            
            if (foundStart && dataReceived > 0) {
                Log.w(TAG, "Scanraw parcial: $dataReceived de $expectedDataBytes bytes")
                return buffer.toByteArray()
            }
            
            Log.w(TAG, "Scanraw timeout: foundStart=$foundStart, dataReceived=$dataReceived")
        } catch (e: Exception) {
            Log.e(TAG, "Error en readScanrawData: ${e.message}", e)
        }
        
        return null
    }

    /**
     * Worker principal que ejecuta los barridos
     * Copia la lógica del worker serial de Python para evitar crashes
     */
    private suspend fun hardwareWorker() {
        try {
            if (usbSerialPort == null || !isRunning) {
                return
            }

            // Reset buffer y abortar cualquier operación pendiente
            try {
                usbSerialPort?.purgeHwBuffers(true, true)
            } catch (_: Exception) {}
            
            try {
                sendCommand("abort\r")
            } catch (_: Exception) {}
            
            // Esperar brevemente y limpiar respuesta
            kotlinx.coroutines.delay(50)
            drainBuffer()

            while (coroutineScope.isActive && isRunning) {
                val sequence = currentSequence.get()
                if (sequence.isEmpty()) {
                    kotlinx.coroutines.delay(100)
                    continue
                }

                val config: ScanConfig
                synchronized(sequenceLock) {
                    if (sequenceIndex >= sequence.size) {
                        sequenceIndex = 0
                    }
                    config = sequence[sequenceIndex]
                }

                val cmd = "scanraw ${config.start} ${config.stop} ${config.points}\r"
                var sweepsDone = 0

                // Loop de barridos - lee cantidad FIJA de bytes para evitar falsos '}'
                while (coroutineScope.isActive && isRunning && sweepsDone < config.sweeps) {
                    try {
                        sendCommand(cmd)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enviando comando: ${e.message}")
                        if (handleUsbException(e)) {
                            kotlinx.coroutines.delay(50)
                            continue
                        } else {
                            return
                        }
                    }

                    // Leer exactamente points*3 bytes después de '{' 
                    val rawBlock = readScanrawData(config.points, 5000L)
                    
                    if (rawBlock == null || rawBlock.size < config.points * 3) {
                        Log.w(TAG, "Datos insuficientes: ${rawBlock?.size ?: 0} bytes")
                        drainBuffer()
                        kotlinx.coroutines.delay(50)
                        continue
                    }

                    // Extraer datos: primer byte es '{', luego los datos
                    val dataBytes = if (rawBlock[0] == '{'.code.toByte()) {
                        rawBlock.sliceArray(1 until minOf(rawBlock.size, 1 + config.points * 3))
                    } else {
                        rawBlock.sliceArray(0 until minOf(rawBlock.size, config.points * 3))
                    }

                    val nPoints = dataBytes.size / 3
                    if (nPoints < 10) {
                        Log.w(TAG, "Muy pocos puntos: $nPoints")
                        kotlinx.coroutines.delay(20)
                        continue
                    }

                    val trimmedBytes = dataBytes.sliceArray(0 until (nPoints * 3))

                    try {
                        val levels = FloatArray(nPoints)
                        val buffer = ByteBuffer.wrap(trimmedBytes).order(ByteOrder.LITTLE_ENDIAN)
                        
                        for (i in 0 until nPoints) {
                            buffer.position(i * 3 + 1)
                            val value = buffer.short.toInt() and 0xFFFF
                            levels[i] = (value / 32.0f) - 174.0f
                        }

                        val freqs = FloatArray(nPoints)
                        val startFreq = config.start.toFloat()
                        val stopFreq = config.stop.toFloat()
                        for (i in 0 until nPoints) {
                            freqs[i] = startFreq + (stopFreq - startFreq) * i / (nPoints - 1)
                        }

                        Log.d(TAG, "Sweep OK: $nPoints puntos")
                        onDataReady(freqs, levels)
                        
                        // Delay para espaciar envíos y evitar buffering TCP
                        kotlinx.coroutines.delay(200)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando: ${e.message}")
                        drainBuffer()
                        kotlinx.coroutines.delay(20)
                        continue
                    }

                    // Leer prompt 'ch> ' con timeout corto (solo para limpiar buffer)
                    try {
                        readUntil("ch> ".toByteArray(Charsets.US_ASCII), 300L)
                    } catch (_: Exception) {
                        // Ignorar timeout, continuar con siguiente barrido
                    }
                    
                    sweepsDone++
                }

                synchronized(sequenceLock) {
                    sequenceIndex = (sequenceIndex + 1) % sequence.size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico en hardware worker: ${e.message}", e)
            onLog("Error crítico: ${e.message}")
        }
    }
}

