# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

FlipperDroid is a single-module Android app (Kotlin + Jetpack Compose) that turns a phone into a Flipper Zero–style wireless/hardware security toolkit. Modules (all reachable as tiles on `HomeScreen`):

- **NFC** — Mifare Classic read, key **dictionary attack**, NDEF read/write, UID clone (magic cards), dump export + save as Flipper `.nfc`.
- **EMV** — contactless bank-card read (IsoDep) + host-card emulation (HCE).
- **BLE** — advertisement **spam** (Apple/Samsung/Microsoft SwiftPair/Google Fast Pair), **scanner + GATT explorer** (read/write/notify), **beacon** emitter (iBeacon/Eddystone/custom), **HID keyboard** (phone as a wireless BLE keyboard).
- **Wi-Fi** — scan, **deauther** (honest root/hardware capability check), **analyzer** (channel graph) + MAC spoof (root), **wardriving** (GPS → Wigle CSV), **Evil Portal** (LocalOnlyHotspot + credential-capturing HTTP server).
- **Network tools** — bundled `nmap` (root), plus pure-socket ping/portscan/DNS/traceroute, Wake-on-LAN, ARP table, ping sweep.
- **IP cameras** — ONVIF discovery (WS-Discovery multicast) + `/24` camera-port scan with a vendor heuristic, RTSP `OPTIONS` auth probe, **default-credential audit** (dictionary over ONVIF `GetDeviceInformation` with WS-UsernameToken, same idea as the NFC key dictionary), and **PTZ control** (ONVIF `ContinuousMove`/`Stop`). Pure SOAP/RTSP/parsing core lives in `camera/OnvifTools.kt` (JVM-testable).
- **Infrared** — a simple universal remote **and** an `.ir` code-database remote (NEC/NECext/Samsung32/SIRC/RC5/JVC/Kaseikyo/Apple/Coolix), importing Flipper-IRDB `.ir` files + power sweep.
- **BadUSB** — legacy USB-Host attempt **and** a real **USB Gadget** version (root, writes HID reports to `/dev/hidgX`) with a DuckyScript parser.
- **Bluetooth Classic** (scan, SDP, SPP terminal), **Tracker detector** (AirTag/SmartTag/Tile), **GATT fuzzer**, **NDEF emulator** + **Card Emulation** (HCE replay of a captured ISO-DEP dialogue), **LAN discovery** (mDNS/SSDP), **Netcat/HTTP**, **Packet sniffer** (root + tcpdump, not bundled), **IoT scanner** (MQTT/CoAP), **GPS spoof** (mock provider), **AC control**, **IR tools** (TV-B-Gone, brute-forcer), **Crypto tools**, **Wi-Fi QR**, **Default creds**, **Report generator**.
- **Skimmer detector**, **QR scanner** (camera), **password generator** (SecureRandom), **2FA/TOTP vault** (RFC 6238/4226, SHA1/256/512, encrypted), **Flipper file viewer** (`.nfc`/`.sub`/`.ir`), central **Logs**, **Settings**, About.

Several modules require **root** and specific hardware (rooted Pixel 7 on LineageOS is the reference target). The guiding principle when extending: **be honest about capability** — most modules check for root/emitter/tooling and report clearly instead of silently doing nothing (see the Wi-Fi deauther and BadUSB-root as the pattern).

## Build & run

Gradle Kotlin DSL, single module rooted at the repo root (there is **no** `app/` subdirectory — `build.gradle.kts` and `src/` live at the top level). Use the wrapper:

```bash
./gradlew testDebugUnitTest      # JVM unit tests (src/test) — no device needed
./gradlew assembleDebug          # build debug APK -> build/outputs/apk/debug/
./gradlew installDebug           # build + install on connected device/emulator
./gradlew connectedAndroidTest   # instrumented tests, needs a device (src/androidTest)
./gradlew lint                   # Android lint
```

Run a single unit test class/method:

```bash
./gradlew testDebugUnitTest --tests "com.example.flipperdroid.LogicUnitTest.totp_rfc6238_vectors"
```

