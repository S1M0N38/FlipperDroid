package com.example.flipperdroid.infrared

/**
 * Infrared protocol encoders: turn (protocol, address, command) into a pair
 * (carrier frequency, pattern of durations in µs) ready for
 * ConsumerIrManager.transmit(). Covers the most common protocols found in
 * Flipper Zero .ir files.
 */
object IrProtocols {

    /** Message shown when no IR emitter is available: nothing is sent. */
    const val NO_EMITTER_MSG = "No IR emitter on this device — nothing sent"

    /**
     * Parse an address entered in hexadecimal, with an optional "0x"/"0X" prefix.
     * @return the value, or null if the input is not valid hexadecimal.
     */
    fun parseHexAddress(text: String): Int? {
        val digits = text.trim().removePrefix("0x").removePrefix("0X")
        if (!HEX_DIGITS.matches(digits)) return null
        return digits.toIntOrNull(16)
    }

    private val HEX_DIGITS = Regex("[0-9A-Fa-f]{1,8}")

    /** @return (frequency Hz, µs pattern) or null if the protocol is unknown. */
    fun encode(protocol: String, address: Int, command: Int): Pair<Int, IntArray>? {
        return when (protocol.uppercase().replace("_", "")) {
            "NEC" -> 38000 to nec(address and 0xFF, command and 0xFF)
            "NECEXT" -> 38000 to necExt(address and 0xFFFF, command and 0xFFFF)
            "SAMSUNG32", "SAMSUNG" -> 38000 to samsung32(address and 0xFF, command and 0xFF)
            "SONY", "SIRC" -> 40000 to sirc(command and 0x7F, address and 0x1F, 5)
            "SIRC15" -> 40000 to sirc(command and 0x7F, address and 0xFF, 8)
            // SIRC20: 7 command bits + 5 device bits + 8 extended bits.
            // The 8 extended bits are taken from the high bits of the address.
            "SIRC20" -> 40000 to sirc20(command and 0x7F, address and 0x1F, (address shr 5) and 0xFF)
            "RC5" -> 36000 to rc5(address and 0x1F, command and 0x3F, extended = false)
            // RC5X: 7-bit extended command (0..127) via the 2nd start bit.
            "RC5X" -> 36000 to rc5(address and 0x1F, command and 0x7F, extended = true)
            // RC6 mode 0 (Manchester, inverted convention relative to RC5, double-width toggle bit).
            // LIMITATION: "RC6X" is not a real RC6X. It is a plain alias of RC6 mode 0
            // (the extended command and the other modes are not encoded).
            "RC6", "RC6X" -> 36000 to rc6(address and 0xFF, command and 0xFF)
            "JVC" -> 38000 to jvc(address and 0xFF, command and 0xFF)
            "APPLE", "APPLETV" -> 38000 to apple(command and 0xFF)
            "KASEIKYO", "PANASONIC" -> 37000 to kaseikyo(address, command)
            // Pioneer uses the NEC format (carrier ~40 kHz).
            "PIONEER" -> 40000 to nec(address and 0xFF, command and 0xFF)
            // RCA: 4000/4000 header, 4 address bits + 8 command bits then their
            // complement, MSB first, 56 kHz carrier.
            "RCA" -> 56000 to rca(address and 0x0F, command and 0xFF)
            "COOLIX", "AC" -> 38000 to coolix(command and 0xFFFFFF)
            // Denon / Sharp: pulse-distance without a header, 15 LSB-first bits.
            "DENON" -> 38000 to denon(address and 0x1F, command and 0xFF)
            "SHARP" -> 38000 to sharp(address and 0x1F, command and 0xFF)
            // Mitsubishi (TV): 16 MSB-first bits, no header, 33 kHz carrier.
            "MITSUBISHI" -> 33000 to mitsubishi(address and 0xFF, command and 0xFF)
            // Sanyo LC7461: NEC header, 42 bits (13-bit address + inverse, cmd + inverse).
            "SANYO", "SANYOLC7461" -> 38000 to sanyo(address and 0x1FFF, command and 0xFF)
            // Best-effort (SB-Projects timings, not verified on hardware):
            "NOKIA", "NRC17" -> 38000 to nokiaNrc17(address and 0xFF, command and 0xFF)
            "RCMM" -> 36000 to rcmm(address and 0xFFFF, command and 0xFF)
            "PANASONICOLD" -> 38000 to panasonicOld(address and 0x1F, command and 0xFF)
            else -> null
        }
    }

