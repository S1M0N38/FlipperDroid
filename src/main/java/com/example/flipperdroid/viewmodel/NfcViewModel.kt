package com.example.flipperdroid.viewmodel

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.flipperdroid.flipper.FlipperNfc
import com.example.flipperdroid.nfc.ApduCapture
import com.example.flipperdroid.nfc.EmulationStore
import com.example.flipperdroid.nfc.MifareClassicUtils
import com.example.flipperdroid.nfc.MifareUltralightUtils
import com.example.flipperdroid.nfc.NfcInfo
import com.example.flipperdroid.nfc.NfcVUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NfcViewModel(app: Application) : AndroidViewModel(app) {

    // Current tag state
    private val _currentTagUid = MutableStateFlow<String?>(null)
    val currentTagUid: StateFlow<String?> = _currentTagUid.asStateFlow()

    private val _currentTagType = MutableStateFlow<String?>(null)
    val currentTagType: StateFlow<String?> = _currentTagType.asStateFlow()

    private val _currentTagDump = MutableStateFlow<List<String>>(emptyList())
    val currentTagDump: StateFlow<List<String>> = _currentTagDump.asStateFlow()

    // Scan history
    private val _scanHistory = MutableStateFlow<List<NfcScanResult>>(emptyList())
    val scanHistory: StateFlow<List<NfcScanResult>> = _scanHistory.asStateFlow()

    // Logs/feedback
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // NDEF content read (text/URI)
    private val _ndefContent = MutableStateFlow<String?>(null)
    val ndefContent: StateFlow<String?> = _ndefContent.asStateFlow()

    // Keys found by the dictionary attack
    private val _foundKeys = MutableStateFlow<List<String>>(emptyList())
    val foundKeys: StateFlow<List<String>> = _foundKeys.asStateFlow()

    private val _isAttacking = MutableStateFlow(false)
    val isAttacking: StateFlow<Boolean> = _isAttacking.asStateFlow()

    // Full clone: when "armed", the next card presented receives the dump.
    private val _cloneArmed = MutableStateFlow(false)
    val cloneArmed: StateFlow<Boolean> = _cloneArmed.asStateFlow()
    @Volatile private var pendingCloneDump: List<String>? = null
    @Volatile private var cloneWriteTrailers = false
    @Volatile private var cloneWriteSectorZero = true

    // HCE emulation capture: when "armed", the next ISO-DEP card is captured.
    private val _emuCaptureArmed = MutableStateFlow(false)
    val emuCaptureArmed: StateFlow<Boolean> = _emuCaptureArmed.asStateFlow()

    // Detailed analysis of the last tag (UID/ATQA/SAK/ATS/NDEF/amiibo…).
    private val _tagInfo = MutableStateFlow<String?>(null)
    val tagInfo: StateFlow<String?> = _tagInfo.asStateFlow()

    // Last scanned tag (needed for the attack, the clone, NDEF)
    @Volatile private var lastTag: Tag? = null

    // Metadata of the last dump, for the Flipper .nfc export.
    @Volatile private var dumpDeviceType: String? = null  // "Mifare Classic", "NTAG/Ultralight" or null
    @Volatile private var dumpAtqa: String? = null
    @Volatile private var dumpSak: String? = null

    // Main actions.
    // NFC reads (connect/authenticate/readBlock) are blocking: they run on an IO
    // thread so the main thread is not blocked (ANR risk).
    fun onTagScanned(tag: Tag) {
        lastTag = tag
        // Clone armed: the card presented is the TARGET. Disarm BEFORE writing (atomic swap)
        // so that a second event does not restart the write.
        if (_cloneArmed.compareAndSet(true, false)) {
            val dump = pendingCloneDump
            val trailers = cloneWriteTrailers
            val sectorZero = cloneWriteSectorZero
            pendingCloneDump = null
            performClone(tag, dump, trailers, sectorZero)
            return
        }
        // If an emulation capture is armed, record the card's APDU dialogue.
        if (_emuCaptureArmed.compareAndSet(true, false)) { performEmulationCapture(tag); return }
        _foundKeys.value = emptyList()
        _ndefContent.value = null
        // UID read (fast, does not require a connection)
        val id = tag.id?.let { MifareClassicUtils.bytesToHex(it) } ?: "-"
        _currentTagUid.value = id
        val techs = tag.techList?.map { it.substringAfterLast('.') } ?: emptyList()
        val typeLabel = describeTag(techs)
        _currentTagType.value = typeLabel
        _tagInfo.value = try { NfcInfo.describe(tag) } catch (e: Exception) { null }
        // Actual ATQA / SAK (for the .nfc export)
        val nfcA = NfcA.get(tag)
        dumpAtqa = nfcA?.let { MifareClassicUtils.bytesToHex(it.atqa) }
        dumpSak = nfcA?.let { "%02X".format(it.sak.toInt() and 0xFF) }

        viewModelScope.launch(Dispatchers.IO) {
            // NDEF then MifareClassic, sequentially: two NFC technologies cannot be
            // connected to the same tag at the same time. Running them in
            // parallel caused intermittent read failures.
            readNdef(tag)

            val mfc = MifareClassic.get(tag)
            val dump = mutableListOf<String>()
            var deviceType: String? = null
            if (mfc != null) {
                deviceType = "Mifare Classic"
                val interrupted = readClassicDefaultKey(mfc, dump)
                if (interrupted) addLog("WARNING: read interrupted, INCOMPLETE dump.")
                val unread = dump.count { it == MifareClassicUtils.NO_DATA }
                if (unread > 0) addLog("$unread unread block(s) (protected sectors or errors).")
            } else {
                // Not Mifare Classic: try Ultralight / NTAG, then ISO 15693 (NfcV).
                val pages = MifareUltralightUtils.readDump(tag)
                val vblocks = if (pages == null) NfcVUtils.readDump(tag) else null
                if (pages != null) {
                    dump.addAll(pages)
                    deviceType = "NTAG/Ultralight"
                    addLog("Ultralight/NTAG read: ${pages.size} pages")
                } else if (vblocks != null && vblocks.isNotEmpty()) {
                    dump.addAll(vblocks)
                    addLog("ISO 15693 (NfcV) read: ${vblocks.size} blocks")
                } else if (techs.any { it.equals("MifareClassic", true) }) {
                    addLog("Mifare Classic detected, but not supported by this device's NFC chip.")
                } else {
                    addLog("Tag read (UID=$id) — type: $typeLabel. Memory read not applicable.")
                }
            }
            dumpDeviceType = deviceType

            // History + dump for ANY scanned tag, not just Mifare Classic.
            _currentTagDump.value = dump.toList()
            // Amiibo (NTAG215, ~135 pages): the amiibo ID (8 bytes) is at pages 21-22.
            if (dump.size in 130..145) {
                val amiiboId = ((dump.getOrNull(21) ?: "") + (dump.getOrNull(22) ?: "")).replace(" ", "")
                if (amiiboId.length == 16 && amiiboId.any { it != '0' } && amiiboId.none { it == '-' }) {
                    _tagInfo.update { (it ?: "") + "\nAmiibo ID: $amiiboId" }
                    addLog("Amiibo detected (ID $amiiboId)")
                }
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _scanHistory.update { it + NfcScanResult(timestamp, id, typeLabel, dump.toList()) }
            addLog("Scanned tag UID=$id ($typeLabel)" + if (dump.isNotEmpty()) ", ${dump.size} blocks read" else "")
        }
    }

    /**
     * Reads all Mifare Classic sectors with the default key. Each sector is read in
     * its own try: on error it is filled with [MifareClassicUtils.NO_DATA], which
     * keeps the indices aligned. If the tag disappears, the read stops and the rest is filled.
     * @return true if the read was interrupted (tag lost or connection impossible).
     */
    private fun readClassicDefaultKey(mfc: MifareClassic, dump: MutableList<String>): Boolean {
        val key = MifareClassicUtils.hexToBytes(MifareClassicUtils.DEFAULT_KEY)
        try {
            mfc.connect()
            for (sector in 0 until mfc.sectorCount) {
                val start = dump.size
                val blockCount = mfc.getBlockCountInSector(sector)
                try {
                    if (key != null && mfc.authenticateSectorWithKeyA(sector, key)) {
                        readSectorBlocks(mfc, sector, dump)
                    } else {
                        // Sector not accessible with the default key
                        repeat(blockCount) { dump.add(MifareClassicUtils.NO_DATA) }
                    }
                } catch (e: TagLostException) {
                    addLog("Tag lost while reading sector $sector.")
                    dump.padTo(mfc.blockCount)
                    return true
                } catch (e: Exception) {
                    dump.padTo(start + blockCount)
                }
            }
            return false
        } catch (e: TagLostException) {
            dump.padTo(mfc.blockCount)
            return true
        } catch (e: Exception) {
            addLog("Tag read error: ${e.message}")
            return true
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    /**
     * Adds the blocks of an already authenticated sector. An unreadable block becomes NO_DATA;
     * [TagLostException] is rethrown to stop the read.
     */
    private fun readSectorBlocks(mfc: MifareClassic, sector: Int, dump: MutableList<String>) {
        val first = mfc.sectorToBlock(sector)
        for (i in 0 until mfc.getBlockCountInSector(sector)) {
            val bb = try { mfc.readBlock(first + i) } catch (e: TagLostException) { throw e } catch (_: Exception) { null }
            dump.add(bb?.let { MifareClassicUtils.bytesToHex(it) } ?: MifareClassicUtils.NO_DATA)
        }
    }

    /** Pads [this] with NO_DATA up to [size] entries. */
    private fun MutableList<String>.padTo(size: Int) {
        while (this.size < size) add(MifareClassicUtils.NO_DATA)
    }

    /** Human-readable tag type label from its technology list. */
    private fun describeTag(techs: List<String>): String = when {
        techs.any { it.equals("MifareClassic", true) } -> "Mifare Classic"
        techs.any { it.equals("MifareUltralight", true) } -> "Mifare Ultralight"
        techs.any { it.equals("IsoDep", true) } -> "ISO-DEP (EMV / DESFire)"
        techs.any { it.equals("NfcA", true) } -> "NFC-A (ISO 14443-3A)"
        techs.any { it.equals("NfcB", true) } -> "NFC-B (ISO 14443-3B)"
        techs.any { it.equals("NfcF", true) } -> "NFC-F (FeliCa)"
        techs.any { it.equals("NfcV", true) } -> "NFC-V (ISO 15693)"
        techs.isNotEmpty() -> techs.joinToString(", ")
        else -> "Unknown"
    }

    /**
     * Reads an NDEF message (text/URI) if the tag supports it.
     * Called from the IO coroutine of [onTagScanned]: blocking and not launched,
     * so Ndef and MifareClassic are not connected to the same tag simultaneously.
     */
    private fun readNdef(tag: Tag) {
        val ndef = Ndef.get(tag) ?: return
        try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
            if (message != null) {
                val text = message.records.joinToString("\n") { record ->
                    when {
                        record.toUri() != null -> "URI: ${record.toUri()}"
                        record.payload.isNotEmpty() && record.type.contentEquals(NdefRecord.RTD_TEXT) ->
                            "Text: ${decodeNdefText(record.payload)}"
                        record.payload.isNotEmpty() -> String(record.payload, Charsets.UTF_8)
                        else -> "(empty record)"
                    }
                }
                _ndefContent.value = text
                addLog("NDEF read (${message.records.size} record(s))")
            }
        } catch (e: Exception) {
            // Non-NDEF tag / read refused: silent
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    /**
     * Decodes the payload of an NDEF Text record (RTD "T").
     * Status byte: bit 7 = UTF-16 (otherwise UTF-8), bits 5..0 = language code length.
     */
    private fun decodeNdefText(payload: ByteArray): String {
        val status = payload[0].toInt() and 0xFF
        val charset = if ((status and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
        val langLen = status and 0x3F
        val start = minOf(langLen + 1, payload.size)
        return String(payload, start, payload.size - start, charset)
    }

    /**
     * Writes an NDEF message (text or auto-detected URI) to the last scanned tag.
     * Formats the tag if necessary (NdefFormatable).
     */
    fun writeNdef(content: String) {
        val tag = lastTag ?: return addLog("No scanned tag")
        if (content.isBlank()) return addLog("Empty NDEF content")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val record = if (content.startsWith("http://", true) || content.startsWith("https://", true)) {
                    NdefRecord.createUri(content)
                } else {
                    NdefRecord.createTextRecord("en", content)
                }
                val message = NdefMessage(arrayOf(record))
                val ndef = Ndef.get(tag)
                if (ndef != null) {
                    try {
                        ndef.connect()
                        when {
                            !ndef.isWritable -> addLog("NDEF tag not writable")
                            ndef.maxSize < message.toByteArray().size -> addLog("Message too large (${message.toByteArray().size} > ${ndef.maxSize})")
                            else -> {
                                ndef.writeNdefMessage(message)
                                _ndefContent.value = "Written: $content"
                                addLog("NDEF written: $content")
                            }
                        }
                    } finally {
                        try { ndef.close() } catch (_: Exception) {}
                    }
                } else {
                    val formatable = NdefFormatable.get(tag)
                    if (formatable != null) {
                        try {
                            formatable.connect()
                            formatable.format(message)
                            _ndefContent.value = "Written: $content"
                            addLog("Tag formatted + NDEF written: $content")
                        } finally {
                            try { formatable.close() } catch (_: Exception) {}
                        }
                    } else {
                        addLog("Tag not NDEF compatible")
                    }
                }
            } catch (e: Exception) {
                addLog("NDEF write error: ${e.message}")
            }
        }
    }

    /**
     * Dictionary attack: tests the known keys (MifareClassicUtils.COMMON_KEYS)
     * on each sector and reads the ones where a key is found.
     */
    fun runDictionaryAttack() {
        val tag = lastTag ?: return addLog("No tag in memory")
        val mfc = MifareClassic.get(tag) ?: return addLog("Tag is not Mifare Classic")
        _isAttacking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                attackAndRead(mfc)
            } finally {
                _isAttacking.value = false
            }
        }
    }

    /** Body of the dictionary attack (run on IO). */
    private fun attackAndRead(mfc: MifareClassic) {
        val found = mutableListOf<String>()
        val dump = mutableListOf<String>()
        var interrupted = false
        try {
            mfc.connect()
            for (sector in 0 until mfc.sectorCount) {
                val start = dump.size
                val blockCount = mfc.getBlockCountInSector(sector)
                try {
                    val label = findSectorKey(mfc, sector)
                    if (label != null) {
                        found.add("Sector $sector -> $label")
                        readSectorBlocks(mfc, sector, dump)
                    } else {
                        found.add("Sector $sector -> no key found")
                        repeat(blockCount) { dump.add(MifareClassicUtils.NO_DATA) }
                    }
                } catch (e: TagLostException) {
                    interrupted = true
                    found.add("Sector $sector -> tag lost")
                    dump.padTo(mfc.blockCount)
                    break
                } catch (e: Exception) {
                    found.add("Sector $sector -> read error")
                    dump.padTo(start + blockCount)
                }
            }
        } catch (e: TagLostException) {
            interrupted = true
            addLog("Tag lost during the attack.")
        } catch (e: Exception) {
            interrupted = true
            addLog("Attack error: ${e.message}")
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
        _foundKeys.value = found
        val cracked = found.count { it.contains("-> Key") }
        addLog("Dictionary attack finished: $cracked/${found.size} sectors cracked")
        if (interrupted && _currentTagDump.value.isNotEmpty()) {
            // An incomplete dump must not replace the previous one: keep it and report it.
            addLog("INCOMPLETE attack dump: previous dump kept (found keys still shown).")
        } else {
            _currentTagDump.value = dump.toList()
            if (interrupted) addLog("WARNING: INCOMPLETE attack dump (tag lost or error).")
        }
    }

    /**
     * Tests the dictionary on one sector. Returns "KeyA:..." / "KeyB:..." or null.
     * Rethrows [TagLostException] to stop the attack.
     */
    private fun findSectorKey(mfc: MifareClassic, sector: Int): String? {
        for (k in MifareClassicUtils.COMMON_KEYS) {
            val kb = MifareClassicUtils.hexToBytes(k) ?: continue
            try {
                if (mfc.authenticateSectorWithKeyA(sector, kb)) return "KeyA:$k"
                if (mfc.authenticateSectorWithKeyB(sector, kb)) return "KeyB:$k"
            } catch (e: TagLostException) {
                throw e
            } catch (e: Exception) {
                // Some chips drop the connection after a failure: re-establish it.
                try { mfc.close(); mfc.connect() } catch (ex: TagLostException) { throw ex } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Prepares the copy: captures the current dump, then waits for the
     * TARGET card (magic / blank Mifare Classic) to be presented so all blocks can be rewritten.
     */
    fun armClone(writeTrailers: Boolean = false, writeSectorZero: Boolean = true) {
        val dump = _currentTagDump.value
        if (dump.isEmpty() || dump.all { it == MifareClassicUtils.NO_DATA }) {
            addLog("No readable dump to clone (read a Mifare Classic card first).")
            return
        }
        pendingCloneDump = dump
        cloneWriteTrailers = writeTrailers
        cloneWriteSectorZero = writeSectorZero
        _cloneArmed.value = true
        addLog("Clone armed: present the target card now (magic/blank).")
    }

    /** Cancels an armed clone. */
    fun cancelClone() {
        pendingCloneDump = null
        _cloneArmed.value = false
        addLog("Clone cancelled.")
    }

    /** Writes the pending dump to the presented target card (settings frozen at arming time). */
    private fun performClone(tag: Tag, dump: List<String>?, writeTrailers: Boolean, writeSectorZero: Boolean) {
        if (dump.isNullOrEmpty()) { addLog("No dump to clone."); return }
        viewModelScope.launch(Dispatchers.IO) {
            addLog("Writing the dump to the target card…")
            val res = when {
                MifareClassic.get(tag) != null ->
                    MifareClassicUtils.writeDump(tag, dump, writeTrailers, writeSectorZero).message
                MifareUltralight.get(tag) != null ->
                    MifareUltralightUtils.writeDump(tag, dump).message
                else -> "Unsupported target card (neither Mifare Classic nor Ultralight/NTAG)."
            }
            addLog("Copy finished — $res")
        }
    }

    /**
     * Prepares the HCE emulation capture: the next ISO-DEP card presented will have
     * its APDU dialogue recorded, to be replayed later by the phone.
     */
    fun armEmulationCapture() {
        _emuCaptureArmed.value = true
        addLog("Emulation capture armed: present an ISO-DEP card.")
    }

    fun cancelEmulationCapture() {
        _emuCaptureArmed.value = false
        addLog("Emulation capture cancelled.")
    }

    /** Captures the APDU dialogue of an ISO-DEP card and stores it (encrypted) for emulation. */
    private fun performEmulationCapture(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            addLog("Emulation impossible: card is not ISO-DEP. Mifare Classic cannot be " +
                "emulated by Android — use the physical clone.")
            return
        }
        // UID of THIS card (not that of the previously displayed tag).
        val uid = tag.id?.let { MifareClassicUtils.bytesToHex(it) } ?: "-"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = ApduCapture.probe(iso)
                if (result.aids.isEmpty()) {
                    addLog("No usable AID: this ISO-DEP card does not expose a known SELECT AID " +
                        "(replay emulation not applicable).")
                } else {
                    val label = "ISO-DEP $uid — ${result.aids.size} AID, ${result.map.size} APDU"
                    EmulationStore.save(getApplication<Application>(), label, result.aids, result.map)
                    addLog("Card captured for emulation (${result.aids.size} AID, ${result.map.size} responses). " +
                        "Open Card Emulation → Start.")
                }
            } catch (e: Exception) {
                addLog("Emulation capture error: ${e.message}")
            } finally {
                try { iso.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Clones the UID (block 0) onto a "magic" (generic) card. Uses the
     * last scanned tag, present as long as the card is on the reader.
     */
    fun onCloneUid(newUid: String) {
        val tag = lastTag ?: return addLog("No scanned tag")
        val bytes = MifareClassicUtils.hexToBytes(newUid.replace(" ", ""))
            ?: return addLog("Invalid UID (hex expected)")
        viewModelScope.launch(Dispatchers.IO) {
            val ok = MifareClassicUtils.cloneUid(tag, bytes)
            withContext(Dispatchers.Main) {
                addLog(if (ok) "UID cloned successfully (magic card)" else "Clone failed (non-magic card?)")
            }
        }
    }

    fun onDumpExport() {
        val dump = _currentTagDump.value
        if (dump.isEmpty()) {
            addLog("No dump to export")
            return
        }
        val uid = _currentTagUid.value ?: "unknown"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = getApplication<Application>().getExternalFilesDir("nfc_dumps")
                    ?: getApplication<Application>().filesDir
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "dump_${uid}_$timestamp.txt")
                file.writeText(dump.joinToString("\n"))
                withContext(Dispatchers.Main) { addLog("Dump exported: ${file.absolutePath}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addLog("Dump export error: ${e.message}") }
            }
        }
    }

    /** Saves the current dump in Flipper Zero .nfc format (actual type, ATQA and SAK). */
    fun saveAsNfc() {
        val dump = _currentTagDump.value
        val uid = _currentTagUid.value
        if (dump.isEmpty() || uid == null) { addLog("Nothing to save as .nfc"); return }
        val deviceType = dumpDeviceType
            ?: return addLog(".nfc format not applicable to this tag type (Mifare Classic or NTAG/Ultralight only).")
        val atqa = dumpAtqa
        val sak = dumpSak
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = FlipperNfc.generate(
                    uid.replace(" ", ""), dump,
                    deviceType = deviceType, atqa = atqa, sak = sak
                )
                val dir = getApplication<Application>().getExternalFilesDir("nfc_dumps")
                    ?: getApplication<Application>().filesDir
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "card_${uid}_$ts.nfc")
                file.writeText(content)
                withContext(Dispatchers.Main) { addLog(".nfc file saved: ${file.absolutePath}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addLog(".nfc error: ${e.message}") }
            }
        }
    }

    /** Exports the current dump as raw binary (.bin). Unreadable blocks are written as zeros. */
    fun exportBin() {
        val dump = _currentTagDump.value
        if (dump.isEmpty()) { addLog("No dump to export"); return }
        val uid = _currentTagUid.value?.replace(" ", "") ?: "unknown"
        val unreadable = dump.count { it == MifareClassicUtils.NO_DATA }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = ByteArrayOutputStream()
                for (entry in dump) {
                    val bytes = if (entry == MifareClassicUtils.NO_DATA) ByteArray(entry.length / 2)
                    else (MifareClassicUtils.hexToBytes(entry.replace(" ", "")) ?: ByteArray(0))
                    out.write(bytes)
                }
                val dir = getApplication<Application>().getExternalFilesDir("nfc_dumps") ?: getApplication<Application>().filesDir
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "dump_${uid}_$ts.bin")
                file.writeBytes(out.toByteArray())
                withContext(Dispatchers.Main) {
                    if (unreadable > 0) addLog("WARNING: $unreadable unreadable block(s) exported as zeros.")
                    addLog(".bin export: ${file.absolutePath}")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { addLog(".bin export error: ${e.message}") } }
        }
    }

    /**
     * Exports the dump in Mifare Classic Tool text format (+Sector + blocks).
     * Sectors 0-31: 4 blocks; sectors 32-39 (4K): 16 blocks.
     */
    fun exportMct() {
        val dump = _currentTagDump.value
        if (dump.isEmpty()) { addLog("No dump to export"); return }
        val uid = _currentTagUid.value?.replace(" ", "") ?: "unknown"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                var idx = 0
                var sector = 0
                while (idx < dump.size) {
                    sb.append("+Sector: $sector\n")
                    for (j in 0 until blocksInSector(sector)) {
                        if (idx >= dump.size) break
                        sb.append(dump[idx].replace(" ", "")).append('\n')
                        idx++
                    }
                    sector++
                }
                val dir = getApplication<Application>().getExternalFilesDir("nfc_dumps") ?: getApplication<Application>().filesDir
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "dump_${uid}_$ts.mct")
                file.writeText(sb.toString())
                withContext(Dispatchers.Main) { addLog("Export MCT : ${file.absolutePath}") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { addLog("MCT export error: ${e.message}") } }
        }
    }

    /** Imports a dump from MCT text; loads it for a later clone. */
    fun importMct(text: String) {
        val blocks = text.lines().map { it.trim() }.filter { line ->
            line.length == 32 && (line == MifareClassicUtils.NO_DATA ||
                line.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        }
        if (blocks.isEmpty()) { addLog("No valid block (MCT) in the file"); return }
        _currentTagDump.value = blocks
        _currentTagUid.value = uidFromDump(blocks)
        setImportedMeta(blocks)
        addLog("MCT import: ${blocks.size} blocks. Use 'Clone' to write to a card.")
    }

    /** Imports a raw binary dump (.bin) split into 16-byte blocks. */
    fun importBin(bytes: ByteArray) {
        if (bytes.isEmpty()) { addLog("Empty file"); return }
        if (bytes.size % 16 != 0) {
            addLog(".bin file rejected: ${bytes.size} bytes, not a multiple of 16 (Mifare Classic blocks).")
            return
        }
        val blocks = (bytes.indices step 16).map { MifareClassicUtils.bytesToHex(bytes.copyOfRange(it, it + 16)) }
        _currentTagDump.value = blocks
        _currentTagUid.value = uidFromDump(blocks)
        setImportedMeta(blocks)
        addLog(".bin import: ${blocks.size} blocks. Use 'Clone' to write to a card.")
    }

    /** An imported dump has no ATQA/SAK; its type is inferred from the entry size. */
    private fun setImportedMeta(blocks: List<String>) {
        dumpAtqa = null
        dumpSak = null
        dumpDeviceType = when {
            blocks.all { it.length == 32 } -> "Mifare Classic"
            blocks.all { it.length == 8 } -> "NTAG/Ultralight"
            else -> null
        }
    }

    /**
     * UID inferred from block 0 (BCC): 4 bytes if byte 4 equals the XOR of bytes 0-3.
     * Otherwise a 7-byte UID: bytes 0-2 then 4-7 of block 0 (byte 3 is a cascade BCC).
     */
    private fun uidFromDump(dump: List<String>): String {
        val b0 = dump.getOrNull(0)?.let { MifareClassicUtils.hexToBytes(it.replace(" ", "")) }
            ?: return "unknown"
        if (b0.size < 5) return MifareClassicUtils.bytesToHex(b0)
        val bcc = (b0[0].toInt() xor b0[1].toInt() xor b0[2].toInt() xor b0[3].toInt()) and 0xFF
        if ((b0[4].toInt() and 0xFF) == bcc) return MifareClassicUtils.bytesToHex(b0.copyOfRange(0, 4))
        if (b0.size < 8) return MifareClassicUtils.bytesToHex(b0.copyOfRange(0, minOf(7, b0.size)))
        return MifareClassicUtils.bytesToHex(b0.copyOfRange(0, 3) + b0.copyOfRange(4, 8))
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun addLog(msg: String) {
        _logs.update { it + msg }
    }
}

/** Number of blocks in a Mifare Classic sector: 4 up to sector 31, 16 beyond (4K). */
private fun blocksInSector(sector: Int): Int = if (sector < 32) 4 else 16

// Model for the history
data class NfcScanResult(
    val timestamp: String,
    val uid: String?,
    val type: String?,
    val dump: List<String>
)
