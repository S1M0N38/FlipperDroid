package com.example.flipperdroid.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * HCE service: replays the APDU dialogue of a captured ISO-DEP card
 * (see [ApduCapture] / [EmulationStore]). With no capture, keeps a stub
 * that answers "success" to SELECT AID Visa (demo).
 *
 * Platform limitation: HCE only handles ISO-DEP (APDU) — not Mifare Classic.
 * A real EMV card cannot be replayed for payment (dynamic cryptogram).
 *
 * Privacy: no command or response is logged.
 */
class EmvCardEmulationService : HostApduService() {
    companion object {
        private val VISA_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x10, 0x10)
        private val SELECT_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val UNKNOWN_CMD = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }

    /** Captured table: read once on the first APDU, then cached. */
    private var replayMap: Map<String, String>? = null

    /** AID selected by the reader: used to find the responses specific to that AID. */
    private var selectedAid: String? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return UNKNOWN_CMD

        // 1) Replay of a captured dialogue (ISO-DEP HCE emulation).
        val map = replayMap ?: EmulationStore.map(applicationContext).also { replayMap = it }
        ApduCapture.selectedAidOf(commandApdu)?.let { selectedAid = it }
        val cmdHex = MifareClassicUtils.bytesToHex(commandApdu)
        // Response specific to the current AID; otherwise fall back to the old "command alone" key (older data).
        val scoped = selectedAid?.let { map[ApduCapture.scopedKey(it, cmdHex)] }
        val replay = scoped ?: map[cmdHex]
        if (replay != null) {
            return MifareClassicUtils.hexToBytes(replay) ?: UNKNOWN_CMD
        }

        // 2) Fallback: legacy Visa stub (SELECT AID Visa -> success).
        if (commandApdu.size >= 12) {
            val aid = commandApdu.copyOfRange(5, 12)
            if (aid.contentEquals(VISA_AID)) return SELECT_OK
        }
        return UNKNOWN_CMD
    }

    override fun onDeactivated(reason: Int) {
        // End of the read session: the next SELECT will redefine the current AID.
        selectedAid = null
    }
}