    /**
     * Builds a reliable **burst** representing a single press: the full frame
     * repeated [presses] times inside ONE single transmit(), with the
     * protocol-specific gap.
     *
     * Why this is essential for TV-B-Gone to actually work:
     * an **isolated** IR frame is almost always filtered out as noise by
     * TVs (LG, Samsung…), and the **Sony SIRC protocol requires at least 3
     * repetitions** — a single frame never does anything. A real remote
     * repeats the frame for as long as the button is held; we reproduce that
     * behaviour. Emitting all the repetitions **in one go** (a single logical
     * press) also avoids the double-toggle (on→off) of a toggle "power"
     * command, unlike separate transmit() calls spaced out in time.
     *
     * @param presses number of frames (0 = protocol-specific default).
     * @return (frequency Hz, µs pattern) or null if the protocol is unknown.
     */
    fun encodePowerBurst(
        protocol: String,
        address: Int,
        command: Int,
        presses: Int = 0
    ): Pair<Int, IntArray>? {
        val base = encode(protocol, address, command) ?: return null
        val (freq, frame) = base
        val p = protocol.uppercase().replace("_", "")
        // Repeat element + inter-frame gap + frame count.
        val (repeatFrame, gapUs, defaultCount) = when {
            // Sony: 45 ms period, minimum of 3 full frames (protocol requirement).
            p.startsWith("SIRC") || p == "SONY" -> Triple(frame, 24000, 3)
            // NEC family: "button held" = NEC repeat code (9000/2250/560).
            p == "NEC" || p == "NECEXT" || p == "APPLE" || p == "APPLETV" ||
                p == "PIONEER" || p.startsWith("SANYO") ->
                Triple(NEC_REPEAT, 40000, 3)
            // Samsung, RC5/RC6, Kaseikyo, etc.: the full frame is repeated.
            else -> Triple(frame, 40000, 3)
        }
        val count = if (presses > 0) presses else defaultCount
        return freq to repeatFrames(frame, repeatFrame, count, gapUs)
    }

    /**
     * Chains [count] frames: [first], then [repeatFrame] repeated (count - 1) times, with
     * a [gapUs] gap between two frames. The pattern is assumed to start with a mark.
     *
     * Elements alternate mark (even index) / space (odd index). If the current pattern
     * has an even length, it ends with a space: the gap is then **added to that
     * space** (otherwise it would become a mark and invert all the repetitions). Otherwise
     * (pattern ending on a mark) the gap is appended as a new element.
     */
    fun repeatFrames(first: IntArray, repeatFrame: IntArray, count: Int, gapUs: Int): IntArray {
        val out = ArrayList<Int>(first.size + maxOf(0, count - 1) * (repeatFrame.size + 1))
        for (v in first) out.add(v)
        for (k in 1 until count) {
            appendGap(out, gapUs)
            for (v in repeatFrame) out.add(v)
        }
        return out.toIntArray()
    }

    /** Appends a gap to the pattern while preserving mark/space alternation (see [repeatFrames]). */
    private fun appendGap(out: ArrayList<Int>, gapUs: Int) {
        if (out.isEmpty()) return
        if (out.size % 2 == 0) {
            // The pattern ends with a space: that space is extended.
            out[out.size - 1] = out[out.size - 1] + gapUs
        } else {
            out.add(gapUs)
        }
    }

    /** NEC repeat code: 9000/2250 header then a final 560 mark (button held). */
    private val NEC_REPEAT = intArrayOf(9000, 2250, 560)

    // --- Pulse-distance (NEC / Samsung) ---

    private fun nec(addr: Int, cmd: Int): IntArray {
        val bytes = intArrayOf(addr, addr.inv() and 0xFF, cmd, cmd.inv() and 0xFF)
        return pulseDistance(9000, 4500, 560, 560, 1690, 560, bytes)
    }

