package com.example.flipperdroid.model.`interface`

import com.example.flipperdroid.model.`class`.AdvertisementSet
import com.example.flipperdroid.model.enums.TxPowerLevel

interface IAdvertisementService {
    fun startAdvertisement(advertisementSet: AdvertisementSet)
    fun stopAdvertisement()

    /**
     * Updates the DATA of the current advertisement without re-registering an advertising
     * set (avoids the accumulation of advertisers -> TOO_MANY_ADVERTISERS during spam).
     * @return true if the update was applied; false if there is no current advertisement
     *   (the caller must then do a regular start).
     */
    fun updateAdvertisement(advertisementSet: AdvertisementSet): Boolean = false
    fun setTxPowerLevel(txPowerLevel: TxPowerLevel)
    fun getTxPowerLevel(): TxPowerLevel
    fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback)
    fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback)
    fun isLegacyService(): Boolean
} 