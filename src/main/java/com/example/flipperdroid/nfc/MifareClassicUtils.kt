package com.example.flipperdroid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic

object MifareClassicUtils {
    const val NO_DATA = "--------------------------------"
    const val DEFAULT_KEY = "FFFFFFFFFFFF"

    /**
     * Dictionary of common Mifare Classic keys (dictionary attack).
     * Real key recovery (Nested/Darkside) is not possible through Android's
     * NFC API: you can only test known keys.
     */
    val COMMON_KEYS = listOf(
        "FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "B0B1B2B3B4B5",
        "C0C1C2C3C4C5", "D0D1D2D3D4D5", "A0B0C0D0E0F0", "A1B1C1D1E1F1",
        "AABBCCDDEEFF", "1A2B3C4D5E6F", "123456789ABC", "010203040506",
        "D3F7D3F7D3F7", "4D3A99C351DD", "1A982C7E459A", "5C8FF9990DA2",
        "D01AFEEB890A", "75CCB59C9BED", "FC00018778F7", "6471A5EF2D1A",
        "4B791BEA7BCC", "2E4C7C8ED17D", "F2E1AA3B33F5", "000000000001",
        "B127C6F41436", "A9C9321E0000", "484558414354", "44FF292317DA"
    )

    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * Converts a hex string to bytes (no separators).
     * @return null if the length is odd or a character is not hexadecimal;
     *   an empty string yields an empty array. Pure function (testable off Android).
     */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[2 * i])
            val lo = hexDigit(hex[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Value of a hexadecimal digit, or -1 if the character is not one. */
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * Writes a block. "Sector trailers" (last block of the sector: keys + access bits)
     * are refused unless [allowTrailer] is true: a bad trailer locks the sector.
     */
    fun writeBlock(
        tag: Tag,
        sectorIndex: Int,
        blockIndex: Int,
        data: ByteArray,
        key: ByteArray,
        useAsKeyB: Boolean,
        allowTrailer: Boolean = false
    ): Boolean {
        val mfc = MifareClassic.get(tag) ?: return false
        try {
            if (!allowTrailer && blockIndex == mfc.getBlockCountInSector(sectorIndex) - 1) return false
            mfc.connect()
            val auth = if (useAsKeyB) mfc.authenticateSectorWithKeyB(sectorIndex, key)
            else mfc.authenticateSectorWithKeyA(sectorIndex, key)
            if (!auth) return false
            val block = mfc.sectorToBlock(sectorIndex) + blockIndex
            mfc.writeBlock(block, data)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    fun cloneUid(tag: Tag, newUid: ByteArray): Boolean {
        // WARNING: This operation only works on "magic" (generic) cards!
        // We try to write block 0 (UID) with the new value
        val mfc = MifareClassic.get(tag) ?: return false
        try {
            mfc.connect()
            // Authentication with the default key A
            val auth = mfc.authenticateSectorWithKeyA(0, hexToBytes(DEFAULT_KEY) ?: return false)
            if (!auth) return false
            val block0 = mfc.readBlock(0)
            // For a 4-byte UID, the BCC (byte 4) = XOR of the 4 UID bytes: we recompute it
            // otherwise the card is malformed. The rest (SAK/ATQA/manufacturer) is kept.
            val newBlock0 = if (newUid.size == 4 && block0.size >= 5) {
                val bcc = (newUid[0].toInt() xor newUid[1].toInt() xor
                    newUid[2].toInt() xor newUid[3].toInt()).toByte()
                newUid + byteArrayOf(bcc) + block0.copyOfRange(5, block0.size)
            } else {
                newUid + block0.copyOfRange(newUid.size, block0.size)
            }
            mfc.writeBlock(0, newBlock0)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    /** Result of rewriting a full dump onto a target card. */
    data class DumpWriteResult(
        val written: Int,
        val failed: Int,
        val skipped: Int,
        val message: String
    )

    /**
     * Rewrites a full Mifare Classic dump onto the presented card (reproduction/cloning).
     *
     * @param blocks source dump, one hex block (32 characters) per global index, or [NO_DATA].
     * @param writeTrailers also rewrite the "sector trailer" blocks (keys + access bits).
     *   Disabled by default: rewriting a trailer with bad access bits can lock the sector
     *   permanently.
     * @param writeSectorZero try to write block 0 (UID) — only works on "magic" cards.
     *
     * NB: only works on writable Mifare Classic cards (magic/blank).
     * Android CANNOT emulate Mifare Classic (HCE = ISO-DEP only).
     */
    fun writeDump(
        tag: Tag,
        blocks: List<String>,
        writeTrailers: Boolean = false,
        writeSectorZero: Boolean = true
    ): DumpWriteResult {
        val mfc = MifareClassic.get(tag)
            ?: return DumpWriteResult(0, 0, 0, "Target card is not Mifare Classic (or incompatible chip).")
        var written = 0
        var failed = 0
        var skipped = 0
        val notes = StringBuilder()
        try {
            mfc.connect()
            for (sector in 0 until mfc.sectorCount) {
                val firstBlock = mfc.sectorToBlock(sector)
                val count = mfc.getBlockCountInSector(sector)
                val srcTrailer = blocks.getOrNull(firstBlock + count - 1)
                var authed = false
                for (key in keyCandidates(srcTrailer)) {
                    val kb = hexToBytes(key) ?: continue
                    try {
                        if (mfc.authenticateSectorWithKeyA(sector, kb) ||
                            mfc.authenticateSectorWithKeyB(sector, kb)) { authed = true; break }
                    } catch (_: Exception) {
                        try { mfc.close(); mfc.connect() } catch (_: Exception) {}
                    }
                }
                if (!authed) { failed += count; notes.append("Sector $sector: authentication failed\n"); continue }

                for (i in 0 until count) {
                    val global = firstBlock + i
                    val raw = blocks.getOrNull(global)?.replace(" ", "")
                    if (raw == null || raw == NO_DATA || raw.length != 32) { skipped++; continue }
                    val isTrailer = i == count - 1
                    val isBlock0 = global == 0
                    if (isBlock0 && !writeSectorZero) { skipped++; continue }
                    if (isTrailer && !writeTrailers) { skipped++; continue }
                    val bytes = hexToBytes(raw)
                    if (bytes == null || bytes.size != 16) { skipped++; continue }
                    try {
                        mfc.writeBlock(global, bytes); written++
                    } catch (e: Exception) {
                        failed++
                        if (isBlock0) notes.append("Block 0 (UID) not writable — card is not magic\n")
                    }
                }
            }
        } catch (e: Exception) {
            notes.append("Error: ${e.message}\n")
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
        val msg = "written: $written, failed: $failed, skipped: $skipped" +
            (if (notes.isNotEmpty()) "\n$notes" else "")
        return DumpWriteResult(written, failed, skipped, msg.trim())
    }

    /** Keys to try when authenticating the target: default, source trailer keys, dictionary. */
    private fun keyCandidates(srcTrailer: String?): List<String> {
        val list = mutableListOf(DEFAULT_KEY)
        if (srcTrailer != null && srcTrailer != NO_DATA) {
            val clean = srcTrailer.replace(" ", "")
            if (clean.length == 32) {
                list.add(clean.substring(0, 12))   // Key A
                list.add(clean.substring(20, 32))  // Key B
            }
        }
        list.addAll(COMMON_KEYS)
        return list.distinct()
    }
} 