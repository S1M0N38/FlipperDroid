package com.example.flipperdroid.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Contactless bank card reader (EMV).
 *
 * Follows the real EMV flow instead of guessing SFIs:
 *   1. SELECT PPSE (2PAY.SYS.DDF01) -> AID discovery (tag 4F)
 *   2. SELECT AID -> FCI (may contain a PDOL, tag 9F38)
 *   3. GET PROCESSING OPTIONS (GPO) -> AIP + AFL (tag 94, or template 80/77)
 *   4. READ RECORD on every record pointed to by the AFL
 *   5. BER-TLV parsing ([EmvTlv]) -> PAN (5A), expiry (5F24), cardholder (5F20), Track2 (57)
 * Fallback: "raw" read of a few SFIs/records if the GPO fails.
 *
 * NB: for authorized testing/research only.
 */
class EmvCardReader {

    companion object {
        private const val TAG = "EmvCardReader"

        /** Fallback AIDs if the PPSE returns nothing usable. */
        private val KNOWN_AIDS = listOf(
            "A0000000041010", // Mastercard
            "A0000000031010", // Visa
            "A0000000651010", // JCB
            "A0000000042203", // Maestro
            "A0000000043060", // Maestro UK
            "A000000025010801", // Amex
            "A0000003330101"  // UnionPay
        )

        private val AID_MAP = mapOf(
            "A0000000041010" to "Mastercard",
            "A0000000031010" to "Visa",
            "A0000000651010" to "JCB",
            "A0000000042203" to "Maestro",
            "A0000000043060" to "Maestro UK",
            "A000000025010801" to "American Express",
            "A0000003330101" to "UnionPay"
        )

        private const val SELECT_PPSE = "2PAY.SYS.DDF01"

        /** Brute-force fallback: SFI 1..3 and records 1..5 only. */
        private const val FALLBACK_MAX_SFI = 3
        private const val FALLBACK_MAX_RECORD = 5

        /** Maximum number of chaining exchanges (GET RESPONSE / Le replay) per command. */
        private const val MAX_CHAINING = 16
    }

    /**
     * Reads an EMV card from an NFC tag.
     * @return the extracted data, or null if no usable EMV data (or the tag was removed).
     */
    suspend fun readCard(tag: Tag): EmvCardData? = withContext(Dispatchers.IO) {
        val iso = IsoDep.get(tag) ?: return@withContext null
        try {
            if (!iso.isConnected) iso.connect()
            iso.timeout = 5000

            // 1) PPSE -> candidate AIDs (order: the card's own first)
            val aids = LinkedHashSet<String>()
            val ppse = selectPpse(iso)
            if (ppse != null && isSuccessful(ppse)) {
                EmvTlv.findAll(ppse, 0x4F).forEach { aids.add(MifareClassicUtils.bytesToHex(it)) }
            }
            KNOWN_AIDS.forEach { aids.add(it) }

            for (aidHex in aids) {
                val card = try {
                    tryAid(iso, aidHex)
                } catch (e: TagLostException) {
                    throw e // card removed: no point continuing with the other AIDs
                } catch (e: Exception) {
                    Log.e(TAG, "AID $aidHex failed", e)
                    null
                }
                if (card != null && card.isValid()) return@withContext card
            }
            null
        } catch (e: TagLostException) {
            Log.w(TAG, "Card removed during the read: stopping")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EMV card", e)
            null
        } finally {
            try { iso.close() } catch (_: Exception) {}
        }
    }

