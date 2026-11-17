package com.example.flipperdroid.infrared

/**
 * "Full-state" air conditioner encoders: unlike TV remote
 * controls (one frame = one key), an air conditioner transmits its whole state (power,
 * mode, temperature, fan, swing) in a single long frame with a
 * checksum. Timings and layout taken from IRremoteESP8266 / SB-Projects.
 *
 * ⚠ IMPORTANT — CAPABILITY HONESTY: [gree] has a frame structure covered by
 * unit tests, but no protocol is validated on a real device. The others
 * in this file are reference ports *not verified on a real
 * device*. The frame structure (header, timings, section split, checksum)
 * follows the documentation, but the exact encoding of some fields
 * (especially mode/fan/temperature) may vary by model. Confirm
 * against the target AC before relying on it.
 */
object AcProtocols {

    // Modes
    const val MODE_AUTO = 0
    const val MODE_COOL = 1
    const val MODE_DRY = 2
    const val MODE_FAN = 3
    const val MODE_HEAT = 4

    // Fan
    const val FAN_AUTO = 0
    const val FAN_LOW = 1
    const val FAN_MED = 2
    const val FAN_HIGH = 3

    /** Common settings passed to every encoder. */
    data class AcState(
        val power: Boolean,
        val mode: Int,
        val tempC: Int,
        val fan: Int,
        val swing: Boolean
    ) {
        val temp: Int get() = tempC.coerceIn(16, 30)
    }

    /**
     * Available brands (for the UI).
     * @param supportsFanMode false if the encoder has no reliable "fan only" mode
     *   (Midea: its 0x4 code is shared with Dry; Coolix: falls back to Auto). The UI then hides
     *   the option and the VM refuses the send.
     * @param supportsSwing false if the encoder ignores swing (LG, Coolix, Midea, Samsung).
     * @param approximateChecksum true if the checksum is an approximation.
     */
    enum class Brand(
        val supportsFanMode: Boolean = true,
        val supportsSwing: Boolean = true,
        val approximateChecksum: Boolean = false
    ) {
        GREE,
        COOLIX(supportsFanMode = false, supportsSwing = false),
        LG(supportsSwing = false),
        MIDEA(supportsFanMode = false, supportsSwing = false),
        SAMSUNG(supportsSwing = false, approximateChecksum = true),
        FUJITSU,
        KELVINATOR,
        HAIER,
        DAIKIN,
        MITSUBISHI,
        PANASONIC
    }

    /** Generic entry point: encodes the state for the chosen brand. */
    fun encode(brand: Brand, power: Boolean, mode: Int, tempC: Int, fan: Int, swing: Boolean): Pair<Int, IntArray> {
        val s = AcState(power, mode, tempC, fan, swing)
        return when (brand) {
            Brand.GREE -> gree(power, mode, tempC, fan, swing)
            Brand.COOLIX -> coolix(s)
            Brand.LG -> lg(s)
            Brand.MIDEA -> midea(s)
            Brand.SAMSUNG -> samsung(s)
            Brand.FUJITSU -> fujitsu(s)
            Brand.KELVINATOR -> kelvinator(s)
            Brand.HAIER -> haier(s)
            Brand.DAIKIN -> daikin(s)
            Brand.MITSUBISHI -> mitsubishi(s)
            Brand.PANASONIC -> panasonic(s)
        }
    }

    // =====================================================================
    // Gree YAW1F / YB1F (8 bytes) — frame structure covered by tests (dedicated unit test), NOT validated on device.
    // =====================================================================

    /**
     * Gree YAW1F / YB1F (8 bytes). @return (frequency Hz, µs pattern).
     * @param tempC temperature in °C, clamped to 16..30.
     */
    fun gree(
        power: Boolean,
        mode: Int,
        tempC: Int,
        fan: Int,
        swing: Boolean
    ): Pair<Int, IntArray> {
        // Documented default state (light on, signature 0x5 on byte 3).
        val state = intArrayOf(0x00, 0x09, 0x20, 0x50, 0x00, 0x20, 0x00, 0x50)

        // Byte 0: mode(0-2), power(3), fan(4-5), auto swing(6)
        state[0] = (mode and 0x07) or
            (if (power) 0x08 else 0) or
            ((fan and 0x03) shl 4) or
            (if (swing) 0x40 else 0)

        // Byte 1: temperature (bits 0-3) = tempC - 16
        val t = tempC.coerceIn(16, 30) - 16
        state[1] = (state[1] and 0xF0) or (t and 0x0F)

        // Checksum (high nibble of byte 7)
        var sum = 0x0A
        for (i in 0..3) sum += state[i] and 0x0F
        for (i in 4..6) sum += (state[i] shr 4) and 0x0F
        sum = sum and 0x0F
        state[7] = (state[7] and 0x0F) or (sum shl 4)

        return 38000 to greeFrame(state)
    }

