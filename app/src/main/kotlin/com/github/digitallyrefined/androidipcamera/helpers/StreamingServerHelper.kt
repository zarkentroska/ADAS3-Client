package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.json.JSONException
import org.json.JSONObject

class StreamingServerHelper(
    private val context: Context,
    private val streamPort: Int = 8080,
    private val maxClients: Int = 3,
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val onTinySACommand: ((String) -> Unit)? = null,
    private val onDetectionEvent: ((DetectionEvent) -> Unit)? = null,
    private val getTinySAStatus: (() -> Boolean)? = null,
    private var bindIpAddress: String? = null  // null = todas las interfaces (0.0.0.0)
) {
    // Audio configuration (updated from preferences)
    var audioSampleRate: Int = 44100
    var audioChannels: Int = 1  // 1 = mono, 2 = stereo
    enum class ClientType {
        VIDEO, AUDIO, TINYSA_DATA, TINYSA_COMMAND
    }
    
    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter,
        val type: ClientType
    )

    data class DetectionEvent(
        val event: String,
        val time: String?,
        val timestamp: Double?,
        val confidence: Double?,
        val confidencePercent: Int?,
        val frequencyHz: Double?
    )

    private var serverSocket: ServerSocket? = null
    private var serverJob: kotlinx.coroutines.Job? = null
    @Volatile private var isServerRunning = false
    private val videoClients = CopyOnWriteArrayList<Client>()
    private val audioClients = CopyOnWriteArrayList<Client>()
    private val tinysaDataClients = CopyOnWriteArrayList<Client>()
    private val tinysaCommandClients = CopyOnWriteArrayList<Client>()

    fun getClients(): List<Client> = (videoClients + audioClients + tinysaDataClients + tinysaCommandClients).toList()
    fun getVideoClients(): List<Client> = videoClients.toList()
    fun getAudioClients(): List<Client> = audioClients.toList()
    fun getTinySADataClients(): List<Client> = tinysaDataClients.toList()
    fun getTinySACommandClients(): List<Client> = tinysaCommandClients.toList()
    
    /**
     * Cierra todas las conexiones activas del tipo TinySA data.
     * Útil cuando el cliente se reconecta para evitar errores 503.
     */
    fun dropTinySADataClients() {
        closeClientsOfType(ClientType.TINYSA_DATA)
    }

    /**
     * Cierra todas las conexiones activas para comandos TinySA.
     */
    fun dropTinySACommandClients() {
        closeClientsOfType(ClientType.TINYSA_COMMAND)
    }

    fun updateBindIpAddress(newIp: String?) {
        bindIpAddress = newIp
    }
    
    fun stopStreamingServer() {
        isServerRunning = false
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            onLog("Error closing server socket: ${e.message}")
        }
        serverSocket = null
        // Close all clients
        closeAllClients()
    }
    
    private fun closeAllClients() {
        (videoClients + audioClients + tinysaDataClients + tinysaCommandClients).forEach { client ->
            try {
                client.socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        videoClients.clear()
        audioClients.clear()
        tinysaDataClients.clear()
        tinysaCommandClients.clear()
    }
    
    fun startStreamingServer() {
        // Stop existing server if running
        if (isServerRunning) {
            stopStreamingServer()
        }
        isServerRunning = true
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val useCertificate = prefs.getBoolean("use_certificate", false)
                val certificatePath = if (useCertificate) prefs.getString("certificate_path", null) else null
                val certificatePassword = if (useCertificate) {
                    prefs.getString("certificate_password", "")?.let {
                        if (it.isEmpty()) null else it.toCharArray()
                    }
                } else null

                val bindAddress = InetAddress.getByName(bindIpAddress ?: "0.0.0.0")
                onLog("Starting server on ${bindIpAddress ?: "all interfaces (0.0.0.0)"}:$streamPort")

                serverSocket = if (certificatePath != null) {
                    try {
                        val uri = certificatePath.toUri()
                        val privateFile = File(context.filesDir, "certificate.p12")
                        if (privateFile.exists()) privateFile.delete()
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            privateFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Failed to open certificate file")
                        privateFile.inputStream().use { inputStream ->
                            val keyStore = KeyStore.getInstance("PKCS12")
                            keyStore.load(inputStream, certificatePassword)
                            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                            keyManagerFactory.init(keyStore, certificatePassword)
                            val sslContext = SSLContext.getInstance("TLSv1.2")
                            sslContext.init(keyManagerFactory.keyManagers, null, null)
                            val sslServerSocketFactory = sslContext.serverSocketFactory
                            (sslServerSocketFactory.createServerSocket(streamPort, 50, bindAddress) as SSLServerSocket).apply {
                                enabledProtocols = arrayOf("TLSv1.2")
                                enabledCipherSuites = supportedCipherSuites
                                reuseAddress = true
                                soTimeout = 30000
                            }
                        } ?: ServerSocket(streamPort, 50, bindAddress)
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            onLog("Failed to create SSL server socket: ${e.message}")
                            Toast.makeText(context, "Failed to create SSL server socket: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        ServerSocket(streamPort, 50, bindAddress)
                    }
                } else {
                    ServerSocket(streamPort, 50, bindAddress).apply {
                        reuseAddress = true
                        soTimeout = 0  // No timeout for server socket - wait indefinitely for connections
                    }
                }
                onLog("Server started on port $streamPort (${if (certificatePath != null) "HTTPS" else "HTTP"})")
                while (isServerRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        val outputStream = socket.getOutputStream()
                        val writer = PrintWriter(outputStream, true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        
                        // Read request line to get the path
                        val requestLine = reader.readLine() ?: continue
                        val path = requestLine.split(" ").getOrNull(1) ?: "/"
                        val method = requestLine.split(" ").getOrNull(0) ?: "GET"
                        
                        // Handle /tinysa/status endpoint
                        if (path.contains("/tinysa/status", ignoreCase = true)) {
                            val isConnected = getTinySAStatus?.invoke() ?: false
                            writer.print("HTTP/1.1 200 OK\r\n")
                            writer.print("Connection: close\r\n")
                            writer.print("Content-Type: application/json\r\n")
                            writer.print("Access-Control-Allow-Origin: *\r\n\r\n")
                            writer.print("{\"connected\":$isConnected}\r\n")
                            writer.flush()
                            socket.close()
                            continue
                        }
                        
                        // Determine client type based on path
                        val clientType = when {
                            path.contains("/tinysa/data", ignoreCase = true) -> ClientType.TINYSA_DATA
                            path.contains("/tinysa/command", ignoreCase = true) -> ClientType.TINYSA_COMMAND
                            path.contains("/audio", ignoreCase = true) -> ClientType.AUDIO
                            else -> ClientType.VIDEO
                        }
                        
                        // For TinySA data, ensure only one client by closing existing ones before proceeding
                        if (clientType == ClientType.TINYSA_DATA) {
                            closeClientsOfType(ClientType.TINYSA_DATA)
                        }
                        
                        // Check max clients based on type
                        val currentClients = when (clientType) {
                            ClientType.VIDEO -> videoClients
                            ClientType.AUDIO -> audioClients
                            ClientType.TINYSA_DATA -> tinysaDataClients
                            ClientType.TINYSA_COMMAND -> tinysaCommandClients
                        }
                        if (currentClients.size >= maxClients) {
                            writer.print("HTTP/1.1 503 Service Unavailable\r\n\r\n")
                            writer.flush()
                            socket.close()
                            continue
                        }
                        
                        val pref = PreferenceManager.getDefaultSharedPreferences(context)
                        val username = pref.getString("username", "") ?: ""
                        val password = pref.getString("password", "") ?: ""
                        
                        // Read remaining headers
                        val headers = mutableListOf<String>()
                        var line: String?
                        var contentLength = 0
                        while (reader.readLine().also { line = it } != null) {
                            if (line.isNullOrEmpty()) break
                            headers.add(line!!)
                            if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        
                        // Read request body for POST requests
                        var requestBody = ""
                        if (method == "POST" && contentLength > 0) {
                            val bodyBuffer = CharArray(contentLength)
                            var totalRead = 0
                            while (totalRead < contentLength) {
                                val read = reader.read(bodyBuffer, totalRead, contentLength - totalRead)
                                if (read <= 0) break
                                totalRead += read
                            }
                            requestBody = String(bodyBuffer, 0, totalRead)
                        }

                        // Dedicated endpoint for server -> client detection events.
                        if (path.contains("/adas3/detection-event", ignoreCase = true)) {
                            if (method != "POST") {
                                writer.print("HTTP/1.1 405 Method Not Allowed\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("Content-Type: application/json\r\n\r\n")
                                writer.print("{\"status\":\"method_not_allowed\"}\r\n")
                                writer.flush()
                                socket.close()
                                continue
                            }

                            val detectionEvent = parseDetectionEvent(requestBody)
                            if (detectionEvent != null) {
                                onDetectionEvent?.invoke(detectionEvent)
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("Content-Type: application/json\r\n\r\n")
                                writer.print("{\"status\":\"received\"}\r\n")
                            } else {
                                writer.print("HTTP/1.1 400 Bad Request\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("Content-Type: application/json\r\n\r\n")
                                writer.print("{\"status\":\"invalid_payload\"}\r\n")
                            }
                            writer.flush()
                            socket.close()
                            continue
                        }
                        
                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            val authHeader = headers.find { it.startsWith("Authorization: Basic ") }
                            if (authHeader == null) {
                                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                                writer.print("WWW-Authenticate: Basic realm=\"ADAS3\"\r\n")
                                writer.print("Connection: close\r\n\r\n")
                                writer.flush()
                                socket.close()
                                continue
                            }
                            val providedAuth = String(Base64.decode(authHeader.substring(21), Base64.DEFAULT))
                            if (providedAuth != "$username:$password") {
                                writer.print("HTTP/1.1 401 Unauthorized\r\n\r\n")
                                writer.flush()
                                socket.close()
                                continue
                            }
                        }
                        
                        // Send appropriate headers based on client type
                        when (clientType) {
                            ClientType.AUDIO -> {
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("Cache-Control: no-cache\r\n")
                                writer.print("Content-Type: audio/pcm; rate=$audioSampleRate; channels=$audioChannels\r\n\r\n")
                                writer.flush()
                                audioClients.add(Client(socket, outputStream, writer, clientType))
                                onLog("Audio client connected (rate=$audioSampleRate, channels=$audioChannels)")
                            }
                            ClientType.TINYSA_DATA -> {
                                // Solo permitimos un cliente TinySA data a la vez.
                                closeClientsOfType(ClientType.TINYSA_DATA)
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Connection: keep-alive\r\n")
                                writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                                writer.print("Content-Type: application/json\r\n\r\n")
                                writer.flush()
                                tinysaDataClients.add(Client(socket, outputStream, writer, clientType))
                                onLog("TinySA data client connected")
                            }
                            ClientType.TINYSA_COMMAND -> {
                                // Process command if body was provided
                                if (requestBody.isNotEmpty()) {
                                    onLog("TinySA command received: $requestBody")
                                    onTinySACommand?.invoke(requestBody)
                                }
                                
                                // Send response and close connection (one-shot command)
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("Content-Type: application/json\r\n\r\n")
                                writer.print("{\"status\":\"received\"}\r\n")
                                writer.flush()
                                socket.close()
                                onLog("TinySA command processed and connection closed")
                            }
                            else -> {
                                // Use HTTP/1.1 for proper keep-alive support
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Connection: keep-alive\r\n")
                                writer.print("Keep-Alive: timeout=60, max=1000\r\n")
                                writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                                writer.print("Pragma: no-cache\r\n")
                                writer.print("Expires: 0\r\n")
                                writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                                writer.flush()
                                if (writer.checkError()) {
                                    socket.close()
                                    continue
                                }
                                // Set socket options for keep-alive
                                try {
                                    socket.keepAlive = true
                                    socket.soTimeout = 0  // No timeout for streaming
                                    socket.tcpNoDelay = true  // Disable Nagle's algorithm for lower latency
                                } catch (e: Exception) {
                                    // Ignore if not supported
                                }
                                videoClients.add(Client(socket, outputStream, writer, clientType))
                                onLog("Video client connected")
                            }
                        }
                        onClientConnected()
                        val delay = pref.getString("stream_delay", "0")?.toLongOrNull() ?: 0L
                        Thread.sleep(delay)
                    } catch (e: IOException) {
                        // Ignore
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } catch (e: IOException) {
                onLog("Could not start server: ${e.message}")
            }
        }
    }

    fun closeClientConnection() {
        (videoClients + audioClients + tinysaDataClients + tinysaCommandClients).forEach { client ->
            try {
                client.socket.close()
            } catch (e: IOException) {
                onLog("Error closing client connection: ${e.message}")
            }
        }
        videoClients.clear()
        audioClients.clear()
        tinysaDataClients.clear()
        tinysaCommandClients.clear()
        onClientDisconnected()
    }

    fun removeClient(client: Client) {
        try {
            if (!client.socket.isClosed) {
                client.socket.close()
            }
        } catch (e: Exception) {
            // Ignore errors when closing
        }
        when (client.type) {
            ClientType.VIDEO -> videoClients.remove(client)
            ClientType.AUDIO -> audioClients.remove(client)
            ClientType.TINYSA_DATA -> tinysaDataClients.remove(client)
            ClientType.TINYSA_COMMAND -> tinysaCommandClients.remove(client)
        }
        onClientDisconnected()
    }

    private fun closeClientsOfType(type: ClientType) {
        val clients = when (type) {
            ClientType.VIDEO -> videoClients.toList()
            ClientType.AUDIO -> audioClients.toList()
            ClientType.TINYSA_DATA -> tinysaDataClients.toList()
            ClientType.TINYSA_COMMAND -> tinysaCommandClients.toList()
        }
        clients.forEach { removeClient(it) }
    }
    
    /**
     * Envía datos de TinySA a los clientes conectados
     */
    fun sendTinySAData(freqs: FloatArray, levels: FloatArray) {
        val toRemove = mutableListOf<Client>()
        tinysaDataClients.forEach { client ->
            try {
                // Formatear como JSON
                val json = buildString {
                    append("{\"freqs\":[")
                    freqs.forEachIndexed { i, freq ->
                        if (i > 0) append(",")
                        append(freq)
                    }
                    append("],\"levels\":[")
                    levels.forEachIndexed { i, level ->
                        if (i > 0) append(",")
                        append(level)
                    }
                    append("]}\n")
                }
                client.writer.print(json)
                client.writer.flush()
                client.outputStream.flush()  // Forzar flush del socket
            } catch (e: IOException) {
                toRemove.add(client)
                onLog("TinySA data client write failed: ${e.message}")
            }
        }
        toRemove.forEach { removeClient(it) }
    }
    
    fun sendAudioData(audioData: ByteArray) {
        val toRemove = mutableListOf<Client>()
        audioClients.forEach { client ->
            try {
                // Send raw PCM audio data
                client.outputStream.write(audioData)
                client.outputStream.flush()
            } catch (e: IOException) {
                toRemove.add(client)
            }
        }
        toRemove.forEach { removeClient(it) }
    }

    private fun parseDetectionEvent(requestBody: String): DetectionEvent? {
        return try {
            val json = JSONObject(requestBody)
            val type = json.optString("type", "")
            if (type.isNotEmpty() && !type.equals("adas3-server-detection", ignoreCase = true)) {
                return null
            }

            val event = json.optString("event", "").lowercase()
            if (event !in setOf("yolo", "tensorflow", "rf")) {
                return null
            }

            val timestamp = if (json.has("timestamp")) json.optDouble("timestamp") else Double.NaN
            val confidence = if (json.has("confidence")) json.optDouble("confidence") else Double.NaN
            val frequencyHz = if (json.has("frequency_hz")) json.optDouble("frequency_hz") else Double.NaN

            DetectionEvent(
                event = event,
                time = json.optString("time", "").ifBlank { null },
                timestamp = timestamp.takeUnless { it.isNaN() },
                confidence = confidence.takeUnless { it.isNaN() },
                confidencePercent = if (json.has("confidence_percent")) json.optInt("confidence_percent") else null,
                frequencyHz = frequencyHz.takeUnless { it.isNaN() }
            )
        } catch (_: JSONException) {
            null
        }
    }
}