    /** Selects an AID, tries the GPO, then reads the records and parses the data. */
    private fun tryAid(iso: IsoDep, aidHex: String): EmvCardData? {
        val aid = MifareClassicUtils.hexToBytes(aidHex) ?: return null
        val selResp = transceive(iso, buildSelectCommand(aid)) ?: return null
        if (!isSuccessful(selResp)) return null

        val cardData = EmvCardData()
        cardData.cardType = AID_MAP[aidHex] ?: brandFromAid(aidHex)

        val pool = ByteArrayOutputStream()
        fun collect(resp: ByteArray) { if (resp.size >= 2) pool.write(resp, 0, resp.size - 2) }

        // GPO (with the PDOL requested by the card, filled with default values)
        val pdol = EmvTlv.findFirst(selResp, 0x9F38)
        val gpoResp = gpo(iso, pdol)
        var readAny = false
        if (gpoResp != null && isSuccessful(gpoResp)) {
            collect(gpoResp)
            val afl = EmvTlv.findFirst(gpoResp, 0x94) ?: run {
                // Format 1: template 80 = AIP(2 bytes) + AFL
                val fmt1 = EmvTlv.findFirst(gpoResp, 0x80)
                if (fmt1 != null && fmt1.size > 2) fmt1.copyOfRange(2, fmt1.size) else null
            }
            if (afl != null) {
                var i = 0
                while (i + 3 < afl.size) {
                    val sfi = (afl[i].toInt() and 0xFF) shr 3
                    val first = afl[i + 1].toInt() and 0xFF
                    val last = afl[i + 2].toInt() and 0xFF
                    if (sfi in 1..30 && first in 1..255 && last in first..255) {
                        for (rec in first..last) {
                            val r = readRecord(iso, sfi, rec)
                            if (r != null && isSuccessful(r)) { collect(r); readAny = true }
                        }
                    }
                    i += 4
                }
            }
        }

        // Fallback: limited raw read (SFI 1..3, records 1..5)
        if (!readAny) {
            for (sfi in 1..FALLBACK_MAX_SFI) {
                for (rec in 1..FALLBACK_MAX_RECORD) {
                    val r = readRecord(iso, sfi, rec)
                    if (r != null && isSuccessful(r)) collect(r)
                }
            }
        }

        parseEmv(pool.toByteArray(), cardData)
        return if (cardData.isValid()) cardData else null
    }

    private fun selectPpse(iso: IsoDep): ByteArray? =
        transceive(iso, buildSelectCommand(SELECT_PPSE.toByteArray()))

    /** GET PROCESSING OPTIONS (CLA 80, INS A8). */
    private fun gpo(iso: IsoDep, pdol: ByteArray?): ByteArray? {
        val pdolData = buildPdolData(pdol)
        val body = ByteArrayOutputStream().apply {
            write(0x83)              // tag 83: Command Template
            write(pdolData.size)
            write(pdolData)
        }.toByteArray()
        val apdu = ByteArrayOutputStream().apply {
            write(0x80); write(0xA8); write(0x00); write(0x00)
            write(body.size); write(body); write(0x00)
        }.toByteArray()
        return transceive(iso, apdu)
    }