    private fun greeFrame(state: IntArray): IntArray {
        val out = ArrayList<Int>()
        out.add(9000); out.add(4500) // header
        for (i in 0..3) appendBitsLsb(out, state[i], 8) // first block (4 bytes)
        appendBitsLsb(out, 0b010, 3)                    // 3 constant bits
        out.add(620); out.add(19000)                    // block separator
        for (i in 4..7) appendBitsLsb(out, state[i], 8) // second block (4 bytes)
        out.add(620); out.add(19000)                    // footer
        return out.toIntArray()
    }

    private fun appendBitsLsb(out: ArrayList<Int>, value: Int, bits: Int) {
        for (i in 0 until bits) {
            out.add(620)
            out.add(if ((value shr i) and 1 == 1) 1600 else 540)
        }
    }

    // =====================================================================
    // Generic frame helpers (LSB per byte) — ⚠ unverified.
    // =====================================================================

    /** Appends bytes [from, to) LSB-first with the given timings. */
    private fun emitBytesLsb(
        out: ArrayList<Int>, bitMark: Int, zeroSpace: Int, oneSpace: Int,
        bytes: IntArray, from: Int, to: Int
    ) {
        for (bi in from until to) {
            val b = bytes[bi] and 0xFF
            for (i in 0 until 8) {
                out.add(bitMark)
                out.add(if ((b shr i) and 1 == 1) oneSpace else zeroSpace)
            }
        }
    }

    /** Plain sum of bytes [from, to) modulo 256. */
    private fun sumBytes(bytes: IntArray, from: Int, to: Int): Int {
        var s = 0
        for (i in from until to) s += bytes[i] and 0xFF
        return s and 0xFF
    }

    // =====================================================================
    // Coolix (24 bits, doubled frame) — ⚠ reference, to be verified.
    // =====================================================================

    fun coolix(s: AcState): Pair<Int, IntArray> {
        // Coolix temperature table 17..30 °C (4-bit code), IRremoteESP8266.
        val tempMap = intArrayOf(0x0, 0x1, 0x3, 0x2, 0x6, 0x7, 0x5, 0x4, 0xC, 0xD, 0x9, 0x8, 0xA, 0xB)
        val data: Int = if (!s.power) {
            0xB27BE0 // documented OFF
        } else {
            val tIdx = (s.temp.coerceIn(17, 30) - 17)
            // MODE_FAN not encoded (Brand.COOLIX.supportsFanMode = false): falls back to Auto here.
            val modeCode = when (s.mode) {
                MODE_COOL -> 0x0; MODE_DRY -> 0x4; MODE_AUTO -> 0x8; MODE_HEAT -> 0xC; else -> 0x8
            }
            val fanCode = when (s.fan) {
                FAN_AUTO -> 0xB0; FAN_LOW -> 0x90; FAN_MED -> 0x50; FAN_HIGH -> 0x30; else -> 0xB0
            }
            (0xB2 shl 16) or (fanCode shl 4) or (modeCode) or (tempMap[tIdx] shl 4)
        }
        return 38000 to coolixWave(data and 0xFFFFFF)
    }

    /** Coolix waveform: 2 frames, each byte followed by its complement, MSB first. */
    private fun coolixWave(data: Int): IntArray {
        val out = ArrayList<Int>()
        repeat(2) {
            out.add(4692); out.add(4416)
            for (shift in intArrayOf(16, 8, 0)) {
                val b = (data shr shift) and 0xFF
                appendByteMsb(out, b)
                appendByteMsb(out, b.inv() and 0xFF)
            }
            out.add(560); out.add(4416)
        }
        out.add(560)
        return out.toIntArray()
    }

    private fun appendByteMsb(out: ArrayList<Int>, b: Int) {
        for (i in 7 downTo 0) {
            out.add(560)
            out.add(if ((b shr i) and 1 == 1) 1656 else 560)
        }
    }

    // =====================================================================
    // LG (28 bits, one frame, checksum = sum of nibbles) — ⚠ reference.
    // =====================================================================

