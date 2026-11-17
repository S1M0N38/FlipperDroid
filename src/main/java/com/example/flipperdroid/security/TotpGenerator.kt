package com.example.flipperdroid.security

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Result of parsing an otpauth:// URI (2FA QR code).
 * The first two properties (label, secret) are kept first: destructuring
 * `val (label, secret) = ...` stays valid.
 */
data class OtpauthData(
    val label: String,
    val secret: String,
    val issuer: String?,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val type: String
)

/**
 * TOTP (RFC 6238) and HOTP (RFC 4226) code generator.
 * HMAC algorithms: SHA1 (default), SHA256, SHA512.
 * Decodes Base32 secrets (RFC 4648).
 */
object TotpGenerator {

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val OTPAUTH_SCHEME = "otpauth://"

    /** Accepted algorithms, in normalized form (uppercase, no dash). */
    val SUPPORTED_ALGORITHMS: List<String> = listOf("SHA1", "SHA256", "SHA512")

    /** Normalizes an algorithm name: "sha-256" -> "SHA256". */
    fun normalizeAlgorithm(algorithm: String): String =
        algorithm.trim().uppercase().replace("-", "")

    fun isSupportedAlgorithm(algorithm: String): Boolean =
        normalizeAlgorithm(algorithm) in SUPPORTED_ALGORITHMS

    private fun macName(algorithm: String): String = when (normalizeAlgorithm(algorithm)) {
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
    }

    /** Decodes a Base32 string (padding and separators ignored). */
    fun base32Decode(input: String): ByteArray {
        val s = input.trim().replace(" ", "").replace("-", "").uppercase().trimEnd('=')
        var bits = 0
        var value = 0
        val out = ByteArrayOutputStream()
        for (c in s) {
            val idx = BASE32.indexOf(c)
            if (idx < 0) continue
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((value shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * Checks that a string is valid Base32 (A-Z, 2-7, optional '=' padding at the end).
     * Whitespace is ignored and case does not matter.
     */
    fun isValidBase32(s: String): Boolean {
        val compact = s.filterNot { it.isWhitespace() }.uppercase()
        val body = compact.trimEnd('=')
        val padding = compact.length - body.length
        if (body.isEmpty() || padding > 6) return false
        return body.all { it in 'A'..'Z' || it in '2'..'7' }
    }

    /**
     * Generates a TOTP code.
     * @param secretB32 shared secret in Base32
     * @param timeSec Unix time in seconds
     * @param digits number of digits (typically 6 to 8)
     * @param step duration of one window in seconds
     * @param algorithm SHA1, SHA256 or SHA512
     */
    fun generate(
        secretB32: String,
        timeSec: Long,
        digits: Int = 6,
        step: Int = 30,
        algorithm: String = "SHA1"
    ): String {
        require(step > 0) { "Invalid period: $step" }
        val key = base32Decode(secretB32)
        if (key.isEmpty()) return "".padStart(digits, '-')
        return hotpFromKey(key, timeSec / step, digits, algorithm)
    }

    /**
     * Generates an HOTP code (RFC 4226) for a given counter.
     */
    fun hotp(secretB32: String, counter: Long, digits: Int = 6, algorithm: String = "SHA1"): String {
        val key = base32Decode(secretB32)
        if (key.isEmpty()) return "".padStart(digits, '-')
        return hotpFromKey(key, counter, digits, algorithm)
    }

    private fun hotpFromKey(key: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        require(digits in 1..9) { "Unsupported digit count: $digits" }
        // 8-byte big-endian counter
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) { msg[i] = (v and 0xFF).toByte(); v = v shr 8 }

        val name = macName(algorithm)
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        val h = mac.doFinal(msg)

        // Dynamic truncation (RFC 4226 §5.3)
        val off = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
                ((h[off + 1].toInt() and 0xff) shl 16) or
                ((h[off + 2].toInt() and 0xff) shl 8) or
                (h[off + 3].toInt() and 0xff)
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (bin.toLong() % mod).toString().padStart(digits, '0')
    }

    /** Seconds left before the next code (for the progress bar). */
    fun secondsRemaining(timeSec: Long, step: Int = 30): Int = (step - (timeSec % step)).toInt()

    /**
     * Parses an otpauth:// URI (2FA QR code).
     * Reads the secret, issuer, algorithm, digits and period parameters; the label is URL-decoded.
     * @return null if the URI is malformed (scheme, type, missing secret, invalid numbers).
     */
    fun parseOtpauthUri(uri: String): OtpauthData? {
        val input = uri.trim()
        if (!input.startsWith(OTPAUTH_SCHEME, ignoreCase = true)) return null
        val rest = input.substring(OTPAUTH_SCHEME.length)
        val beforeQuery = rest.substringBefore('?')
        val type = beforeQuery.substringBefore('/').lowercase()
        if (type != "totp" && type != "hotp") return null
        val rawLabel = if (beforeQuery.contains('/')) beforeQuery.substringAfter('/') else ""

        val params = parseQuery(input.substringAfter('?', "").substringBefore('#'))
        val secret = params["secret"]?.filterNot { it.isWhitespace() }.orEmpty()
        if (secret.isEmpty()) return null

        // '+' is a literal character in the label: protect it before URL decoding
        val label = decodeComponent(rawLabel.replace("+", "%2B"))
        val account = label.substringAfterLast(':').trim().ifBlank { "Account" }
        val issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: label.takeIf { it.contains(':') }?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }

        val digits = parseIntParam(params, "digits", 6) ?: return null
        val period = parseIntParam(params, "period", 30) ?: return null
        val algorithm = normalizeAlgorithm(params["algorithm"] ?: "SHA1")

        return OtpauthData(account, secret, issuer, algorithm, digits, period, type)
    }

    private fun parseIntParam(params: Map<String, String>, key: String, default: Int): Int? {
        val raw = params[key] ?: return default
        return raw.trim().toIntOrNull()
    }

    /** Splits a query string into key/value pairs (keys lowercased, first occurrence wins). */
    private fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decodeComponent(pair.substringBefore('=')).lowercase()
            val value = decodeComponent(pair.substringAfter('=', ""))
            if (key !in out) out[key] = value
        }
        return out
    }

    /** UTF-8 URL decoding; an invalid % sequence returns the string unchanged. */
    private fun decodeComponent(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
