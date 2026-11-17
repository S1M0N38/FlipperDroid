package com.example.flipperdroid.infrared

/** Inter-frame gap used for the burst of a "raw" button. */
private const val RAW_GAP_US = 40000

/** A button of an .ir file (either "parsed" with a protocol, or "raw"). */
data class IrButton(
    val name: String,
    val type: String,
    val protocol: String? = null,
    val address: Int = 0,
    val command: Int = 0,
    val frequency: Int = 38000,
    val rawData: IntArray? = null
) {
    /** @return (frequency, µs pattern) ready to transmit, or null if not encodable. */
    fun toSignal(): Pair<Int, IntArray>? {
        return when (type.lowercase()) {
            "raw" -> if (rawData != null && rawData.isNotEmpty()) frequency to rawData else null
            "parsed" -> protocol?.let { IrProtocols.encode(it, address, command) }
            else -> null
        }
    }

    /**
     * Repeated burst (one reliable press) for TV-B-Gone. For a "parsed" button,
     * delegates to IrProtocols.encodePowerBurst; for a "raw" button, simply repeats
     * the capture [presses] times (an isolated raw frame is often ignored).
     */
    fun toBurst(presses: Int = 0): Pair<Int, IntArray>? {
        return when (type.lowercase()) {
            "parsed" -> protocol?.let {
                IrProtocols.encodePowerBurst(it, address, command, presses)
            }
            "raw" -> {
                val data = rawData ?: return null
                if (data.isEmpty()) return null
                val count = if (presses > 0) presses else 3
                frequency to IrProtocols.repeatFrames(data, data, count, RAW_GAP_US)
            }
            else -> null
        }
    }
}

/**
 * Parser for the Flipper Zero .ir file format.
 *
 * Example input:
 * ```
 * name: Power
 * type: parsed
 * protocol: NEC
 * address: 04 00 00 00
 * command: 08 00 00 00
 * ```
 * or raw:
 * ```
 * name: Power
 * type: raw
 * frequency: 38000
 * data: 9000 4500 560 560 ...
 * ```
 *
 * A button with an invalid hexadecimal byte or an invalid raw data value is
 * **ignored** (and not turned into 0): a missing button is better than a wrong code.
 */
object IrFile {

    private val HEX_BYTE = Regex("[0-9A-Fa-f]{1,2}")
    private val WHITESPACE = Regex("\\s+")

    fun parse(text: String): List<IrButton> {
        val buttons = mutableListOf<IrButton>()
        var cur = mutableMapOf<String, String>()

        fun flush() {
            val name = cur["name"] ?: return
            val type = cur["type"] ?: return
            val address = parseHexBytesLE(cur["address"]) ?: return   // invalid byte: button ignored
            val command = parseHexBytesLE(cur["command"]) ?: return
            val rawData = cur["data"]?.let { parseRaw(it) }
            // Raw data present but corrupted: button ignored.
            if (cur.containsKey("data") && rawData == null) return
            buttons.add(
                IrButton(
                    name = name,
                    type = type,
                    protocol = cur["protocol"],
                    address = address,
                    command = command,
                    frequency = cur["frequency"]?.trim()?.toIntOrNull() ?: 38000,
                    rawData = rawData
                )
            )
        }

        // lines() handles "\n" and "\r\n"; a possible BOM at the start of the file is stripped.
        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("Filetype") || line.startsWith("Version")) {
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            // A new "name" key starts a new button
            if (key == "name" && cur.isNotEmpty()) {
                flush()
                cur = mutableMapOf()
            }
            cur[key] = value
        }
        flush()
        return buttons
    }

    /**
     * Converts "04 00 00 00" (little-endian) to an integer.
     * @return 0 if the value is absent, null if a byte is not valid hexadecimal.
     */
    private fun parseHexBytesLE(s: String?): Int? {
        if (s.isNullOrBlank()) return 0
        var result = 0
        val tokens = s.trim().split(WHITESPACE)
        for ((i, tok) in tokens.withIndex()) {
            if (!HEX_BYTE.matches(tok)) return null
            if (i < 4) result = result or (tok.toInt(16) shl (8 * i))
        }
        return result
    }

    /**
     * Converts "9000 4500 560 ..." to an integer array.
     * @return null if a token is not a strictly positive integer, or if the list is empty.
     */
    private fun parseRaw(s: String): IntArray? {
        val tokens = s.trim().split(WHITESPACE)
        val out = IntArray(tokens.size)
        for ((i, tok) in tokens.withIndex()) {
            val v = tok.toIntOrNull()
            if (v == null || v <= 0) return null
            out[i] = v
        }
        return if (out.isEmpty()) null else out
    }
}
