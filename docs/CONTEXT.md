# FlipperDroid — project context

An Android phone turned into a Flipper Zero–style wireless/hardware toolkit:
Kotlin + Jetpack Compose, single module, MVVM. Offensive-security tooling for
**authorized testing and research only** — the app shows a legal gate on first
launch and README restates the disclaimer; keep that framing in code and docs.

## Shape of the build

- **One Gradle module at the repo root** — no `app/` directory; `build.gradle.kts`
  and `src/` are top level. Wrapper-driven: `./gradlew`.
- `namespace`/`applicationId` = `com.example.flipperdroid` (default, change before
  shipping); `compileSdk`/`targetSdk` 35, `minSdk` 24, JVM target 11. The `debug`
  build type adds the `.debug` suffix so two checkouts can coexist on one device.
- `java.time` works on 24 via core-library desugaring; `Process.waitFor(timeout,
  unit)` does not (API 26).
- Release APKs are built and signed **only in CI** from repository secrets on a
  `v*` tag; never publish a debug APK. Signing is env-driven
  (`RELEASE_KEYSTORE_FILE`), and `isMinifyEnabled = false`, so R8 shrinking is not
  in play when debugging a release-only bug.
- `gradle.properties` turns on the build cache and the configuration cache; if
  storing the cache ever fails a build, delete that line instead of fighting it.
- `res/xml/` holds the pieces that are easy to forget: `backup_rules.xml` +
  `data_extraction_rules.xml` (backups off), `network_security_config.xml`
  (cleartext allowed for LAN devices), and the two HCE descriptions plus the NFC
  tech filter (`emv_card_service.xml`, `ndef_hce_service.xml`,
  `nfc_tech_filter.xml`).
- `AndroidManifest.xml` registers two HCE services
  (`nfc.NdefHceService`, `nfc.EmvCardEmulationService`), which is why NFC
  emulation needs no hardware. Permissions cover NFC, BLE scan/advertise/connect,
  Wi-Fi, fine location, IR, camera, internet; NFC/IR/camera/USB-host are
  `required=false` features, BLE and Wi-Fi are required.
- Assets are loaded by path at runtime: `assets/nmap/` (bundled binaries),
  `assets/infrared/*.ir`, `assets/legacy/*.txt` legal texts.

## Packages (`src/main/java/com/example/flipperdroid/`)

| Package | What it is |
|---|---|
| `Main.kt` | `MainActivity` + `NavHost`; holds the ViewModels that must receive hardware intents |
| `view/` | one `*Screen.kt` per feature |
| `viewmodel/` | one `ViewModel` per feature, state via `StateFlow`; where root shells, hardware APIs and background coroutines live |
| `nfc/` | `EmvCardReader`, `MifareClassicUtils` (incl. `COMMON_KEYS` dictionary), `MifareUltralightUtils`, `NfcVUtils`, `NfcInfo`, `ApduCapture`, HCE: `NdefHceService`/`NdefHceStore`, `EmvCardEmulationService`/`EmulationStore` |
| `model/` | the BLE advertisement engine; subpackages are backtick-escaped: `` `class` `` (AdvertiseData, services, queue handler), `` `enums` ``, `` `interface` ``, `` `object` `` (Apple/Samsung/SwiftPair/FastPair payload generators) |
| `infrared/` | protocol-agnostic IR: `IrProtocols` (encoders → µs patterns), `AcProtocols` (full-state AC frames), `PowerCodes` (TV-B-Gone sweep), `IrFile` (Flipper `.ir` parser: parsed + raw) |
| `flipper/` | `FlipperNfc` / `FlipperSub` readers-writers for `.nfc` / `.sub` |
| `camera/` | `OnvifTools` — pure JVM logic for WS-Discovery, credential audit, PTZ |
| `network/` | `PacketSniffer` helpers (root + `tcpdump`, not bundled) |
| `iot/` | `IotProtocolScanner` (MQTT/CoAP probes) |
| `redteam/` | `FuzzPayload` corpus used by the BLE GATT fuzzer |
| `reports/` | `ReportGenerator` (audit report assembly) |
| `security/` | `TotpGenerator` (RFC 6238/4226, SHA1/256/512, Base32, `otpauth://` parsing) |
| `util/` | `AppLog`, `RootShell`, `ShellArgs`, `SecureStore`, `SecureFlag`, `WifiChannels`, `WifiSecurity`, `CryptoTools`, `Csv` |
| `ui/theme/` | `FlipperDroidTheme`; dark mode is a runtime toggle from `ThemeViewModel` |

