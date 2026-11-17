package com.example.flipperdroid.model.`class`

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.example.flipperdroid.model.enums.AdvertisementError
import com.example.flipperdroid.model.enums.TxPowerLevel
import com.example.flipperdroid.model.`interface`.IAdvertisementService
import com.example.flipperdroid.model.`interface`.IAdvertisementServiceCallback
import com.example.flipperdroid.model.`object`.AdvertisementPayload
import com.example.flipperdroid.model.`object`.AdvertisementPayload.LEGACY_MAX_PAYLOAD_BYTES as LEGACY_MAX
import com.example.flipperdroid.model.`object`.BluetoothHelpers.bluetoothAdapter

/**
 * Extended advertising (AdvertisingSet) exists from Android 8 (API 26).
 * [com.example.flipperdroid.model.object.BluetoothHelpers.getAdvertisementService]
 * only instantiates it when the device is new enough; the annotation states the
 * floor so lint does not have to infer it from a caller's boolean.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ModernAdvertisementService(
    private val context: Context,
): IAdvertisementService {
    private val _logTag = "ModernAdvertisementService"
    private var _bluetoothAdapter: BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks: MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel: TxPowerLevel? = null
    // The advertising set actually registered (returned by onAdvertisingSetStarted):
    // allows updating the data without re-registering.
    private var _runningSet: AdvertisingSet? = null

    init {
        _bluetoothAdapter = context.bluetoothAdapter()
        if (_bluetoothAdapter != null) {
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }
    }

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet): AdvertisementSet {
        if (_txPowerLevel != null) {
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        // Legacy vs extended: legacy caps the frame at 31 bytes, hence the DATA_TOO_LARGE
        // failures on Apple Continuity / SwiftPair. BUT most targets (Windows Swift Pair,
        // iOS/macOS Continuity, Android Fast Pair) only listen to legacy advertising by
        // default: a short frame sent as extended is invisible to them. So we keep LEGACY
        // as long as the payload fits in 31 bytes (the case for the vast majority of spam
        // payloads) and only switch to EXTENDED when it actually overflows.
        val hasScanResponse = advertisementSet.scanResponse != null
        // No SDK_INT check here: the class is @RequiresApi(O) and
        // getAdvertisementService only builds it on API 26+.
        val extendedSupported =
            _bluetoothAdapter?.isLeExtendedAdvertisingSupported == true
        val data = advertisementSet.advertiseData
        val estimatedBytes = AdvertisementPayload.estimateLegacyBytes(
            connectable = advertisementSet.advertiseSettings.connectable,
            includeDeviceName = data.includeDeviceName,
            deviceNameLength = if (data.includeDeviceName) safeAdapterNameLength() else 0,
            includeTxPower = data.includeTxPower,
            manufacturerDataLengths = data.manufacturerData.map { it.manufacturerSpecificData.size },
            service16Count = data.services.count { it.serviceUuid != null },
            serviceData16Lengths = data.services.mapNotNull { it.serviceData?.size },
        )
        val overflowsLegacy = AdvertisementPayload.overflowsLegacy(estimatedBytes)
        val params = advertisementSet.advertisingSetParameters
        if (extendedSupported && !hasScanResponse && overflowsLegacy) {
            // Frame > 31 bytes: no choice, extended advertising (some targets
            // will not receive it, but legacy would reject it with DATA_TOO_LARGE).
            Log.d(_logTag, "Payload ~$estimatedBytes B > $LEGACY_MAX — extended advertising (may be invisible to legacy-only scanners)")
            params.legacyMode = false
            params.connectable = advertisementSet.advertiseSettings.connectable
            params.scanable = false
        } else {
            // Frame ≤ 31 bytes: LEGACY, so that it is actually received by the targets.
            params.legacyMode = true
            params.connectable = advertisementSet.advertiseSettings.connectable
            params.scanable = hasScanResponse
        }
        advertisementSet.advertisingSetCallback = getAdvertisingSetCallback()
        return advertisementSet
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun startAdvertisement(advertisementSet: AdvertisementSet) {
        if (_advertiser != null) {
            if (advertisementSet.validate()) {
                val preparedAdvertisementSet = prepareAdvertisementSet(advertisementSet)
                if (preparedAdvertisementSet.scanResponse != null) {
                    _advertiser!!.startAdvertisingSet(
                        preparedAdvertisementSet.advertisingSetParameters.build(),
                        preparedAdvertisementSet.advertiseData.build(),
                        preparedAdvertisementSet.scanResponse!!.build(),
                        null, null, preparedAdvertisementSet.advertisingSetCallback
                    )
                } else {
                    _advertiser!!.startAdvertisingSet(
                        preparedAdvertisementSet.advertisingSetParameters.build(),
                        preparedAdvertisementSet.advertiseData.build(),
                        null, null, null, preparedAdvertisementSet.advertisingSetCallback
                    )
                }
                Log.d(_logTag, "Started Modern Advertisement")
                _currentAdvertisementSet = preparedAdvertisementSet
                _advertisementServiceCallbacks.map {
                    it.onAdvertisementSetStart(advertisementSet)
                }
            } else {
                Log.d(_logTag, "Advertisement Set could not be validated")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun stopAdvertisement() {
        if (_advertiser != null) {
            if (_currentAdvertisementSet != null) {
                _advertiser!!.stopAdvertisingSet(_currentAdvertisementSet!!.advertisingSetCallback)
                _currentAdvertisementSet = null
                _runningSet = null
            } else {
                Log.d(_logTag, "Current Modern Advertising Set is null")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun updateAdvertisement(advertisementSet: AdvertisementSet): Boolean {
        val running = _runningSet ?: return false
        val data = advertisementSet.advertiseData.build() ?: return false
        return try {
            running.setAdvertisingData(data)
            _currentAdvertisementSet?.let { advertisementSet.advertisingSetCallback = it.advertisingSetCallback }
            true
        } catch (e: Exception) {
            Log.d(_logTag, "updateAdvertisement failed: ${e.message}")
            false
        }
    }

    override fun setTxPowerLevel(txPowerLevel: TxPowerLevel) {
        _txPowerLevel = txPowerLevel
    }

    override fun getTxPowerLevel(): TxPowerLevel {
        return _txPowerLevel ?: TxPowerLevel.TX_POWER_HIGH
    }

    override fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback) {
        if (!_advertisementServiceCallbacks.contains(callback)) {
            _advertisementServiceCallbacks.add(callback)
        }
    }
    override fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback) {
        if (_advertisementServiceCallbacks.contains(callback)) {
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    override fun isLegacyService(): Boolean {
        return false
    }

    // getName() may throw SecurityException on API 31+ without BLUETOOTH_CONNECT.
    private fun safeAdapterNameLength(): Int = try {
        _bluetoothAdapter?.name?.length ?: 0
    } catch (e: SecurityException) {
        0
    }

    private fun getAdvertisingSetCallback(): AdvertisingSetCallback {
        return object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    _runningSet = advertisingSet
                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetSucceeded(_currentAdvertisementSet)
                    }
                } else {
                    val advertisementError = when (status) {
                        ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                        ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                        else -> AdvertisementError.ADVERTISE_FAILED_UNKNOWN
                    }
                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetFailed(_currentAdvertisementSet, advertisementError)
                    }
                }
            }
            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet, status: Int) {
                // The controller may reject an update (e.g. data > 31 bytes on a
                // legacy set): report it instead of pretending the send succeeded.
                if (status != ADVERTISE_SUCCESS) {
                    val err = if (status == ADVERTISE_FAILED_DATA_TOO_LARGE)
                        AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                    else AdvertisementError.ADVERTISE_FAILED_UNKNOWN
                    _advertisementServiceCallbacks.map { it.onAdvertisementSetFailed(_currentAdvertisementSet, err) }
                }
            }
            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet, status: Int) {}
            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet) {
                _runningSet = null
                _advertisementServiceCallbacks.map {
                    it.onAdvertisementSetStop(_currentAdvertisementSet)
                }
            }
        }
    }
}