    private fun necExt(addr: Int, cmd: Int): IntArray {
        // 16-bit address (no inversion) + 8-bit command + its inverse.
        // This is the Flipper "NECext" (used by Roku, Epson projectors, etc.).
        val bytes = intArrayOf(addr and 0xFF, (addr shr 8) and 0xFF, cmd and 0xFF, cmd.inv() and 0xFF)
        return pulseDistance(9000, 4500, 560, 560, 1690, 560, bytes)
    }

    /** Apple Remote: address 0x77E1 + command + fixed device id 0x70 (based on NEC). */
    private fun apple(cmd: Int): IntArray {
        val bytes = intArrayOf(0xE1, 0x77, cmd and 0xFF, 0x70)
        return pulseDistance(9000, 4500, 560, 560, 1690, 560, bytes)
    }

    private fun samsung32(addr: Int, cmd: Int): IntArray {
        val bytes = intArrayOf(addr, addr, cmd, cmd.inv() and 0xFF)
        return pulseDistance(4500, 4500, 560, 560, 1690, 560, bytes)
    }

    /**
     * Builds a pulse-distance pattern: header, then each byte (LSB first)
     * encoded as mark + short/long space, ending with a trailing mark.
     */
    private fun pulseDistance(
        headerMark: Int, headerSpace: Int,
        bitMark: Int, zeroSpace: Int, oneSpace: Int, trailerMark: Int,
        bytes: IntArray
    ): IntArray {
        val out = ArrayList<Int>()
        out.add(headerMark); out.add(headerSpace)
        for (b in bytes) {
            for (i in 0 until 8) {
                val bit = (b shr i) and 1
                out.add(bitMark)
                out.add(if (bit == 1) oneSpace else zeroSpace)
            }
        }
        out.add(trailerMark)
        return out.toIntArray()
    }

    private fun jvc(addr: Int, cmd: Int): IntArray {
        // 16 bits (8 address + 8 command), LSB first, 8400/4200 header
        val bytes = intArrayOf(addr and 0xFF, cmd and 0xFF)
        return pulseDistance(8400, 4200, 525, 525, 1575, 525, bytes)
    }

    /** Kaseikyo (Panasonic variant, vendor 0x2002). address = device | (subdevice<<8). */
    private fun kaseikyo(address: Int, command: Int): IntArray {
        val device = address and 0xFF
        val subdevice = (address shr 8) and 0xFF
        val cmd = command and 0xFF
        val checksum = device xor subdevice xor cmd
        // vendor 0x2002 LSB first (0x02, 0x20), then device, subdevice, command, checksum
        val bytes = intArrayOf(0x02, 0x20, device, subdevice, cmd, checksum)
        return pulseDistance(3456, 1728, 432, 432, 1296, 432, bytes)
    }

    // --- Coolix (air conditioners), 24 bits, each byte followed by its complement (MSB first) ---

    private fun coolix(data: Int): IntArray {
        val out = ArrayList<Int>()
        repeat(2) { // Coolix repeats the frame
            out.add(4692); out.add(4416) // header
            for (shift in intArrayOf(16, 8, 0)) {
                val b = (data shr shift) and 0xFF
                appendByteMsb(out, b)
                appendByteMsb(out, b.inv() and 0xFF)
            }
            out.add(560); out.add(4416) // separator
        }
        out.add(560) // trailing mark
        return out.toIntArray()
    }

    private fun appendByteMsb(out: ArrayList<Int>, b: Int) {
        for (i in 7 downTo 0) {
            out.add(560)
            out.add(if ((b shr i) and 1 == 1) 1656 else 560)
        }
    }

    // --- Sony SIRC (mark variable) ---

    private fun sirc(cmd: Int, addr: Int, addrBits: Int): IntArray {
        val out = ArrayList<Int>()
        out.add(2400); out.add(600) // header
        for (i in 0 until 7) { // 7 command bits, LSB first
            out.add(if ((cmd shr i) and 1 == 1) 1200 else 600)
            out.add(600)
        }
        for (i in 0 until addrBits) { // address bits, LSB first
            out.add(if ((addr shr i) and 1 == 1) 1200 else 600)
            out.add(600)
        }
        return out.toIntArray()
    }

