package com.example.flipperdroid.camera

import java.security.MessageDigest

/**
 * Pure logic core (no Android dependency) for discovery, auditing and PTZ
 * control of IP cameras over ONVIF / RTSP.
 *
 * Everything here is deterministic and testable on the JVM (see LogicUnitTest):
 * building SOAP envelopes, the WS-UsernameToken security token, RTSP requests,
 * and parsing the responses. Network I/O lives in IpCameraViewModel.
 *
 * Project principle: stay honest about capability. These tools are only for
 * authorized testing on hardware you have permission to test.
 */
object OnvifTools {

    // --- ONVIF namespaces ---
    private const val NS_SOAP = "http://www.w3.org/2003/05/soap-envelope"
    private const val NS_WSA = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
    private const val NS_WSD = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    private const val NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl"
    /** Namespace of the NetworkVideoTransmitter type (WS-Discovery), distinct from device/wsdl. */
    private const val NS_NETWORK = "http://www.onvif.org/ver10/network/wsdl"
    private const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
    private const val NS_PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
    private const val NS_SCHEMA = "http://www.onvif.org/ver10/schema"
    private const val NS_WSSE =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val NS_WSU =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val TYPE_PASSWORD_DIGEST =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val ENC_BASE64 =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"

    /** Typical IP camera / NVR TCP ports, used by the heuristic and the scan. */
    val CAMERA_PORTS = listOf(80, 443, 554, 8000, 8080, 8443, 8554, 2020, 34567, 37777, 37778, 9000)

    /** Multicast WS-Discovery ONVIF. */
    const val WS_DISCOVERY_ADDR = "239.255.255.250"
    const val WS_DISCOVERY_PORT = 3702

    // ---------------------------------------------------------------------
    // Discovery: WS-Discovery
    // ---------------------------------------------------------------------

