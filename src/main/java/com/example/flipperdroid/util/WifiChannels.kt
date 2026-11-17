package com.example.flipperdroid.util

/**
 * Wi-Fi frequency (MHz) -> channel number and band label conversions.
 * Single source used by the Wi-Fi analyzer and wardriving (formerly private copies).
 */
object WifiChannels {

    /** Standard 2.4 GHz channels. */
    val STANDARD_2_4_GHZ: List<Int> = (1..14).toList()

    /** Standard 5 GHz channels (20 MHz width). */
    val STANDARD_5_GHZ: List<Int> =
        listOf(36, 40, 44, 48, 52, 56, 60, 64) + (100..144 step 4).toList() + (149..165 step 4).toList()

    /**
     * Channel matching a frequency in MHz, 0 if the frequency is not recognized.
     * 2.4 GHz: 2412..2472 -> (f-2407)/5, channel 14 at 2484; 5 GHz: (f-5000)/5;
     * 6 GHz: (f-5950)/5 (channel 1 = 5955 MHz).
     */
    fun frequencyToChannel(freq: Int): Int = when (freq) {
        2484 -> 14
        in 2412..2472 -> (freq - 2407) / 5
        in 5160..5885 -> (freq - 5000) / 5
        in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    /** Band label: "2.4 GHz", "5 GHz", "6 GHz" or "Unknown". */
    fun band(freq: Int): String = when (freq) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5895 -> "5 GHz"
        in 5955..7115 -> "6 GHz"
        else -> "Unknown"
    }
}
