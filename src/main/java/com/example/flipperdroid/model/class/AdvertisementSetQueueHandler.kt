package com.example.flipperdroid.model.`class`

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.flipperdroid.model.enums.AdvertisementError
import com.example.flipperdroid.model.enums.AdvertisementQueueMode
import com.example.flipperdroid.model.`interface`.IAdvertisementService
import com.example.flipperdroid.model.`interface`.IAdvertisementServiceCallback

class AdvertisementSetQueueHandler(
    context: Context,
    adService: IAdvertisementService
) : IAdvertisementServiceCallback {
    private val _logTag = "AdvertisementSetQueueHandler"
    private var _advertisementService: IAdvertisementService = adService
    private var _advertisementQueueMode: AdvertisementQueueMode = AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR
    private var _advertisementSets: List<AdvertisementSet> = listOf()
    private var _intervalMillis: Long = 10L
    private var _active = false
    private var _currentIndex = 0
    private val _callbacks = mutableListOf<IAdvertisementServiceCallback>()
    private var _isSpamming = false
    private var spamHandler: Handler? = null
    private var spamRunnable: Runnable? = null
    var onSpamSent: ((AdvertisementSet) -> Unit)? = null
    var onSpamError: ((AdvertisementSet?, AdvertisementError) -> Unit)? = null

    init {
        // Registers with the service to receive success/failure callbacks.
        // Without this, advertising failures (e.g. payload > 31 bytes in legacy mode) were
        // lost and the spam was logged as "sent" even when it failed.
        _advertisementService.addAdvertisementServiceCallback(this)
    }

    fun setAdvertisementSets(advertisementSets: List<AdvertisementSet>) { _advertisementSets = advertisementSets; _currentIndex = 0 }

    /** Interval between two spam frames (ms). Smaller = more aggressive. */
    fun setIntervalMillis(ms: Long) { _intervalMillis = ms.coerceIn(5L, 2000L) }

    private fun advertiseNext() {
        if (!_active || _advertisementSets.isEmpty()) return
        val nextSet = when (_advertisementQueueMode) {
            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR -> _advertisementSets[_currentIndex % _advertisementSets.size]
            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_RANDOM -> _advertisementSets.random()
        }
        _advertisementService.startAdvertisement(nextSet)
    }

    private fun onAdvertisementComplete() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (_active) {
                _currentIndex = (_currentIndex + 1) % _advertisementSets.size
                advertiseNext()
            }
        }, _intervalMillis)
    }

    // Callbacks
    override fun onAdvertisementSetStart(advertisementSet: AdvertisementSet?) { _callbacks.forEach { it.onAdvertisementSetStart(advertisementSet) } }
    override fun onAdvertisementSetStop(advertisementSet: AdvertisementSet?) { _callbacks.forEach { it.onAdvertisementSetStop(advertisementSet) } }
    override fun onAdvertisementSetSucceeded(advertisementSet: AdvertisementSet?) { _callbacks.forEach { it.onAdvertisementSetSucceeded(advertisementSet) }; onAdvertisementComplete() }
    override fun onAdvertisementSetFailed(advertisementSet: AdvertisementSet?, error: AdvertisementError) { _callbacks.forEach { it.onAdvertisementSetFailed(advertisementSet, error) }; onSpamError?.invoke(advertisementSet, error); onAdvertisementComplete() }

    fun startSpam(advertisementSets: List<AdvertisementSet>) {
        stopSpam()
        _isSpamming = true
        var index = 0
        spamHandler = Handler(Looper.getMainLooper())
        spamRunnable = object : Runnable {
            override fun run() {
                if (!_isSpamming || advertisementSets.isEmpty()) return
                val set = advertisementSets[index % advertisementSets.size]
                try {
                    set.build()
                    // Update the data of the current advertisement (no re-registration
                    // -> no accumulation of advertisers -> no more TOO_MANY_ADVERTISERS).
                    // On the 1st round (nothing running) or in legacy mode: plain start.
                    if (!_advertisementService.updateAdvertisement(set)) {
                        _advertisementService.stopAdvertisement()
                        _advertisementService.startAdvertisement(set)
                    }
                    onSpamSent?.invoke(set)
                } catch (e: Exception) {
                    Log.e(_logTag, "BLE spam error: ${e.message}", e)
                }
                index++
                spamHandler?.postDelayed(this, _intervalMillis)
            }
        }
        spamHandler?.post(spamRunnable!!)
    }

    fun stopSpam() {
        _isSpamming = false
        spamHandler?.removeCallbacksAndMessages(null)
        spamHandler = null
        spamRunnable = null
        _advertisementService.stopAdvertisement()
    }
}