- **Test tiers** — see [`docs/dev-environment.md`](docs/dev-environment.md) for the full matrix:
  1. **JVM, pure logic** (`src/test`, no device): `LogicUnitTest` plus per-area files (`IrLogicTest`, `NfcLogicTest`, `NetLogicTest`, `TotpReportTest`, `ShellArgsTest`, `BleLogicTest`). Keep that logic free of `android.*` imports (pattern: `camera/OnvifTools.kt`, `util/ShellArgs`).
  2. **JVM, Android framework** (`AndroidTierTest`, Robolectric, `@Config(sdk = [34])`): real `SharedPreferences`/`Log`/`Context` without a device. This is the tier to extend for anything Android-facing but radio-free; `unitTests.isReturnDefaultValues = true` only *stubs* `android.*` and hides such gaps.
  3. **Emulator** (`src/androidTest`, Compose UI): `HomeSmokeTest` only — legal gate + Home grid. Run it against the Simlock lease: `eval "$(scripts/adb-env.sh)" && ./gradlew connectedAndroidTest -Pandroid.injected.device.serial="$ANDROID_SERIAL"` (pin the serial: Gradle installs to every device its adb server sees).
  4. **Real phone**: anything that needs NFC, IR, BLE/BT radios, Wi-Fi monitor/injection, USB gadget or `su` against real hardware.
- `java.time` is available on `minSdk` 24 through core-library desugaring (`desugar_jdk_libs`). `Process.waitFor(timeout, unit)` is NOT (API 26): poll `exitValue()` instead.
- SDK: `compileSdk`/`targetSdk` 35, `minSdk` 24, JVM target 11. `applicationId`/`namespace` = `com.example.flipperdroid` (still the default — change before shipping); the `debug` build type adds `.debug` so two worktrees can be installed side by side on one emulator.
- The Android SDK is found through `ANDROID_HOME` (exported by mise: `android-sdk = "23.0"`), so **no `local.properties` is needed** — deliberately, because git-ignored files are not copied into new worktrees. If you add one, every worktree has to add it again.
- Every dependency version lives in the version catalog (`gradle/libs.versions.toml`), including the core-library desugaring one (`libs.desugar.jdk.libs`).

### Driving a leased emulator

`agent-device` is the first choice and `adb` the fallback, not the other way round. **Prefer agent-device for anything it can do** (UI actions, snapshots, screenshots, recording, permission grants, OS settings, install/uninstall, session logs, HTTP traffic, perf, replayable suites, telephony, orientation, clipboard) and defer to `adb` only for what it has no command for — Gradle builds/installs, `logcat`, `adb shell` one-offs, port forwards, pulling files.

```bash
# device_open prints the launcher bound to the lease; there is no stable name for it.
AGENT_DEVICE="<the command device_open printed>"
"$AGENT_DEVICE" open com.example.flipperdroid.debug --relaunch
"$AGENT_DEVICE" snapshot -i           # @eN refs for Compose screens
"$AGENT_DEVICE" press @e3 --settle    # prints a UI diff
"$AGENT_DEVICE" settings permission grant camera
"$AGENT_DEVICE" screenshot /tmp/shot.png
```

- **The lease is bound to that launcher**, which is under a per-lease, per-thread path (`~/.local/share/t3/userdata/device/targets/t3-<hash>/agent-device`). A plain `agent-device` from `mise` cannot reach it: without `--config <host>.json` **and** `--session t3-<id>` it answers `DEVICE_NOT_FOUND`, and the session id changes per thread — so no saved project script can launch the app. The project scripts are deliberately `install`, `test` and `lint` only; launching is the launcher's job.
- The lease is **wiped when it ends** and starts with no apps installed, so reinstall each session; `scripts/adb-env.sh` resolves the changing adb port/serial for `installDebug`.
- `takeover` hands the device to a human for what an emulator cannot do (NFC tap, IR blaster, Wi-Fi monitor mode); the API 34 `google_apis` image also has no real BT/Wi-Fi radio.

