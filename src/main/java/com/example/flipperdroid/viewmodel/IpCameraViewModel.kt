package com.example.flipperdroid.viewmodel

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.flipperdroid.camera.CameraCredential
import com.example.flipperdroid.camera.DEFAULT_CAMERA_CREDENTIALS
import com.example.flipperdroid.camera.IpCamera
import com.example.flipperdroid.camera.OnvifTools
import com.example.flipperdroid.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Discovery / reconnaissance / audit / PTZ control of IP cameras (ONVIF + RTSP).
 *
 * - Discovery: WS-Discovery (multicast) + "camera" port scan on a /24.
 * - Reconnaissance: port heuristic, RTSP probe, GetDeviceInformation.
 * - Audit: default-credential dictionary tested via WS-UsernameToken,
 *   on the same principle as the Mifare key dictionary.
 * - PTZ: ONVIF ContinuousMove / Stop.
 *
 * Project principle: honesty about capability. Every step clearly reports
 * failure (timeout, 401, SOAP Fault) instead of pretending.
 * Reserved for authorized testing on hardware we have permission for.
 */
class IpCameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _cameras = MutableStateFlow<List<IpCamera>>(emptyList())
    val cameras: StateFlow<List<IpCamera>> = _cameras

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** ONVIF service path (reused for device/media/ptz on the cameras
     * that route everything through a single endpoint; otherwise the failure is reported). */
    val onvifPath = MutableStateFlow("/onvif/device_service")

    private val rng = SecureRandom()

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** WS-Discovery: emits a multicast Probe and collects the ONVIF cameras. */
    fun discover() {
        if (_isBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _status.value = "WS-Discovery (ONVIF)…"
            var lock: WifiManager.MulticastLock? = null
            try {
                // Multicast lock: without it some phones filter the
                // WS-Discovery replies. Best-effort — never blocking.
                val wifi = getApplication<Application>()
                    .applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("flipperdroid-onvif")?.apply {
                    setReferenceCounted(true); acquire()
                }
                val socket = DatagramSocket()
                socket.soTimeout = 4000
                socket.broadcast = true
                val probe = OnvifTools.wsDiscoveryProbe("uuid:" + UUID.randomUUID()).toByteArray()
                val group = InetAddress.getByName(OnvifTools.WS_DISCOVERY_ADDR)
                socket.send(DatagramPacket(probe, probe.size, group, OnvifTools.WS_DISCOVERY_PORT))
                val buf = ByteArray(8192)
                var found = 0
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (e: Exception) { break }
                    val xml = String(packet.data, 0, packet.length)
                    val host = packet.address?.hostAddress ?: continue
                    val xaddrs = OnvifTools.parseXAddrs(xml)
                    upsert(host) { it.copy(xaddr = xaddrs.firstOrNull() ?: it.xaddr,
                        vendorHint = if (it.vendorHint.isEmpty()) "ONVIF" else it.vendorHint) }
                    found++
                }
                socket.close()
                _status.value = "WS-Discovery done. ${_cameras.value.size} host(s)."
                AppLog.log("IpCamera", "WS-Discovery: $found reply/replies")
            } catch (e: Exception) {
                _status.value = "Discovery error: ${e.message}"
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                _isBusy.value = false
            }
        }
    }

    /** Manual addition of a target (IP or host) entered by the user. */
    fun addManual(host: String) {
        val h = host.trim()
        if (h.isEmpty()) return
        upsert(h) { it }
        _status.value = "Added $h"
    }

    /**
     * Scan of the "camera" ports (OnvifTools.CAMERA_PORTS) on every host of a
     * /24 derived from baseIp, then heuristic classification. No root.
     */
    fun scanSubnet(baseIp: String) {
        if (_isBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            val prefix = baseIp.substringBeforeLast('.', "")
            if (prefix.isEmpty() || !baseIp.contains('.')) {
                _status.value = "Invalid IP"; _isBusy.value = false; return@launch
            }
            _status.value = "Scanning $prefix.0/24 for camera ports…"
            val hits = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<Int>>>())
            val threads = (1..254).map { n ->
                Thread {
                    val ip = "$prefix.$n"
                    val open = OnvifTools.CAMERA_PORTS.filter { port -> isOpen(ip, port, 250) }
                    if (open.isNotEmpty()) hits.add(ip to open)
                }.apply { start() }
            }
            threads.forEach { it.join(6000) }
            hits.sortedBy { it.first.substringAfterLast('.').toIntOrNull() ?: 0 }.forEach { (ip, ports) ->
                val guess = OnvifTools.classifyCameraPorts(ports)
                if (guess.isLikelyCamera) {
                    upsert(ip) { it.copy(openPorts = ports, vendorHint =
                        if (it.vendorHint.isEmpty() || it.vendorHint == "ONVIF") guess.vendorHint else it.vendorHint,
                        confidence = maxOf(it.confidence, guess.confidence)) }
                }
            }
            _status.value = "Subnet scan done. ${_cameras.value.size} likely camera(s)."
            _isBusy.value = false
        }
    }

    /** Rescans the ports of an already listed host and updates the heuristic. */
    fun scanHost(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = "Scanning ports on $host…"
            val open = OnvifTools.CAMERA_PORTS.filter { isOpen(host, it, 500) }
            val guess = OnvifTools.classifyCameraPorts(open)
            upsert(host) { it.copy(openPorts = open, confidence = maxOf(it.confidence, guess.confidence),
                vendorHint = if (it.vendorHint.isEmpty() || it.vendorHint == "ONVIF") guess.vendorHint else it.vendorHint) }
            _status.value = "$host: ports ${open.joinToString(",")} (${guess.vendorHint})"
        }
    }

    // ------------------------------------------------------------------
    // Reconnaissance: RTSP probe
    // ------------------------------------------------------------------

    /** RTSP OPTIONS probe on the given port: reports whether the stream requires auth. */
    fun probeRtsp(host: String, port: Int = 554) {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = "RTSP probe $host:$port…"
            val result = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000)
                    s.soTimeout = 3000
                    val req = OnvifTools.rtspOptions("rtsp://$host:$port/", 1)
                    s.getOutputStream().apply { write(req.toByteArray()); flush() }
                    val buf = ByteArray(1024)
                    val n = s.getInputStream().read(buf)
                    if (n <= 0) "no response" else {
                        val auth = OnvifTools.parseRtspAuth(String(buf, 0, n))
                        when {
                            auth.status in 200..299 -> "OPEN (no auth) — status ${auth.status}"
                            auth.needsAuth -> "Auth required (${auth.scheme}${if (auth.realm.isNotEmpty()) ", realm=\"${auth.realm}\"" else ""})"
                            else -> "status ${auth.status}"
                        }
                    }
                }
            } catch (e: Exception) { "error: ${e.message}" }
            upsert(host) { it.copy(rtsp = result) }
            _status.value = "RTSP $host:$port → $result"
        }
    }

    // ------------------------------------------------------------------
    // GetDeviceInformation (reconnaissance / credential verification)
    // ------------------------------------------------------------------

    /** Queries GetDeviceInformation (with or without credentials) and shows the model. */
    fun fetchDeviceInfo(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = "GetDeviceInformation $host…"
            val url = serviceUrl(host, port)
            val resp = onvifPost(url, OnvifTools.ACTION_DEVICE_INFO,
                OnvifTools.getDeviceInformationBody(), user, pass)
            val text = describeDeviceInfo(resp)
            upsert(host) { it.copy(deviceInfo = text) }
            _status.value = "$host → $text"
        }
    }

    private fun describeDeviceInfo(resp: OnvifResponse): String {
        if (resp.body == null) return "unreachable (${resp.error ?: "no response"})"
        if (resp.httpCode == 401) return "401 Unauthorized"
        OnvifTools.soapFaultReason(resp.body)?.let { return "Fault: $it" }
        val d = OnvifTools.parseDeviceInformation(resp.body)
        return if (d.manufacturer.isEmpty() && d.model.isEmpty()) "HTTP ${resp.httpCode} (no device info)"
        else "${d.manufacturer} ${d.model} · fw ${d.firmware}".trim()
    }

    // ------------------------------------------------------------------
    // Audit: default-credential dictionary
    // ------------------------------------------------------------------

    /**
     * Tests the default-credential dictionary via ONVIF GetDeviceInformation.
     * Stops at the first valid pair. Same principle as the NFC module's
     * dictionary attack — to check for a weak config, nothing more.
     */
    fun auditDefaultCredentials(host: String, port: Int) {
        if (_isBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            val url = serviceUrl(host, port)
            var hit: CameraCredential? = null
            var faults = 0
            for ((i, cred) in DEFAULT_CAMERA_CREDENTIALS.withIndex()) {
                _status.value = "Audit $host (${i + 1}/${DEFAULT_CAMERA_CREDENTIALS.size}): ${cred.username}:${cred.password.ifEmpty { "<empty>" }}"
                val resp = onvifPost(url, OnvifTools.ACTION_DEVICE_INFO,
                    OnvifTools.getDeviceInformationBody(), cred.username, cred.password)
                if (resp.body == null) {
                    if (resp.error != null) { faults++; if (faults >= 3) break } // host unreachable
                    continue
                }
                val fault = OnvifTools.soapFaultReason(resp.body)
                val d = OnvifTools.parseDeviceInformation(resp.body)
                if (resp.httpCode in 200..299 && fault == null && (d.manufacturer.isNotEmpty() || d.model.isNotEmpty())) {
                    hit = cred
                    upsert(host) { it.copy(
                        foundCredentials = "${cred.username}:${cred.password.ifEmpty { "<empty>" }} (${cred.vendor})",
                        deviceInfo = "${d.manufacturer} ${d.model} · fw ${d.firmware}".trim()) }
                    break
                }
            }
            _status.value = if (hit != null)
                "WEAK: $host accepts default ${hit.username}:${hit.password.ifEmpty { "<empty>" }}"
            else "No default credentials worked on $host (good, or non-ONVIF/hardened)."
            AppLog.log("IpCamera", "Credential audit $host → ${if (hit != null) "weak default found" else "none"}")
            _isBusy.value = false
        }
    }

    // ------------------------------------------------------------------
    // Media profiles (required for PTZ)
    // ------------------------------------------------------------------

    /** Fetches the media profile tokens: essential to drive PTZ. */
    fun fetchProfiles(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = "GetProfiles $host…"
            val resp = onvifPost(serviceUrl(host, port), OnvifTools.ACTION_GET_PROFILES,
                OnvifTools.getProfilesBody(), user, pass)
            if (resp.body == null) { _status.value = "GetProfiles: unreachable"; return@launch }
            val fault = OnvifTools.soapFaultReason(resp.body)
            val tokens = OnvifTools.parseProfileTokens(resp.body)
            upsert(host) { it.copy(profileTokens = tokens) }
            _status.value = when {
                fault != null -> "GetProfiles fault: $fault"
                tokens.isEmpty() -> "No profiles (HTTP ${resp.httpCode})"
                else -> "Profiles: ${tokens.joinToString(", ")}"
            }
        }
    }

    // ------------------------------------------------------------------
    // PTZ
    // ------------------------------------------------------------------

    /** ONVIF ContinuousMove: moves continuously until ptzStop(). */
    fun ptzMove(host: String, port: Int, user: String, pass: String, token: String,
                pan: Float, tilt: Float, zoom: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = token.ifBlank { firstProfile(host) }
            if (profile.isBlank()) { _status.value = "PTZ: no profile token (run GetProfiles first)"; return@launch }
            val resp = onvifPost(serviceUrl(host, port), OnvifTools.ACTION_CONTINUOUS_MOVE,
                OnvifTools.continuousMoveBody(profile, pan, tilt, zoom), user, pass)
            _status.value = ptzResult(host, "move(p=$pan,t=$tilt,z=$zoom)", resp)
        }
    }

    /** ONVIF Stop: stops pan/tilt and zoom. */
    fun ptzStop(host: String, port: Int, user: String, pass: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = token.ifBlank { firstProfile(host) }
            if (profile.isBlank()) { _status.value = "PTZ: no profile token"; return@launch }
            val resp = onvifPost(serviceUrl(host, port), OnvifTools.ACTION_STOP,
                OnvifTools.stopBody(profile), user, pass)
            _status.value = ptzResult(host, "stop", resp)
        }
    }

    private fun ptzResult(host: String, what: String, resp: OnvifResponse): String {
        if (resp.body == null) return "PTZ $what: unreachable (${resp.error ?: "no response"})"
        OnvifTools.soapFaultReason(resp.body)?.let { return "PTZ $what fault: $it" }
        return if (resp.httpCode in 200..299) "PTZ $what OK ($host)" else "PTZ $what: HTTP ${resp.httpCode}"
    }

    private fun firstProfile(host: String): String =
        _cameras.value.firstOrNull { it.host == host }?.profileTokens?.firstOrNull() ?: ""

    // ------------------------------------------------------------------
    // Transport ONVIF (HTTP POST SOAP + WS-UsernameToken)
    // ------------------------------------------------------------------

    private data class OnvifResponse(val httpCode: Int, val body: String?, val error: String? = null)

    /** POSTs SOAP to an ONVIF endpoint; adds the WS-Security token when user is not empty. */
    private fun onvifPost(url: String, action: String, body: String, user: String, pass: String): OnvifResponse {
        val header = if (user.isNotEmpty()) {
            val nonce = ByteArray(16).also { rng.nextBytes(it) }
            OnvifTools.securityHeader(user, pass, nonce, isoUtcNow())
        } else ""
        val envelope = OnvifTools.soapEnvelope(body, header)
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
                setRequestProperty("SOAPAction", "\"$action\"")
            }
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            OnvifResponse(code, text)
        } catch (e: Exception) {
            OnvifResponse(0, null, e.message)
        }
    }

    private fun serviceUrl(host: String, port: Int): String {
        val existing = _cameras.value.firstOrNull { it.host == host }?.xaddr
        // XAddr from WS-Discovery if present (exact endpoint); otherwise we build it.
        if (!existing.isNullOrBlank()) return existing
        val path = onvifPath.value.let { if (it.startsWith("/")) it else "/$it" }
        return "http://$host:$port$path"
    }

    fun clear() { _cameras.value = emptyList(); _status.value = "" }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    private fun isoUtcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    /** Inserts/updates a camera by host, preserving the order of appearance. */
    private fun upsert(host: String, transform: (IpCamera) -> IpCamera) {
        val list = _cameras.value.toMutableList()
        val idx = list.indexOfFirst { it.host == host }
        if (idx >= 0) list[idx] = transform(list[idx])
        else list.add(transform(IpCamera(host)))
        _cameras.value = list
    }
}