    /** Sony SIRC 20 bits: 7 command bits + 5 device bits + 8 extended bits. */
    private fun sirc20(cmd: Int, addr: Int, ext: Int): IntArray {
        val out = ArrayList<Int>()
        out.add(2400); out.add(600)
        for (i in 0 until 7) { out.add(if ((cmd shr i) and 1 == 1) 1200 else 600); out.add(600) }
        for (i in 0 until 5) { out.add(if ((addr shr i) and 1 == 1) 1200 else 600); out.add(600) }
        for (i in 0 until 8) { out.add(if ((ext shr i) and 1 == 1) 1200 else 600); out.add(600) }
        return out.toIntArray()
    }

    // --- RCA (4000/4000 header, MSB first, data + complement) ---

    private fun rca(addr: Int, cmd: Int): IntArray {
        val out = ArrayList<Int>()
        out.add(4000); out.add(4000)
        appendRca(out, addr and 0x0F, 4)
        appendRca(out, cmd and 0xFF, 8)
        appendRca(out, addr.inv() and 0x0F, 4)
        appendRca(out, cmd.inv() and 0xFF, 8)
        out.add(500) // trailing mark
        return out.toIntArray()
    }

    private fun appendRca(out: ArrayList<Int>, value: Int, bits: Int) {
        for (i in bits - 1 downTo 0) { // MSB first
            out.add(500)
            out.add(if ((value shr i) and 1 == 1) 2000 else 1000)
        }
    }

    // --- RC5 (Manchester) ---

    private fun rc5(addr: Int, cmd: Int, extended: Boolean): IntArray {
        val bits = ArrayList<Int>()
        bits.add(1)                        // S1 (start, always 1)
        // S2: classic RC5 = 1 ; RC5X = inverse of the 7th command bit (allows 0..127).
        bits.add(if (extended) (cmd shr 6).inv() and 1 else 1)
        bits.add(0)                        // toggle (fixed on the transmitter side)
        for (i in 4 downTo 0) bits.add((addr shr i) and 1) // 5 address bits (MSB first)
        for (i in 5 downTo 0) bits.add((cmd shr i) and 1)  // 6 low command bits (MSB first)

        val half = 889
        val levels = ArrayList<Pair<Boolean, Int>>()
        for (b in bits) {
            if (b == 1) { levels.add(false to half); levels.add(true to half) }  // 1 = space->mark
            else { levels.add(true to half); levels.add(false to half) }         // 0 = mark->space
        }
        return levelsToPattern(levels)
    }