Pure-logic cores stay free of `android.*` so they are JVM-testable — that is the
pattern to follow when adding a module (`camera/OnvifTools.kt`, `util/ShellArgs`).
New tests: pure logic goes in a sibling `*LogicTest`; framework or serialization
behaviour goes to Robolectric, either in `AndroidTierTest` or a new
`@Config(sdk = [34])` file — both already run under `testDebugUnitTest`.

## Two ViewModel patterns (do not unify)

- **Declared in `MainActivity`** with `by viewModels()` when they need hardware
  intents or must be shared: `nfc`, `emvReader`, `emvCardEmulation`, `badUsb`,
  `bluetooth`, `bleSpam`, `networkTools`, `wifiDeauther`, `theme`.
- **Everything else** builds its ViewModel with the default `viewModel()` inside
  the `composable("route")` block.

NFC foreground dispatch reaches `nfcViewModel.onTagScanned(tag)` always and
`emvReaderViewModel.readCard(tag)` only when NFC is not busy (`if (!nfcBusy)`).
`readCard` returns immediately unless `IsoDep.get(tag) != null`; `onTagScanned`
serves the *armed* modes first — clone-armed is disarmed with `compareAndSet`
before writing so a second tap cannot restart the write, then
emulation-capture-armed — and falls back to a normal read. USB-attach intents go
to `BadUsbViewModel.connectUsb`.

New screen = `view/` composable (+ `viewmodel/` if stateful) + a
`composable("route")` block in `Main.kt` + a `FeatureItem` tile in `HomeScreen`.
Conventions the code enforces but nothing used to state:

- **Home sections live in the private `CATEGORY_ORDER` list in `HomeScreen.kt`**
  and rendering iterates that list: a `FeatureItem.category` that is not in it
  compiles and then renders **nowhere**. Adding a section means editing that list
  too.
- Routes are snake_case (`"emv_reader"`, `"wifi_deauther"`); the tile title and
  the screen's `TopAppBar` title are the same words.
- Screen shape: `fun XScreen(navController: NavHostController, viewModel:
  XViewModel = viewModel())` — the default argument is the convention (most
  screens do this); the ViewModel is constructed in the `composable` block, not
  passed in from `Main.kt`, unless it needs hardware intents.
- A ViewModel is for state that must survive recomposition or work that blocks;
  a screen reading constants can compute in composition, and small local UI state
  may use `remember` (`HomeScreen`, `LogsScreen` do).
- Reading `android.os.Build` fields needs no API gate — the floor rule applies
  only to classes that do not exist below it.

**API-gated screens** (`HidKeyboardScreen` needs API 28, `EvilPortalScreen` API 26)
check `Build.VERSION.SDK_INT` before touching their classes and render
`UnsupportedScreen` below the floor, because `minSdk` is 24. Follow that shape for
any newer-API-only class referenced from a ViewModel field.

## Invariants that bite when broken

- **Root goes through `util/RootShell`**, never a raw `su`. It probes the device's
  dialect once (`su -c …` vs `su 0 sh -c …`) and exposes `isAvailable`, `exec`,
  `output`, `exitCode`.
- **Every root command is built with `ShellArgs`** (quoting + host/interface/MAC
  validation); unquoted concatenation was a command-injection bug.
- **Be honest about capability.** Most modules check root/emitter/tooling and say
  so plainly (Wi-Fi deauther, BadUSB-root, NFC on an emulator) instead of failing
  silently. Keep that when adding radio features a phone cannot really do.
