# FlipperDroid — Evolution Roadmap

Comparison with the [official Flipper Zero firmware](https://github.com/flipperdevices/flipperzero-firmware)
and proposals for new features, with an **honest** assessment of Android feasibility.

## ✅ Implemented

- **QR / Barcode Scanner** for real, with the camera (dep. `com.journeyapps:zxing-android-embedded`).
- **BLE Scanner + GATT Explorer** (nRF Connect style): scan, connect, discover services/characteristics, read values.
- **BLE Spam** expanded: besides Apple/Samsung, now **Microsoft SwiftPair** and **Google Fast Pair**.
- **Advanced NFC:** Mifare Classic key dictionary attack, NDEF read, fixed UID clone ("magic" card), dump export.
- **Infrared:** real transmission via `ConsumerIrManager` (NEC) + numeric keypad.
- **IR code base (universal remote):** NEC/NECext/Samsung32/Sony-SIRC/RC5 encoders, **parser for the Flipper `.ir` format** (parsed + raw), bundled example remotes, **import of external `.ir`** (Flipper-IRDB via SAF) and **power sweep** (simplified TV-B-Gone).
- **Wardriving** (inspired by Evil-M5Project): Wi-Fi + GPS scan → **Wigle/WiGLE CSV export**.
- **Skimmer Detector** (Bluetooth Classic + BLE by serial module signature).
- **WiFi Analyzer** (2.4/5 GHz channel occupancy graph) + **MAC spoofing (root)**.
- **BLE Beacon** emitter: iBeacon, Eddystone-URL, custom manufacturer data.
- **BLE GATT write + notify** (besides read) in the BLE Scanner.
- **NFC NDEF write** (text/URI, formats the tag if needed).
- **Native network extras**: Wake-on-LAN, ARP table (`/proc/net/arp`), /24 ping sweep.
- **Real BadUSB via USB Gadget (root)** + **DuckyScript parser** (writes to `/dev/hidgX`).
- **BLE HID device** (phone as a wireless Bluetooth keyboard, via `BluetoothHidDevice`).
- **Evil Portal** (LocalOnlyHotspot + embedded HTTP server that captures credentials).
- **2FA Vault (TOTP)** — RFC 6238, validated against the official vectors.
- **Flipper format decoders** — `.nfc` (read/generate), `.sub` (read/analyze) + save `.nfc` in NFC.
- Central **Logs screen** (`AppLog`) with export.
- **SettingsScreen** — connected/persisted toggles (working keep-screen-on).
- **Unit tests** (`src/test/LogicUnitTest.kt`) for the pure logic: TOTP, IR encoders, `.ir` parser, DuckyScript, Mifare, Wi-Fi channel, skimmer, `.nfc`/`.sub`.
- New manifest permissions: `TRANSMIT_IR`, `CAMERA`.

### Note on build/SDK
CI (GitHub Actions) builds, runs the JVM tests and lint on every push/PR. Locally, point `local.properties` at your Android SDK (`sdk.dir`) and run:
- `./gradlew testDebugUnitTest` — runs the unit tests (no device needed).
- `./gradlew assembleDebug` — builds the APK.

### From Evil-M5Project — what is portable
- ✅ **Native:** Wardriving (done), port scan (nmap, done), skimmer detection (BLE/Wi-Fi), open-Wi-Fi checker.
- ⚠️ **Partially native:** Evil Portal (`LocalOnlyHotspot` + HTTP server; no internet routing).
- ❌ **M5 only:** deauth, beacon spam, karma, evil twin, sniffing/handshake (they require 802.11 injection).
- 🔌 **Best path:** an **Evil-M5 controller** inside the app (HTTP Web UI / UART-USB / BLE) to trigger the ESP32 attacks.

The rest below remains as backlog.

## Context: what the Flipper Zero has vs. what a phone has

| Flipper Zero interface | Dedicated radio/hardware | Does an Android phone have it? |
|---|---|---|
| **Sub-GHz** (300–928 MHz, CC1101) | Yes | ❌ No (no Sub-GHz radio) |
| **RFID 125 kHz** (LF) | Yes | ❌ No (the phone's NFC is 13.56 MHz only) |
| **NFC 13.56 MHz** | Yes | ✅ Yes (limited by the NFC controller) |
| **Infrared** | Yes (TX+RX) | ⚠️ Only some phones (TX only, no RX) |
| **iButton / 1-Wire** | Yes | ❌ No |
| **GPIO** | Yes | ❌ No (no exposed pins) |
| **BadUSB (HID gadget)** | Yes | ⚠️ Requires a kernel with USB gadget + root |
| **Bluetooth LE** | Yes | ✅ Yes |
| **U2F** | Yes | ✅ Yes (via API) |

> **Key conclusion:** a phone **never** replaces the Sub-GHz, the 125 kHz RFID, the iButton or the GPIO
> of the Flipper — the radio hardware is missing. Where FlipperDroid can shine is **NFC, IR, BLE, Wi-Fi and USB**,
> and in file-format compatibility (`.nfc`, `.ir`, `.sub`) to interoperate with a real Flipper.

---

## HIGH priority — high value, feasible on Android

### 1. Universal IR remote (code base)
- **What it does:** universal TV/AC/sound remote, like the Flipper's *Infrared Remote* app.
- **Maps to:** Infrared.
- **Feasibility:** ✅ Feasible on phones **with an IR blaster** (Xiaomi, some Samsung/Huawei). Without RX, you cannot *learn* codes by capturing them — only transmit.
- **How:** `ConsumerIrManager.transmit()` (already implemented with NEC in this codebase). A **code database** per brand is missing. Import [Flipper-IRDB](https://github.com/logickworkshop/Flipper-IRDB) and a parser for the `.ir` format (NEC, RC5, RC6, SIRC, Samsung32, RAW protocols).
- **Effort:** M

### 2. Advanced NFC — save, re-emit and Mifare key dictionary
- **What it does:** save tag dumps, re-emulate/rewrite, and a **dictionary attack** on Mifare Classic keys (like the Flipper's NFC app).
- **Maps to:** NFC.
- **Feasibility:** ✅ Feasible. (The Flipper's Nested/Darkside is **not** replicable — the Android NFC controller does not expose the radio; you can only test a dictionary of known keys.)
- **How:** `MifareClassic.authenticateSectorWithKeyA/B` iterating over a dictionary (the Flipper/mfoc `std.keys`). Persist dumps in a Flipper-compatible `.nfc` format. `MifareClassicUtils.writeBlock`/`cloneUid` already exist ("magic" cards).
- **Effort:** M

### 3. QR / Barcode Scanner (with camera)
- **What it does:** read QR/barcodes — the current screen is only "Coming Soon".
- **Feasibility:** ✅ Fully feasible (ZXing is already a project dependency).
- **How:** CameraX + `zxing` (or ML Kit Barcode). We already generate QR; reading is missing.
- **Effort:** S

### 4. Flipper file-format compatibility
- **What it does:** import/export `.nfc`, `.ir`, `.sub` to interoperate with a real Flipper.
- **Feasibility:** ✅ Parsing is 100% feasible (using `.sub` depends on external hardware).
- **How:** simple text parsers for those formats + Storage Access Framework.
- **Effort:** M

### 5. BLE beyond spam — Scanner + GATT Explorer
- **What it does:** list BLE devices, GATT services/characteristics, read/write (basis for IoT pentesting).
- **Maps to:** Bluetooth.
- **Feasibility:** ✅ Feasible. `BluetoothViewModel` already exists but is **incomplete** (no public `startScan()`). Complete it.
- **How:** `BluetoothLeScanner` + `BluetoothGatt`.
- **Effort:** M

---

## MEDIUM priority — feasible with root or caveats

### 6. Wi-Fi Evil Portal / Captive Portal
- **What it does:** creates an AP with a captive portal to capture credentials (authorized test).
- **Feasibility:** ⚠️ Partial. `WifiManager.startLocalOnlyHotspot` + local HTTP server (NanoHTTPD). SSID and full redirection are limited compared to a real AP.
- **Effort:** M

### 7. U2F / FIDO2 Authenticator
- **What it does:** the phone as a U2F security key.
- **Maps to:** U2F.
- **Feasibility:** ✅ Via API (FIDO2 API / Credential Manager) — a different approach from the Flipper but the same result.
- **Effort:** M

### 8. Real BadUSB (USB HID Gadget) — **fix the architecture**
- **What it does:** the phone acts as a keyboard when plugged into a PC (DuckyScript).
- **Feasibility:** ⚠️ **The current implementation is inverted.** It uses the **USB Host** API (phone as host) to send HID to a peripheral — that is *not* BadUSB. Real BadUSB requires the phone in **USB Gadget/Peripheral** mode via `configfs` (`/dev/hidgX`), which needs a **kernel with HID gadget + root**.
- **How:** root script that configures the HID gadget via `configfs` and writes reports to `/dev/hidg0`. Add a **DuckyScript parser** (DELAY, STRING, ENTER, GUI, CTRL, etc.).
- **Effort:** L

### 9. Working Wi-Fi deauth (via an ESP32 companion)
- **What it does:** a real 802.11 deauth.
- **Feasibility:** ❌ on the internal radio of most phones (no monitor mode/injection), even with root. ✅ With an **external ESP32** (e.g. ESP32 Marauder) over USB/BLE.
- **How:** serial protocol with the ESP32 Marauder firmware. (In this codebase the deauth already honestly reports the prerequisites instead of failing silently.)
- **Effort:** L

---

## LOW priority / Not feasible on a phone (document honestly)

| Feature | Verdict | Reason / alternative |
|---|---|---|
| **Sub-GHz** (433 MHz garage, etc.) | ❌ Not feasible | No Sub-GHz radio. Alternative: external CC1101 dongle over USB. |
| **RFID 125 kHz** (LF badges) | ❌ Not feasible | The phone's NFC is 13.56 MHz. Requires an external LF reader. |
| **iButton / 1-Wire** | ❌ Not feasible | No 1-Wire hardware. |
| **GPIO / hardware debug** | ❌ Not feasible | No pins. Alternative: USB-serial/OTG adapter. |
| **App Catalog / plugins** | ⚠️ Rethink | Internal modules + deep links; copying the Flipper `.fap` makes no sense. |

---

## Recommended technical fixes (technical debt)

- **`BluetoothViewModel`**: incomplete (no `startScan()` or public StateFlows) — either complete it (item 5) or remove it.
- **BadUSB**: rewrite for USB Gadget (item 8).
- **`namespace`/`applicationId` = `com.example.flipperdroid`**: change to a real domain before publishing.
- **Tests**: only the generated `ExampleUnitTest`/`ExampleInstrumentedTest` exist — add coverage for the parsers (NEC/IR, `.nfc`, Mifare dictionary).

---

## Suggested sequence

1. QR Scanner (S) → quick win, closes a "Coming Soon".
2. Universal IR + code base + `.ir` parser (M).
3. NFC: save/re-emit dumps + Mifare dictionary + `.nfc` format (M).
4. BLE Scanner/GATT (M) completing `BluetoothViewModel`.
5. BadUSB via USB Gadget + DuckyScript (L).
6. ESP32 bridge for Sub-GHz + real deauth (L) — the only path to "radio parity" with the Flipper.
