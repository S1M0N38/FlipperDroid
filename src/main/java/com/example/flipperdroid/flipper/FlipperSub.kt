package com.example.flipperdroid.flipper

/** Representation of a Flipper Zero .sub (Sub-GHz) file. */
data class SubFile(
    val frequency: Long?,
    val preset: String?,
    val protocol: String?,
    val rawData: IntArray,
    val keyData: String?,
    /** false if the "Filetype: Flipper ..." header is missing (file probably non-conforming). */
    val valid: Boolean = true
)

/**
 * Reader for the Flipper .sub format (Sub-GHz).
 *
 * NOTE: a smartphone has no Sub-GHz radio — this module can only *read / analyze*
 * a .sub, not transmit it (that would require an external CC1101/HackRF).
 */
object FlipperSub {

    fun parse(text: String): SubFile {
        var frequency: Long? = null
        var preset: String? = null
        var protocol: String? = null
        var keyData: String? = null
        var hasHeader = false
        val raw = mutableListOf<Int>()

        for (line in text.split("\n")) {
            val t = line.trim()
            val idx = t.indexOf(':')
            if (idx <= 0) continue
            val key = t.substring(0, idx).trim()
            val value = t.substring(idx + 1).trim()
            when {
                key.equals("Filetype", true) -> hasHeader = value.contains("Flipper", ignoreCase = true)
                key.equals("Frequency", true) -> frequency = value.toLongOrNull()
                key.equals("Preset", true) -> preset = value
                key.equals("Protocol", true) -> protocol = value
                key.equals("Key", true) -> keyData = value
                key.equals("RAW_Data", true) ->
                    value.split(Regex("\\s+")).mapNotNullTo(raw) { it.toIntOrNull() }
            }
        }
        return SubFile(frequency, preset, protocol, raw.toIntArray(), keyData, hasHeader)
    }

    /** Human-readable summary of a .sub. */
    fun summarize(sub: SubFile): String {
        val sb = StringBuilder()
        sb.append("Frequency: ${sub.frequency?.let { "%.3f MHz".format(it / 1_000_000.0) } ?: "?"}\n")
        sb.append("Preset: ${sub.preset ?: "?"}\n")
        sb.append("Protocol: ${sub.protocol ?: "?"}\n")
        sub.keyData?.let { sb.append("Key: $it\n") }
        if (sub.rawData.isNotEmpty()) sb.append("RAW samples: ${sub.rawData.size}\n")
        sb.append("\n⚠ Sub-GHz cannot be transmitted from a phone (no <1 GHz radio). " +
            "Use an external CC1101/HackRF.")
        return sb.toString()
    }
}
