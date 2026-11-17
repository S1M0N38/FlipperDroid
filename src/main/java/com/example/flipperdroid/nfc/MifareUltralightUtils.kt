package com.example.flipperdroid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight

/**
 * Read / write / clone of Mifare Ultralight and NTAG21x tags (Ultralight-compatible).
 * Pages are 4 bytes. Pages 0-3 (UID/lock/OTP/CC) are never written
 * by the clone; the configuration pages (dynamic lock, CFG, PWD, PACK)
 * at the end of memory are only written when the explicit [writeConfigPages] option is enabled,
 * because writing them can lock or permanently protect the chip.
 */
object MifareUltralightUtils {

    /** Number of pages of the classic Ultralight (MF0ICU1). */
    private const val ULTRALIGHT_PAGES = 16
    /** Number of pages of the Ultralight C (MF0ICU2). */
    private const val ULTRALIGHT_C_PAGES = 48

    /**
     * Range of the dynamic lock / configuration / PWD / PACK pages depending on the tag's
     * total size, or null if the size is not recognized.
     * Pure function, tested in NfcLogicTest.
     */
    fun protectedPages(totalPages: Int): IntRange? = when (totalPages) {
        ULTRALIGHT_PAGES -> 2..3        // static lock + OTP (already excluded by the clone)
        45 -> 40..44                    // NTAG213
        135 -> 130..134                 // NTAG215
        231 -> 226..230                 // NTAG216
        else -> null
    }

    /**
     * Number of pages of a NTAG according to the "storage size" byte of the GET_VERSION response
     * (byte 6: 0x0F = NTAG213, 0x11 = NTAG215, 0x13 = NTAG216), or null if unknown.
     */
    fun ntagPagesFromStorageSize(storageByte: Int): Int? = when (storageByte and 0xFF) {
        0x0F -> 45
        0x11 -> 135
        0x13 -> 231
        else -> null
    }

    /**
     * Real capacity of the chip in pages. GET_VERSION (0x60) is queried first: Android
     * reports NTAG213/215/216 as TYPE_ULTRALIGHT, so the type alone is not enough.
     * Falls back to the type if GET_VERSION fails (original Ultralight: doesn't support the command).
     * Returns null if the capacity remains unknown.
     */
    private fun capacityPages(mfu: MifareUltralight): Int? {
        val version = try { mfu.transceive(byteArrayOf(0x60)) } catch (e: Exception) { null }
        if (version != null && version.size >= 7) {
            ntagPagesFromStorageSize(version[6].toInt())?.let { return it }
        }
        return when (mfu.type) {
            MifareUltralight.TYPE_ULTRALIGHT_C -> ULTRALIGHT_C_PAGES
            MifareUltralight.TYPE_ULTRALIGHT -> ULTRALIGHT_PAGES
            else -> null
        }
    }

    /**
     * Reads every available page; returns one hex page (8 characters) per entry.
     * Reading stops at the chip's real capacity (GET_VERSION) or, if it is
     * unknown, at the first read error.
     */
    fun readDump(tag: Tag): List<String>? {
        val mfu = MifareUltralight.get(tag) ?: return null
        return try {
            mfu.connect()
            val max = capacityPages(mfu) ?: 231 // NTAG216 at most; we stop at the first error
            val pages = ArrayList<String>()
            var i = 0
            while (i < max) {
                val block = try { mfu.readPages(i) } catch (e: Exception) { break } // 16 bytes = 4 pages
                var p = 0
                while (p < 4 && pages.size < max) {
                    val start = p * 4
                    if (start + 4 <= block.size) pages.add(MifareClassicUtils.bytesToHex(block.copyOfRange(start, start + 4)))
                    p++
                }
                i += 4
            }
            pages
        } catch (e: Exception) {
            null
        } finally {
            try { mfu.close() } catch (_: Exception) {}
        }
    }

    /**
     * Writes a dump (4-byte pages) to a target Ultralight/NTAG tag.
     *
     * Refuses to write if the dump size does not match the target chip, or if the
     * size is unknown (configuration area not identifiable) without [writeConfigPages].
     * @param writeConfigPages also write the lock / configuration / PWD / PACK pages.
     *   Disabled by default: can lock or permanently protect the chip.
     */
    fun writeDump(
        tag: Tag,
        pages: List<String>,
        writeConfigPages: Boolean = false
    ): MifareClassicUtils.DumpWriteResult {
        val mfu = MifareUltralight.get(tag)
            ?: return MifareClassicUtils.DumpWriteResult(0, 0, 0, "Target is not Ultralight/NTAG.")
        val reserved = protectedPages(pages.size)
        if (reserved == null && !writeConfigPages) {
            return MifareClassicUtils.DumpWriteResult(
                0, 0, pages.size,
                "Dump of ${pages.size} pages: unknown chip size, configuration area " +
                    "not identifiable. Write cancelled."
            )
        }
        var written = 0
        var failed = 0
        var skipped = 0
        try {
            mfu.connect()
            val target = capacityPages(mfu)
            if (target != null && target != pages.size) {
                return MifareClassicUtils.DumpWriteResult(
                    0, 0, pages.size,
                    "Dump of ${pages.size} pages incompatible with the target chip ($target pages). Write cancelled."
                )
            }
            for (i in pages.indices) {
                if (i < 4) { skipped++; continue } // UID / lock / OTP / CC: never written
                if (!writeConfigPages && reserved?.contains(i) == true) { skipped++; continue }
                val raw = pages[i].replace(" ", "")
                if (raw.length != 8 || raw.all { it == '-' }) { skipped++; continue }
                val bytes = MifareClassicUtils.hexToBytes(raw)
                if (bytes == null || bytes.size != 4) { skipped++; continue }
                try { mfu.writePage(i, bytes); written++ } catch (e: Exception) { failed++ }
            }
        } catch (e: Exception) {
            // ignored; we return the partial count
        } finally {
            try { mfu.close() } catch (_: Exception) {}
        }
        return MifareClassicUtils.DumpWriteResult(
            written, failed, skipped,
            "written: $written, failures: $failed, skipped: $skipped"
        )
    }
}
