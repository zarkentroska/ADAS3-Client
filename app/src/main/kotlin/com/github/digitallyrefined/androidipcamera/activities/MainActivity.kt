package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.ImageButton
import android.widget.Toast
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
import com.github.digitallyrefined.androidipcamera.helpers.StreamingServerHelper
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
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "image_quality", "stream_delay" -> {
                // These changes don't require camera restart, just log
                Log.d(TAG, "Preference changed: $key")
            }
            "camera_resolution" -> {
                // Resolution change requires camera restart (handled in SettingsActivity)
                Log.d(TAG, "Resolution changed, will restart camera")
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

    private fun startStreamingServer() {
        try {
            // Get certificate path from preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val useCertificate = prefs.getBoolean("use_certificate", false)
            val certificatePath = if (useCertificate) prefs.getString("certificate_path", null) else null
            val certificatePassword = if (useCertificate) {
                prefs.getString("certificate_password", "")?.let {
                    if (it.isEmpty()) null else it.toCharArray()
                }
            } else null

            // Create server socket with specific bind address
            val streamPortForServer = getStreamPort()
            streamingServerHelper = StreamingServerHelper(this, streamPortForServer)
            streamingServerHelper?.startStreamingServer()

            Log.i(TAG, "Server started on port $streamPortForServer (${if (certificatePath != null) "HTTPS" else "HTTP"})")

        } catch (e: IOException) {
            Log.e(TAG, "Could not start server: ${e.message}")
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

        // Read audio settings from preferences
        val prefsForAudio = PreferenceManager.getDefaultSharedPreferences(this)
        val audioChannelsPref = prefsForAudio.getString("audio_channels", "mono") ?: "mono"
        val audioSampleRatePref = prefsForAudio.getString("audio_sample_rate", "44100")?.toIntOrNull() ?: 44100
        
        // Get stream port from preferences
        val streamPort = getStreamPort()
        
        // Get default IP address (Tailscale > WiFi/LAN > Mobile Data)
        val defaultIpForServer = getDefaultIpAddress()
        val savedBindIp = prefsForAudio.getString("selected_bind_ip", defaultIpForServer)
        // Handle ADB option: convert "ADB" to "127.0.0.1"
        val bindIp = when (savedBindIp) {
            "ADB", "127.0.0.1" -> "127.0.0.1"
            null -> defaultIpForServer
            else -> savedBindIp
        } ?: defaultIpForServer
        
        // Start streaming server with TinySA command handler
        streamingServerHelper = StreamingServerHelper(
            this, 
            streamPort,
            maxClients = MAX_CLIENTS,
            onLog = { Log.d(TAG, it) },
            onClientConnected = {},
            onClientDisconnected = {},
            onTinySACommand = { commandBody ->
                handleTinySACommand(commandBody)
            },
            getTinySAStatus = {
                isTinySAConnected
            },
            bindIpAddress = bindIp
        )
        // Configure audio settings for HTTP headers
        streamingServerHelper?.audioSampleRate = audioSampleRatePref
        streamingServerHelper?.audioChannels = if (audioChannelsPref == "stereo") 2 else 1
        
        lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.startStreamingServer() }
        
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
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)
        
        // Hide the TextView to avoid duplication
        ipAddressText.visibility = View.GONE

        // Get and display all IP addresses
        val ipAddresses = getAllLocalIpAddresses()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Get stream port from preferences for spinner
        val streamPortForSpinner = getStreamPort()
        
        // Get default IP address (prioritize Tailscale)
        val defaultIp = getDefaultIpAddress()
        
        // Check if ADB is connected
        val adbConnected = isAdbConnected()
        
        // Configure IP address spinner (only IPs, no "Todas") - show IP:port
        val spinnerItems = buildIpSpinnerItems(ipAddresses, streamPortForSpinner)
        
        // Create adapter with white text color
        val adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            spinnerItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(android.graphics.Color.WHITE)
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(android.graphics.Color.BLACK)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipAddressSpinner.adapter = adapter
        
        // Load saved IP selection or use default IP
        // If ADB is connected, automatically select ADB option
        val savedIp = prefs.getString("selected_bind_ip", null)
        
        // If ADB is connected, prioritize ADB selection
        val ipToUse = if (adbConnected) {
            "ADB (127.0.0.1:$streamPortForSpinner)"
        } else {
            val savedIpWithPort = savedIp?.let { 
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
            }
            val savedIpExists = savedIp != null && spinnerItems.contains(savedIpWithPort)
            if (savedIpExists) savedIpWithPort else {
                defaultIp?.let {
                    if (it.startsWith("100.")) {
                        "$it:$streamPortForSpinner (Tailscale)"
                    } else {
                        "$it:$streamPortForSpinner"
                    }
                }
            }
        }
        
        val selectedIndex = spinnerItems.indexOfFirst { it == ipToUse }.takeIf { it >= 0 } 
            ?: spinnerItems.indexOfFirst { it.contains(defaultIp ?: "") }.takeIf { it >= 0 } ?: 0
        
        // Ensure we have a valid IP (extract IP without port for saving)
        val currentIpWithPort = if (selectedIndex >= 0 && selectedIndex < spinnerItems.size) {
            spinnerItems[selectedIndex]
        } else {
            defaultIp?.let {
                if (it.startsWith("100.")) {
                    "$it:$streamPortForSpinner (Tailscale)"
                } else {
                    "$it:$streamPortForSpinner"
                }
            } ?: ipAddresses.firstOrNull()?.let { 
                if (it.startsWith("100.")) {
                    "$it:$streamPortForSpinner (Tailscale)"
                } else {
                    "$it:$streamPortForSpinner"
                }
            } ?: ""
        }
        val currentIp = if (currentIpWithPort.startsWith("ADB")) {
            "ADB"
        } else {
            currentIpWithPort.substringBefore(":").substringBefore(" (")
        }
        
        // Save the current IP (force save if we're using default or ADB is connected)
        if (currentIp.isNotEmpty() && (adbConnected && currentIp == "ADB" || currentIp != savedIp)) {
            prefs.edit().putString("selected_bind_ip", currentIp).apply()
        }
        
        ipAddressSpinner.setSelection(selectedIndex)
        
        // Setup refresh button
        val refreshIpButton = findViewById<ImageButton>(R.id.refreshIpButton)
        refreshIpButton?.setOnClickListener {
            refreshIpSpinner()
        }
        
        // Setup Tailscale switch
        setupTailscaleSwitch()
        
        // Handle IP selection change
        ipAddressSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position < 0 || position >= spinnerItems.size) return
                
                val selectedIpWithPort = spinnerItems[position]
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
                prefs.edit().putString("selected_bind_ip", ipToSave).apply()
                
                // Determine bind IP: use 127.0.0.1 for ADB, otherwise use selected IP
                val bindIp = if (selectedIpWithPort.startsWith("ADB")) {
                    "127.0.0.1"
                } else {
                    selectedIp
                }
                
                // Update server bind IP and restart
                streamingServerHelper?.updateBindIpAddress(bindIp)
                lifecycleScope.launch(Dispatchers.IO) {
                    streamingServerHelper?.stopStreamingServer()
                    kotlinx.coroutines.delay(500) // Small delay to ensure clean shutdown
                    streamingServerHelper?.startStreamingServer()
                }
                
                Log.d(TAG, "Changed bind IP to: $bindIp (${if (selectedIpWithPort.startsWith("ADB")) "ADB mode" else "normal"})")
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

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
        
        // Initialize audio capture helper with preferences
        val audioChannels = prefs.getString("audio_channels", "mono") ?: "mono"
        val audioSampleRate = prefs.getString("audio_sample_rate", "44100")?.toIntOrNull() ?: 44100
        val channelConfig = if (audioChannels == "stereo") {
            android.media.AudioFormat.CHANNEL_IN_STEREO
        } else {
            android.media.AudioFormat.CHANNEL_IN_MONO
        }
        audioCaptureHelper = AudioCaptureHelper(audioSampleRate, channelConfig)
        audioCaptureHelper?.addAudioDataListener { audioData ->
            if (isAudioEnabled && streamingServerHelper?.getAudioClients()?.isNotEmpty() == true) {
                streamingServerHelper?.sendAudioData(audioData)
            }
        }
        
        // Add audio toggle button
        val audioToggleButton = findViewById<ImageButton>(R.id.audioToggleButton)
        updateAudioButtonIcon(audioToggleButton)
        audioToggleButton.setOnClickListener {
            toggleAudio()
            updateAudioButtonIcon(audioToggleButton)
        }
        
        // Register connectivity change listener
        registerConnectivityListener()
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
                if (audioCaptureHelper?.startRecording() == true) {
                    isAudioEnabled = true
                    findViewById<ImageButton>(R.id.audioToggleButton)?.let { updateAudioButtonIcon(it) }
                    Toast.makeText(this, getString(R.string.toast_audio_enabled), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_audio_permission_required), Toast.LENGTH_SHORT).show()
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
            
            // Build list in order: USB, Tailscale, WiFi/LAN, Mobile Data
            usbIp?.let { ipAddresses.add(it) }
            ipAddresses.addAll(tailscaleIps)
            ipAddresses.addAll(wifiLanIps)
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
        
        // Create new adapter
        val adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            spinnerItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(android.graphics.Color.WHITE)
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(android.graphics.Color.BLACK)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipAddressSpinner.adapter = adapter
        
        // Try to restore previous selection or select default
        val defaultIp = getDefaultIpAddress()
        val adbConnected = isAdbConnected()
        
        // Always prioritize Tailscale IP if available
        val tailscaleIp = ipAddresses.firstOrNull { it.startsWith("100.") }
        val tailscaleIpWithPort = tailscaleIp?.let {
            "$it:$streamPortForSpinner (Tailscale)"
        }
        
        val ipToSelect = when {
            adbConnected -> "ADB (127.0.0.1:$streamPortForSpinner)"
            tailscaleIpWithPort != null && spinnerItems.contains(tailscaleIpWithPort) -> tailscaleIpWithPort
            currentIp == "ADB" -> "ADB (127.0.0.1:$streamPortForSpinner)"
            currentIp.isNotEmpty() -> {
                // Try to find the current IP in the new list
                val currentIpWithPort = if (currentIp.startsWith("100.")) {
                    "$currentIp:$streamPortForSpinner (Tailscale)"
                } else {
                    "$currentIp:$streamPortForSpinner"
                }
                if (spinnerItems.contains(currentIpWithPort)) {
                    currentIpWithPort
                } else {
                    defaultIp?.let { 
                        if (it.startsWith("100.")) {
                            "$it:$streamPortForSpinner (Tailscale)"
                        } else {
                            "$it:$streamPortForSpinner"
                        }
                    }
                }
            }
            else -> defaultIp?.let { 
                if (it.startsWith("100.")) {
                    "$it:$streamPortForSpinner (Tailscale)"
                } else {
                    "$it:$streamPortForSpinner"
                }
            }
        }
        
        val selectedIndex = ipToSelect?.let { spinnerItems.indexOfFirst { item -> item == it } }?.takeIf { it >= 0 } ?: 0
        ipAddressSpinner.setSelection(selectedIndex)
        
        // Update saved IP if needed
        val newSelectedItem = spinnerItems[selectedIndex]
        val newIp = if (newSelectedItem.startsWith("ADB")) {
            "ADB"
        } else {
            newSelectedItem.substringBefore(":").substringBefore(" (")
        }
        prefs.edit().putString("selected_bind_ip", newIp).apply()
        
        if (showToast) {
            Toast.makeText(this, getString(R.string.toast_ips_updated), Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "IP spinner refreshed. Found ${ipAddresses.size} IPs")
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
    
    private fun updateIpSpinnerForAdb(selectAdb: Boolean) {
        val ipAddressSpinner = findViewById<android.widget.Spinner>(R.id.ipAddressSpinner) ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val streamPortForSpinner = getStreamPort()
        
        if (selectAdb) {
            // Save current IP before switching to ADB
            val currentSelection = ipAddressSpinner.selectedItem?.toString() ?: ""
            if (!currentSelection.startsWith("ADB")) {
                val currentIp = currentSelection.substringBefore(":")
                if (currentIp.isNotEmpty() && currentIp != "ADB" && currentIp != "127.0.0.1") {
                    prefs.edit().putString("previous_bind_ip", currentIp).apply()
                    Log.d(TAG, "Saved previous IP before switching to ADB: $currentIp")
                }
            }
            
            // Find and select ADB option
            val adapter = ipAddressSpinner.adapter as? android.widget.ArrayAdapter<*>
            if (adapter != null) {
                for (i in 0 until adapter.count) {
                    val item = adapter.getItem(i)?.toString() ?: ""
                    if (item.startsWith("ADB")) {
                        ipAddressSpinner.setSelection(i, false)
                        // Trigger selection change manually
                        val selectedIp = "ADB"
                        prefs.edit().putString("selected_bind_ip", selectedIp).apply()
                        
                        val bindIp = "127.0.0.1"
                        streamingServerHelper?.updateBindIpAddress(bindIp)
                        lifecycleScope.launch(Dispatchers.IO) {
                            streamingServerHelper?.stopStreamingServer()
                            kotlinx.coroutines.delay(500)
                            streamingServerHelper?.startStreamingServer()
                        }
                        Log.d(TAG, "Automatically switched to ADB mode")
                        break
                    }
                }
            }
        } else {
            // Restore previous IP
            val previousIp = prefs.getString("previous_bind_ip", null)
            if (previousIp != null) {
                val adapter = ipAddressSpinner.adapter as? android.widget.ArrayAdapter<*>
                if (adapter != null) {
                    val previousIpWithPort = "$previousIp:$streamPortForSpinner"
                    for (i in 0 until adapter.count) {
                        val item = adapter.getItem(i)?.toString() ?: ""
                        if (item == previousIpWithPort) {
                            ipAddressSpinner.setSelection(i, false)
                            prefs.edit().putString("selected_bind_ip", previousIp).apply()
                            
                            streamingServerHelper?.updateBindIpAddress(previousIp)
                            lifecycleScope.launch(Dispatchers.IO) {
                                streamingServerHelper?.stopStreamingServer()
                                kotlinx.coroutines.delay(500)
                                streamingServerHelper?.startStreamingServer()
                            }
                            Log.d(TAG, "Restored previous IP: $previousIp")
                            break
                        }
                    }
                }
            }
        }
    }
    
    private fun hidePreview() {
        val viewFinder = viewBinding.viewFinder
        val rootView = viewBinding.root
        val ipAddressContainer = findViewById<android.view.ViewGroup>(R.id.ipAddressContainer)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val switchCameraButton = findViewById<ImageButton>(R.id.switchCameraButton)
        val hidePreviewButton = findViewById<ImageButton>(R.id.hidePreviewButton)
        val audioToggleButton = findViewById<ImageButton>(R.id.audioToggleButton)

        if (viewFinder.isVisible) {
            viewFinder.visibility = View.GONE
            ipAddressContainer.visibility = View.GONE
            settingsButton.visibility = View.GONE
            switchCameraButton.visibility = View.GONE
            audioToggleButton.visibility = View.GONE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_slideshow) // use open eye as placeholder for closed eye
        } else {
            viewFinder.visibility = View.VISIBLE
            ipAddressContainer.visibility = View.VISIBLE
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
            audioCaptureHelper?.stopRecording()
            isAudioEnabled = false
            Toast.makeText(this, getString(R.string.toast_audio_disabled), Toast.LENGTH_SHORT).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (audioCaptureHelper?.startRecording() == true) {
                    isAudioEnabled = true
                    Toast.makeText(this, getString(R.string.toast_audio_enabled), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Error al iniciar la captura de audio", Toast.LENGTH_SHORT).show()
                }
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO_PERMISSION)
            }
        }
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
        streamingServerHelper?.closeClientConnection()
        stopTinySAConnectionCheck()
        unregisterUsbReceiver()
        
        // Stop Tailscale update handler
        tailscaleUpdateRunnable?.let { tailscaleUpdateHandler.removeCallbacks(it) }
        tailscaleUpdateRunnable = null
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
                                    
                                    // Check if ADB is now connected and switch to ADB mode
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (isAdbConnected()) {
                                            Log.d(TAG, "ADB detected, switching to ADB mode")
                                            updateIpSpinnerForAdb(true)
                                        }
                                    }, 1000) // Small delay to ensure ADB is fully initialized
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
                                    
                                    // Check if ADB is no longer connected and restore previous IP
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (!isAdbConnected()) {
                                            Log.d(TAG, "ADB disconnected, restoring previous IP")
                                            updateIpSpinnerForAdb(false)
                                        }
                                    }, 1000) // Small delay to ensure ADB is fully disconnected
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
            // Parsear JSON del comando
            Log.d(TAG, "Procesando comando TinySA: $commandBody")
            
            // Parseo simple de JSON usando regex (más robusto que antes)
            val actionMatch = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(commandBody)
            val action = actionMatch?.groupValues?.get(1)
            
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
                    parseAndSetTinySASequence(commandBody)
                }
                else -> {
                    Log.w(TAG, "Acción TinySA desconocida: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando comando TinySA: ${e.message}", e)
        }
    }
    
    /**
     * Parsea y configura la secuencia de barridos de TinySA usando Gson
     */
    private fun parseAndSetTinySASequence(json: String) {
        try {
            // Parseo mejorado usando regex para encontrar objetos en el array
            val sequenceStart = json.indexOf("\"sequence\"")
            if (sequenceStart == -1) return
            
            val arrayStart = json.indexOf('[', sequenceStart)
            if (arrayStart == -1) return
            
            val configs = mutableListOf<TinySAHelper.ScanConfig>()
            
            // Buscar todos los objetos { } en el array
            var pos = arrayStart + 1
            var depth = 0
            var objStart = -1
            
            while (pos < json.length) {
                when (json[pos]) {
                    '{' -> {
                        if (depth == 0) objStart = pos
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && objStart >= 0) {
                            val objStr = json.substring(objStart, pos + 1)
                            val start = extractLongValue(objStr, "start") ?: continue
                            val stop = extractLongValue(objStr, "stop") ?: continue
                            val points = extractIntValue(objStr, "points") ?: 290
                            val sweeps = extractIntValue(objStr, "sweeps") ?: 5
                            val label = extractStringValue(objStr, "label") ?: ""
                            
                            configs.add(TinySAHelper.ScanConfig(start, stop, points, sweeps, label))
                            objStart = -1
                        }
                    }
                }
                pos++
            }
            
            if (configs.isNotEmpty()) {
                tinySAHelper?.setSequence(configs)
                Log.d(TAG, "Secuencia TinySA configurada: ${configs.size} rangos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando secuencia: ${e.message}", e)
        }
    }
    
    private fun extractLongValue(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
    
    private fun extractIntValue(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractStringValue(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_PORT = 8080
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_AUDIO_PERMISSION = 11
        private const val MAX_CLIENTS = 3  // Limit concurrent connections
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

