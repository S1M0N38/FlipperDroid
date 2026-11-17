package com.example.flipperdroid.util

/**
 * Classification of a Wi-Fi network's security type from the
 * `ScanResult.capabilities` string, e.g. `[WPA2-PSK-CCMP][RSN-SAE-CCMP][WPS][ESS]`.
 *
 * The previous detection only recognized WPA3 with the literal "WPA3": a
 * pure WPA3 network (`[RSN-SAE-CCMP]`) was classified OPEN, and "Enhanced Open"
 * (OWE) was mistaken for an unencrypted open network. This pure classifier
 * (no `android.*` dependency, hence JVM-testable) recognizes SAE,
 * OWE, the WPA2/WPA3 transition, enterprise authentication (EAP) and WPS.
 */
enum class WifiSecurity {
    OPEN,               // open, unencrypted
    OWE,                // Enhanced Open (OWE): open but encrypted
    WEP,
    WPA,
    WPA2,
    WPA3,
    WPA3_TRANSITION,    // WPA2-PSK + WPA3-SAE on the same BSS
}

object WifiSecurityClassifier {

    /** Infers the dominant security type from the capabilities string. */
    fun classify(capabilities: String): WifiSecurity {
        val caps = capabilities.uppercase()
        val hasSae = caps.contains("SAE")
        // "PSK" covers WPA2-PSK/WPA-PSK; the WPA/WPA2 labels are also matched.
        val hasPsk = caps.contains("PSK") || caps.contains("WPA2") || caps.contains("WPA-")
        return when {
            caps.contains("WEP") -> WifiSecurity.WEP
            hasSae || caps.contains("WPA3") ->
                if (hasSae && hasPsk) WifiSecurity.WPA3_TRANSITION else WifiSecurity.WPA3
            caps.contains("OWE") -> WifiSecurity.OWE
            caps.contains("WPA2") || caps.contains("RSN") -> WifiSecurity.WPA2
            caps.contains("WPA") -> WifiSecurity.WPA
            else -> WifiSecurity.OPEN
        }
    }

    /** true if WPS is advertised (additional attack surface: PIN/PBC). */
    fun hasWps(capabilities: String): Boolean = capabilities.uppercase().contains("WPS")

    /** true if the network uses 802.1X / enterprise authentication (EAP). */
    fun isEnterprise(capabilities: String): Boolean =
        capabilities.uppercase().let { it.contains("EAP") || it.contains("IEEE8021X") }

    /** Human-readable label, enriched with the enterprise and WPS flags, for the UI. */
    fun label(capabilities: String): String {
        val base = when (classify(capabilities)) {
            WifiSecurity.OPEN -> "Open"
            WifiSecurity.OWE -> "Enhanced Open (OWE)"
            WifiSecurity.WEP -> "WEP"
            WifiSecurity.WPA -> "WPA"
            WifiSecurity.WPA2 -> "WPA2"
            WifiSecurity.WPA3 -> "WPA3"
            WifiSecurity.WPA3_TRANSITION -> "WPA2/WPA3"
        }
        val suffix = buildString {
            if (isEnterprise(capabilities)) append(" · Enterprise")
            if (hasWps(capabilities)) append(" · WPS")
        }
        return base + suffix
    }
}
