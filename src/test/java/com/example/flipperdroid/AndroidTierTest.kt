package com.example.flipperdroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.flipperdroid.nfc.NdefHceStore
import com.example.flipperdroid.util.AppLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android tier of the JVM tests: real framework behaviour (SharedPreferences,
 * `android.util.Log`) through Robolectric, so no emulator and no phone is needed
 * for anything that is Android-facing but radio-free.
 *
 * `sdk = [34]` matches the emulator image we develop against (`system-images;
 * android-34;google_apis;arm64-v8a`). Robolectric downloads its `android-all`
 * artifact on the first run only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun ndef_hce_store_roundtrip() {
        val payload = byteArrayOf(0xD1.toByte(), 0x01, 0x0E, 0x55, 0x04) +
            "example.org".toByteArray()

        NdefHceStore.save(context, "test label", payload)

        assertEquals("test label", NdefHceStore.label(context))
        assertArrayEquals(payload, NdefHceStore.ndefBytes(context))
    }

    @Test
    fun ndef_hce_store_clear_empties_it() {
        NdefHceStore.save(context, "label", byteArrayOf(1, 2, 3))

        NdefHceStore.clear(context)

        assertNull(NdefHceStore.label(context))
        assertArrayEquals(ByteArray(0), NdefHceStore.ndefBytes(context))
    }

    @Test
    fun applog_is_ordered_and_capped() {
        AppLog.clear()
        repeat(520) { AppLog.log("t", "line $it") }

        val entries = AppLog.entries.value
        assertEquals(500, entries.size)
        assertTrue(entries.last().endsWith("[t] line 519"))
        assertTrue(entries.first().endsWith("[t] line 20"))
    }
}
