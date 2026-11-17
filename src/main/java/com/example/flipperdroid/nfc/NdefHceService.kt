package com.example.flipperdroid.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Emulates an NFC Forum Type 4 tag (HCE): the phone answers as an NDEF tag on
 * read, serving the configured message (URL/text) via [NdefHceStore]. Another
 * phone/reader in reader mode then reads the tag.
 *
 * The APDU response logic lives in [Type4Responder] (pure, JVM-testable).
 */
class NdefHceService : HostApduService() {

    companion object {
        /** "Instruction not supported" response (6D00). */
        private val UNKNOWN = byteArrayOf(0x6D, 0x00)
    }

    /** Responder of the current session; recreated on each session to re-read the stored message. */
    private var responder: Type4Responder? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val cmd = commandApdu ?: return UNKNOWN
        val r = responder ?: Type4Responder(NdefHceStore.ndefBytes(applicationContext)).also { responder = it }
        return r.process(cmd)
    }

    override fun onDeactivated(reason: Int) {
        responder = null
    }
}

/**
 * NFC Forum Type 4 responder with no Android dependency.
 *
 * Files: CC (E103, 15 bytes) and NDEF (E104 = 2-byte NLEN + message). NDEF application
 * D2760000850101: it must be selected (by name) before selecting a file.
 * Read-only: UPDATE BINARY is denied (6982).
 *
 * @param ndefMessage raw NDEF message (without NLEN).
 */
class Type4Responder(ndefMessage: ByteArray) {

    companion object {
        /** AID of the Type 4 NDEF application. */
        private val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
        )
        private const val FILE_CC = 0xE103
        private const val FILE_NDEF = 0xE104

        /**
         * Capability Container (15 bytes): version 2.0, MLe 0x003B, MLc 0x0034,
         * NDEF-file-control TLV: file E104, max size 0x0400, free read, write denied (FF).
         */
        private val CC = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34,
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x04, 0x00, 0x00, 0xFF.toByte()
        )

        private fun sw(code: Int): ByteArray = byteArrayOf((code shr 8).toByte(), code.toByte())
        private val SW_OK = sw(0x9000)
        private val SW_WRONG_LENGTH = sw(0x6700)
        private val SW_FILE_NOT_FOUND = sw(0x6A82)
        private val SW_NO_CURRENT_EF = sw(0x6986)
        private val SW_WRONG_OFFSET = sw(0x6B00)
        private val SW_WRITE_DENIED = sw(0x6982)
        private val SW_INS_NOT_SUPPORTED = sw(0x6D00)
    }

    /** Fichier NDEF = NLEN (2 octets, big-endian) + message. */
    private val ndefFile: ByteArray = byteArrayOf(
        ((ndefMessage.size shr 8) and 0xFF).toByte(),
        (ndefMessage.size and 0xFF).toByte()
    ) + ndefMessage

    private var appSelected = false
    /** 0 = no file selected, otherwise [FILE_CC] or [FILE_NDEF]. */
    private var selectedFile = 0

    /** Handles a command APDU and returns the full response (data + SW). */
    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            0xA4 -> select(p1, apdu)
            0xB0 -> readBinary(p1, p2, apdu)
            0xD6 -> SW_WRITE_DENIED // UPDATE BINARY: file is read-only
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(p1: Int, apdu: ByteArray): ByteArray {
        val data = dataField(apdu) ?: return SW_WRONG_LENGTH
        return when (p1) {
            0x04 -> { // select an application by name
                if (!data.contentEquals(NDEF_AID)) return SW_FILE_NOT_FOUND
                appSelected = true
                selectedFile = 0
                SW_OK
            }
            0x00 -> { // select a file by identifier
                if (!appSelected) return SW_FILE_NOT_FOUND
                if (data.size != 2) return SW_FILE_NOT_FOUND
                val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                when (id) {
                    FILE_CC, FILE_NDEF -> {
                        selectedFile = id
                        SW_OK
                    }
                    else -> SW_FILE_NOT_FOUND
                }
            }
            else -> sw(0x6A86) // incorrect P1/P2
        }
    }

    /** READ BINARY: 00 B0 P1 P2 Le. Le = 0 means 256 bytes. */
    private fun readBinary(p1: Int, p2: Int, apdu: ByteArray): ByteArray {
        if (selectedFile == 0) return SW_NO_CURRENT_EF
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val file = if (selectedFile == FILE_CC) CC else ndefFile
        val offset = ((p1 and 0x7F) shl 8) or p2
        if (offset >= file.size) return SW_WRONG_OFFSET
        val le = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        val end = minOf(offset + le, file.size)
        return file.copyOfRange(offset, end) + SW_OK
    }

    /** Data field of a case 3/4 APDU (Lc + data), or null if truncated. */
    private fun dataField(apdu: ByteArray): ByteArray? {
        if (apdu.size < 5) return if (apdu.size == 4) ByteArray(0) else null
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }
}