- **NFC I/O runs on `Dispatchers.IO`** (`NfcViewModel` keeps the last `Tag` so
  dictionary attack, NDEF write and UID clone work after a scan).
- **Never log secrets or captured credentials** through `AppLog` (it feeds
  `LogsScreen`). Secrets live in `SecureStore` (Keystore AES-GCM): TOTP vault,
  captured APDUs. Screens handling them use `SecureFlag.SecureWindow()`.
- **English everywhere** — comments, KDoc, docs, user-facing strings, identifiers.
- The `Authorized testing only` line belongs to offensive modules; read-only
  informational screens do not carry it.
- Do not write file/screen counts into docs — they rot.
- Broadcast receivers listen to protected system broadcasts (Wi-Fi scan results,
  `ACTION_FOUND`), so they need no `RECEIVER_EXPORTED` flag on target 35.
- Backups are disabled (`allowBackup="false"` + `res/xml/` exclusions). Cleartext
  HTTP is allowed app-wide (`res/xml/network_security_config.xml`) because
  ONVIF/LAN devices speak nothing else.

## Root and native code

- **nmap** ships prebuilt in `assets/nmap/`, is installed once into
  `/data/local/tmp/flipperdroid/` (`NetworkToolsViewModel.NMAP_DIR`) and runs with
  `LD_LIBRARY_PATH`/`RESOLV_CONF`/`NSSWITCH_CONF` under a 120 s timeout. Options
  are filtered by `ShellArgs.splitSafeOptions` — no `-o*`, `-i*`, `--script`.
  The "traceroute" is honestly a TCP reachability probe.
- **Wi-Fi deauth** needs root *and* an injection tool (`aireplay-ng`/`mdk4`) and
  says so; most phone chipsets cannot do monitor mode. **MAC spoof** is
  `ip link set … address …`.
- **BadUSB (root gadget)** needs a kernel HID gadget at `/dev/hidgX`, opened as
  `cat > /dev/hidgX` over root, streaming 8-byte HID reports from the DuckyScript
  engine. `RootBadUsbViewModel.charToKeycode` is the shared US-keyboard map, also
  used by the BLE HID keyboard.

## BLE advertisement engine (`model/`)

`IAdvertisementService` has two implementations chosen at runtime by API level
(`BluetoothHelpers.getAdvertisementService`): `ModernAdvertisementService`
(extended advertising, 26+) and `LegacyAdvertisementService`.
`AdvertisementSetQueueHandler` cycles `AdvertisementSet`s to registered
callbacks. Payloads come from the `` `object` `` generators — Apple Continuity,
Samsung EasySetup, SwiftPair (Microsoft), FastPair (Google, service data UUID
`0xFE2C`) — selected by the `BleSpamBrand` enum in `BleSpamViewModel`.
`AdvertiseData.build()` supports manufacturer and service data.

## IR code database

`IrProtocols.encode(protocol, address, command)` → `(frequencyHz, IntArray µs)`;
timings verified against SB-Projects / IRremoteESP8266. `.ir` files store
`address`/`command` as little-endian hex; `IrFile.parse` handles `parsed`
(encoded) and `raw` (sent as-is). NEC-family brands plus Roku/Epson use `NECext`
(16-bit address + command + inverted command — not a 16-bit command).

## Where the rest lives

| Topic | Doc |
|---|---|
| Toolchain, keystore, emulator lease, test tiers, CI/release, project scripts | [`dev-environment.md`](dev-environment.md) |
| Driving a phone with agent-device (loop, refs, flows, gotchas) | [`device-interaction.md`](device-interaction.md) |
| What a phone physically can/can't do | [`ANDROID_CAPABILITIES.md`](ANDROID_CAPABILITIES.md) |
| Implemented vs planned | [`ROADMAP.md`](ROADMAP.md) |
| Defect/quality audit history | [`AUDIT_REPORT.md`](AUDIT_REPORT.md) |