    /** Builds the data for the requested PDOL, with reasonable default values. */
    private fun buildPdolData(pdol: ByteArray?): ByteArray {
        if (pdol == null || pdol.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var i = 0
        try {
            while (i < pdol.size) {
                var tag = pdol[i].toInt() and 0xFF; i++
                if ((tag and 0x1F) == 0x1F) {
                    while (i < pdol.size) {
                        val b = pdol[i].toInt() and 0xFF; tag = (tag shl 8) or b; i++
                        if ((b and 0x80) == 0) break
                    }
                }
                if (i >= pdol.size) break
                val len = pdol[i].toInt() and 0xFF; i++
                out.write(defaultPdolValue(tag, len))
            }
        } catch (_: Exception) { /* malformed PDOL: return what we have */ }
        return out.toByteArray()
    }

    /** Default value for one PDOL entry (zeros, except a few useful terminal fields). */
    private fun defaultPdolValue(tag: Int, len: Int): ByteArray {
        val v = ByteArray(if (len in 0..255) len else 0)
        when (tag) {
            0x9F66 -> if (v.isNotEmpty()) v[0] = 0x36.toByte() // TTQ: EMV contactless mode supported
            0x9F35 -> if (v.isNotEmpty()) v[0] = 0x22.toByte() // Terminal Type
        }
        return v
    }

    /** READ RECORD (CLA 00, INS B2), P2 = (SFI << 3) | 4. */
    private fun readRecord(iso: IsoDep, sfi: Int, record: Int): ByteArray? {
        val command = byteArrayOf(
            0x00,
            0xB2.toByte(),
            record.toByte(),
            ((sfi shl 3) or 4).toByte(),
            0x00
        )
        return transceive(iso, command)
    }

    /**
     * Sends a command following ISO 7816-4 chaining: SW 61xx -> GET RESPONSE
     * (00 C0 00 00 xx), SW 6Cxx -> replays the command with Le = xx.
     * @return data + final SW, or null. A tag loss ([TagLostException]) is rethrown.
     */
    private fun transceive(iso: IsoDep, apdu: ByteArray): ByteArray? {
        var resp = rawTransceive(iso, apdu) ?: return null
        val pending = ByteArrayOutputStream()
        repeat(MAX_CHAINING) {
            if (resp.size < 2) return null
            val sw1 = resp[resp.size - 2].toInt() and 0xFF
            val sw2 = resp[resp.size - 1].toInt() and 0xFF
            when (sw1) {
                0x61 -> {
                    pending.write(resp, 0, resp.size - 2)
                    resp = rawTransceive(iso, byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte())) ?: return null
                }
                0x6C -> {
                    // wrong Le: replay the same command with Le = sw2 (last byte of our APDU).
                    val retry = apdu.copyOf()
                    retry[retry.size - 1] = sw2.toByte()
                    resp = rawTransceive(iso, retry) ?: return null
                }
                else -> {
                    pending.write(resp, 0, resp.size)
                    return pending.toByteArray()
                }
            }
        }
        return null
    }

