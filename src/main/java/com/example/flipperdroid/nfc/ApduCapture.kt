package com.example.flipperdroid.nfc

import android.nfc.tech.IsoDep
import java.io.ByteArrayOutputStream

/**
 * Probes an ISO-DEP card and records its APDU dialogue (command -> response)
 * to replay it later through HCE. Best-effort: only captures what the card
 * answers to a set of standard commands (SELECT known PPSE/PSE/AID, GPO, READ RECORD).
 *
 * Table keys: a SELECT AID is stored as-is (it identifies the AID). The following
 * commands (GPO, READ RECORD) are stored under "AID|command" ([scopedKey]) so that the
 * responses of one AID do not overwrite those of another AID.
 * Only 9000 responses are kept for GPO and READ RECORD.
 */
object ApduCapture {

    data class Result(val aids: List<String>, val map: Map<String, String>)

    private val PROBE_AIDS = listOf(
        "325041592E5359532E4444463031", // 2PAY.SYS.DDF01 (PPSE)
        "315041592E5359532E4444463031", // 1PAY.SYS.DDF01 (PSE)
        "A0000000031010", "A0000000041010", "A0000000651010",
        "A0000000042203", "A0000000043060", "A000000025010801", "A0000003330101"
    )

    fun probe(iso: IsoDep): Result {
        val map = LinkedHashMap<String, String>()
        val aids = LinkedHashSet<String>()
        if (!iso.isConnected) iso.connect()
        iso.timeout = 5000
        for (aidHex in PROBE_AIDS) {
            val cmd = buildSelect(MifareClassicUtils.hexToBytes(aidHex) ?: continue)
            val resp = try { iso.transceive(cmd) } catch (e: Exception) { null } ?: continue
            map[MifareClassicUtils.bytesToHex(cmd)] = MifareClassicUtils.bytesToHex(resp)
            val isDirectory = aidHex.startsWith("325041") || aidHex.startsWith("315041")
            if (isOk(resp) && !isDirectory) {
                aids.add(aidHex)
                probeAfterSelect(iso, aidHex, map)
            }
        }
        return Result(aids.toList(), map)
    }

    /** Table key of a command issued after the SELECT of [aidHex] (GPO, READ RECORD…). */
    fun scopedKey(aidHex: String, commandHex: String): String = "$aidHex|$commandHex"

    /**
     * If [cmd] is a SELECT by name (00 A4 04 ..), returns the selected AID in hexadecimal,
     * otherwise null.
     */
    fun selectedAidOf(cmd: ByteArray): String? {
        if (cmd.size < 5) return null
        if (cmd[0] != 0x00.toByte() || cmd[1] != 0xA4.toByte() || cmd[2] != 0x04.toByte()) return null
        val lc = cmd[4].toInt() and 0xFF
        if (cmd.size < 5 + lc) return null
        return MifareClassicUtils.bytesToHex(cmd.copyOfRange(5, 5 + lc))
    }

    /** Enriches the capture after a successful SELECT AID: GPO + a few READ RECORD. */
    private fun probeAfterSelect(iso: IsoDep, aidHex: String, map: MutableMap<String, String>) {
        val gpo = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00)
        try {
            val r = iso.transceive(gpo)
            // A non-9000 GPO response (refusal, error) is not replayable: we do not store it.
            if (isOk(r)) map[scopedKey(aidHex, MifareClassicUtils.bytesToHex(gpo))] = MifareClassicUtils.bytesToHex(r)
        } catch (_: Exception) {}
        for (sfi in 1..3) {
            for (rec in 1..5) {
                val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)
                try {
                    val r = iso.transceive(cmd)
                    if (isOk(r)) map[scopedKey(aidHex, MifareClassicUtils.bytesToHex(cmd))] = MifareClassicUtils.bytesToHex(r)
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildSelect(aid: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(0x00); write(0xA4); write(0x04); write(0x00)
            write(aid.size); write(aid); write(0x00)
        }.toByteArray()

    private fun isOk(r: ByteArray): Boolean =
        r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
}