    /** Merges consecutive identical levels and produces an on/off pattern starting with ON. */
    private fun levelsToPattern(levels: List<Pair<Boolean, Int>>): IntArray {
        val merged = ArrayList<Pair<Boolean, Int>>()
        for (l in levels) {
            if (merged.isNotEmpty() && merged.last().first == l.first) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(l.first to (last.second + l.second))
            } else merged.add(l)
        }
        // Drops a possible leading OFF level (nothing is emitted before the first mark)
        val start = if (merged.isNotEmpty() && !merged[0].first) 1 else 0
        val out = ArrayList<Int>()
        for (i in start until merged.size) out.add(merged[i].second)
        return out.toIntArray()
    }

    // --- RC6 mode 0 (Manchester, inverted convention relative to RC5, double-width toggle) ---

    private fun rc6(addr: Int, cmd: Int): IntArray {
        // Symbols = (bit value, double width?). In RC6: 1 start bit (=1),
        // 3 mode bits (000), 1 toggle bit (double width), 8 address bits, 8
        // command bits (MSB first).
        val symbols = ArrayList<Pair<Int, Boolean>>()
        symbols.add(1 to false)                                   // start
        repeat(3) { symbols.add(0 to false) }                     // mode 0
        symbols.add(0 to true)                                    // toggle (double)
        for (i in 7 downTo 0) symbols.add(((addr shr i) and 1) to false)
        for (i in 7 downTo 0) symbols.add(((cmd shr i) and 1) to false)

        val t = 444
        val levels = ArrayList<Pair<Boolean, Int>>()
        levels.add(true to (6 * t))   // header: 6T mark
        levels.add(false to (2 * t))  // header: 2T space
        for ((bit, dbl) in symbols) {
            val half = if (dbl) 2 * t else t
            // RC6: '1' = mark→space, '0' = space→mark (inverse of RC5).
            if (bit == 1) { levels.add(true to half); levels.add(false to half) }
            else { levels.add(false to half); levels.add(true to half) }
        }
        return levelsToPattern(levels)
    }

    // --- Generic bit-level pulse-distance (protocols not byte-aligned) ---

    private fun pdBits(
        headerMark: Int, headerSpace: Int,
        bitMark: Int, zeroSpace: Int, oneSpace: Int, trailerMark: Int,
        value: Long, nbits: Int, lsbFirst: Boolean
    ): IntArray {
        val out = ArrayList<Int>()
        if (headerMark > 0) { out.add(headerMark); out.add(headerSpace) }
        for (i in 0 until nbits) {
            val bitIndex = if (lsbFirst) i else (nbits - 1 - i)
            val bit = ((value shr bitIndex) and 1L).toInt()
            out.add(bitMark)
            out.add(if (bit == 1) oneSpace else zeroSpace)
        }
        out.add(trailerMark)
        return out.toIntArray()
    }

    /** Sharp: 15 LSB-first bits = address(5) + command(8) + expansion(1) + check(0), no header. */
    private fun sharp(addr: Int, cmd: Int): IntArray {
        val value = (addr and 0x1F).toLong() or
            ((cmd and 0xFF).toLong() shl 5) or
            (1L shl 13)   // expansion = 1, check = 0
        return pdBits(0, 0, 320, 680, 1680, 320, value, 15, lsbFirst = true)
    }

    /** Denon: close to Sharp (15 LSB-first bits), own timings. */
    private fun denon(addr: Int, cmd: Int): IntArray {
        val value = (addr and 0x1F).toLong() or ((cmd and 0xFF).toLong() shl 5)
        return pdBits(0, 0, 264, 782, 1794, 264, value, 15, lsbFirst = true)
    }

    /** Mitsubishi (TV): 16 MSB-first bits, no header, 33 kHz carrier. */
    private fun mitsubishi(addr: Int, cmd: Int): IntArray {
        val value = (((addr and 0xFF) shl 8) or (cmd and 0xFF)).toLong()
        return pdBits(0, 0, 300, 500, 1500, 300, value, 16, lsbFirst = false)
    }

    /** Sanyo LC7461: NEC 9000/4500 header, 42 LSB-first bits (adr13 + ~adr13 + cmd8 + ~cmd8). */
    private fun sanyo(addr: Int, cmd: Int): IntArray {
        val a = (addr and 0x1FFF).toLong()
        val c = (cmd and 0xFF).toLong()
        val value = a or
            ((a.inv() and 0x1FFFL) shl 13) or
            (c shl 26) or
            ((c.inv() and 0xFFL) shl 34)
        return pdBits(9000, 4500, 560, 560, 1690, 560, value, 42, lsbFirst = true)
    }

    /** RCMM (Philips): 2 bits per symbol via 4 space lengths. 24 bits, MSB first. */
    private fun rcmm(hi16: Int, cmd: Int): IntArray {
        val value = ((hi16 and 0xFFFF) shl 8) or (cmd and 0xFF)
        val spaces = intArrayOf(277, 444, 611, 778) // 00, 01, 10, 11
        val out = ArrayList<Int>()
        out.add(416); out.add(277) // header
        var i = 22
        while (i >= 0) {
            out.add(166); out.add(spaces[(value shr i) and 0x3])
            i -= 2
        }
        out.add(166) // trailing mark
        return out.toIntArray()
    }

    /**
     * Nokia NRC17 — best-effort (SB-Projects timings, NOT verified on hardware).
     * 500/2500 pre-pulse then 16 LSB-first bits (command + address).
     */
    private fun nokiaNrc17(addr: Int, cmd: Int): IntArray {
        val value = (cmd and 0xFF).toLong() or ((addr and 0xFF).toLong() shl 8)
        return pdBits(500, 2500, 500, 500, 1000, 500, value, 16, lsbFirst = true)
    }

    /**
     * Panasonic "old" (22 bits) — best-effort (SB-Projects timings, NOT verified).
     */
    private fun panasonicOld(addr: Int, cmd: Int): IntArray {
        val value = (addr and 0x1F).toLong() or ((cmd and 0xFF).toLong() shl 5)
        return pdBits(3502, 3510, 872, 872, 2612, 872, value, 22, lsbFirst = true)
    }
}
