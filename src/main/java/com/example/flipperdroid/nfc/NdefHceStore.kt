package com.example.flipperdroid.nfc

import android.content.Context

/** Persists the NDEF message (bytes) served by [NdefHceService] and its label. */
object NdefHceStore {
    /**
     * Maximum size of a served NDEF message: the NDEF file announced in the CC is 1024 bytes
     * (0x0400), NLEN (2 bytes) included.
     */
    const val MAX_MESSAGE_BYTES = 1022

    private const val PREFS = "ndef_hce"
    private const val KEY_NDEF = "ndef_hex"
    private const val KEY_LABEL = "label"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, label: String, ndef: ByteArray) {
        prefs(ctx).edit()
            .putString(KEY_LABEL, label)
            .putString(KEY_NDEF, MifareClassicUtils.bytesToHex(ndef))
            .apply()
    }

    fun label(ctx: Context): String? = prefs(ctx).getString(KEY_LABEL, null)

    fun ndefBytes(ctx: Context): ByteArray {
        val hex = prefs(ctx).getString(KEY_NDEF, null) ?: return ByteArray(0)
        return MifareClassicUtils.hexToBytes(hex) ?: ByteArray(0)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
