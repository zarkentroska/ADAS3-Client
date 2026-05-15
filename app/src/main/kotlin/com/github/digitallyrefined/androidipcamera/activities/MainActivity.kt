package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import com.github.digitallyrefined.androidipcamera.helpers.AudioCaptureHelper
import com.github.digitallyrefined.androidipcamera.helpers.CameraResolutionHelper
import com.github.digitallyrefined.androidipcamera.helpers.Ep32BluetoothHelper
import com.github.digitallyrefined.androidipcamera.helpers.LanDiscoveryBeaconHelper
import com.github.digitallyrefined.androidipcamera.helpers.MicArrayWiring
import com.github.digitallyrefined.androidipcamera.helpers.StreamingServerHelper
import com.github.digitallyrefined.androidipcamera.helpers.TinySACommandParser
import com.github.digitallyrefined.androidipcamera.helpers.TinySAHelper
import com.github.digitallyrefined.androidipcamera.helpers.convertNV21toJPEG
import com.github.digitallyrefined.androidipcamera.helpers.convertYUV420toNV21
import com.github.digitallyrefined.androidipcamera.SettingsDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var streamingServerHelper: StreamingServerHelper? = null
    private var hasRequestedPermissions = false
    private var cameraResolutionHelper: CameraResolutionHelper? = null
    private var lastFrameTime = 0L
    private var audioCaptureHelper: AudioCaptureHelper? = null
    private var isAudioEnabled = false
    private var tinySAHelper: TinySAHelper? = null
    private var connectivityReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkState: String? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private val tinysaCheckHandler = Handler(Looper.getMainLooper())
    private var tinysaCheckRunnable: Runnable? = null
    private var isTinySAConnected = false
    private var usbReceiver: BroadcastReceiver? = null
    private val USB_PERMISSION_REQUEST = 100
    private val tailscaleUpdateHandler = Handler(Looper.getMainLooper())
    private var tailscaleUpdateRunnable: Runnable? = null
    private val ipAutoRefreshHandler = Handler(Looper.getMainLooper())
    private var ipAutoRefreshRunnable: Runnable? = null
    @Volatile private var hasServerConnection = false
    private var hasRequestedAudioPermission = false
    private var currentServerBindIp: String? = null
    private var shouldRestartServerOnResume = false
    private var lanDiscoveryBeaconHelper: LanDiscoveryBeaconHelper? = null
    private var ep32BluetoothHelper: Ep32BluetoothHelper? = null
    private var serverConnectionStatusText: TextView? = null
    private var detectionSessionText: TextView? = null
    private var ep32BluetoothSwitch: Switch? = null
    private var ep32BluetoothStatusText: TextView? = null
    private var ep32ControlPanel: View? = null
    private var isEp32Enabled = false

    // ───── Selector de fuente de audio para Keras (phone vs ESP32 array) ─────
    // El servidor consume PCM16 mono 44100 vía /audio. Cuando la fuente es el
    // micro del móvil, AudioCaptureHelper alimenta sendAudioData directamente.
    // Cuando la fuente es el array ESP32, recibimos PCM16 mono a 8 kHz por SPP
    // (firmware AUDIO_ON), lo upsampleamos a 44100 con interpolación lineal y
    // lo metemos por el mismo sendAudioData. El servidor no nota la diferencia
    // de origen salvo por las cabeceras `X-Audio-Source` / `Content-Type` que
    // StreamingServerHelper inyecta. La preferencia persiste en SharedPrefs.
    private var audioSource: String = AUDIO_SOURCE_PHONE_MIC
    private var lastArrayAudioFrameTs: Long = 0L
    private var lastArrayAudioSampleRate: Int = 0
    private val arrayAudioFramesIn = java.util.concurrent.atomic.AtomicLong(0)
    private val arrayAudioFramesForwarded = java.util.concurrent.atomic.AtomicLong(0)
    private val arrayAudioFramesPrefetchBuffered = java.util.concurrent.atomic.AtomicLong(0)
    private val arrayAudioFramesDropped = java.util.concurrent.atomic.AtomicLong(0)
    private val arrayAudioBytesForwarded = java.util.concurrent.atomic.AtomicLong(0)
    private val phoneMicBytesSent = java.util.concurrent.atomic.AtomicLong(0)
    private val audioStatusStartMs: Long = System.currentTimeMillis()
    @Volatile private var arrayUpsamplerLastSample: Short = 0  // estado interp.

    // Cola acotada (back-pressure) entre el reader Bluetooth (productor) y el
    // worker que upsamplea+envía al server (consumidor). Tamaño 4 frames
    // ~= 400 ms de buffer a 100 ms/frame. Cuando se llena, descartamos los
    // frames más antiguos: para Keras importa la última ventana, no la cola
    // histórica. El reader nunca se bloquea, así que las flechas y el
    // resto del puente BT no se ralentizan.
    private val arrayAudioQueue =
        java.util.concurrent.ArrayBlockingQueue<Ep32BluetoothHelper.AudioFrame>(4)
    @Volatile private var arrayAudioWorker: Thread? = null
    private val arrayAudioWorkerRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var yoloDetections = 0
    private var tensorflowDetections = 0
    private var rfDetections = 0
    private val recentDetectionEvents = mutableListOf<String>()
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "image_quality", "stream_delay" -> {
                // These changes don't require camera restart, just log
                Log.d(TAG, "Preference changed: $key")
            }
            "camera_resolution" -> {
                // Resolution change requires camera restart (handled in SettingsActivity)
                Log.d(TAG, "Resolution changed, will restart camera")
            }
            PREF_AUDIO_SOURCE -> {
                val newValue = prefs?.getString(PREF_AUDIO_SOURCE, AUDIO_SOURCE_PHONE_MIC)
                    ?: AUDIO_SOURCE_PHONE_MIC
                if (newValue != audioSource) {
                    // applyAudioSource ya persiste, pero al venir el cambio del
                    // Settings dialog la preferencia YA está escrita; usamos
                    // persist=false para no escribir dos veces.
                    runOnUiThread { applyAudioSource(newValue, persist = false) }
                }
            }
        }
    }

    private fun processImage(image: ImageProxy) {
        try {
            // Get delay from preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val actualDelay = prefs.getString("stream_delay", "0")?.toLongOrNull() ?: 0L

            // Check if enough time has passed since last frame
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < actualDelay) {
                image.close()
                return
            }
            lastFrameTime = currentTime

            // Convert YUV_420_888 to NV21
            val nv21 = convertYUV420toNV21(image)

            // Get JPEG quality from preferences with safe fallback
            val imageQuality = try {
                val quality = prefs.getInt("image_quality", 40)
                quality.coerceIn(0, 100)
            } catch (e: ClassCastException) {
                // SeekBarPreference might store as String in some cases
                try {
                    prefs.getString("image_quality", "40")?.toIntOrNull()?.coerceIn(0, 100) ?: 40
                } catch (e2: Exception) {
                    40
                }
            } catch (e: Exception) {
                40
            }
            
            // Convert NV21 to JPEG with quality from preferences
            val jpegBytes = convertNV21toJPEG(nv21, image.width, image.height, imageQuality)

            val videoClients = streamingServerHelper?.getVideoClients()
            if (videoClients != null && videoClients.isNotEmpty()) {
                    val toRemove = mutableListOf<StreamingServerHelper.Client>()
                    videoClients.forEach { client ->
                        try {
                            // Send MJPEG frame only to video clients
                            client.writer.print("--frame\r\n")
                            client.writer.print("Content-Type: image/jpeg\r\n")
                            client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                            client.writer.flush()
                            client.outputStream.write(jpegBytes)
                            client.outputStream.flush()
                        } catch (e: IOException) {
                            Log.e(TAG, "Error sending frame: ${e.message}")
                            try {
                                client.socket.close()
                            } catch (e: IOException) {
                                Log.e(TAG, "Error closing client: ${e.message}")
                            }
                            toRemove.add(client)
                        }
                    }
                    toRemove.forEach { streamingServerHelper?.removeClient(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processImage: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply language before creating views
        applyLanguage()
        
        super.onCreate(savedInstanceState)

        // Initialize view binding first
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Hide the action bar
        supportActionBar?.hide()

        // Set full screen flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }


        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions before starting camera
        if (!allPermissionsGranted() && !hasRequestedPermissions) {
            hasRequestedPermissions = true
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else if (allPermissionsGranted()) {
            startCamera()
        } else {
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Register preference change listener
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        // Audio settings: fixed to Mono 44100 Hz
        val audioSampleRatePref = 44100
        val audioChannelsPref = 1  // 1 = mono
        
        // Get stream port from preferences
        val streamPort = getStreamPort()
        
        // Get saved IP address or use first available
        val prefsForAudio = PreferenceManager.getDefaultSharedPreferences(this)
        val savedBindIp = prefsForAudio.getString("selected_bind_ip", null)
        val availableIps = getAllLocalIpAddresses()
        val firstAvailableIp = availableIps.firstOrNull()
        
        // Handle ADB option: convert "ADB" to "127.0.0.1"
        // If no IP is saved, use first available (or ADB if available)
        val bindIp = when (savedBindIp) {
            "ADB", "127.0.0.1" -> "127.0.0.1"
            null -> firstAvailableIp ?: "127.0.0.1"
            else -> savedBindIp
        } ?: firstAvailableIp ?: "127.0.0.1"
        
        // Start streaming server with TinySA command handler
        streamingServerHelper = StreamingServerHelper(
            this, 
            streamPort,
            maxClients = MAX_CLIENTS,
            onLog = { Log.d(TAG, it) },
            onClientConnected = { handleStreamingClientConnected() },
            onClientDisconnected = { handleStreamingClientDisconnected() },
            onTinySACommand = { commandBody ->
                handleTinySACommand(commandBody)
            },
            onDetectionEvent = { detectionEvent ->
                handleDetectionEvent(detectionEvent)
            },
            onEp32Command = { commandRequest ->
                handleEp32CommandRequest(commandRequest)
            },
            onEp32Control = { controlRequest ->
                handleEp32ControlRequest(controlRequest)
            },
            getTinySAStatus = {
                isTinySAConnected
            },
            getMicArrayStatus = {
                buildMicArrayStatusJson()
            },
            getEp32Status = {
                buildEp32StatusJson()
            },
            getAudioSourceStatus = {
                buildAudioSourceStatusJson()
            },
            onAudioSourceRequest = { canonical ->
                runOnUiThread { applyAudioSource(canonical) }
                true
            },
            bindIpAddress = bindIp
        )
        currentServerBindIp = bindIp
        // Configure audio settings for HTTP headers (fixed to Mono 44100 Hz)
        streamingServerHelper?.audioSampleRate = audioSampleRatePref
        streamingServerHelper?.audioChannels = audioChannelsPref
        lanDiscoveryBeaconHelper = LanDiscoveryBeaconHelper(
            onLog = { Log.d(TAG, "[Discovery] $it") }
        )
        
        lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.startStreamingServer() }
        startLanDiscoveryBeacon()
        
        // Initialize TinySA helper (with error handling)
        try {
            tinySAHelper = TinySAHelper(
                this,
                onDataReady = { freqs, levels ->
                    // Send data to connected clients
                    streamingServerHelper?.sendTinySAData(freqs, levels)
                },
                onLog = { message ->
                    Log.d(TAG, "[TinySA] $message")
                    runOnUiThread {
                        if (message.contains("Conexión TinySA establecida", ignoreCase = true)) {
                            showTinySAStatus(true)
                            Toast.makeText(this, getString(R.string.toast_tinysa_connected), Toast.LENGTH_SHORT).show()
                        } else if (message.contains("Conexión TinySA cerrada", ignoreCase = true)) {
                            showTinySAStatus(false)
                        }
                    }
                }
            )
            
            // Register USB device receiver
            try {
                registerUsbReceiver()
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando USB receiver: ${e.message}", e)
            }
            
            // Start checking for TinySA connection periodically
            startTinySAConnectionCheck()
            
            // Check for already connected TinySA devices (delayed to avoid blocking startup)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    checkTinySAConnection()
                } catch (e: Exception) {
                    Log.e(TAG, "Error en check inicial TinySA: ${e.message}", e)
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando TinySA helper: ${e.message}", e)
            // Continue without TinySA support
        }

        // Find the Spinner and TextView
        val ipAddressSpinner = findViewById<android.widget.Spinner>(R.id.ipAddressSpinner)
        serverConnectionStatusText = findViewById(R.id.serverConnectionStatusText)
        detectionSessionText = findViewById(R.id.detectionSessionText)
        ep32BluetoothSwitch = findViewById(R.id.ep32BluetoothSwitch)
        ep32BluetoothStatusText = findViewById(R.id.ep32BluetoothStatusText)
        ep32ControlPanel = findViewById(R.id.ep32ControlPanel)
        findViewById<ImageButton>(R.id.detectionDetailsButton)?.setOnClickListener {
            showDetectionDetailsDialog()
        }
        updateServerConnectionIndicator(hasServerConnection)
        updateDetectionSessionSummary()
        setupEp32Controls()

        // Get and display all IP addresses
        val ipAddresses = getAllLocalIpAddresses()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Get stream port from preferences for spinner
        val streamPortForSpinner = getStreamPort()
        
        // Configure IP address spinner (only IPs, no "Todas") - show IP:port
        val spinnerItems = buildIpSpinnerItems(ipAddresses, streamPortForSpinner)
        
        // Create adapter with dark theme colors
        val adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            spinnerItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(Color.parseColor("#FFFFFF"))
                textView.textSize = 14f
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                view.setBackgroundColor(Color.parseColor("#2A2A30"))
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(Color.parseColor("#E0E0E0"))
                textView.textSize = 14f
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipAddressSpinner.adapter = adapter
        
        // Load saved IP selection - DO NOT change it automatically
        val savedIp = prefs.getString("selected_bind_ip", null)
        
        // Try to find saved IP in spinner items
        val ipToUse = savedIp?.let { 
            if (savedIp == "ADB" || savedIp == "127.0.0.1") {
                "ADB (127.0.0.1:$streamPortForSpinner)"
            } else {
                // Check if it's a Tailscale IP
                if (savedIp.startsWith("100.")) {
                    "$savedIp:$streamPortForSpinner (Tailscale)"
                } else {
                    "$savedIp:$streamPortForSpinner"
                }
            }
        }?.takeIf { spinnerItems.contains(it) }
        
        // Find index of saved IP
        val selectedIndex = if (ipToUse != null) {
            // Use saved IP if found
            spinnerItems.indexOfFirst { item -> item == ipToUse }.takeIf { it >= 0 } ?: 0
        } else if (savedIp != null) {
            // If saved IP is not in the list but exists, keep it and select first non-ADB IP as fallback
            // But don't save it - keep the original saved IP
            val nonAdbIndex = spinnerItems.indexOfFirst { !it.startsWith("ADB") }.takeIf { it >= 0 } ?: 0
            nonAdbIndex
        } else {
            // Only if no IP was ever saved, use first non-ADB IP and save it
            val firstNonAdbItem = spinnerItems.firstOrNull { !it.startsWith("ADB") } ?: spinnerItems.firstOrNull() ?: ""
            val firstNonAdbIndex = spinnerItems.indexOf(firstNonAdbItem).takeIf { it >= 0 } ?: 0
            if (firstNonAdbItem.isNotEmpty()) {
                val firstIp = if (firstNonAdbItem.startsWith("ADB")) {
                    "ADB"
                } else {
                    firstNonAdbItem.substringBefore(":").substringBefore(" (")
                }
                if (firstIp.isNotEmpty()) {
                    prefs.edit().putString("selected_bind_ip", firstIp).apply()
                }
            }
            firstNonAdbIndex
        }
        
        ipAddressSpinner.setSelection(selectedIndex)
        syncServerBindWithSpinnerSelection(ipAddressSpinner)
        
        // Setup refresh button
        val refreshIpButton = findViewById<ImageButton>(R.id.refreshIpButton)
        refreshIpButton?.setOnClickListener {
            refreshIpSpinner()
        }
        
        // Setup Tailscale switch
        setupTailscaleSwitch()
        
        // Handle IP selection change - only when user manually changes it
        var isInitialSelection = true
        ipAddressSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedIpWithPort = parent?.getItemAtPosition(position)?.toString() ?: return
                
                // Skip the initial selection event when setting up the spinner
                if (isInitialSelection) {
                    isInitialSelection = false
                    return
                }
                
                val selectedIp = if (selectedIpWithPort.startsWith("ADB")) {
                    "ADB"
                } else {
                    // Extract IP, removing port and (Tailscale) label
                    selectedIpWithPort.substringBefore(":").substringBefore(" (")
                }
                
                // Save IP (use "ADB" for ADB option, or actual IP for others)
                val ipToSave = if (selectedIpWithPort.startsWith("ADB")) {
                    "ADB"
                } else {
                    selectedIp
                }
                val previousIp = prefs.getString("selected_bind_ip", null)
                if (previousIp == ipToSave) {
                    return
                }
                prefs.edit().putString("selected_bind_ip", ipToSave).apply()
                
                // Determine bind IP: use 127.0.0.1 for ADB, otherwise use selected IP
                val bindIp = if (selectedIpWithPort.startsWith("ADB")) "127.0.0.1" else selectedIp
                updateServerBindIfNeeded(bindIp)
                
                Log.d(TAG, "User changed bind IP to: $bindIp (${if (selectedIpWithPort.startsWith("ADB")) "ADB mode" else "normal"})")
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Mark initial selection as complete after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            isInitialSelection = false
        }, 500)

        // Add toggle preview button
        findViewById<ImageButton>(R.id.hidePreviewButton).setOnClickListener {
            hidePreview()
        }

        // Add switch camera button handler
        findViewById<ImageButton>(R.id.switchCameraButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            // Reset resolution helper to detect new camera's resolutions
            cameraResolutionHelper = null
            startCamera()
        }

        // Add settings button - show as dialog instead of new activity
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            SettingsDialogFragment.show(supportFragmentManager)
        }
        
        // Initialize audio capture helper with fixed settings: Mono 44100 Hz
        val audioSampleRate = 44100
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        audioCaptureHelper = AudioCaptureHelper(audioSampleRate, channelConfig)
        // El listener vuelve a ser exactamente el original: NO filtra por
        // audio_source. La garantía de single-stream se hace ARRIBA: cuando
        // la fuente es esp32_array, llamamos a stopRecording() y AudioRecord
        // deja de generar bytes. Filtrar aquí era frágil y abría la puerta
        // a "audio activado pero micro no envia nada" cuando el flag local
        // y la preferencia se desincronizaban (causa del bug reportado).
        audioCaptureHelper?.addAudioDataListener { audioData ->
            if (isAudioEnabled &&
                streamingServerHelper?.getAudioClients()?.isNotEmpty() == true) {
                streamingServerHelper?.sendAudioData(audioData)
                phoneMicBytesSent.addAndGet(audioData.size.toLong())
            }
        }
        // Carga inicial de la fuente persistida. Sólo lectura: la transición
        // efectiva (start/stop AudioRecord + AUDIO_ON/OFF al ESP32) se hace
        // a través de applyAudioSource() cuando la UI esté lista para
        // mostrar toasts.
        audioSource = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_AUDIO_SOURCE, AUDIO_SOURCE_PHONE_MIC)
            ?: AUDIO_SOURCE_PHONE_MIC
        
        // Add audio toggle button
        val audioToggleButton = findViewById<ImageButton>(R.id.audioToggleButton)
        updateAudioButtonIcon(audioToggleButton)
        audioToggleButton.setOnClickListener {
            toggleAudio()
            updateAudioButtonIcon(audioToggleButton)
        }
        enableAudioByDefault(audioToggleButton)
        
        // Register connectivity change listener
        registerConnectivityListener()
        startAutoIpRefreshUntilConnected()
    }
    
    private fun registerConnectivityListener() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Store initial network state
        lastNetworkState = getCurrentNetworkState(connectivityManager)
        Log.d(TAG, "Initial network state: $lastNetworkState")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use NetworkCallback for Android 7.0+
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    handleConnectivityChange(connectivityManager)
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    handleConnectivityChange(connectivityManager)
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    Log.d(TAG, "Network capabilities changed: $network")
                    handleConnectivityChange(connectivityManager)
                }
            }
            
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_USB)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } else {
            // Use BroadcastReceiver for older Android versions
            connectivityReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "Connectivity broadcast received")
                    handleConnectivityChange(connectivityManager)
                }
            }
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            registerReceiver(connectivityReceiver, filter)
        }
    }
    
    private fun getCurrentNetworkState(connectivityManager: ConnectivityManager): String {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val ipAddress = getLocalIpAddress()
            
            // Check if ADB is connected by looking for USB interfaces
            var isAdbConnected = false
            try {
                NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                    val name = networkInterface.name.lowercase()
                    if (name.contains("rndis") || name.contains("usb")) {
                        networkInterface.inetAddresses.toList().forEach { address ->
                            if (address is Inet4Address) {
                                val ip = address.hostAddress ?: ""
                                if (!ip.startsWith("127.") && ip != "0.0.0.0" && ip != "unknown") {
                                    isAdbConnected = true
                                    Log.d(TAG, "ADB detected via interface: $name, IP: $ip")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking ADB: ${e.message}")
            }
            
            // Include ADB state in the network state string
            val state = "$ipAddress-${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false}-${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ?: false}-ADB:$isAdbConnected"
            Log.d(TAG, "Current network state: $state")
            state
        } catch (e: Exception) {
            val ipAddress = getLocalIpAddress()
            var isAdbConnected = false
            try {
                NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                    val name = networkInterface.name.lowercase()
                    if (name.contains("rndis") || name.contains("usb")) {
                        isAdbConnected = true
                    }
                }
            } catch (e2: Exception) {
                // Ignore
            }
            "$ipAddress-ADB:$isAdbConnected"
        }
    }
    
    private fun handleConnectivityChange(connectivityManager: ConnectivityManager) {
        val currentState = getCurrentNetworkState(connectivityManager)
        
        // Only restart if the state actually changed
        if (currentState != lastNetworkState && lastNetworkState != null) {
            Log.d(TAG, "Network state changed from $lastNetworkState to $currentState, restarting app...")
            lastNetworkState = currentState
            
            // Cancel any pending restart
            restartRunnable?.let { restartHandler.removeCallbacks(it) }
            
            // Schedule restart with a small delay to avoid multiple restarts
            restartRunnable = Runnable {
                restartApp()
            }
            restartHandler.postDelayed(restartRunnable!!, 1000) // 1 second delay
        } else {
            lastNetworkState = currentState
        }
    }
    
    private fun restartApp() {
        Log.d(TAG, "Restarting app due to connectivity change")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    // Add this method to handle permission results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                findViewById<ImageButton>(R.id.audioToggleButton)?.let { enableAudioByDefault(it) }
                ensureAudioPermissionRequested()
            } else {
                // Show which permissions are missing
                REQUIRED_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(baseContext, it) != PackageManager.PERMISSION_GRANTED
                }
                Toast.makeText(this,
                    "Please allow camera permissions",
                    Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Sólo abrimos AudioRecord si la fuente activa es el mic
                // del móvil. Si la fuente es el array, RECORD_AUDIO está
                // concedido pero el mic no debe abrirse — el array es la
                // fuente.
                if (audioSource == AUDIO_SOURCE_PHONE_MIC) {
                    if (audioCaptureHelper?.startRecording() == true) {
                        isAudioEnabled = true
                        findViewById<ImageButton>(R.id.audioToggleButton)?.let { updateAudioButtonIcon(it) }
                        Toast.makeText(this, getString(R.string.toast_audio_enabled), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Permiso concedido pero la fuente es el array: sólo
                    // marcamos audio activado y dejamos que applyAudioSource
                    // mande AUDIO_ON cuando proceda.
                    isAudioEnabled = true
                    findViewById<ImageButton>(R.id.audioToggleButton)?.let { updateAudioButtonIcon(it) }
                    applyAudioSource(audioSource, persist = false)
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_audio_permission_required), Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CODE_EP32_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                if (ep32BluetoothSwitch?.isChecked == true) {
                    startEp32AutoConnectIfAllowed()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_ep32_permission_required), Toast.LENGTH_SHORT).show()
                ep32BluetoothSwitch?.isChecked = false
                updateEp32UiState(Ep32BluetoothHelper.State.OFF)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return getAllLocalIpAddresses().firstOrNull() ?: "unknown"
    }
    
    private fun getAllLocalIpAddresses(): List<String> {
        val ipAddresses = mutableListOf<String>()
        try {
            var usbIp: String? = null
            val tailscaleIps = mutableListOf<String>()
            val wifiLanIps = mutableListOf<String>()
            val mobileDataIps = mutableListOf<String>()
            
            // Scan all network interfaces
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isUp = networkInterface.isUp
                
                if (!isUp) {
                    return@forEach
                }
                
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip != "0.0.0.0" && !ip.startsWith("127.")) {
                            // USB/ADB interfaces
                            if (name.contains("rndis") || name.contains("usb") || 
                                (name.contains("eth") && !name.contains("wlan"))) {
                                usbIp = ip
                                Log.d(TAG, "Found USB/ADB interface: $name with IP: $ip")
                            }
                            // Tailscale interfaces
                            else if (name.contains("tailscale") || name.contains("ts") || ip.startsWith("100.")) {
                                tailscaleIps.add(ip)
                                Log.d(TAG, "Found Tailscale interface: $name with IP: $ip")
                            }
                            // Datos móviles (rmnet, ccmni, pdp, etc.)
                            else if (name.contains("rmnet") || name.contains("ccmni") || 
                                     name.contains("pdp") || name.contains("ppp") ||
                                     name.contains("wwan") || name.contains("rmnet_data")) {
                                mobileDataIps.add(ip)
                                Log.d(TAG, "Found mobile data interface: $name with IP: $ip")
                            }
                            // WiFi/LAN interfaces
                            else if (name.contains("wlan") || name.contains("wifi") || 
                                     name.contains("eth") || name.contains("ap")) {
                                wifiLanIps.add(ip)
                                Log.d(TAG, "Found WiFi/LAN interface: $name with IP: $ip")
                            }
                            // Otras interfaces (las agregamos como WiFi/LAN por defecto)
                            else {
                                wifiLanIps.add(ip)
                                Log.d(TAG, "Found network interface: $name with IP: $ip")
                            }
                        }
                    }
                }
            }
            
            // Build list in order: USB, WiFi/LAN, Tailscale, Mobile Data (no automatic prioritization)
            usbIp?.let { ipAddresses.add(it) }
            ipAddresses.addAll(wifiLanIps)
            ipAddresses.addAll(tailscaleIps)
            ipAddresses.addAll(mobileDataIps)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP addresses: ${e.message}")
            e.printStackTrace()
        }
        return ipAddresses
    }
    
    private fun getIpType(ip: String): String? {
        // Check if it's a mobile data IP by checking the interface name
        // We'll need to scan interfaces again, but for now use heuristics
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { networkInterface ->
                networkInterface.inetAddresses.toList().any { address ->
                    address is Inet4Address && address.hostAddress == ip
                }
            }?.name?.lowercase()?.let { name ->
                when {
                    name.contains("tailscale") || name.contains("ts") || ip.startsWith("100.") -> getString(R.string.ip_label_tailscale)
                    name.contains("rmnet") || name.contains("ccmni") || 
                    name.contains("pdp") || name.contains("ppp") ||
                    name.contains("wwan") || name.contains("rmnet_data") -> getString(R.string.ip_label_4g_5g)
                    ip.startsWith("192.168.") -> getString(R.string.ip_label_lan_wifi)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getDefaultIpAddress(): String? {
        try {
            var tailscaleIp: String? = null
            var wifiLanIp: String? = null
            var mobileDataIp: String? = null
            
            // First pass: collect all IPs by type (scan all interfaces first, then prioritize)
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isUp = networkInterface.isUp
                
                if (!isUp) {
                    return@forEach
                }
                
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip != "0.0.0.0" && !ip.startsWith("127.")) {
                            // Check Tailscale first (by name OR IP range 100.x.x.x)
                            if (name.contains("tailscale") || name.contains("ts") || ip.startsWith("100.")) {
                                if (tailscaleIp == null) {
                                    tailscaleIp = ip
                                    Log.d(TAG, "Found Tailscale IP for default: $ip")
                                }
                            }
                            // Check WiFi/LAN (only if not Tailscale)
                            else if (name.contains("wlan") || name.contains("wifi") || 
                                     name.contains("eth") || name.contains("ap")) {
                                if (wifiLanIp == null) {
                                    wifiLanIp = ip
                                    Log.d(TAG, "Found WiFi/LAN IP for default: $ip")
                                }
                            }
                            // Check mobile data (only if not Tailscale or WiFi/LAN)
                            else if (name.contains("rmnet") || name.contains("ccmni") || 
                                     name.contains("pdp") || name.contains("ppp") ||
                                     name.contains("wwan") || name.contains("rmnet_data")) {
                                if (mobileDataIp == null) {
                                    mobileDataIp = ip
                                    Log.d(TAG, "Found mobile data IP for default: $ip")
                                }
                            }
                        }
                    }
                }
            }
            
            // Return in priority order: Tailscale > WiFi/LAN > Mobile Data
            val defaultIp = tailscaleIp ?: wifiLanIp ?: mobileDataIp
            Log.d(TAG, "Selected default IP: $defaultIp (Tailscale: $tailscaleIp, WiFi/LAN: $wifiLanIp, Mobile: $mobileDataIp)")
            return defaultIp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default IP address: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    private fun getStreamPort(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val portString = prefs.getString("stream_port", "8080") ?: "8080"
        return portString.toIntOrNull() ?: 8080
    }
    
    private fun applyLanguage() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val languageCode = prefs.getString("app_language", "es") ?: "es"
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
    
    private fun buildIpSpinnerItems(ipAddresses: List<String>, streamPort: Int): MutableList<String> {
        val spinnerItems = mutableListOf<String>()
        
        // Always add ADB option first
        spinnerItems.add("ADB (127.0.0.1:$streamPort)")
        
        // Add IPs with appropriate labels
        ipAddresses.forEach { ip ->
            val label = when {
                ip.startsWith("100.") -> getString(R.string.ip_label_tailscale)
                ip.startsWith("192.168.") -> getString(R.string.ip_label_lan_wifi)
                else -> {
                    // Check if it's mobile data by interface name
                    getIpType(ip) ?: if (!ip.startsWith("10.") && !ip.startsWith("172.16.") && 
                                         !ip.startsWith("172.17.") && !ip.startsWith("172.18.") &&
                                         !ip.startsWith("172.19.") && !ip.startsWith("172.20.") &&
                                         !ip.startsWith("172.21.") && !ip.startsWith("172.22.") &&
                                         !ip.startsWith("172.23.") && !ip.startsWith("172.24.") &&
                                         !ip.startsWith("172.25.") && !ip.startsWith("172.26.") &&
                                         !ip.startsWith("172.27.") && !ip.startsWith("172.28.") &&
                                         !ip.startsWith("172.29.") && !ip.startsWith("172.30.") &&
                                         !ip.startsWith("172.31.")) {
                        getString(R.string.ip_label_4g_5g)
                    } else {
                        getString(R.string.ip_label_lan_wifi)
                    }
                }
            }
            
            val ipWithLabel = "$ip:$streamPort ($label)"
            spinnerItems.add(ipWithLabel)
        }
        
        return spinnerItems
    }
    
    private fun refreshIpSpinner(showToast: Boolean = true) {
        val ipAddressSpinner = findViewById<android.widget.Spinner>(R.id.ipAddressSpinner) ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val streamPortForSpinner = getStreamPort()
        
        // Get current selection before refresh
        val currentSelection = ipAddressSpinner.selectedItem?.toString() ?: ""
        val currentIp = if (currentSelection.startsWith("ADB")) {
            "ADB"
        } else {
            currentSelection.substringBefore(":").substringBefore(" (")
        }
        
        // Get fresh IP addresses
        val ipAddresses = getAllLocalIpAddresses()
        
        // Build new spinner items
        val spinnerItems = buildIpSpinnerItems(ipAddresses, streamPortForSpinner)
        
        // Create new adapter with dark theme colors
        val adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            spinnerItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(Color.parseColor("#FFFFFF"))
                textView.textSize = 14f
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                view.setBackgroundColor(Color.parseColor("#2A2A30"))
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(Color.parseColor("#E0E0E0"))
                textView.textSize = 14f
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipAddressSpinner.adapter = adapter
        
        // Try to restore previous selection - DO NOT change it automatically
        val savedIp = prefs.getString("selected_bind_ip", null)
        
        // Try to find saved IP in the new list
        val ipToSelect = savedIp?.let { 
            if (savedIp == "ADB" || savedIp == "127.0.0.1") {
                "ADB (127.0.0.1:$streamPortForSpinner)"
            } else {
                // Check if it's a Tailscale IP
                if (savedIp.startsWith("100.")) {
                    "$savedIp:$streamPortForSpinner (Tailscale)"
                } else {
                    "$savedIp:$streamPortForSpinner"
                }
            }
        }?.takeIf { spinnerItems.contains(it) }
        
        // Find index of saved IP - DO NOT prioritize any IP automatically
        val selectedIndex = if (ipToSelect != null) {
            // Use saved IP if found
            spinnerItems.indexOfFirst { item -> item == ipToSelect }.takeIf { it >= 0 } ?: 0
        } else if (savedIp != null) {
            // If saved IP is not in the list but exists, keep it and select first non-ADB IP as fallback
            // But don't save it - keep the original saved IP
            // Try WiFi/LAN first, then any other, avoid Tailscale if possible
            val wifiLanIndex = spinnerItems.indexOfFirst { 
                !it.startsWith("ADB") && !it.contains("Tailscale") 
            }.takeIf { it >= 0 }
            wifiLanIndex ?: spinnerItems.indexOfFirst { !it.startsWith("ADB") }.takeIf { it >= 0 } ?: 0
        } else {
            // Only if no IP was ever saved, use first non-ADB, non-Tailscale IP if available
            // Prefer WiFi/LAN over Tailscale for first-time setup
            val nonAdbNonTailscaleIndex = spinnerItems.indexOfFirst { 
                !it.startsWith("ADB") && !it.contains("Tailscale") 
            }.takeIf { it >= 0 }
            nonAdbNonTailscaleIndex ?: spinnerItems.indexOfFirst { !it.startsWith("ADB") }.takeIf { it >= 0 } ?: 0
        }
        
        ipAddressSpinner.setSelection(selectedIndex)
        syncServerBindWithSpinnerSelection(ipAddressSpinner)
        
        // DO NOT update saved IP automatically - only update if user manually selects
        // The saved IP remains unchanged until user explicitly changes it
        
        if (showToast) {
            Toast.makeText(this, getString(R.string.toast_ips_updated), Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "IP spinner refreshed. Found ${ipAddresses.size} IPs")
    }

    private fun hasActiveStreamingClients(): Boolean {
        val helper = streamingServerHelper ?: return false
        return helper.getVideoClients().isNotEmpty() || helper.getAudioClients().isNotEmpty()
    }

    private fun handleStreamingClientConnected() {
        runOnUiThread {
            if (!hasActiveStreamingClients()) return@runOnUiThread
            if (!hasServerConnection) {
                hasServerConnection = true
                stopAutoIpRefresh()
                updateServerConnectionIndicator(true)
                Toast.makeText(
                    this,
                    getString(R.string.toast_server_connection_established),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleStreamingClientDisconnected() {
        runOnUiThread {
            if (!hasActiveStreamingClients()) {
                hasServerConnection = false
                updateServerConnectionIndicator(false)
                startAutoIpRefreshUntilConnected()
            }
        }
    }

    private fun startAutoIpRefreshUntilConnected() {
        if (ipAutoRefreshRunnable != null) return
        ipAutoRefreshRunnable = object : Runnable {
            override fun run() {
                if (hasServerConnection || hasActiveStreamingClients()) {
                    hasServerConnection = true
                    stopAutoIpRefresh()
                    updateServerConnectionIndicator(true)
                    return
                }
                refreshIpSpinner(showToast = false)
                ipAutoRefreshHandler.postDelayed(this, IP_AUTO_REFRESH_INTERVAL_MS)
            }
        }
        ipAutoRefreshHandler.postDelayed(ipAutoRefreshRunnable!!, IP_AUTO_REFRESH_INTERVAL_MS)
    }

    private fun handleDetectionEvent(event: StreamingServerHelper.DetectionEvent) {
        runOnUiThread {
            when (event.event) {
                "yolo" -> yoloDetections++
                "tensorflow" -> tensorflowDetections++
                "rf" -> rfDetections++
            }
            recentDetectionEvents.add(0, buildDetectionEventLine(event))
            while (recentDetectionEvents.size > MAX_RECENT_DETECTIONS) {
                recentDetectionEvents.removeAt(recentDetectionEvents.lastIndex)
            }
            updateDetectionSessionSummary()
        }
    }

    private fun buildDetectionEventLine(event: StreamingServerHelper.DetectionEvent): String {
        val label = when (event.event) {
            "yolo" -> getString(R.string.detection_type_yolo)
            "tensorflow" -> getString(R.string.detection_type_tensorflow)
            "rf" -> getString(R.string.detection_type_rf)
            else -> event.event
        }
        val time = event.time ?: java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val details = mutableListOf<String>()
        event.confidencePercent?.let {
            details.add("${getString(R.string.detection_event_confidence)} $it%")
        } ?: event.confidence?.let {
            details.add("${getString(R.string.detection_event_confidence)} ${(it * 100).toInt()}%")
        }
        event.frequencyHz?.let {
            val mhz = it / 1_000_000.0
            details.add("${getString(R.string.detection_event_frequency)} ${"%.2f".format(Locale.US, mhz)} MHz")
        }
        return if (details.isEmpty()) {
            "[$time] $label"
        } else {
            "[$time] $label - ${details.joinToString(", ")}"
        }
    }

    private fun updateDetectionSessionSummary() {
        detectionSessionText?.text = getString(
            R.string.detection_session_summary,
            yoloDetections,
            tensorflowDetections,
            rfDetections
        )
    }

    private fun showDetectionDetailsDialog() {
        val session = getString(
            R.string.detection_details_session_counts,
            yoloDetections,
            tensorflowDetections,
            rfDetections
        )
        val recentHeader = getString(R.string.detection_details_recent_title)
        val recent = if (recentDetectionEvents.isEmpty()) {
            getString(R.string.detection_details_empty)
        } else {
            recentDetectionEvents.joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.detection_details_title))
            .setMessage("$session\n\n$recentHeader\n$recent")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupEp32Controls() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ep32BluetoothHelper = Ep32BluetoothHelper(
            this,
            prefs = prefs,
            onStateChanged = { state, detail -> handleEp32State(state, detail) },
            onLog = { message -> Log.d(TAG, "[EP32] $message") },
            onHeartbeat = { hb -> forwardMicArrayHeartbeat(hb) },
            onAcoustic = { ac -> forwardMicArrayAcoustic(ac) },
            onAudioFrame = { frame -> handleEsp32AudioFrame(frame) },
            onUnknownPayload = { raw ->
                Log.d(TAG, "[EP32] unknown JSONL payload: ${raw.take(160)}")
            }
        )
        isEp32Enabled = prefs.getBoolean(PREF_EP32_ENABLED, false)

        ep32BluetoothSwitch?.setOnCheckedChangeListener(null)
        ep32BluetoothSwitch?.isChecked = isEp32Enabled
        setupEp32SwitchListener()

        setupEp32ButtonBindings()
        updateEp32UiState(Ep32BluetoothHelper.State.OFF)

        if (isEp32Enabled) {
            startEp32AutoConnectIfAllowed()
        }
    }

    private fun setupEp32ButtonBindings() {
        // Hold-to-move: ACTION_DOWN -> HOLD_<DIR>, ACTION_UP/CANCEL -> RELEASE.
        // Firmware v0.4.0+ mantiene el optoacoplador activo y el server (o
        // este propio handler) refrescaría con HOLD_* si quisiéramos
        // mantener el watchdog feliz. Aquí mandamos un solo HOLD al
        // pulsar y dejamos al firmware mantener (watchdog 2 s desde el
        // último HOLD; un movimiento continuo entre Android y el ESP32
        // sólo dura mientras dedo toca pantalla, que típicamente es
        // <2 s; si el usuario necesita más se reenvía abajo).
        bindDpadHoldButton(R.id.dpadUp, "HOLD_UP")
        bindDpadHoldButton(R.id.dpadDown, "HOLD_DOWN")
        bindDpadHoldButton(R.id.dpadLeft, "HOLD_LEFT")
        bindDpadHoldButton(R.id.dpadRight, "HOLD_RIGHT")
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun bindDpadHoldButton(viewId: Int, holdCommand: String) {
        val view = findViewById<ImageButton>(viewId) ?: return
        // Refresca el HOLD cada 500 ms mientras el dedo sigue tocando
        // (firmware watchdog = 2000 ms). Esto cubre el caso de pulsaciones
        // largas sin depender del server.
        val refreshRunnable = object : Runnable {
            override fun run() {
                sendEp32Command(holdCommand)
                view.postDelayed(this, 500L)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendEp32Command(holdCommand)
                    v.postDelayed(refreshRunnable, 500L)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    v.removeCallbacks(refreshRunnable)
                    sendEp32Command("RELEASE")
                    v.performClick()  // a11y compliance
                    true
                }
                else -> false
            }
        }
        // performClick fallback (a11y): pulsación corta = un HOLD breve y RELEASE.
        view.setOnClickListener {
            sendEp32Command(holdCommand)
            view.postDelayed({ sendEp32Command("RELEASE") }, 250L)
        }
    }

    private fun sendEp32Command(command: String): Boolean {
        return ep32BluetoothHelper?.sendCommand(command) == true
    }

    private fun handleEp32CommandRequest(commandRequest: StreamingServerHelper.Ep32CommandRequest): Boolean {
        if (!isEp32Enabled || ep32BluetoothHelper?.isConnected() != true) {
            return false
        }
        val command = commandRequest.command ?: commandRequest.sequence.firstOrNull() ?: return false
        return sendEp32Command(command)
    }

    // Server-driven control of the Android-side ESP32 Bluetooth bridge. The
    // phone is the BT master: the ADAS3 server has NO BT link of its own and
    // must never try to scan. From here, `enable` flips the same path used by
    // the UI toggle (`PREF_EP32_ENABLED` + startAutoConnect). `reconnect`
    // bounces the link without touching the preference. `disable`/`stop` tear
    // it down. Replies always include a status snapshot so the server can
    // poll `/adas3/ep32-status` afterwards (or just inspect the body).
    private fun handleEp32ControlRequest(
        request: StreamingServerHelper.Ep32ControlRequest
    ): StreamingServerHelper.Ep32ControlResult {
        val helper = ep32BluetoothHelper
        if (helper == null) {
            return StreamingServerHelper.Ep32ControlResult(
                accepted = false,
                httpStatus = 503,
                statusJson = "{\"status\":\"helper_unavailable\"}"
            )
        }
        runOnUiThread {
            when (request.action) {
                StreamingServerHelper.Ep32ControlAction.ENABLE -> {
                    isEp32Enabled = true
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean(PREF_EP32_ENABLED, true).apply()
                    ep32BluetoothSwitch?.setOnCheckedChangeListener(null)
                    ep32BluetoothSwitch?.isChecked = true
                    setupEp32SwitchListener()
                    startEp32AutoConnectIfAllowed()
                }
                StreamingServerHelper.Ep32ControlAction.DISABLE,
                StreamingServerHelper.Ep32ControlAction.STOP -> {
                    isEp32Enabled = false
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean(PREF_EP32_ENABLED, false).apply()
                    ep32BluetoothSwitch?.setOnCheckedChangeListener(null)
                    ep32BluetoothSwitch?.isChecked = false
                    setupEp32SwitchListener()
                    helper.stop()
                    updateEp32UiState(Ep32BluetoothHelper.State.OFF)
                }
                StreamingServerHelper.Ep32ControlAction.RECONNECT -> {
                    // Bounce the link without touching the preference. We
                    // call stop+start back-to-back; startAutoConnect already
                    // handles permissions and adapter checks.
                    helper.stop()
                    if (isEp32Enabled) {
                        startEp32AutoConnectIfAllowed()
                    }
                }
            }
        }
        return StreamingServerHelper.Ep32ControlResult(
            accepted = true,
            httpStatus = 202,
            statusJson = buildEp32StatusJson()
        )
    }

    // Refactored switch-listener setup so the control endpoint can re-attach
    // it after a programmatic flip without duplicating logic.
    private fun setupEp32SwitchListener() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ep32BluetoothSwitch?.setOnCheckedChangeListener { _, isChecked ->
            isEp32Enabled = isChecked
            prefs.edit().putBoolean(PREF_EP32_ENABLED, isChecked).apply()
            if (isChecked) {
                startEp32AutoConnectIfAllowed()
            } else {
                ep32BluetoothHelper?.stop()
                updateEp32UiState(Ep32BluetoothHelper.State.OFF)
            }
        }
    }

    private fun buildEp32StatusJson(): String {
        val helper = ep32BluetoothHelper
        val state = helper?.currentState() ?: Ep32BluetoothHelper.State.OFF
        val detail = helper?.currentStateDetail()
        val connected = helper?.isConnected() == true
        val active = helper?.isActive() == true
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val btAdapterEnabled = adapter != null && adapter.isEnabled
        val hasPerms = hasEp32Permissions()
        val hb = helper?.getLastHeartbeat()
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"connected\":").append(connected)
        sb.append(",\"state\":\"").append(state.name).append("\"")
        detail?.let { sb.append(",\"detail\":").append(org.json.JSONObject.quote(it)) }
        sb.append(",\"enabled\":").append(isEp32Enabled)
        sb.append(",\"active\":").append(active)
        sb.append(",\"bt_adapter_enabled\":").append(btAdapterEnabled)
        sb.append(",\"permissions_granted\":").append(hasPerms)
        if (hb != null) {
            sb.append(",\"firmware\":")
                .append(hb.firmware?.let { org.json.JSONObject.quote(it) } ?: "null")
            sb.append(",\"mic_count\":").append(effectiveMicCount(hb.micCount))
        }
        sb.append("}")
        return sb.toString()
    }

    // Mirror of ensureEp32Permissions() but read-only: never triggers a
    // permission prompt. The server uses this hint to know whether to ask
    // the user via the Android UI to grant permissions before calling
    // `/adas3/ep32-control` with `action=enable`.
    private fun hasEp32Permissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Mic-array bridging: payloads come in via Bluetooth SPP from the ESP32
    // (which does the I2S beamforming/GCC-PHAT locally on 4 mics across 2 I2S
    // pairs) and are forwarded to the ADAS3 server through the HTTP server
    // already exposed by this client. Phone-mic audio path is unaffected and
    // Telegram is NOT triggered from here (the server is responsible for
    // queueing an internal `acoustic_array` event to avoid duplicate alerts).
    //
    // The firmware may emit the legacy minimal contract (mic_count alone) or
    // an enriched payload (pair/bus/wiring/config). If wiring is missing the
    // client injects the definitive wiring from `MicArrayWiring` so the
    // server always receives a fully-described event.
    private fun forwardMicArrayHeartbeat(hb: Ep32BluetoothHelper.Heartbeat) {
        val sb = StringBuilder()
        sb.append("{\"type\":\"heartbeat\"")
        sb.append(",\"mic_count\":").append(effectiveMicCount(hb.micCount))
        val fw = hb.firmware?.let { org.json.JSONObject.quote(it) } ?: "null"
        sb.append(",\"firmware\":").append(fw)
        hb.pair?.let { sb.append(",\"pair\":").append(org.json.JSONObject.quote(it)) }
        hb.bus?.let { sb.append(",\"bus\":").append(org.json.JSONObject.quote(it)) }
        sb.append(",\"wiring\":").append(hb.wiringJson ?: MicArrayWiring.toJson())
        hb.configJson?.let { sb.append(",\"config\":").append(it) }
        appendExtras(sb, hb.extras)
        sb.append("}")
        streamingServerHelper?.sendMicArrayPayload(sb.toString())
    }

    private fun forwardMicArrayAcoustic(ac: Ep32BluetoothHelper.Acoustic) {
        val sb = StringBuilder()
        sb.append("{\"type\":\"acoustic\",\"detected\":").append(ac.detected)
        ac.doaDeg?.let { sb.append(",\"doa_deg\":").append(it) }
        ac.energy?.let { sb.append(",\"energy\":").append(it) }
        ac.confidence?.let { sb.append(",\"confidence\":").append(it) }
        sb.append(",\"mic_count\":").append(effectiveMicCount(ac.micCount))
        ac.pair?.let { sb.append(",\"pair\":").append(org.json.JSONObject.quote(it)) }
        ac.bus?.let { sb.append(",\"bus\":").append(org.json.JSONObject.quote(it)) }
        sb.append(",\"wiring\":").append(ac.wiringJson ?: MicArrayWiring.toJson())
        ac.configJson?.let { sb.append(",\"config\":").append(it) }
        appendExtras(sb, ac.extras)
        sb.append("}")
        streamingServerHelper?.sendMicArrayPayload(sb.toString())
    }

    // If the firmware reports a mic_count we keep it for traceability, but if
    // it is missing or implausible (<= 0) we force the definitive count (4)
    // so the server always sees the real soldered topology.
    private fun effectiveMicCount(reported: Int?): Int {
        return when {
            reported == null -> MicArrayWiring.MIC_COUNT
            reported <= 0 -> MicArrayWiring.MIC_COUNT
            else -> reported
        }
    }

    private fun appendExtras(sb: StringBuilder, extras: Map<String, String>) {
        for ((k, v) in extras) {
            sb.append(",").append(org.json.JSONObject.quote(k)).append(":").append(v)
        }
    }

    private fun buildMicArrayStatusJson(): String {
        val helper = ep32BluetoothHelper
        val connected = helper?.isConnected() == true
        val hb = helper?.getLastHeartbeat()
        val ac = helper?.getLastAcoustic()
        val sb = StringBuilder()
        sb.append("{\"connected\":").append(connected)
        // Always advertise the definitive wiring so the server can pick it up
        // even when no heartbeat has arrived yet.
        sb.append(",\"wiring\":").append(MicArrayWiring.toJson())
        if (hb != null) {
            sb.append(",\"heartbeat\":{\"mic_count\":").append(effectiveMicCount(hb.micCount))
            val fw = hb.firmware?.let { org.json.JSONObject.quote(it) } ?: "null"
            sb.append(",\"firmware\":").append(fw)
            hb.pair?.let { sb.append(",\"pair\":").append(org.json.JSONObject.quote(it)) }
            hb.bus?.let { sb.append(",\"bus\":").append(org.json.JSONObject.quote(it)) }
            sb.append("}")
        }
        if (ac != null) {
            sb.append(",\"last_acoustic\":{\"detected\":").append(ac.detected)
            ac.doaDeg?.let { sb.append(",\"doa_deg\":").append(it) }
            ac.energy?.let { sb.append(",\"energy\":").append(it) }
            ac.confidence?.let { sb.append(",\"confidence\":").append(it) }
            sb.append(",\"mic_count\":").append(effectiveMicCount(ac.micCount))
            ac.pair?.let { sb.append(",\"pair\":").append(org.json.JSONObject.quote(it)) }
            ac.bus?.let { sb.append(",\"bus\":").append(org.json.JSONObject.quote(it)) }
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Audio source selector: ESP32 array vs phone mic
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carga la preferencia de fuente de audio y aplica el efecto correspondiente
     * (arrancar/parar AudioRecord, mandar AUDIO_ON/OFF al ESP32).
     */
    /**
     * Coordinador único de transición de fuente de audio. Cualquier
     * cambio (UI, Settings, server POST, arranque) debe pasar por aquí.
     *
     * Garantías:
     * - Persiste la nueva preferencia (a menos que persist=false porque ya
     *   se persistió por otra vía, p. ej. cuando el cambio viene del
     *   Settings dialog).
     * - Si la nueva fuente es PHONE_MIC: para definitivamente cualquier
     *   stream del array (AUDIO_OFF al ESP32, drena cola, para worker) Y
     *   abre el AudioRecord si `isAudioEnabled` y hay permiso.
     * - Si la nueva fuente es ESP32_ARRAY: para AudioRecord Y lanza el
     *   worker + manda AUDIO_ON al ESP32 si el puente está conectado.
     *   El re-AUDIO_ON tras reconexión SPP lo hace `handleEp32State`.
     * - Idempotente: llamar dos veces con la misma fuente no rompe nada.
     *
     * Llamar SIEMPRE en el hilo de UI (runOnUiThread si vienes de I/O).
     */
    private fun applyAudioSource(newSource: String, persist: Boolean = true) {
        val normalized = when (newSource) {
            AUDIO_SOURCE_ESP32_ARRAY -> AUDIO_SOURCE_ESP32_ARRAY
            else -> AUDIO_SOURCE_PHONE_MIC
        }
        val prev = audioSource
        audioSource = normalized
        if (persist) {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString(PREF_AUDIO_SOURCE, normalized).apply()
        }
        Log.i(TAG, "[AUDIO] source: $prev -> $normalized (audioEnabled=$isAudioEnabled)")
        when (normalized) {
            AUDIO_SOURCE_PHONE_MIC -> switchToPhoneMicSource(showToast = prev != normalized)
            AUDIO_SOURCE_ESP32_ARRAY -> switchToEsp32ArraySource(showToast = prev != normalized)
        }
    }

    private fun switchToPhoneMicSource(showToast: Boolean) {
        // 1. Asegurar que el array NO sigue mandando: AUDIO_OFF al ESP32,
        //    drenar cola y parar worker para no acumular bytes residuales.
        ep32BluetoothHelper?.let {
            if (it.isConnected()) it.requestAudioOff()
        }
        stopArrayAudioWorker()

        // 2. Abrir el mic del móvil sólo si el usuario ha pedido audio
        //    encendido y hay permiso. Si no lo hay, lo pedimos para que
        //    el flujo legacy funcione exactamente como antes del selector.
        if (isAudioEnabled) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                if (audioCaptureHelper?.isRecording() != true) {
                    audioCaptureHelper?.startRecording()
                }
            } else if (!hasRequestedAudioPermission) {
                hasRequestedAudioPermission = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_AUDIO_PERMISSION,
                )
            }
        }
        if (showToast) {
            Toast.makeText(this, "Audio: micrófono del móvil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToEsp32ArraySource(showToast: Boolean) {
        // 1. Parar el AudioRecord del móvil: ya no debe generar bytes.
        audioCaptureHelper?.stopRecording()

        // 2. Preparar el worker que upsamplea+envía PCM del array, AUN
        //    SI todavía no llegan frames. Así el primer frame que entre
        //    se procesa sin demora.
        if (isAudioEnabled) ensureArrayAudioWorker()

        // 3. Pedir al firmware que empiece a emitir. Si BT no está aún
        //    conectado, `handleEp32State(CONNECTED)` re-enviará AUDIO_ON.
        val helper = ep32BluetoothHelper
        if (helper != null && helper.isConnected() && isAudioEnabled) {
            helper.requestAudioOn()
        }

        if (showToast) {
            val msg = if (helper?.isConnected() == true)
                "Audio: ESP32 array (AUDIO_ON)"
            else
                "Audio: ESP32 array (esperando conexión Bluetooth)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Frame PCM16 mono recibido del ESP32 a 8 kHz. Para no bloquear el
     * hilo del reader Bluetooth ni introducir picos en el envío HTTP,
     * encolamos a una cola acotada y dejamos que un worker dedicado haga
     * el upsample + sendAudioData. Si la cola está llena, descartamos el
     * frame más antiguo (drop-oldest): Keras tolera saltos cortos mejor
     * que latencia acumulada o que la pérdida de FPS del video.
     */
    private fun handleEsp32AudioFrame(frame: Ep32BluetoothHelper.AudioFrame) {
        arrayAudioFramesIn.incrementAndGet()
        lastArrayAudioFrameTs = System.currentTimeMillis()
        lastArrayAudioSampleRate = frame.sampleRate
        if (audioSource != AUDIO_SOURCE_ESP32_ARRAY) return
        if (!isAudioEnabled) return
        if (frame.pcm.isEmpty() || frame.pcm.size % 2 != 0) return

        // Back-pressure: si la cola está llena, descartamos el más antiguo
        // y metemos el nuevo. `offer` no bloquea.
        if (!arrayAudioQueue.offer(frame)) {
            val dropped = arrayAudioQueue.poll()
            if (dropped != null) {
                arrayAudioFramesDropped.incrementAndGet()
            }
            arrayAudioQueue.offer(frame)
        }
        ensureArrayAudioWorker()
    }

    private fun ensureArrayAudioWorker() {
        if (arrayAudioWorkerRunning.get()) return
        if (!arrayAudioWorkerRunning.compareAndSet(false, true)) return
        val t = Thread({
            try {
                while (arrayAudioWorkerRunning.get()) {
                    val frame = arrayAudioQueue.poll(
                        500, java.util.concurrent.TimeUnit.MILLISECONDS
                    ) ?: continue
                    // Gates secundarios: si la fuente cambió mientras el
                    // frame estaba en cola, descartar.
                    if (audioSource != AUDIO_SOURCE_ESP32_ARRAY) continue
                    if (!isAudioEnabled) continue
                    val srcRate = if (frame.sampleRate > 0) frame.sampleRate
                                  else ARRAY_AUDIO_SOURCE_RATE
                    val resampled = upsamplePcm16Mono(
                        frame.pcm, srcRate, ANDROID_AUDIO_SAMPLE_RATE
                    )
                    if (resampled.isNotEmpty()) {
                        // Siempre upsample + sendAudioData: si aún no hay GET
                        // /audio, StreamingServerHelper guarda en prefetch y
                        // vacía al conectar el server (ADAS3 testcam).
                        streamingServerHelper?.sendAudioData(resampled)
                        if (streamingServerHelper?.getAudioClients()?.isNotEmpty() == true) {
                            arrayAudioFramesForwarded.incrementAndGet()
                            arrayAudioBytesForwarded.addAndGet(resampled.size.toLong())
                        } else {
                            arrayAudioFramesPrefetchBuffered.incrementAndGet()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // shutdown
            } catch (e: Exception) {
                Log.w(TAG, "[AUDIO] worker error: ${e.message}")
            } finally {
                arrayAudioWorkerRunning.set(false)
            }
        }, "array-audio-worker")
        t.isDaemon = true
        arrayAudioWorker = t
        t.start()
    }

    private fun stopArrayAudioWorker() {
        arrayAudioWorkerRunning.set(false)
        arrayAudioWorker?.interrupt()
        arrayAudioWorker = null
        arrayAudioQueue.clear()
        arrayUpsamplerLastSample = 0
    }

    /**
     * Upsampler PCM16 mono → PCM16 mono con interpolación lineal.
     * src y dst son little-endian (formato AudioRecord ENCODING_PCM_16BIT).
     */
    private fun upsamplePcm16Mono(src: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate <= 0 || dstRate <= 0) return src
        if (srcRate == dstRate) return src
        val nSrc = src.size / 2
        if (nSrc == 0) return ByteArray(0)
        val srcShorts = ShortArray(nSrc + 1)
        srcShorts[0] = arrayUpsamplerLastSample
        for (i in 0 until nSrc) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            srcShorts[i + 1] = ((hi shl 8) or lo).toShort()
        }
        arrayUpsamplerLastSample = srcShorts[nSrc]
        val nDst = (nSrc.toLong() * dstRate / srcRate).toInt()
        if (nDst <= 0) return ByteArray(0)
        val out = ByteArray(nDst * 2)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (j in 0 until nDst) {
            val srcPos = 1.0 + j * ratio
            val i0 = srcPos.toInt().coerceIn(0, nSrc)
            val i1 = (i0 + 1).coerceAtMost(nSrc)
            val frac = srcPos - i0
            val s0 = srcShorts[i0].toInt()
            val s1 = srcShorts[i1].toInt()
            val interp = (s0 + (s1 - s0) * frac).toInt()
            val clipped = when {
                interp > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE.toInt()
                interp < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE.toInt()
                else -> interp
            }
            out[j * 2] = (clipped and 0xFF).toByte()
            out[j * 2 + 1] = ((clipped shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Estado de la fuente de audio para el endpoint /adas3/audio-source.
     *  Devuelve kbps efectivos hacia el servidor (mismo dato útil tanto
     *  para phone_mic como para esp32_array, ya que ambos pasan por el
     *  mismo sendAudioData → /audio). */
    private fun buildAudioSourceStatusJson(): String {
        val helper = ep32BluetoothHelper
        val now = System.currentTimeMillis()
        val uptimeMs = (now - audioStatusStartMs).coerceAtLeast(1L)
        val phoneBytes = phoneMicBytesSent.get()
        val arrayBytes = arrayAudioBytesForwarded.get()
        // kbps efectivo desde el arranque de la app (medida estable).
        val phoneKbps = (phoneBytes * 8.0 / uptimeMs).let { kb ->
            String.format(java.util.Locale.US, "%.1f", kb)
        }
        val arrayKbps = (arrayBytes * 8.0 / uptimeMs).let { kb ->
            String.format(java.util.Locale.US, "%.1f", kb)
        }
        val sb = StringBuilder()
        sb.append("{\"source\":").append(org.json.JSONObject.quote(audioSource))
        sb.append(",\"audio_enabled\":").append(isAudioEnabled)
        sb.append(",\"encoding\":\"pcm16\"")
        sb.append(",\"channels\":1")
        sb.append(",\"sample_rate\":").append(ANDROID_AUDIO_SAMPLE_RATE)
        sb.append(",\"phone_mic_kbps_avg\":").append(phoneKbps)
        sb.append(",\"array_kbps_avg\":").append(arrayKbps)
        if (audioSource == AUDIO_SOURCE_ESP32_ARRAY) {
            val connected = helper?.isConnected() == true
            sb.append(",\"array_audio_active\":").append(
                connected && (now - lastArrayAudioFrameTs) < 5000L
            )
            sb.append(",\"array_audio_source_rate\":").append(
                if (lastArrayAudioSampleRate > 0) lastArrayAudioSampleRate
                else ARRAY_AUDIO_SOURCE_RATE
            )
            sb.append(",\"array_audio_frames_in\":").append(arrayAudioFramesIn.get())
            sb.append(",\"array_audio_frames_forwarded\":")
                .append(arrayAudioFramesForwarded.get())
            sb.append(",\"array_audio_frames_prefetch_buffered\":")
                .append(arrayAudioFramesPrefetchBuffered.get())
            sb.append(",\"audio_http_listeners\":")
                .append(streamingServerHelper?.getAudioClients()?.size ?: 0)
            sb.append(",\"array_audio_frames_dropped\":")
                .append(arrayAudioFramesDropped.get())
            sb.append(",\"array_audio_queue_depth\":")
                .append(arrayAudioQueue.size)
            sb.append(",\"array_audio_worker_running\":")
                .append(arrayAudioWorkerRunning.get())
            sb.append(",\"array_audio_last_frame_age_ms\":").append(
                if (lastArrayAudioFrameTs == 0L) -1L
                else now - lastArrayAudioFrameTs
            )
            sb.append(",\"bridge_connected\":").append(connected)
        } else {
            sb.append(",\"array_audio_active\":false")
            sb.append(",\"array_audio_frames_dropped\":")
                .append(arrayAudioFramesDropped.get())
        }
        sb.append("}")
        return sb.toString()
    }

    private fun startEp32AutoConnectIfAllowed() {
        if (!ensureEp32Permissions()) {
            ep32BluetoothSwitch?.setOnCheckedChangeListener(null)
            ep32BluetoothSwitch?.isChecked = false
            ep32BluetoothSwitch?.setOnCheckedChangeListener { _, checked ->
                isEp32Enabled = checked
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putBoolean(PREF_EP32_ENABLED, checked)
                    .apply()
                if (checked) startEp32AutoConnectIfAllowed() else {
                    ep32BluetoothHelper?.stop()
                    updateEp32UiState(Ep32BluetoothHelper.State.OFF)
                }
            }
            isEp32Enabled = false
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_EP32_ENABLED, false).apply()
            return
        }

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, getString(R.string.toast_ep32_enable_bluetooth), Toast.LENGTH_SHORT).show()
            updateEp32UiState(Ep32BluetoothHelper.State.ERROR)
            return
        }

        ep32BluetoothHelper?.startAutoConnect()
    }

    private fun ensureEp32Permissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), REQUEST_CODE_EP32_PERMISSIONS)
            return false
        }
        return true
    }

    private fun handleEp32State(state: Ep32BluetoothHelper.State, detail: String?) {
        updateEp32UiState(state)
        when (state) {
            Ep32BluetoothHelper.State.CONNECTED -> {
                Log.i(TAG, "EP32 connected: ${detail ?: "unknown"}")
                // Si la fuente de audio activa es el array, re-armar el
                // streaming en cuanto el puente SPP esté disponible (cubre
                // reconexiones tras pérdida de Bluetooth).
                if (audioSource == AUDIO_SOURCE_ESP32_ARRAY) {
                    ep32BluetoothHelper?.requestAudioOn()
                }
            }
            Ep32BluetoothHelper.State.ERROR -> {
                Log.w(TAG, "EP32 state error: ${detail ?: "unknown"}")
            }
            else -> Unit
        }
    }

    private fun updateEp32UiState(state: Ep32BluetoothHelper.State) {
        val statusText = ep32BluetoothStatusText ?: return
        when (state) {
            Ep32BluetoothHelper.State.CONNECTED -> {
                statusText.text = getString(R.string.ep32_status_connected)
                statusText.setTextColor(Color.parseColor("#00FF00"))
                ep32ControlPanel?.visibility = View.VISIBLE
            }
            Ep32BluetoothHelper.State.SCANNING -> {
                statusText.text = getString(R.string.ep32_status_scanning)
                statusText.setTextColor(Color.parseColor("#FFCC00"))
                ep32ControlPanel?.visibility = View.GONE
            }
            Ep32BluetoothHelper.State.CONNECTING -> {
                statusText.text = getString(R.string.ep32_status_connecting)
                statusText.setTextColor(Color.parseColor("#FFCC00"))
                ep32ControlPanel?.visibility = View.GONE
            }
            Ep32BluetoothHelper.State.ERROR -> {
                statusText.text = getString(R.string.ep32_status_error)
                statusText.setTextColor(Color.parseColor("#FF4444"))
                ep32ControlPanel?.visibility = View.GONE
            }
            Ep32BluetoothHelper.State.OFF -> {
                statusText.text = getString(R.string.ep32_status_off)
                statusText.setTextColor(Color.parseColor("#FF4444"))
                ep32ControlPanel?.visibility = View.GONE
            }
        }
    }

    private fun stopAutoIpRefresh() {
        ipAutoRefreshRunnable?.let { ipAutoRefreshHandler.removeCallbacks(it) }
        ipAutoRefreshRunnable = null
    }
    
    private fun isTailscaleInstalled(): Boolean {
        // Since Android blocks package queries, we can't reliably check if Tailscale is installed
        // Instead, we'll just try to detect if it's active (has IP) which is more reliable
        // This function is kept for compatibility but always returns true to allow the switch to work
        return true
    }
    
    private fun getTailscalePackageName(): String? {
        return try {
            val packageNames = listOf(
                "com.tailscale.ipn",
                "com.tailscale.ipn.debug",
                "com.tailscale.ipn.beta"
            )
            
            // First try known package names
            for (packageName in packageNames) {
                try {
                    packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                    Log.d(TAG, "Found Tailscale package: $packageName")
                    return packageName
                } catch (e: Exception) {
                    // Continue
                }
            }
            
            // If not found, search in installed packages
            val installedPackages = packageManager.getInstalledPackages(0)
            val tailscalePackage = installedPackages.firstOrNull { 
                it.packageName.contains("tailscale", ignoreCase = true) 
            }
            
            tailscalePackage?.packageName?.also {
                Log.d(TAG, "Found Tailscale package by search: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Tailscale package name: ${e.message}")
            null
        }
    }
    
    private fun isTailscaleActive(): Boolean {
        return try {
            // Check network interfaces for Tailscale IPs (100.x.x.x)
            NetworkInterface.getNetworkInterfaces().toList().any { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isUp = networkInterface.isUp
                if (!isUp) return@any false
                
                networkInterface.inetAddresses.toList().any { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        ip.startsWith("100.") || name.contains("tailscale") || name.contains("ts")
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Tailscale active: ${e.message}")
            false
        }
    }
    
    private fun setupTailscaleSwitch() {
        val tailscaleSwitch = findViewById<android.widget.Switch>(R.id.tailscaleSwitch) ?: return
        
        // Check if Tailscale is installed
        val isInstalled = isTailscaleInstalled()
        val isActive = isTailscaleActive()
        
        Log.d(TAG, "Tailscale switch setup: installed=$isInstalled, active=$isActive")
        
        // Always enable the switch (user can try to open Tailscale even if not installed)
        tailscaleSwitch.isEnabled = true
        tailscaleSwitch.alpha = 1.0f
        
        // Set initial switch state based on whether Tailscale is active
        tailscaleSwitch.isChecked = isActive
        
        // Use a flag to prevent listener from interfering during programmatic updates
        var isUpdatingProgrammatically = false
        
        tailscaleSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Ignore if this is a programmatic update
            if (isUpdatingProgrammatically) {
                return@setOnCheckedChangeListener
            }
            
            Log.d(TAG, "Tailscale switch clicked: checked=$isChecked")
            
            // Try multiple methods to open Tailscale
            var opened = false
            
            // Method 1: Try getLaunchIntentForPackage
            try {
                val intent1 = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                if (intent1 != null) {
                    intent1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent1)
                    Toast.makeText(this, getString(R.string.toast_opening_tailscale), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Opened Tailscale with getLaunchIntentForPackage")
                    opened = true
                } else {
                    Log.d(TAG, "getLaunchIntentForPackage returned null")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Method 1 failed: ${e.message}")
            }
            
            // Method 2: Try creating intent with explicit package and resolveActivity
            if (!opened) {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage("com.tailscale.ipn")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    
                    // Check if there's an activity that can handle this intent
                    val resolveInfo = packageManager.resolveActivity(intent, 0)
                    if (resolveInfo != null) {
                        startActivity(intent)
                        Toast.makeText(this, getString(R.string.toast_opening_tailscale), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Opened Tailscale with resolveActivity")
                        opened = true
                    } else {
                        Log.d(TAG, "No activity found to handle intent")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Method 2 failed: ${e.message}")
                }
            }
            
            // Method 3: Try queryIntentActivities (may be blocked but worth trying)
            if (!opened) {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage("com.tailscale.ipn")
                    }
                    val activities = packageManager.queryIntentActivities(intent, 0)
                    if (activities.isNotEmpty()) {
                        val activityInfo = activities[0].activityInfo
                        val launchIntent = Intent().apply {
                            setClassName(activityInfo.packageName, activityInfo.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(launchIntent)
                        Toast.makeText(this, getString(R.string.toast_opening_tailscale), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Opened Tailscale with queryIntentActivities")
                        opened = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Method 3 failed: ${e.message}")
                }
            }
            
            // If we couldn't open Tailscale, try to open Play Store
            if (!opened) {
                try {
                    // Try market:// scheme first
                    val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("market://details?id=com.tailscale.ipn")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(playStoreIntent)
                    Toast.makeText(this, getString(R.string.toast_tailscale_play_store), Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Opened Play Store for Tailscale (market://)")
                    opened = true
                } catch (e: Exception) {
                    Log.d(TAG, "Play Store market:// failed: ${e.message}, trying web URL")
                    // Fallback to web URL
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(webIntent)
                        Toast.makeText(this, getString(R.string.toast_tailscale_play_store), Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Opened Play Store for Tailscale (web URL)")
                        opened = true
                    } catch (e2: Exception) {
                        Log.e(TAG, "Could not open Play Store: ${e2.message}")
                        Toast.makeText(this, getString(R.string.toast_tailscale_cannot_open), Toast.LENGTH_LONG).show()
                    }
                }
                
                if (!opened) {
                    isUpdatingProgrammatically = true
                    tailscaleSwitch.isChecked = !isChecked
                    isUpdatingProgrammatically = false
                }
            }
        }
        
        // Update switch state periodically to reflect actual Tailscale status
        tailscaleUpdateRunnable = object : Runnable {
            override fun run() {
                val currentlyActive = isTailscaleActive()
                if (tailscaleSwitch.isChecked != currentlyActive) {
                    isUpdatingProgrammatically = true
                    tailscaleSwitch.isChecked = currentlyActive
                    isUpdatingProgrammatically = false
                    Log.d(TAG, "Tailscale status updated: active=$currentlyActive")
                }
                tailscaleUpdateHandler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
        tailscaleUpdateHandler.post(tailscaleUpdateRunnable!!)
    }
    
    private fun isAdbConnected(): Boolean {
        return try {
            // Check if ADB is enabled via system property
            val adbEnabled = try {
                android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
            } catch (e: Exception) {
                false
            }
            
            // Also check for USB debugging via USB manager
            val usbManager = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            val hasUsbDevices = usbManager?.deviceList?.isNotEmpty() == true
            
            // Check for network interfaces that indicate USB tethering/ADB
            val hasUsbInterface = NetworkInterface.getNetworkInterfaces().toList().any { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isUp = networkInterface.isUp
                if (!isUp) return@any false
                
                name.contains("rndis") || name.contains("usb") || 
                (name.contains("eth") && !name.contains("wlan"))
            }
            
            val isConnected = adbEnabled || hasUsbDevices || hasUsbInterface
            Log.d(TAG, "ADB check: adbEnabled=$adbEnabled, hasUsbDevices=$hasUsbDevices, hasUsbInterface=$hasUsbInterface, result=$isConnected")
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ADB connection: ${e.message}")
            false
        }
    }
    
    private fun hidePreview() {
        val viewFinder = viewBinding.viewFinder
        val rootView = viewBinding.root
        val ipAddressContainer = findViewById<android.view.ViewGroup>(R.id.ipAddressContainer)
        val detectionSessionContainer = findViewById<android.view.ViewGroup>(R.id.detectionSessionContainer)
        val ep32BluetoothContainer = findViewById<android.view.ViewGroup>(R.id.ep32BluetoothContainer)
        val ep32ControlPanelContainer = findViewById<android.widget.FrameLayout>(R.id.ep32ControlPanel)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val switchCameraButton = findViewById<ImageButton>(R.id.switchCameraButton)
        val hidePreviewButton = findViewById<ImageButton>(R.id.hidePreviewButton)
        val audioToggleButton = findViewById<ImageButton>(R.id.audioToggleButton)

        if (viewFinder.isVisible) {
            viewFinder.visibility = View.GONE
            ipAddressContainer.visibility = View.GONE
            detectionSessionContainer.visibility = View.GONE
            ep32BluetoothContainer.visibility = View.GONE
            ep32ControlPanelContainer.visibility = View.GONE
            settingsButton.visibility = View.GONE
            switchCameraButton.visibility = View.GONE
            audioToggleButton.visibility = View.GONE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_slideshow) // use open eye as placeholder for closed eye
        } else {
            viewFinder.visibility = View.VISIBLE
            ipAddressContainer.visibility = View.VISIBLE
            detectionSessionContainer.visibility = View.VISIBLE
            ep32BluetoothContainer.visibility = View.VISIBLE
            ep32ControlPanelContainer.visibility = if (ep32BluetoothHelper?.isConnected() == true) View.VISIBLE else View.GONE
            settingsButton.visibility = View.VISIBLE
            switchCameraButton.visibility = View.VISIBLE
            audioToggleButton.visibility = View.VISIBLE
            rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_view) // open eye
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // Initialize camera resolution helper if not already done
            if (cameraResolutionHelper == null) {
                cameraResolutionHelper = CameraResolutionHelper(this)
                // Get camera ID based on lens facing
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = when (lensFacing) {
                    CameraSelector.DEFAULT_BACK_CAMERA -> {
                        cameraManager.cameraIdList.find { id ->
                            val characteristics = cameraManager.getCameraCharacteristics(id)
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        } ?: "0"
                    }
                    CameraSelector.DEFAULT_FRONT_CAMERA -> {
                        cameraManager.cameraIdList.find { id ->
                            val characteristics = cameraManager.getCameraCharacteristics(id)
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        } ?: "1"
                    }
                    else -> "0"
                }
                cameraResolutionHelper?.initializeResolutions(cameraId)
            }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            
            // Configurar FPS desde preferencias
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val fpsOption = prefs.getString("camera_fps", "auto") ?: "auto"
            
            // Solo usar Camera2Interop si no es "auto"
            if (fpsOption != "auto") {
                val targetFps = fpsOption.toIntOrNull() ?: 30
                try {
                    @Suppress("UnsafeOptInUsageError")
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    Log.i(TAG, "Camera FPS forzado a: $targetFps")
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo configurar FPS target: ${e.message}")
                }
            } else {
                Log.i(TAG, "Camera FPS: modo automático (sin forzar)")
            }
            
            imageAnalyzer = analysisBuilder.apply {
                    // Get resolution from preferences
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val resolutionString = prefs.getString("camera_resolution", null)
                    
                    // Get the appropriate resolution
                    val targetResolution = if (resolutionString != null && resolutionString.contains("x")) {
                        // New format: "WIDTHxHEIGHT"
                        cameraResolutionHelper?.getResolutionForQuality(resolutionString)
                    } else {
                        // Legacy format: "high/medium/low" or null
                        val quality = resolutionString ?: "low"
                        cameraResolutionHelper?.getResolutionForQuality(quality)
                    }

                    if (targetResolution != null) {
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(
                                targetResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            ))
                            .build()
                        setResolutionSelector(resolutionSelector)
                        Log.i(TAG, "Using resolution: ${targetResolution.width}x${targetResolution.height}")
                    } else {
                        // Fallback to hardcoded resolutions if detection fails
                        Log.w(TAG, "No resolution found, using fallback resolution")
                        val fallbackResolution = Size(800, 600)
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(
                                fallbackResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            ))
                            .build()
                        setResolutionSelector(resolutionSelector)
                        Log.i(TAG, "Using fallback resolution: ${fallbackResolution.width}x${fallbackResolution.height}")
                    }
                }
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        // Always process image (it will check for clients internally)
                        processImage(image)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleAudio() {
        if (isAudioEnabled) {
            // Apagar audio completamente. Cualquiera de las dos rutas:
            // - phone_mic: stopRecording.
            // - esp32_array: AUDIO_OFF al ESP32 + parar worker + drenar cola.
            isAudioEnabled = false
            audioCaptureHelper?.stopRecording()
            stopArrayAudioWorker()
            ep32BluetoothHelper?.let {
                if (it.isConnected()) it.requestAudioOff()
            }
            Toast.makeText(this, getString(R.string.toast_audio_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        // Encender audio. Dejamos a applyAudioSource decidir qué stream se
        // abre según la fuente activa. Para PHONE_MIC necesitamos permiso
        // RECORD_AUDIO; si no lo hay, lo pedimos y el resto del flujo se
        // completa desde onRequestPermissionsResult.
        if (audioSource == AUDIO_SOURCE_PHONE_MIC &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_CODE_AUDIO_PERMISSION,
            )
            return
        }
        isAudioEnabled = true
        applyAudioSource(audioSource, persist = false)
        Toast.makeText(this, getString(R.string.toast_audio_enabled), Toast.LENGTH_SHORT).show()
    }

    private fun enableAudioByDefault(audioToggleButton: ImageButton) {
        if (isAudioEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (audioSource == AUDIO_SOURCE_PHONE_MIC) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isAudioEnabled = true
                applyAudioSource(audioSource, persist = false)
                updateAudioButtonIcon(audioToggleButton)
                Toast.makeText(this, getString(R.string.toast_audio_enabled), Toast.LENGTH_SHORT).show()
            } else if (!hasRequestedAudioPermission) {
                hasRequestedAudioPermission = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO_PERMISSION)
            }
        } else {
            // audioSource = esp32_array: NO necesitamos permiso de micrófono;
            // sólo arrancamos el worker + AUDIO_ON via applyAudioSource.
            isAudioEnabled = true
            applyAudioSource(audioSource, persist = false)
            updateAudioButtonIcon(audioToggleButton)
        }
    }

    private fun ensureAudioPermissionRequested() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (!hasRequestedAudioPermission) {
            hasRequestedAudioPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO_PERMISSION)
        }
    }

    private fun getBindIpFromSpinnerItem(item: String): String {
        return if (item.startsWith("ADB")) "127.0.0.1" else item.substringBefore(":").substringBefore(" (")
    }

    private fun syncServerBindWithSpinnerSelection(spinner: android.widget.Spinner) {
        val selectedItem = spinner.selectedItem?.toString() ?: return
        val bindIp = getBindIpFromSpinnerItem(selectedItem)
        updateServerBindIfNeeded(bindIp)
    }

    private fun updateServerBindIfNeeded(bindIp: String) {
        if (bindIp == currentServerBindIp) {
            return
        }

        currentServerBindIp = bindIp
        streamingServerHelper?.updateBindIpAddress(bindIp)
        restartStreamingServer()
        hasServerConnection = false
        updateServerConnectionIndicator(false)
        startAutoIpRefreshUntilConnected()
        startLanDiscoveryBeacon()
    }

    private fun updateServerConnectionIndicator(connected: Boolean) {
        runOnUiThread {
            val statusTextView = serverConnectionStatusText ?: findViewById<TextView>(R.id.serverConnectionStatusText)
            statusTextView ?: return@runOnUiThread
            statusTextView.text = getString(
                if (connected) R.string.server_connection_on else R.string.server_connection_off
            )
            val onlineColor = if (connected) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            statusTextView.setTextColor(onlineColor)

            // Update connection dot indicator
            val connectionDot = findViewById<View>(R.id.connectionDot)
            connectionDot?.let {
                it.background = if (connected) {
                    getDrawable(R.drawable.status_indicator_online)
                } else {
                    getDrawable(R.drawable.status_indicator_offline)
                }
            }
        }
    }

    private fun restartStreamingServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
            kotlinx.coroutines.delay(500) // Small delay to ensure clean shutdown
            streamingServerHelper?.startStreamingServer()
        }
    }

    private fun syncServerBindWithCurrentSelection() {
        val ipAddressSpinner = findViewById<android.widget.Spinner>(R.id.ipAddressSpinner) ?: return
        syncServerBindWithSpinnerSelection(ipAddressSpinner)
    }

    private fun startLanDiscoveryBeacon() {
        lanDiscoveryBeaconHelper?.start(
            getBindIp = {
                currentServerBindIp
                    ?: runCatching {
                        val ipAddressSpinner = findViewById<android.widget.Spinner>(R.id.ipAddressSpinner)
                        val selected = ipAddressSpinner?.selectedItem?.toString()
                        if (selected.isNullOrEmpty()) null else getBindIpFromSpinnerItem(selected)
                    }.getOrNull()
                    ?: getAllLocalIpAddresses().firstOrNull()
                    ?: "127.0.0.1"
            },
            getStreamPort = { getStreamPort() }
        )
    }
    
    private fun updateAudioButtonIcon(button: ImageButton) {
        if (isAudioEnabled) {
            button.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else {
            button.setImageResource(android.R.drawable.ic_lock_silent_mode)
        }
    }
    
    
    override fun onResume() {
        super.onResume()
        // CameraX will automatically resume when activity resumes
        Log.d(TAG, "Activity resumed")
        
        // Refresh IP spinner when activity resumes (silently, no toast)
        refreshIpSpinner(showToast = false)
        syncServerBindWithCurrentSelection()
        if (shouldRestartServerOnResume) {
            restartStreamingServer()
            shouldRestartServerOnResume = false
        }
        startLanDiscoveryBeacon()
        if (!hasServerConnection) {
            startAutoIpRefreshUntilConnected()
        }
        ensureAudioPermissionRequested()
        if (isEp32Enabled) {
            startEp32AutoConnectIfAllowed()
        }
    }

    override fun onStop() {
        super.onStop()
        // Ensure server sockets are cleanly reopened on return to avoid stale handshake state.
        hasServerConnection = false
        updateServerConnectionIndicator(false)
        stopAutoIpRefresh()
        shouldRestartServerOnResume = true
        lanDiscoveryBeaconHelper?.stop()
        ep32BluetoothHelper?.stop()
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister preference change listener
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        
        // Unregister connectivity listener
        unregisterConnectivityListener()
        
        // Cancel any pending restart
        restartRunnable?.let { restartHandler.removeCallbacks(it) }
        
        cameraExecutor.shutdown()
        audioCaptureHelper?.stopRecording()
        tinySAHelper?.stopScanning()
        tinySAHelper?.closeConnection()
        streamingServerHelper?.stopStreamingServer()
        lanDiscoveryBeaconHelper?.stop()
        ep32BluetoothHelper?.stop()
        stopTinySAConnectionCheck()
        unregisterUsbReceiver()
        
        // Stop Tailscale update handler
        tailscaleUpdateRunnable?.let { tailscaleUpdateHandler.removeCallbacks(it) }
        tailscaleUpdateRunnable = null
        stopAutoIpRefresh()
    }
    
    private fun unregisterConnectivityListener() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering network callback: ${e.message}")
                }
            }
            networkCallback = null
        } else {
            connectivityReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering connectivity receiver: ${e.message}")
                }
            }
            connectivityReceiver = null
        }
    }

    /**
     * Inicia la verificación periódica de conexión TinySA
     */
    private fun startTinySAConnectionCheck() {
        tinysaCheckRunnable = object : Runnable {
            override fun run() {
                checkTinySAConnection()
                tinysaCheckHandler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
        tinysaCheckHandler.post(tinysaCheckRunnable!!)
    }
    
    /**
     * Detiene la verificación de conexión TinySA
     */
    private fun stopTinySAConnectionCheck() {
        tinysaCheckRunnable?.let { tinysaCheckHandler.removeCallbacks(it) }
        tinysaCheckRunnable = null
    }
    
    /**
     * Verifica si TinySA está conectado
     */
    private fun checkTinySAConnection() {
        val wasConnected = isTinySAConnected
        val helperConnected = tinySAHelper?.isConnected() == true
        val driver = if (helperConnected) null else tinySAHelper?.findTinySADevice()
        val isConnected = helperConnected || driver != null
        
        if (isConnected != wasConnected) {
            isTinySAConnected = isConnected
            runOnUiThread {
                showTinySAStatus(isTinySAConnected)
                if (isTinySAConnected && driver != null) {
                    // Request USB permission if needed
                    requestTinySAPermission(driver?.device)
                } else {
                    // Device disconnected
                    Log.d(TAG, "TinySA desconectado")
                    tinySAHelper?.stopScanning()
                    streamingServerHelper?.dropTinySADataClients()
                }
            }
        }
    }
    
    /**
     * Registra el BroadcastReceiver para detectar dispositivos USB
     */
    private fun registerUsbReceiver() {
        try {
            val filter = IntentFilter()
            filter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            filter.addAction("android.hardware.usb.action.USB_DEVICE_PERMISSION")
            
            usbReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let { intentNotNull ->
                        when (intentNotNull.action) {
                            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                                val device: UsbDevice? = intentNotNull.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                                device?.let {
                                    Log.d(TAG, "USB device attached: ${it.deviceName}")
                                    
                                    // Handle TinySA
                                    if (it.vendorId == 0x0483 && it.productId == 0x5740) {
                                        Log.d(TAG, "TinySA detectado, solicitando permiso...")
                                        requestTinySAPermission(it)
                                    }
                                    
                                    // ADB connection detected (no automatic IP change)
                                    Log.d(TAG, "ADB connection detected")
                                }
                            }
                            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                                val device: UsbDevice? = intentNotNull.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                                device?.let {
                                    Log.d(TAG, "USB device detached: ${it.deviceName}")
                                    
                                    // Handle TinySA
                                    if (it.vendorId == 0x0483 && it.productId == 0x5740) {
                                        Log.d(TAG, "TinySA desconectado")
                                        isTinySAConnected = false
                                        runOnUiThread {
                                            showTinySAStatus(false)
                                        }
                                    }
                                    
                                    // ADB disconnection detected (no automatic IP change)
                                    Log.d(TAG, "ADB disconnection detected")
                                }
                            }
                            "android.hardware.usb.action.USB_DEVICE_PERMISSION" -> {
                                val device: UsbDevice? = intentNotNull.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                                if (intentNotNull.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                    device?.let {
                                        if (it.vendorId == 0x0483 && it.productId == 0x5740) {
                                            Log.d(TAG, "Permiso USB concedido para TinySA")
                                            checkTinySAConnection()
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "Permiso USB denegado para TinySA")
                                }
                            }
                            else -> {
                                // Other USB actions, ignore
                            }
                        }
                    }
                }
            }
            registerReceiver(usbReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando USB receiver: ${e.message}", e)
        }
    }
    
    /**
     * Desregistra el BroadcastReceiver USB
     */
    private fun unregisterUsbReceiver() {
        usbReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error desregistrando USB receiver: ${e.message}")
            }
        }
        usbReceiver = null
    }
    
    /**
     * Solicita permiso USB para TinySA
     */
    private fun requestTinySAPermission(device: UsbDevice?) {
        try {
            if (device == null) return
            
            val usbManager = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager no disponible")
                return
            }
            
            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "Ya tiene permiso para TinySA")
                isTinySAConnected = true
                runOnUiThread {
                    showTinySAStatus(true)
                    Toast.makeText(this, "Conexión con TinySA exitosa", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "Solicitando permiso USB para TinySA...")
                val permissionIntent = android.app.PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("android.hardware.usb.action.USB_DEVICE_PERMISSION"),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error solicitando permiso USB: ${e.message}", e)
        }
    }
    
    /**
     * Muestra u oculta el indicador de estado TinySA
     */
    private fun showTinySAStatus(connected: Boolean) {
        val statusText = findViewById<TextView>(R.id.tinysaStatusText)
        statusText?.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
    }
    
    /**
     * Maneja comandos recibidos del cliente para controlar TinySA
     */
    private fun handleTinySACommand(commandBody: String) {
        try {
            Log.d(TAG, "Procesando comando TinySA: $commandBody")
            val action = TinySACommandParser.parseAction(commandBody)

            when (action) {
                "stop" -> {
                    tinySAHelper?.stopScanning()
                    streamingServerHelper?.dropTinySADataClients()
                    Log.d(TAG, "TinySA detenido por comando")
                }
                "start" -> {
                    tinySAHelper?.startScanning()
                    Log.d(TAG, "TinySA iniciado por comando")
                }
                "set_sequence" -> {
                    val configs = TinySACommandParser.parseSequence(commandBody)
                    if (configs.isNotEmpty()) {
                        tinySAHelper?.setSequence(configs)
                        Log.d(TAG, "Secuencia TinySA configurada: ${configs.size} rangos")
                    } else {
                        Log.w(TAG, "Comando set_sequence sin rangos validos")
                    }
                }
                else -> {
                    Log.w(TAG, "Acción TinySA desconocida: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando comando TinySA: ${e.message}", e)
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val IP_AUTO_REFRESH_INTERVAL_MS = 2000L
        private const val MAX_RECENT_DETECTIONS = 25
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_AUDIO_PERMISSION = 11
        private const val REQUEST_CODE_EP32_PERMISSIONS = 12
        private const val MAX_CLIENTS = 3  // Limit concurrent connections
        private const val PREF_EP32_ENABLED = "ep32_bluetooth_enabled"

        // ── Selector de fuente de audio ──────────────────────────────────
        // Persistido en SharedPreferences. Default = micrófono del móvil
        // (PHONE_MIC) para no romper a usuarios existentes.
        const val PREF_AUDIO_SOURCE = "audio_source"
        const val AUDIO_SOURCE_PHONE_MIC = "phone_mic"
        const val AUDIO_SOURCE_ESP32_ARRAY = "esp32_array"

        // El servidor espera PCM16 mono a este sample rate.
        private const val ANDROID_AUDIO_SAMPLE_RATE = 44100
        // El array ESP32 emite PCM16 mono a 8 kHz por SPP (limite ancho de
        // banda Bluetooth Classic). Resampleamos a 44100 con interp lineal.
        private const val ARRAY_AUDIO_SOURCE_RATE = 8000
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
}

