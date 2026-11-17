package com.example.flipperdroid.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.ConsumerIrManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.flipperdroid.infrared.IrFile
import com.example.flipperdroid.infrared.IrProtocols
import com.example.flipperdroid.infrared.PowerCode
import com.example.flipperdroid.infrared.PowerCodes
import com.example.flipperdroid.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Offensive IR tools: TV-B-Gone (sweep of every power-off code) and
 * command brute-force (mapping an unknown remote). For authorized testing
 * only.
 */
class IrToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val irManager =
        app.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val hasEmitter: Boolean get() = irManager?.hasIrEmitter() == true

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    private val _bruteCommand = MutableStateFlow(-1)
    val bruteCommand: StateFlow<Int> = _bruteCommand

    private var job: Job? = null

    /**
     * Emits every known power code (bundled .ir files + curated list).
     *
     * Each code is sent as a **repeated burst** (IrProtocols.encodePowerBurst):
     * an isolated frame is filtered out as noise by most TVs, and Sony requires
     * 3 repetitions. Duplicate codes (e.g. LG present in LG_TV.ir AND in the
     * curated list) are **deduplicated**: "power" being a toggle, emitting the same
     * code twice would turn back on the TV we just turned off.
     *
     * @param rounds number of passes. 1 by default (one toggle = power-off). Do NOT
     *   increase it without reason: each extra pass toggles every device again.
     */
    fun tvBGone(rounds: Int = 1) {
        if (_running.value) return
        val manager = irManager
        if (manager == null || !manager.hasIrEmitter()) {
            _progress.value = IrProtocols.NO_EMITTER_MSG
            return
        }
        _running.value = true   // set BEFORE launching: a second tap is refused immediately
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val codes = collectPowerSignals()
                _progress.value = "TV-B-Gone: ${codes.size} unique codes loaded"
                var sent = 0
                var lastError: String? = null
                repeat(rounds) {
                    for ((label, signal) in codes) {
                        if (!isActive) return@launch
                        val (freq, pattern) = signal
                        try {
                            manager.transmit(freq, pattern)
                            sent++
                        } catch (e: Exception) {
                            lastError = e.message ?: e.javaClass.simpleName
                        }
                        _progress.value = "Sent $sent/${codes.size} — $label"
                        // Give the receiver time to process the press before the next one.
                        delay(200)
                    }
                }
                val errTail = lastError?.let { " — error: $it" } ?: ""
                _progress.value = "TV-B-Gone done: $sent transmissions (${codes.size} unique codes)$errTail"
                AppLog.log("IRTools", "TV-B-Gone fired $sent bursts (${codes.size} unique codes)")
            } finally {
                _running.value = false
            }
        }
    }

    /**
     * Collects the power signals to emit, **deduplicated** on the content of the base
     * frame (so that the same code present in a .ir and in the curated list is not
     * emitted twice → no re-toggle). Each entry is already a ready burst.
     */
    private fun collectPowerSignals(): List<Pair<String, Pair<Int, IntArray>>> {
        val out = mutableListOf<Pair<String, Pair<Int, IntArray>>>()
        val seen = HashSet<String>()

        // Uniqueness key: the base frame (frequency + pattern), independent of the repeat count.
        fun addUnique(label: String, base: Pair<Int, IntArray>?, burst: Pair<Int, IntArray>?) {
            if (base == null || burst == null) return
            val key = "${base.first}:${base.second.joinToString(",")}"
            if (seen.add(key)) out.add(label to burst)
        }

        try {
            val assets = getApplication<Application>().assets
            val files = assets.list("infrared")?.filter { it.endsWith(".ir") } ?: emptyList()
            for (file in files) {
                val text = assets.open("infrared/$file").bufferedReader().use { it.readText() }
                IrFile.parse(text)
                    .filter {
                        it.name.contains("power", true) ||
                            it.name.equals("on", true) ||
                            it.name.equals("off", true)
                    }
                    .forEach { b ->
                        addUnique("${file.removeSuffix(".ir")}/${b.name}", b.toSignal(), b.toBurst())
                    }
            }
        } catch (e: Exception) {
            // Do not swallow it: the sweep continues with the curated list, and the problem is logged.
            AppLog.log("IRTools", "Loading .ir files failed: ${e.message}")
        }

        for (pc in PowerCodes.EXTRA) {
            addUnique(pc.brand, pc.toSignal(), pc.toBurst())
        }
        return out
    }

    /** Power codes that can be targeted one by one — precise mode: aim at one device, fire once. */
    val powerBrands: List<PowerCode> = PowerCodes.EXTRA

    /**
     * Emits a SINGLE power code, as a robust "held" burst (high press count), to target
     * one precise device instead of sweeping the whole list. "Power" being a toggle, one
     * press = one toggle: if nothing happens, re-aim and fire again (do not hammer
     * in a loop, which would turn back on a device just turned off).
     */
    fun fireBrand(pc: PowerCode) {
        if (_running.value) return
        val manager = irManager
        if (manager == null || !manager.hasIrEmitter()) {
            _progress.value = IrProtocols.NO_EMITTER_MSG
            return
        }
        _running.value = true
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val burst = pc.toBurst(presses = 6)
                if (burst == null) {
                    _progress.value = "Code ${pc.brand} cannot be encoded"
                    return@launch
                }
                val (freq, pattern) = burst
                try {
                    manager.transmit(freq, pattern)
                    _progress.value = "Sent: ${pc.brand} (power). Nothing? Aim better and tap again."
                    AppLog.log("IRTools", "Fired targeted power code ${pc.brand}")
                } catch (e: Exception) {
                    _progress.value = "Failed to send ${pc.brand}: ${e.message}"
                }
            } finally {
                _running.value = false
            }
        }
    }

    /** Sweeps commands [start]..[end] for (protocol, address). */
    fun bruteForce(protocol: String, address: Int, start: Int, end: Int, delayMs: Long) {
        if (_running.value) return
        if (IrProtocols.encode(protocol, address, start) == null) {
            _progress.value = "Unsupported protocol: $protocol"
            return
        }
        val manager = irManager
        if (manager == null || !manager.hasIrEmitter()) {
            _progress.value = IrProtocols.NO_EMITTER_MSG
            return
        }
        _running.value = true
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                var cmd = start
                var lastError: String? = null
                while (cmd <= end && isActive) {
                    _bruteCommand.value = cmd
                    val step = IrProtocols.encode(protocol, address, cmd)
                    if (step != null) {
                        try {
                            manager.transmit(step.first, step.second)
                        } catch (e: Exception) {
                            lastError = e.message ?: e.javaClass.simpleName
                        }
                    }
                    val hex = cmd.toString(16).uppercase()
                    _progress.value = "Brute $protocol addr=0x${address.toString(16).uppercase()} cmd=0x$hex ($cmd)"
                    delay(delayMs)
                    cmd++
                }
                val errTail = lastError?.let { " — error: $it" } ?: ""
                if (isActive) _progress.value = "Brute-force done (up to 0x${end.toString(16).uppercase()})$errTail"
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        _running.value = false
        if (_progress.value.isNotEmpty()) _progress.value = "${_progress.value} — stopped"
    }
}
