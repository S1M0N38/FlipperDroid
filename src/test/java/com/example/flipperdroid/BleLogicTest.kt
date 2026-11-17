package com.example.flipperdroid

import com.example.flipperdroid.model.`object`.AdvertisementPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the BLE frame size estimate and the legacy/extended choice.
 *
 * The key point: a short frame (≤ 31 bytes) must stay LEGACY to be
 * actually received by the targets (Windows Swift Pair, iOS Continuity, Android
 * Fast Pair only listen to legacy by default); only a frame that overflows
 * switches to extended advertising.
 */
class BleLogicTest {

    @Test
    fun swiftpair_short_name_fits_in_legacy() {
        // Swift Pair: connectable, no device name, manufacturer data =
        // 3-byte header + ASCII name. "Surface Mouse" = 13 bytes.
        // flags(3) + [overhead(2) + companyId(2) + (3 + 13)] = 3 + 20 = 23 ≤ 31.
        val bytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = true,
            includeDeviceName = false,
            deviceNameLength = 0,
            includeTxPower = false,
            manufacturerDataLengths = listOf(3 + "Surface Mouse".length),
            service16Count = 0,
            serviceData16Lengths = emptyList(),
        )
        assertEquals(23, bytes)
        assertFalse(AdvertisementPayload.overflowsLegacy(bytes))
    }

    @Test
    fun swiftpair_long_name_overflows_legacy() {
        // "Microsoft Bluetooth Mouse" = 25 bytes:
        // flags(3) + [2 + 2 + (3 + 25)] = 3 + 32 = 35 > 31 → extended.
        val bytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = true,
            includeDeviceName = false,
            deviceNameLength = 0,
            includeTxPower = false,
            manufacturerDataLengths = listOf(3 + "Microsoft Bluetooth Mouse".length),
            service16Count = 0,
            serviceData16Lengths = emptyList(),
        )
        assertEquals(35, bytes)
        assertTrue(AdvertisementPayload.overflowsLegacy(bytes))
    }

    @Test
    fun continuity_nonconnectable_no_flags() {
        // Continuity: non-connectable → no Flags structure.
        // 10-byte Apple manufacturer data: 2 + 2 + 10 = 14 ≤ 31.
        val bytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = false,
            includeDeviceName = false,
            deviceNameLength = 0,
            includeTxPower = false,
            manufacturerDataLengths = listOf(10),
            service16Count = 0,
            serviceData16Lengths = emptyList(),
        )
        assertEquals(14, bytes)
        assertFalse(AdvertisementPayload.overflowsLegacy(bytes))
    }

    @Test
    fun fastpair_service_data_fits_in_legacy() {
        // Fast Pair: 16-bit service data (UUID 0xFE2C) of 3 bytes + advertised UUID.
        // flags(3) + uuid16 list[2 + 2] + serviceData[2 + 2 + 3] = 3 + 4 + 7 = 14.
        val bytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = true,
            includeDeviceName = false,
            deviceNameLength = 0,
            includeTxPower = false,
            manufacturerDataLengths = emptyList(),
            service16Count = 1,
            serviceData16Lengths = listOf(3),
        )
        assertEquals(14, bytes)
        assertFalse(AdvertisementPayload.overflowsLegacy(bytes))
    }

    @Test
    fun device_name_and_tx_power_count_toward_budget() {
        // includeDeviceName (8-char name) + txPower + 20-byte manufacturer data:
        // 0 flags (non connectable) + [2 + 8] + 3 + [2 + 2 + 20] = 10 + 3 + 24 = 37 > 31.
        val bytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = false,
            includeDeviceName = true,
            deviceNameLength = 8,
            includeTxPower = true,
            manufacturerDataLengths = listOf(20),
            service16Count = 0,
            serviceData16Lengths = emptyList(),
        )
        assertEquals(37, bytes)
        assertTrue(AdvertisementPayload.overflowsLegacy(bytes))
    }

    @Test
    fun boundary_exactly_31_stays_legacy() {
        assertFalse(AdvertisementPayload.overflowsLegacy(AdvertisementPayload.LEGACY_MAX_PAYLOAD_BYTES))
        assertTrue(AdvertisementPayload.overflowsLegacy(AdvertisementPayload.LEGACY_MAX_PAYLOAD_BYTES + 1))
    }
}