    fun lg(s: AcState): Pair<Int, IntArray> {
        // 28 bits : [8][8][mode][temp-15][fan][checksum]
        val modeCode = when (s.mode) {
            MODE_COOL -> 0x0; MODE_DRY -> 0x1; MODE_FAN -> 0x2; MODE_AUTO -> 0x3; MODE_HEAT -> 0x4; else -> 0x0
        }
        val fanCode = when (s.fan) { FAN_LOW -> 0x0; FAN_MED -> 0x2; FAN_HIGH -> 0x4; else -> 0x5 }
        val tempCode = (s.temp.coerceIn(15, 30) - 15) and 0x0F
        var value = if (!s.power) 0x88C0051 else {
            (0x8 shl 24) or (0x8 shl 20) or (modeCode shl 16) or (tempCode shl 12) or (fanCode shl 8) or (0 shl 4)
        }
        // Checksum = (sum of nibbles 6..1) & 0xF in nibble 0.
        var sum = 0
        for (sh in intArrayOf(24, 20, 16, 12, 8, 4)) sum += (value shr sh) and 0xF
        value = (value and 0xF.inv()) or (sum and 0xF)

        val out = ArrayList<Int>()
        out.add(8500); out.add(4250)
        for (i in 27 downTo 0) { // MSB first
            out.add(550)
            out.add(if ((value shr i) and 1 == 1) 1600 else 550)
        }
        out.add(550)
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Midea (48 bits = 24 data + 24 complement) — ⚠ reference.
    // =====================================================================

    fun midea(s: AcState): Pair<Int, IntArray> {
        // Bytes: [0xB2][fan/state][mode+temp]
        val tempMap = intArrayOf(0, 1, 3, 2, 6, 7, 5, 4, 12, 13, 9, 8, 10, 11) // 17..30
        val tIdx = (s.temp.coerceIn(17, 30) - 17)
        val fanCode = when (s.fan) { FAN_LOW -> 0x9; FAN_MED -> 0x5; FAN_HIGH -> 0x3; else -> 0xB }
        // MODE_FAN is not encoded: its 0x4 code would be confused with Dry (Brand.MIDEA.supportsFanMode = false).
        val modeCode = when (s.mode) {
            MODE_COOL -> 0x0; MODE_DRY -> 0x4; MODE_AUTO -> 0x8; MODE_HEAT -> 0xC; else -> 0x8
        }
        val b0 = 0xB2
        val b1 = (fanCode shl 4) or 0x0F
        val b2 = if (!s.power) 0xFF else (tempMap[tIdx] shl 4) or (modeCode shr 2)
        val bytes = intArrayOf(b0, b1 and 0xFF, b2 and 0xFF, b0.inv() and 0xFF, b1.inv() and 0xFF, b2.inv() and 0xFF)

        val out = ArrayList<Int>()
        out.add(4480); out.add(4480)
        emitBytesLsb(out, 560, 560, 1680, bytes, 0, 6)
        out.add(560)
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Samsung AC (21 bytes, 3 sections of 7) — ⚠ reference.
    // =====================================================================

    fun samsung(s: AcState): Pair<Int, IntArray> {
        val st = IntArray(21)
        // Documented base values ("cool 24 auto" state).
        val base = intArrayOf(
            0x02, 0x92, 0x0F, 0x00, 0x00, 0x00, 0xF0,
            0x01, 0xE2, 0xFE, 0x71, 0x40, 0x11, 0xF0,
            0x01, 0xE2, 0xFE, 0x71, 0x80, 0x11, 0xC0
        )
        base.copyInto(st)
        st[1] = if (s.power) 0x92 else 0x02 // approximate power
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x1; MODE_DRY -> 0x2; MODE_FAN -> 0x3; MODE_HEAT -> 0x4; else -> 0x1
        }
        val fanCode = when (s.fan) { FAN_LOW -> 0x2; FAN_MED -> 0x4; FAN_HIGH -> 0x5; else -> 0x0 }
        st[12] = (modeCode shl 4) or (fanCode shl 1)
        st[11] = ((s.temp - 16) and 0x0F) shl 4
        // Section checksums (nibble) — approximation.
        st[13] = sectionChecksum(st, 8, 13)
        st[20] = sectionChecksum(st, 15, 20)

        val out = ArrayList<Int>()
        for (sec in 0 until 3) {
            out.add(690); out.add(17844)
            emitBytesLsb(out, 590, 470, 1470, st, sec * 7, sec * 7 + 7)
            out.add(590)
            if (sec < 2) out.add(2670)
        }
        return 38000 to out.toIntArray()
    }

    private fun sectionChecksum(st: IntArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from until to) sum += (st[i] and 0xF) + ((st[i] shr 4) and 0xF)
        return (sum and 0xFF)
    }