## Architecture

MVVM with Compose. Single Activity, string-route navigation.

- **`Main.kt` (`MainActivity`)** — the single entry point + `NavHost` inside `AppNavigation()`. To add a screen: create the `view/` composable (+ a `viewmodel/` if it has state), and add a `composable("route")` block, plus a `FeatureItem` tile in `HomeScreen`.
- **Two ViewModel-ownership patterns coexist** (don't "fix" this into one):
  - ViewModels that must receive **hardware intents** or be shared are declared in `MainActivity` via `by viewModels()`: `nfcViewModel`, `emvReaderViewModel`, `emvCardEmulationViewModel`, `badUsbViewModel`, `bluetoothViewModel`, `bleSpamViewModel`, `networkToolsViewModel`, `wifiDeautherViewModel`, `themeViewModel`. `MainActivity` routes NFC foreground-dispatch tags to `NfcViewModel.onTagScanned` **and** `EmvReaderViewModel.readCard` (each ignores tags of the wrong tech), and USB-attach intents to `BadUsbViewModel.connectUsb`.
  - Everything else constructs its ViewModel with the default Compose `viewModel()` **inside** the `composable(...)` block (WiFi analyzer/deauther, wardriving, skimmer, beacon, TOTP, IR remote, Evil Portal, HID keyboard, root BadUSB, …).
- **API-gated screens**: `HidKeyboardScreen` (BluetoothHidDevice, API 28+) and `EvilPortalScreen` (LocalOnlyHotspot, API 26+) check `Build.VERSION.SDK_INT` first and render `UnsupportedScreen` (defined in `HidKeyboardScreen.kt`) below their floor, because `minSdk` is 24. Follow this pattern for any newer-API-only class referenced in a ViewModel field.

Package layout under `src/main/java/com/example/flipperdroid/`:

- `view/` — one Compose screen per feature (`*Screen.kt`).
- `viewmodel/` — one `ViewModel`/`AndroidViewModel` per feature; state via `StateFlow`. This is where the real work happens (root shells, hardware APIs, background coroutines).
- `nfc/` — `EmvCardReader`, `MifareClassicUtils` (incl. `COMMON_KEYS` dictionary), `EmvCardEmulationService` (a manifest-registered `HostApduService`).
- `model/` — the **BLE advertisement engine** (see below). Subpackages use backtick-escaped names: `` `class` ``, `` `interface` ``, `` `object` ``, `enums`.
- `infrared/` — protocol-agnostic IR: `IrProtocols` (encoders → µs patterns for `ConsumerIrManager`), `IrFile` (Flipper `.ir` parser, parsed + raw). Bundled remotes live in `assets/infrared/*.ir`.
- `flipper/` — `FlipperNfc` / `FlipperSub` readers-writers for Flipper `.nfc` / `.sub` files.
- `security/` — `TotpGenerator` (RFC 6238 TOTP + Base32).
- `util/` — `AppLog` (app-wide log surfaced by `LogsScreen`; never log secrets or captured credentials into it), `SecureStore` (Keystore AES-GCM for secrets: TOTP vault, captured APDUs), `SecureFlag.SecureWindow()` (FLAG_SECURE on sensitive screens), `ShellArgs` (quoting/validation for every root command), `RootShell` (the single root-shell entry point), `WifiChannels` (single 2.4/5/6 GHz channel table).
- `ui/theme/` — `FlipperDroidTheme`; dark mode is a runtime toggle driven by `ThemeViewModel.isDarkMode` (persisted).

### BLE advertisement engine (`model/`)
`IAdvertisementService` has two implementations chosen at runtime by API level via `BluetoothHelpers.getAdvertisementService`: `ModernAdvertisementService` (extended advertising, API 26+) and `LegacyAdvertisementService`. `AdvertisementSetQueueHandler` cycles through `AdvertisementSet`s and notifies `IAdvertisementServiceCallback`s. Payloads come from the `*AdvertisementSetGenerator` objects — Apple Continuity, Samsung EasySetup, **SwiftPair (Microsoft)**, **FastPair (Google)** — selected in `BleSpamViewModel` by the `BleSpamBrand` enum. `AdvertiseData.build()` supports both manufacturer data and service data (FastPair uses service data UUID 0xFE2C).

### Root & native code
Root-dependent modules shell out through `su` and **report honestly when root/tooling is missing**:
- **Never call `su` directly: go through `util/RootShell`.** It probes the device's `su` dialect once (`su -c '<cmd>'` for Magisk/SuperSU, `su 0 sh -c '<cmd>'` for the AOSP/toybox `su` on userdebug and emulator images, which rejects `-c`) and exposes `isAvailable()`, `exec()`, `output()`, `exitCode()` and `commandLine()`. Before it existed, three copies of `hasRoot()` and five raw `su` call sites reported "no root" on the emulator purely because of an argv mismatch.
- **Every command string must be built with `ShellArgs`** (quote user/network data, validate hosts/interfaces/MACs). Unquoted concatenation previously allowed root command injection.
- **Network tools** ship a prebuilt `nmap` + `.so`s in `assets/nmap/`; installed once into `/data/local/tmp/flipperdroid/` (`NetworkToolsViewModel.NMAP_DIR`), then run with `LD_LIBRARY_PATH`/`RESOLV_CONF`/`NSSWITCH_CONF` under a 120 s timeout. Options are filtered by `ShellArgs.splitSafeOptions` (no `-o*`, `-i*`, `--script`). The "traceroute" is honestly a TCP reachability probe.
- **Wi-Fi deauth** checks for root + an injection tool (`aireplay-ng`/`mdk4`) and explains that most phone Wi-Fi chipsets can't do monitor-mode/injection.
- **MAC spoof** uses `ip link set … address …`.
- **BadUSB (root gadget)** requires a kernel HID gadget at `/dev/hidgX`; it opens `su -c "cat > /dev/hidgX"` and streams 8-byte HID reports built by the DuckyScript engine (`RootBadUsbViewModel.charToKeycode` is the shared US-keyboard map, reused by the BLE HID keyboard).

### IR code database
`IrProtocols.encode(protocol, address, command)` returns `(frequencyHz, IntArray µs pattern)`. Timing constants were verified against SB-Projects / IRremoteESP8266 (see `ROADMAP.md`). `.ir` files store `address`/`command` as little-endian hex bytes; `IrFile.parse` handles both `parsed` (encoded via `IrProtocols`) and `raw` (transmitted directly). NEC-family brands and the added Roku/Epson use `NECext` (16-bit address + command + inverted command — **not** a 16-bit command).

## Conventions & gotchas

- **The codebase is written in English**: code comments, KDoc, markdown docs and user-facing strings are all English, and so are identifiers and log tags. Keep it that way when editing a file.
- BroadcastReceivers register for **protected system broadcasts** (Wi-Fi scan results, Bluetooth `ACTION_FOUND`), so they don't need the `RECEIVER_EXPORTED` flag even on target 35.
- Assets under `src/main/assets/` (`nmap/` binaries, `infrared/*.ir`, `legacy/*.txt` legal texts) are loaded by path at runtime — keep paths in sync with the code.
- `NfcViewModel` keeps the last `Tag` so dictionary-attack / NDEF-write / UID-clone work after a scan; NFC I/O runs on `Dispatchers.IO` (blocking calls would ANR on the main thread).
- Backups are disabled (`allowBackup="false"`, plus exclusions in `res/xml/`). Cleartext HTTP is allowed app-wide by `res/xml/network_security_config.xml` because ONVIF/LAN devices only speak plain HTTP.
- Releases are built by CI only on `v*` tags and signed from repository secrets (`RELEASE_KEYSTORE_BASE64`, …); never publish the debug APK.
- This is offensive-security tooling for **authorized testing/research only** (see README + in-app legal gate). Keep that framing, and keep the honest-capability-check pattern when adding radio features a phone can't really do.
