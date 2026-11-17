package com.example.flipperdroid.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.example.flipperdroid.model.`class`.AdvertisementSetQueueHandler
import com.example.flipperdroid.model.`object`.BluetoothHelpers
import com.example.flipperdroid.model.`object`.ContinuityNewDevicePopUpAdvertisementSetGenerator
import com.example.flipperdroid.model.`object`.ContinuityNearbyActionAdvertisementSetGenerator
import com.example.flipperdroid.model.`object`.EasySetupWatchAdvertisementSetGenerator
import com.example.flipperdroid.model.`object`.EasySetupBudsAdvertisementSetGenerator
import com.example.flipperdroid.model.`object`.SwiftPairAdvertisementSetGenerator
import com.example.flipperdroid.model.`object`.FastPairAdvertisementSetGenerator
import com.example.flipperdroid.model.`class`.AdvertisementSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BleSpamViewModel(app: Application) : AndroidViewModel(app) {
    enum class BleSpamBrand { APPLE, SAMSUNG, MICROSOFT, GOOGLE, ALL }
    @SuppressLint("StaticFieldLeak")
    private val context = app.applicationContext
    // Extended advertising (API 26+) to exceed the 31-byte legacy limit, otherwise legacy.
    private val handler = AdvertisementSetQueueHandler(
        context,
        BluetoothHelpers.getAdvertisementService(
            context,
            useLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        )
    )
    private val _advertisementSets = MutableStateFlow<List<AdvertisementSet>>(emptyList())
    val advertisementSets: StateFlow<List<AdvertisementSet>> = _advertisementSets
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive
    private var _allAdvertisementSets: List<AdvertisementSet> = emptyList()
    private val _brand = MutableStateFlow(BleSpamBrand.APPLE)
    val brand: StateFlow<BleSpamBrand> = _brand
    private val _spamLogs = MutableStateFlow<List<String>>(emptyList())
    val spamLogs: StateFlow<List<String>> = _spamLogs
    private var spamCount = 0

    // Spam speed (interval in ms between two frames): smaller = more aggressive.
    // 100 ms by default: setAdvertisingData is rate-limited by the controller; too fast,
    // some updates are ignored (without leaking advertisers).
    private val _speedMs = MutableStateFlow(100L)
    val speedMs: StateFlow<Long> = _speedMs

    fun setSpeed(ms: Long) {
        _speedMs.value = ms
        handler.setIntervalMillis(ms)
    }

    // Payloads actually selected for spamming (all by default)
    private var _selectedSets: List<AdvertisementSet> = emptyList()

    init {
        loadAdvertisementSets()
    }

    fun setBrand(brand: BleSpamBrand) {
        _brand.value = brand
        loadAdvertisementSets()
    }

    fun startSpam() {
        // Spam only the selected payloads; fall back to all of them if nothing is selected.
        val sets = _selectedSets.ifEmpty { _advertisementSets.value }
        if (sets.isEmpty()) return
        _isActive.value = true
        handler.onSpamSent = { set ->
            spamCount++
            val log = "[${System.currentTimeMillis() % 100000}] ${(if (set.title.isNotBlank()) set.title else set.toString())} (${set.type})"
            _spamLogs.value = (_spamLogs.value + log).takeLast(100)
        }
        // Honest reporting of failures (e.g. DATA_TOO_LARGE: payload > 31 bytes rejected
        // by legacy advertising) instead of implying a successful send.
        handler.onSpamError = { set, error ->
            val name = set?.title?.takeIf { it.isNotBlank() } ?: set?.type?.toString() ?: "?"
            val hint = if (error == com.example.flipperdroid.model.enums.AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
                " — toggle Bluetooth OFF/ON to clear leaked advertisers" else ""
            _spamLogs.value = (_spamLogs.value + "⚠ FAILED: $name ($error)$hint").takeLast(100)
        }
        handler.setIntervalMillis(_speedMs.value)
        handler.startSpam(sets)
    }

    fun stopSpam() {
        _isActive.value = false
        handler.onSpamSent = null
        handler.onSpamError = null
        handler.stopSpam()
    }

    private fun loadAdvertisementSets() {
        val sets = when (_brand.value) {
            BleSpamBrand.APPLE -> (
                ContinuityNewDevicePopUpAdvertisementSetGenerator.getAdvertisementSets() +
                    ContinuityNearbyActionAdvertisementSetGenerator.getAdvertisementSets()
            )
            BleSpamBrand.SAMSUNG -> (
                EasySetupWatchAdvertisementSetGenerator.getAdvertisementSets() + EasySetupBudsAdvertisementSetGenerator.getAdvertisementSets()
            )
            BleSpamBrand.MICROSOFT -> SwiftPairAdvertisementSetGenerator.getAdvertisementSets()
            BleSpamBrand.GOOGLE -> FastPairAdvertisementSetGenerator.getAdvertisementSets()
            BleSpamBrand.ALL -> (
                ContinuityNewDevicePopUpAdvertisementSetGenerator.getAdvertisementSets()
                + ContinuityNearbyActionAdvertisementSetGenerator.getAdvertisementSets()
                + EasySetupWatchAdvertisementSetGenerator.getAdvertisementSets()
                + EasySetupBudsAdvertisementSetGenerator.getAdvertisementSets()
                + SwiftPairAdvertisementSetGenerator.getAdvertisementSets()
                + FastPairAdvertisementSetGenerator.getAdvertisementSets()
            )
        }
        _advertisementSets.value = sets
        _allAdvertisementSets = sets
        _selectedSets = sets
        handler.setAdvertisementSets(sets)
    }

    override fun onCleared() {
        super.onCleared()
        // The handler runs on the main looper: without an explicit stop, advertising
        // kept going after the activity was destroyed.
        stopSpam()
    }

    fun setCheckedPayloads(checkedStates: List<Boolean>) {
        _selectedSets = _allAdvertisementSets.filterIndexed { idx, _ -> checkedStates.getOrNull(idx) == true }
    }
} 