    // =====================================================================
    // Fujitsu (16 bytes, checksum = 256 - sum) — ⚠ reference.
    // =====================================================================

    fun fujitsu(s: AcState): Pair<Int, IntArray> {
        val st = intArrayOf(
            0x14, 0x63, 0x00, 0x10, 0x10, 0xFE, 0x09, 0x30,
            0x81, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        st[8] = ((s.temp.coerceIn(16, 30) - 16) shl 4) or (if (s.power) 0x01 else 0x00)
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x1; MODE_DRY -> 0x2; MODE_FAN -> 0x3; MODE_HEAT -> 0x4; else -> 0x1
        }
        val fanCode = when (s.fan) { FAN_HIGH -> 0x1; FAN_MED -> 0x2; FAN_LOW -> 0x3; else -> 0x0 }
        st[9] = (modeCode) or (fanCode shl 4) or (if (s.swing) 0x10 else 0x00)
        // Checksum = (256 - sum of bytes 8..14) & 0xFF in byte 15.
        st[15] = (0x100 - sumBytes(st, 8, 15)) and 0xFF

        val out = ArrayList<Int>()
        out.add(3324); out.add(1574)
        emitBytesLsb(out, 448, 390, 1182, st, 0, 16)
        out.add(448)
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Kelvinator (16 bytes, 2 sections of 8, per-section checksum) — ⚠.
    // =====================================================================

    fun kelvinator(s: AcState): Pair<Int, IntArray> {
        val st = IntArray(16)
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x1; MODE_DRY -> 0x2; MODE_FAN -> 0x3; MODE_HEAT -> 0x4; else -> 0x1
        }
        val fanCode = when (s.fan) { FAN_LOW -> 0x1; FAN_MED -> 0x2; FAN_HIGH -> 0x3; else -> 0x0 }
        st[0] = (modeCode) or (if (s.power) 0x08 else 0x00) or (((s.temp - 16) and 0x0F) shl 4)
        st[4] = fanCode or (if (s.swing) 0x40 else 0x00)
        // Each section (8 bytes): byte 7 = checksum of nibbles 0..6 + constant.
        st[7] = kelvinatorSectionChecksum(st, 0)
        // Section 2 copies the command into 8..14 (often identical).
        for (i in 0 until 7) st[8 + i] = st[i]
        st[15] = kelvinatorSectionChecksum(st, 8)

        val out = ArrayList<Int>()
        out.add(9010); out.add(4505)
        emitBytesLsb(out, 680, 510, 1530, st, 0, 8)
        out.add(680); out.add(19975) // inter-section gap
        emitBytesLsb(out, 680, 510, 1530, st, 8, 16)
        out.add(680)
        return 38000 to out.toIntArray()
    }

    private fun kelvinatorSectionChecksum(st: IntArray, base: Int): Int {
        var sum = 0
        for (i in base until base + 4) sum += (st[i] shr 4) and 0xF // high nibbles 0..3
        for (i in base + 4 until base + 7) sum += st[i] and 0xF      // low nibbles 4..6
        return (sum + 10) and 0xF0
    }

    // =====================================================================
    // Haier (9 bytes, checksum = sum) — ⚠ reference.
    // =====================================================================

    fun haier(s: AcState): Pair<Int, IntArray> {
        val st = IntArray(9)
        st[0] = 0xA5 // signature
        st[1] = ((s.temp.coerceIn(16, 30) - 16) and 0x0F) shl 4
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x1; MODE_DRY -> 0x2; MODE_HEAT -> 0x3; MODE_FAN -> 0x4; else -> 0x1
        }
        val fanCode = when (s.fan) { FAN_LOW -> 0x1; FAN_MED -> 0x2; FAN_HIGH -> 0x3; else -> 0x0 }
        st[4] = (modeCode shl 5) or fanCode
        st[6] = (if (s.power) 0x40 else 0x00) or (if (s.swing) 0x04 else 0x00)
        st[8] = sumBytes(st, 0, 8) // checksum
        val out = ArrayList<Int>()
        out.add(3000); out.add(3000)
        emitBytesLsb(out, 520, 650, 1650, st, 0, 9)
        out.add(520)
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Daikin (3 frames: 8 + 8 + 19 bytes, per-frame checksum) — ⚠.
    // =====================================================================