    /** A single raw exchange. Rethrows [TagLostException]; any other error yields null. */
    private fun rawTransceive(iso: IsoDep, apdu: ByteArray): ByteArray? =
        try {
            iso.transceive(apdu)
        } catch (e: TagLostException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Builds a SELECT command (CLA 00, INS A4, P1 04, P2 00). */
    private fun buildSelectCommand(data: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(0x00); write(0xA4); write(0x04); write(0x00)
            write(data.size); write(data); write(0x00)
        }.toByteArray()

    /** Extracts PAN / expiry / cardholder from the collected TLVs. */
    private fun parseEmv(data: ByteArray, cardData: EmvCardData) {
        EmvTlv.findFirst(data, 0x5A)?.let {
            cardData.pan = formatPan(MifareClassicUtils.bytesToHex(it).trimEnd('F', 'f'))
        }
        EmvTlv.findFirst(data, 0x5F24)?.let {
            cardData.expiryDate = formatExpiryDate(MifareClassicUtils.bytesToHex(it))
        }
        EmvTlv.findFirst(data, 0x5F20)?.let {
            val name = String(it, Charsets.UTF_8).trim()
            if (name.isNotBlank()) cardData.cardholderName = name
        }
        // Track2 (tag 57): fallback for PAN/expiry
        if (cardData.pan == null || cardData.expiryDate == null) {
            EmvTlv.findFirst(data, 0x57)?.let { t2 ->
                val s = MifareClassicUtils.bytesToHex(t2)
                val sep = s.indexOfFirst { it == 'D' || it == 'd' }
                if (sep > 0) {
                    if (cardData.pan == null) cardData.pan = formatPan(s.substring(0, sep))
                    if (cardData.expiryDate == null && s.length >= sep + 5) {
                        cardData.expiryDate = formatExpiryDate(s.substring(sep + 1, sep + 5))
                    }
                }
            }
        }
        // Application name (label) if no type is already defined
        if (cardData.cardType.isNullOrBlank()) {
            (EmvTlv.findFirst(data, 0x50) ?: EmvTlv.findFirst(data, 0x9F12))?.let {
                val label = String(it, Charsets.UTF_8).trim()
                if (label.isNotBlank()) cardData.cardType = label
            }
        }
    }

    private fun brandFromAid(aidHex: String): String {
        val a = aidHex.uppercase()
        return when {
            a.startsWith("A000000004") -> "Mastercard"
            a.startsWith("A000000003") -> "Visa"
            a.startsWith("A000000025") -> "American Express"
            a.startsWith("A000000065") -> "JCB"
            a.startsWith("A000000333") -> "UnionPay"
            else -> "EMV card"
        }
    }

    private fun formatPan(pan: String): String = pan.chunked(4).joinToString(" ")

    /** date "YYMM[DD]" -> "MM/20YY". */
    private fun formatExpiryDate(date: String): String = try {
        val year = date.substring(0, 2)
        val month = date.substring(2, 4)
        "$month/20$year"
    } catch (e: Exception) { date }

    private fun isSuccessful(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
}

/**
 * Pure BER-TLV parser (no Android dependency, JVM-testable).
 * Handles multi-byte tags (e.g. 9F38), short and long lengths (0x81, 0x82…)
 * and nested constructed templates. A truncated or malformed entry simply stops
 * the parse: no exception is thrown.
 */
object EmvTlv {

    /** Maximum depth of nested templates (protects against hostile data). */
    private const val MAX_DEPTH = 8

    /** All values (recursively) for tag [wanted]. */
    fun findAll(data: ByteArray, wanted: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        walk(data, 0, data.size, wanted, out, 0)
        return out
    }

    /** First value for tag [wanted], or null. */
    fun findFirst(data: ByteArray, wanted: Int): ByteArray? = findAll(data, wanted).firstOrNull()

    private fun walk(data: ByteArray, from: Int, to: Int, wanted: Int, out: MutableList<ByteArray>, depth: Int) {
        var i = from
        while (i < to) {
            val first = data[i].toInt() and 0xFF
            if (first == 0x00 || first == 0xFF) { i++; continue } // padding bytes
            var tag = first
            i++
            if ((first and 0x1F) == 0x1F) {
                // Multi-byte tag: each byte has bit 8 set as long as the tag continues.
                while (true) {
                    if (i >= to) return
                    val b = data[i].toInt() and 0xFF
                    tag = (tag shl 8) or b
                    i++
                    if ((b and 0x80) == 0) break
                }
            }
            if (i >= to) return
            val lenByte = data[i].toInt() and 0xFF
            i++
            val len: Int
            if ((lenByte and 0x80) == 0) {
                len = lenByte
            } else {
                val n = lenByte and 0x7F
                if (n == 0 || n > 4 || i + n > to) return
                var acc = 0
                repeat(n) {
                    acc = (acc shl 8) or (data[i].toInt() and 0xFF)
                    i++
                }
                len = acc
            }
            if (len < 0 || len > to - i) return
            val value = data.copyOfRange(i, i + len)
            if (tag == wanted) out.add(value)
            // Bit 6 of the first byte = constructed template: descend into it.
            if ((first and 0x20) != 0 && depth < MAX_DEPTH) {
                walk(value, 0, value.size, wanted, out, depth + 1)
            }
            i += len
        }
    }
}

/**
 * Data extracted from an EMV card.
 */
data class EmvCardData(
    var pan: String? = null,
    var expiryDate: String? = null,
    var cardholderName: String? = null,
    var cardType: String? = null
) {
    fun isValid(): Boolean = !pan.isNullOrEmpty() && !expiryDate.isNullOrEmpty()

    fun isComplete(): Boolean = isValid() && !cardType.isNullOrEmpty()

    /** Masked PAN: only the last 4 digits stay visible. */
    fun maskedPan(): String? = pan?.let { p ->
        val digits = p.filter { it.isDigit() }
        if (digits.length <= 4) "****" else "*".repeat(digits.length - 4) + digits.takeLast(4)
    }

    /** Safe representation (masked PAN): must never display the full number. */
    override fun toString(): String {
        val sb = StringBuilder()
        cardType?.let { sb.appendLine("Type: $it") }
        maskedPan()?.let { sb.appendLine("Card Number: $it") }
        expiryDate?.let { sb.appendLine("Expires: $it") }
        cardholderName?.let { sb.appendLine("Cardholder: $it") }
        return sb.toString()
    }
}
