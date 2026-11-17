package com.example.flipperdroid.nfc

import android.nfc.Tag
import android.nfc.tech.NfcV

/**
 * Read / write of ISO 15693 tags (NfcV): ICODE, ST25, badges, library tags…
 * Blocks are typically 4 bytes. Read block by block until the first error.
 */
object NfcVUtils {

    /** Reads all readable blocks; returns one hex block per entry, or null if not NfcV. */
    fun readDump(tag: Tag): List<String>? {
        val v = NfcV.get(tag) ?: return null
        return try {
            v.connect()
            val blocks = ArrayList<String>()
            var b = 0
            while (b < 256) {
                // READ SINGLE BLOCK: [flags=0x02][0x20][blockNo]
                val resp = try { v.transceive(byteArrayOf(0x02, 0x20, b.toByte())) } catch (e: Exception) { null } ?: break
                // resp[0] = response flags (0 = OK); the rest = block data.
                if (resp.size < 2 || (resp[0].toInt() and 0xFF) != 0x00) break
                blocks.add(MifareClassicUtils.bytesToHex(resp.copyOfRange(1, resp.size)))
                b++
            }
            blocks
        } catch (e: Exception) {
            null
        } finally {
            try { v.close() } catch (_: Exception) {}
        }
    }

    /** Writes an ISO 15693 block. Best-effort: some tags require the "option" flag. */
    fun writeBlock(tag: Tag, block: Int, data: ByteArray): Boolean {
        val v = NfcV.get(tag) ?: return false
        return try {
            v.connect()
            // WRITE SINGLE BLOCK: [flags=0x02][0x21][blockNo][data]
            val cmd = byteArrayOf(0x02, 0x21, block.toByte()) + data
            val resp = try { v.transceive(cmd) } catch (e: Exception) { null }
            if (resp != null && resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == 0x00) return true
            // Retry with the Option flag (0x40) that some tags require for writing.
            val cmd2 = byteArrayOf(0x42, 0x21, block.toByte()) + data
            val resp2 = v.transceive(cmd2)
            resp2.isNotEmpty() && (resp2[0].toInt() and 0xFF) == 0x00
        } catch (e: Exception) {
            false
        } finally {
            try { v.close() } catch (_: Exception) {}
        }
    }
}