    fun daikin(s: AcState): Pair<Int, IntArray> {
        // Frames 1 and 2: documented fixed headers. Frame 3: the actual state.
        val f1 = intArrayOf(0x11, 0xDA, 0x27, 0x00, 0xC5, 0x00, 0x00, 0x00)
        val f2 = intArrayOf(0x11, 0xDA, 0x27, 0x00, 0x42, 0x00, 0x00, 0x00)
        val f3 = IntArray(19)
        val head = intArrayOf(0x11, 0xDA, 0x27, 0x00, 0x00)
        head.copyInto(f3)
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x3; MODE_DRY -> 0x2; MODE_FAN -> 0x6; MODE_HEAT -> 0x4; else -> 0x3
        }
        f3[5] = (modeCode shl 4) or (if (s.power) 0x01 else 0x00)
        f3[6] = (s.temp.coerceIn(16, 30)) shl 1
        val fanCode = when (s.fan) { FAN_LOW -> 0x3; FAN_MED -> 0x5; FAN_HIGH -> 0x7; else -> 0xA }
        f3[8] = (fanCode shl 4) or (if (s.swing) 0x0F else 0x00)
        f1[7] = sumBytes(f1, 0, 7)
        f2[7] = sumBytes(f2, 0, 7)
        f3[18] = sumBytes(f3, 0, 18)

        val out = ArrayList<Int>()
        for (frame in listOf(f1, f2, f3)) {
            out.add(3500); out.add(1728)
            emitBytesLsb(out, 460, 420, 1270, frame, 0, frame.size)
            out.add(460); out.add(29000) // inter-frame gap
        }
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Mitsubishi AC (18 bytes, doubled frame, checksum = sum) — ⚠.
    // =====================================================================

    fun mitsubishi(s: AcState): Pair<Int, IntArray> {
        val st = intArrayOf(
            0x23, 0xCB, 0x26, 0x01, 0x00, 0x20, 0x08, 0x06, 0x30,
            0x45, 0x67, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        st[5] = (if (s.power) 0x20 else 0x00)
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x20; MODE_COOL -> 0x18; MODE_DRY -> 0x10; MODE_HEAT -> 0x08; MODE_FAN -> 0x38; else -> 0x18
        }
        st[6] = modeCode
        st[7] = (s.temp.coerceIn(16, 31) - 16) and 0x0F
        val fanCode = when (s.fan) { FAN_LOW -> 0x01; FAN_MED -> 0x02; FAN_HIGH -> 0x03; else -> 0x00 }
        st[8] = fanCode or (if (s.swing) 0x38 else 0x00)
        st[17] = sumBytes(st, 0, 17)

        val out = ArrayList<Int>()
        repeat(2) {
            out.add(3400); out.add(1750)
            emitBytesLsb(out, 450, 420, 1300, st, 0, 18)
            out.add(450); out.add(17100) // gap before repeat
        }
        return 38000 to out.toIntArray()
    }

    // =====================================================================
    // Panasonic AC (27 bytes: 8 + 19, checksum = sum) — ⚠.
    // =====================================================================

    fun panasonic(s: AcState): Pair<Int, IntArray> {
        val st = IntArray(27)
        val head = intArrayOf(0x02, 0x20, 0xE0, 0x04, 0x00, 0x00, 0x00, 0x06)
        head.copyInto(st)
        st[8] = 0x02; st[9] = 0x20; st[10] = 0xE0; st[11] = 0x04
        val modeCode = when (s.mode) {
            MODE_AUTO -> 0x0; MODE_COOL -> 0x3; MODE_DRY -> 0x2; MODE_FAN -> 0x6; MODE_HEAT -> 0x4; else -> 0x3
        }
        st[13] = (modeCode shl 4) or (if (s.power) 0x01 else 0x00)
        st[14] = (s.temp.coerceIn(16, 30)) shl 1
        val fanCode = when (s.fan) { FAN_LOW -> 0x3; FAN_MED -> 0x5; FAN_HIGH -> 0x7; else -> 0xA }
        st[16] = (fanCode shl 4) or (if (s.swing) 0x0F else 0x01)
        st[26] = sumBytes(st, 0, 26)

        val out = ArrayList<Int>()
        // Section 1 (8 bytes)
        out.add(3456); out.add(1728)
        emitBytesLsb(out, 432, 432, 1296, st, 0, 8)
        out.add(432); out.add(10000)
        // Section 2 (19 bytes)
        out.add(3456); out.add(1728)
        emitBytesLsb(out, 432, 432, 1296, st, 8, 27)
        out.add(432)
        return 38000 to out.toIntArray()
    }
}
