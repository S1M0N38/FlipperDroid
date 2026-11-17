package com.example.flipperdroid.viewmodel

import com.example.flipperdroid.util.csvField

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.flipperdroid.util.WifiChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An observed access point, tied to a GPS position. */
data class WardriveEntry(
    val mac: String,
    val ssid: String,
    val auth: String,
    val firstSeen: String,
    val channel: Int,
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val accuracy: Double
)


/**
 * Native wardriving (inspired by Evil-M5Project): combines Wi-Fi scanning and
 * GPS position to map networks, with export in Wigle CSV format.
 *
 * Android limitation: Wi-Fi scans are throttled (≈4 scans / 2 min in the foreground
 * since Android 9), so the cadence is deliberately slow.
 */
class WardrivingViewModel(app: Application) : AndroidViewModel(app) {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = app.applicationContext
    private var wifiManager: WifiManager? = null
    private var locationManager: LocationManager? = null

    /**
     * Collected networks, key = BSSID. Written on the main thread (scan results)
     * and read on IO (export): every access goes through synchronized(entriesMap).
     */
    private val entriesMap = LinkedHashMap<String, WardriveEntry>()

    private val _entries = MutableStateFlow<List<WardriveEntry>>(emptyList())
    val entries: StateFlow<List<WardriveEntry>> = _entries

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    @Volatile private var lastLocation: Location? = null
    private var scanJob: Job? = null
    private var scanReceiver: BroadcastReceiver? = null

    private val locationListener = LocationListener { location -> lastLocation = location }

    fun initialize() {
        wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_isRunning.value) return
        if (!hasPermissions()) { _status.value = "Location permission required"; return }
        initialize()
        if (wifiManager == null) { _status.value = "Wi-Fi service unavailable"; return }
        _isRunning.value = true
        _status.value = "Wardriving started..."

        // GPS updates: each provider in its own try, a missing provider
        // must not prevent the other one from working.
        val lm = locationManager
        if (lm != null) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(provider, 1000L, 0f, locationListener)
                } catch (e: Exception) {
                    Log.e("Wardriving", "requestLocationUpdates($provider): ${e.message}")
                }
            }
            lastLocation = lastKnown(lm, LocationManager.GPS_PROVIDER)
                ?: lastKnown(lm, LocationManager.NETWORK_PROVIDER)
        } else {
            _status.value = "LocationManager unavailable: no GPS fix possible"
        }

        // Scan results receiver
        registerScanReceiver()

        // Scan loop
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _isRunning.value) {
                try { wifiManager?.startScan() } catch (_: Exception) {}
                delay(30_000) // respects the Android scan throttling
            }
        }
    }

    private fun lastKnown(lm: LocationManager, provider: String): Location? =
        try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }

    private fun registerScanReceiver() {
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    processResults()
                }
            }
        }
        context.registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    @SuppressLint("MissingPermission")
    private fun processResults() {
        val loc = lastLocation
        if (loc == null) { _status.value = "Waiting for GPS fix..."; return }
        try {
            val results = wifiManager?.scanResults ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            var added = 0
            val snapshot = synchronized(entriesMap) {
                for (r in results) {
                    val bssid: String = r.BSSID?.takeIf { it.isNotEmpty() } ?: continue
                    val previous = entriesMap[bssid]
                    if (previous == null) added++
                    entriesMap[bssid] = WardriveEntry(
                        mac = bssid,
                        ssid = r.SSID ?: "",
                        auth = r.capabilities ?: "",
                        // First observation kept across updates.
                        firstSeen = previous?.firstSeen ?: timestamp,
                        channel = WifiChannels.frequencyToChannel(r.frequency),
                        rssi = r.level,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy.toDouble()
                    )
                }
                entriesMap.values.toList()
            }
            _entries.value = snapshot
            _status.value = "Networks: ${snapshot.size} (+$added last scan)"
        } catch (e: SecurityException) {
            _status.value = "Permission error during scan"
        }
    }

    fun stop() {
        _isRunning.value = false
        scanJob?.cancel()
        scanJob = null
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        try { scanReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        scanReceiver = null
        val count = synchronized(entriesMap) { entriesMap.size }
        _status.value = "Stopped. $count networks collected."
    }

    /** Exports the networks in Wigle CSV format (WigleWifi-1.4). */
    fun exportCsv() {
        val hasEntries = synchronized(entriesMap) { entriesMap.isNotEmpty() }
        if (!hasEntries) { _status.value = "Nothing to export"; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val dir = app.getExternalFilesDir("wardriving") ?: app.filesDir
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "wardrive_$ts.csv")
                // Copy under lock: the export must not iterate the map during a scan.
                val snapshot = synchronized(entriesMap) { entriesMap.values.toList() }
                val sb = StringBuilder()
                sb.append("WigleWifi-1.4,appRelease=FlipperDroid,model=Android,release=Android,device=Android,display=,board=,brand=\n")
                sb.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
                for (e in snapshot) {
                    sb.append(csvField(e.mac)).append(',')
                        .append(csvField(e.ssid)).append(',')
                        .append(csvField(e.auth)).append(',')
                        .append(csvField(e.firstSeen)).append(',')
                        .append(e.channel).append(',')
                        .append(e.rssi).append(',')
                        .append(e.lat).append(',')
                        .append(e.lon).append(',')
                        .append(e.altitude).append(',')
                        .append(e.accuracy).append(",WIFI\n")
                }
                file.writeText(sb.toString())
                withContext(Dispatchers.Main) { _status.value = "Exported: ${file.absolutePath}" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _status.value = "Export error: ${e.message}" }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
