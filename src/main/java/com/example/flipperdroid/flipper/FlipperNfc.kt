package com.example.flipperdroid.flipper

/**
 * Representation of a Flipper Zero .nfc file.
 * @property blocks Mifare Classic blocks ("Block i:")
 * @property pages Ultralight/NTAG pages ("Page i:")
 * @property valid false if the "Filetype: Flipper NFC ..." header is missing
 */
data class NfcCard(
    val deviceType: String?,
    val uid: String?,
    val atqa: String?,
    val sak: String?,
    val blocks: List<String>,
    val pages: List<String> = emptyList(),
    val valid: Boolean = true
)

/**
 * Reader / generator for the Flipper Zero .nfc file format.
 */
object FlipperNfc {

    private val BLOCK_KEY = Regex("(?i)block\\s+\\d+")
    private val PAGE_KEY = Regex("(?i)page\\s+\\d+")

    fun parse(text: String): NfcCard {
        var hasHeader = false
        var deviceType: String? = null
        var uid: String? = null
        var atqa: String? = null
        var sak: String? = null
        val blocks = mutableListOf<String>()
        val pages = mutableListOf<String>()

        for (raw in text.split("\n")) {
            val line = raw.trim()
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when {
                key.equals("Filetype", true) -> hasHeader = value.contains("NFC", ignoreCase = true)
                key.equals("Device type", true) -> deviceType = value
                key.equals("UID", true) -> uid = value
                key.equals("ATQA", true) -> atqa = value
                key.equals("SAK", true) -> sak = value
                BLOCK_KEY.matches(key) -> blocks.add(value)
                PAGE_KEY.matches(key) -> pages.add(value)
            }
        }
        return NfcCard(deviceType, uid, atqa, sak, blocks, pages, hasHeader)
    }

    /**
     * Generates an .nfc file (Flipper v4 format).
     *
     * @param uidHex UID (spaces tolerated, e.g. "04A2B3C4")
     * @param blocks Mifare Classic: 32-hex-character blocks. NTAG/Ultralight: 8-character pages.
     *   An unreadable entry (dashes) is written as "?? ?? ..." (Flipper convention for "unknown").
     * @param deviceType "Mifare Classic" (default) or "NTAG/Ultralight"
     * @param atqa actual ATQA (hex); if null, the default value for the type
     * @param sak actual SAK (hex); if null: 4K -> "18", 1K -> "08", Ultralight -> "00"
     */
    fun generate(
        uidHex: String,
        blocks: List<String>,
        deviceType: String = "Mifare Classic",
        atqa: String? = null,
        sak: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("Filetype: Flipper NFC device\n")
        sb.append("Version: 4\n")
        if (isUltralight(deviceType)) {
            sb.append("Device type: NTAG/Ultralight\n")
            sb.append("UID: ${spaced(uidHex)}\n")
            sb.append("ATQA: ${spaced(atqa ?: "00 44")}\n")
            sb.append("SAK: ${spaced(sak ?: "00")}\n")
            sb.append("Mifare Ultralight type: ${ultralightType(blocks.size)}\n")
            sb.append("Pages total: ${blocks.size}\n")
            sb.append("Pages read: ${blocks.count { !it.contains('-') }}\n")
            blocks.forEachIndexed { i, p -> sb.append("Page $i: ${spaced(p)}\n") }
        } else {
            val is4k = blocks.size > 64
            sb.append("Device type: Mifare Classic\n")
            sb.append("UID: ${spaced(uidHex)}\n")
            sb.append("ATQA: ${spaced(atqa ?: "00 04")}\n")
            sb.append("SAK: ${spaced(sak ?: if (is4k) "18" else "08")}\n")
            sb.append("Mifare Classic type: ${if (is4k) "4K" else "1K"}\n")
            sb.append("Data format version: 2\n")
            blocks.forEachIndexed { i, b -> sb.append("Block $i: ${spaced(b)}\n") }
        }
        return sb.toString()
    }

    private fun isUltralight(deviceType: String): Boolean =
        deviceType.contains("NTAG", ignoreCase = true) || deviceType.contains("Ultralight", ignoreCase = true)

    /** Chip name according to the number of pages in the dump. */
    private fun ultralightType(pages: Int): String = when (pages) {
        16 -> "Ultralight"
        48 -> "Ultralight C"
        45 -> "NTAG213"
        135 -> "NTAG215"
        231 -> "NTAG216"
        else -> "NTAG/Ultralight"
    }

    /**
     * Inserts a space every 2 hex characters. A pair containing a dash (unknown byte)
     * becomes "??".
     */
    private fun spaced(hex: String): String =
        hex.replace(" ", "").chunked(2).joinToString(" ") { if (it.contains('-')) "??" else it }
}
