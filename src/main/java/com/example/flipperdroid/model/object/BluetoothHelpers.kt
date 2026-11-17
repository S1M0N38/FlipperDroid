package com.example.flipperdroid.model.`object`

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.example.flipperdroid.model.`class`.LegacyAdvertisementService
import com.example.flipperdroid.model.`class`.ModernAdvertisementService
import com.example.flipperdroid.model.`interface`.IAdvertisementService

object BluetoothHelpers {
    fun Context.bluetoothManager(): BluetoothManager? =
        this.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    fun Context.bluetoothAdapter(): BluetoothAdapter? = this.bluetoothManager()?.adapter


    fun getAdvertisementService(context: Context, useLegacy: Boolean = true): IAdvertisementService {
        // The API check belongs here, where the object is built: lint (and a future
        // caller) cannot see a boolean that was computed somewhere else.
        return if (!useLegacy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ModernAdvertisementService(context)
        } else {
            LegacyAdvertisementService(context)
        }
    }
} 