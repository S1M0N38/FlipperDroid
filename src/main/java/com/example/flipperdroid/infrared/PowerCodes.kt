package com.example.flipperdroid.infrared

/** One power-off code for the TV-B-Gone sweep. */
data class PowerCode(
    val brand: String,
    val protocol: String,
    val address: Int,
    val command: Int
) {
    fun toSignal(): Pair<Int, IntArray>? = IrProtocols.encode(protocol, address, command)

    /** Repeated burst (one reliable press) — see IrProtocols.encodePowerBurst. */
    fun toBurst(presses: Int = 0): Pair<Int, IntArray>? =
        IrProtocols.encodePowerBurst(protocol, address, command, presses)
}

/**
 * Database of published "power" codes for TV-B-Gone mode: each code is emitted
 * one after another to turn off any TV / projector / display in range. A code
 * that does not match the device is simply ignored by it, so sweeping
 * broadly is harmless.
 *
 * These codes complement the ones extracted dynamically from the bundled .ir files
 * (see IrToolsViewModel.tvBGone).
 */
object PowerCodes {

    /** Markers of an explicit on/off pair in a button name. */
    private val PAIR_MARKERS = listOf("_on", "_off", " on", " off")

    /**
     * True if the name designates a **toggle** "power" button: contains "power" and is not
     * an explicit on/off pair (Power_on, Power off…). Emitting both would turn back on a
     * device that was just switched off.
     */
    fun isPowerToggleName(name: String): Boolean {
        val n = name.lowercase()
        if (!n.contains("power")) return false
        return PAIR_MARKERS.none { n.contains(it) }
    }

    /**
     * Uniqueness key of a button: (protocol, address, command) for a "parsed" button,
     * capture content for a "raw" button.
     */
    fun buttonKey(b: IrButton): String =
        if (b.type.equals("parsed", ignoreCase = true)) {
            "P|${b.protocol?.uppercase()?.replace("_", "")}|${b.address}|${b.command}"
        } else {
            "R|${b.frequency}|${b.rawData?.joinToString(",")}"
        }

    /** Toggle power buttons, deduplicated on [buttonKey] (order preserved). */
    fun distinctPowerToggles(buttons: List<IrButton>): List<IrButton> =
        buttons.filter { isPowerToggleName(it.name) }.distinctBy { buttonKey(it) }

    val EXTRA: List<PowerCode> = listOf(
        // --- Samsung: 0x07/0x02 = frame E0E040BF (Samsung TV universal) ---
        PowerCode("Samsung", "Samsung32", 0x07, 0x02),
        PowerCode("Samsung-alt", "Samsung32", 0x07, 0x99),   // discrete POWER OFF (some models)
        // --- LG: 0x04/0x08 = frame 20DF10EF (LG TV universal, webOS included) ---
        PowerCode("LG", "NEC", 0x04, 0x08),
        PowerCode("LG-alt", "NECext", 0x20DF, 0x10),         // equivalent 16-bit form
        // --- Sony: SIRC requires >=3 repetitions (handled by the burst) ---
        PowerCode("Sony-12", "SIRC", 0x01, 0x15),
        PowerCode("Sony-15", "SIRC15", 0x01, 0x15),
        // --- Philips: older ones on RC5, recent ones on RC6 ---
        PowerCode("Philips-RC5", "RC5", 0x00, 0x0C),
        PowerCode("Philips-RC6", "RC6", 0x00, 0x0C),
        // --- Panasonic: Kaseikyo device 0x40 / subdevice 0x04 ---
        PowerCode("Panasonic", "Kaseikyo", 0x0440, 0x3D),
        PowerCode("Panasonic-alt", "Kaseikyo", 0x80, 0x3D),
        PowerCode("Toshiba", "NEC", 0x40, 0x12),
        PowerCode("Vizio", "NEC", 0x00, 0x08),
        PowerCode("Vizio-alt", "NEC", 0x04, 0x08),
        PowerCode("TCL", "NEC", 0x04, 0x08),
        PowerCode("Hisense", "NEC", 0x00, 0x08),
        PowerCode("Element", "NEC", 0x00, 0x0A),
        PowerCode("Insignia", "NEC", 0x04, 0x08),
        PowerCode("Sanyo", "NEC", 0x1C, 0x48),
        PowerCode("JVC", "JVC", 0x03, 0x17),
        PowerCode("Pioneer", "PIONEER", 0xA5, 0x1A),
        PowerCode("RCA", "RCA", 0x0F, 0x2A),
        PowerCode("Sharp", "NEC", 0x01, 0x1E),
        PowerCode("Emerson", "NEC", 0x00, 0x0A),
        PowerCode("Sceptre", "NEC", 0x04, 0x08),
        PowerCode("Epson-Proj", "NECext", 0xC1AA, 0x90),
    )
}
