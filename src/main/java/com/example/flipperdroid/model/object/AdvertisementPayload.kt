package com.example.flipperdroid.model.`object`

/**
 * Estimation of the size (in bytes) of a *legacy* BLE advertising frame
 * and choice of the legacy / extended mode.
 *
 * Legacy caps the payload at 31 bytes. Many targets
 * (Windows Swift Pair, iOS/macOS Continuity, Android Fast Pair) listen
 * ONLY to legacy advertising by default: sending a short frame as extended
 * makes it invisible (Windows requires `AllowExtendedAdvertisements`, false by
 * default). We therefore keep legacy as long as the payload fits in 31 bytes and
 * only switch to extended when it actually overflows.
 *
 * Pure object (no `android.*` dependency) so that it is testable on the JVM.
 */
object AdvertisementPayload {
    /** Maximum payload of a legacy advertising frame, in bytes. */
    const val LEGACY_MAX_PAYLOAD_BYTES = 31

    // Overhead of one AD structure: 1 length byte + 1 type byte.
    private const val AD_STRUCT_OVERHEAD = 2
    private const val FLAGS_BYTES = 3        // length(1) + type(1) + value(1)
    private const val TX_POWER_BYTES = 3     // length(1) + type(1) + value(1)
    private const val COMPANY_ID_BYTES = 2   // manufacturer identifier (little-endian)
    private const val UUID16_BYTES = 2       // 16-bit service UUID

    /**
     * Estimates the number of bytes the frame would take up in legacy advertising.
     *
     * @param connectable the adv is connectable (the stack then adds the Flags structure)
     * @param includeDeviceName the full device name is included in the frame
     * @param deviceNameLength name length (in bytes) if it is included
     * @param includeTxPower the transmit power level is included
     * @param manufacturerDataLengths manufacturer-specific data lengths
     *        (excluding the manufacturer identifier), one entry per structure
     * @param service16Count number of advertised 16-bit service UUIDs
     * @param serviceData16Lengths 16-bit service data lengths, one entry per structure
     */
    fun estimateLegacyBytes(
        connectable: Boolean,
        includeDeviceName: Boolean,
        deviceNameLength: Int,
        includeTxPower: Boolean,
        manufacturerDataLengths: List<Int>,
        service16Count: Int,
        serviceData16Lengths: List<Int>,
    ): Int {
        var bytes = 0
        if (connectable) bytes += FLAGS_BYTES
        if (includeTxPower) bytes += TX_POWER_BYTES
        if (includeDeviceName && deviceNameLength > 0) bytes += AD_STRUCT_OVERHEAD + deviceNameLength
        for (len in manufacturerDataLengths) bytes += AD_STRUCT_OVERHEAD + COMPANY_ID_BYTES + len
        if (service16Count > 0) bytes += AD_STRUCT_OVERHEAD + UUID16_BYTES * service16Count
        for (len in serviceData16Lengths) bytes += AD_STRUCT_OVERHEAD + UUID16_BYTES + len
        return bytes
    }

    /** true if the frame overflows legacy and must switch to extended advertising. */
    fun overflowsLegacy(estimatedBytes: Int): Boolean = estimatedBytes > LEGACY_MAX_PAYLOAD_BYTES
}