    /**
     * Builds the WS-Discovery SOAP Probe message that looks for
     * NetworkVideoTransmitter (ONVIF cameras) on the local network.
     *
     * @param messageId unique identifier (e.g. "uuid:...") — passed in as a
     *   parameter to stay deterministic/testable; the ViewModel supplies a random UUID.
     */
    fun wsDiscoveryProbe(messageId: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="$NS_SOAP" xmlns:w="$NS_WSA" xmlns:d="$NS_WSD" xmlns:dn="$NS_NETWORK">
<e:Header>
<w:MessageID>$messageId</w:MessageID>
<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
</e:Header>
<e:Body>
<d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
</e:Body>
</e:Envelope>"""

    /**
     * Extracts the service URLs (XAddrs) from a WS-Discovery ProbeMatch response.
     * Independent of the namespace prefix (d:XAddrs, wsd:XAddrs, …).
     */
    fun parseXAddrs(xml: String): List<String> {
        val out = LinkedHashSet<String>()
        val regex = Regex("<(?:[A-Za-z0-9]+:)?XAddrs>(.*?)</(?:[A-Za-z0-9]+:)?XAddrs>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in regex.findAll(xml)) {
            m.groupValues[1].trim().split(Regex("\\s+")).forEach { url ->
                if (url.startsWith("http", ignoreCase = true)) out.add(url.trim())
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------------
    // Reconnaissance: port-based classification heuristic
    // ---------------------------------------------------------------------

    /** Heuristic result: is the target probably a camera? */
    data class CameraGuess(val isLikelyCamera: Boolean, val vendorHint: String, val confidence: Int)

    /**
     * Guesses whether a host is a camera/NVR from its open ports, and
     * suggests a vendor lead. Purely indicative (never proof).
     */
    fun classifyCameraPorts(openPorts: Collection<Int>): CameraGuess {
        val ports = openPorts.toSet()
        var score = 0
        var hint = "Unknown"
        if (554 in ports || 8554 in ports) score += 2          // RTSP = strong presumption
        if (37777 in ports || 37778 in ports || 34567 in ports) { score += 2; hint = "Dahua/XM (DVR)" }
        if (8000 in ports && (80 in ports || 554 in ports)) { score += 1; if (hint == "Unknown") hint = "Hikvision" }
        if (2020 in ports) { score += 1; if (hint == "Unknown") hint = "Amcrest/Dahua" }
        if (80 in ports || 8080 in ports || 443 in ports || 8443 in ports) score += 1 // web interface
        val likely = score >= 2
        val confidence = (score * 25).coerceAtMost(100)
        return CameraGuess(likely, hint, confidence)
    }

    // ---------------------------------------------------------------------
    // RTSP
    // ---------------------------------------------------------------------

    /** Builds an RTSP OPTIONS request (openness / authentication probe). */
    fun rtspOptions(url: String, cseq: Int = 1): String =
        "OPTIONS $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: FlipperDroid\r\n\r\n"

    /** Authentication info inferred from an RTSP response. */
    data class RtspAuth(val status: Int, val needsAuth: Boolean, val scheme: String, val realm: String)

    /** Parses an RTSP response: status code, auth requirement, scheme and realm. */
    fun parseRtspAuth(response: String): RtspAuth {
        val status = Regex("RTSP/1\\.0\\s+(\\d{3})").find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val authLine = response.lineSequence()
            .firstOrNull { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
        val scheme = when {
            authLine == null -> ""
            authLine.contains("Digest", true) -> "Digest"
            authLine.contains("Basic", true) -> "Basic"
            else -> "Unknown"
        }
        val realm = authLine?.let { Regex("realm=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) } ?: ""
        return RtspAuth(status, status == 401 || scheme.isNotEmpty(), scheme, realm)
    }

    // ---------------------------------------------------------------------
    // WS-Security UsernameToken (standard ONVIF authentication)
    // ---------------------------------------------------------------------

    /**
     * Computes the WS-Security PasswordDigest:
     *   Base64( SHA1( nonce_bytes + created_bytes + password_bytes ) ).
     *
     * @param nonce raw nonce bytes (the same ones Base64-encoded in the token)
     * @param created ISO-8601 UTC timestamp (e.g. "2026-09-21T10:00:00Z")
     */
    fun passwordDigest(nonce: ByteArray, created: String, password: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        return base64(sha1.digest())
    }

    /**
     * Builds the WS-UsernameToken SOAP <Security> header (hashed password).
     * @param nonce raw nonce bytes (random, supplied by the caller)
     */
    fun securityHeader(username: String, password: String, nonce: ByteArray, created: String): String {
        val digest = passwordDigest(nonce, created, password)
        val nonceB64 = base64(nonce)
        return """<s:Header><Security s:mustUnderstand="1" xmlns="$NS_WSSE">""" +
            "<UsernameToken><Username>${escapeXml(username)}</Username>" +
            """<Password Type="$TYPE_PASSWORD_DIGEST">$digest</Password>""" +
            """<Nonce EncodingType="$ENC_BASE64">$nonceB64</Nonce>""" +
            """<Created xmlns="$NS_WSU">$created</Created>""" +
            "</UsernameToken></Security></s:Header>"
    }

    /** Wraps a SOAP body, with an optional security header. */
    fun soapEnvelope(body: String, header: String = ""): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<s:Envelope xmlns:s="$NS_SOAP">$header<s:Body>$body</s:Body></s:Envelope>"""

    // ---------------------------------------------------------------------
    // ONVIF SOAP bodies (device / media / ptz)
    // ---------------------------------------------------------------------

    /** SOAPAction HTTP action for GetDeviceInformation. */
    const val ACTION_DEVICE_INFO = "$NS_DEVICE/GetDeviceInformation"
    const val ACTION_GET_PROFILES = "$NS_MEDIA/GetProfiles"
    const val ACTION_CONTINUOUS_MOVE = "$NS_PTZ/ContinuousMove"
    const val ACTION_STOP = "$NS_PTZ/Stop"

    fun getDeviceInformationBody(): String =
        """<GetDeviceInformation xmlns="$NS_DEVICE"/>"""

    fun getProfilesBody(): String =
        """<GetProfiles xmlns="$NS_MEDIA"/>"""

    /**
     * PTZ ContinuousMove body: continuous movement until Stop.
     * pan/tilt/zoom are clamped to [-1.0, 1.0] (ONVIF normalized speeds).
     */
    fun continuousMoveBody(profileToken: String, pan: Float, tilt: Float, zoom: Float): String {
        val p = clampVel(pan); val t = clampVel(tilt); val z = clampVel(zoom)
        return """<ContinuousMove xmlns="$NS_PTZ">""" +
            "<ProfileToken>${escapeXml(profileToken)}</ProfileToken>" +
            """<Velocity><PanTilt x="$p" y="$t" xmlns="$NS_SCHEMA"/>""" +
            """<Zoom x="$z" xmlns="$NS_SCHEMA"/></Velocity></ContinuousMove>"""
    }

    /** PTZ Stop body: stops pan/tilt and zoom. */
    fun stopBody(profileToken: String): String =
        """<Stop xmlns="$NS_PTZ"><ProfileToken>${escapeXml(profileToken)}</ProfileToken>""" +
            "<PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>"

    // ---------------------------------------------------------------------
    // ONVIF response parsing
    // ---------------------------------------------------------------------

    /** Hardware info extracted from a GetDeviceInformation response. */
    data class DeviceInfo(val manufacturer: String, val model: String, val firmware: String, val serial: String)

    fun parseDeviceInformation(xml: String): DeviceInfo = DeviceInfo(
        manufacturer = tagText(xml, "Manufacturer"),
        model = tagText(xml, "Model"),
        firmware = tagText(xml, "FirmwareVersion"),
        serial = tagText(xml, "SerialNumber")
    )

    /**
     * Extracts the media profile tokens (token="…" attribute) from a
     * GetProfiles response. A profile token is required to drive PTZ.
     */
    fun parseProfileTokens(xml: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("<(?:[A-Za-z0-9]+:)?Profiles[^>]*\\btoken=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(xml).forEach { out.add(it.groupValues[1]) }
        return out.toList()
    }

    /** Detects a SOAP Fault (bad credentials, unsupported action…). */
    fun soapFaultReason(xml: String): String? {
        // An element named Fault (with or without a prefix) is required: avoids false positives
        // such as “DefaultUser”.
        if (!SOAP_FAULT_TAG.containsMatchIn(xml)) return null
        return tagText(xml, "Text").ifBlank { tagText(xml, "faultstring") }
            .ifBlank { "SOAP Fault" }
    }

    private val SOAP_FAULT_TAG = Regex("<(\\w+:)?Fault[\\s>]")

    // ---------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------

    private fun clampVel(v: Float): String {
        val c = v.coerceIn(-1.0f, 1.0f)
        // Stable, locale-independent format (no decimal comma).
        return String.format(java.util.Locale.US, "%.2f", c)
    }

    /** Text content of a tag, independent of the namespace prefix. */
    private fun tagText(xml: String, local: String): String =
        // Accepts attributes on the tag (e.g. <env:Text xml:lang="en">).
        Regex("<(?:[A-Za-z0-9]+:)?$local(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?$local>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim() ?: ""

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Standard Base64 encoder (with padding) in pure Kotlin: avoids
     * android.util.Base64 (not mocked in JVM tests) and java.util.Base64 (API 26+,
     * while minSdk = 24).
     */
    fun base64(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(B64[b0 shr 2])
            sb.append(B64[(b0 and 0x03 shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) B64[(b1 and 0x0F shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) B64[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}

/** A discovered or manually entered camera/NVR. */
data class IpCamera(
    val host: String,
    val openPorts: List<Int> = emptyList(),
    val vendorHint: String = "",
    val confidence: Int = 0,
    val xaddr: String = "",              // ONVIF device service URL (WS-Discovery)
    val rtsp: String = "",               // RTSP probe state
    val deviceInfo: String = "",         // GetDeviceInformation / audit result
    val foundCredentials: String = "",   // confirmed default credentials (audit)
    val profileTokens: List<String> = emptyList()
)

/** A default credential pair commonly shipped on IP cameras. */
data class CameraCredential(val username: String, val password: String, val vendor: String)

/**
 * Small dictionary of IP camera default credentials, for auditing
 * (same idea as the Mifare key dictionary). Authorized testing only.
 */
val DEFAULT_CAMERA_CREDENTIALS = listOf(
    CameraCredential("admin", "admin", "Generic"),
    CameraCredential("admin", "12345", "Hikvision (legacy)"),
    CameraCredential("admin", "", "Generic (blank)"),
    CameraCredential("admin", "password", "Generic"),
    CameraCredential("admin", "123456", "Generic"),
    CameraCredential("admin", "888888", "Dahua"),
    CameraCredential("admin", "9999", "Dahua"),
    CameraCredential("root", "root", "Axis/Generic"),
    CameraCredential("root", "pass", "Vivotek"),
    CameraCredential("root", "12345", "Generic"),
    CameraCredential("user", "user", "Generic"),
    CameraCredential("supervisor", "supervisor", "Generic"),
    CameraCredential("admin", "meinsm", "Panasonic"),
    CameraCredential("admin", "4321", "Ubiquiti/Generic"),
    CameraCredential("service", "service", "Bosch")
)
