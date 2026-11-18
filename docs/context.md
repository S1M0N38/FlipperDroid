# FlipperDroid — project context

Offensive-security toolkit for **authorized testing only**: a legal gate on first
launch (`HomeScreen`, persisted as `SharedPreferences("legal_prefs").legalAccepted`)
and a README disclaimer. Offensive modules carry an "Authorized testing only"
line, read-only screens do not.

## Constraints

- `minSdk` is 24 and much of the code needs newer APIs. A class newer than 24
  compiles fine and blows up at class-load time, so gate with
  `Build.VERSION.SDK_INT` and render `UnsupportedScreen` below the floor — also
  when only a ViewModel *field* references it. `UnsupportedScreen` is declared in
  `view/HidKeyboardScreen.kt` (not in its own file). Reading `android.os.Build`
  fields needs no gate.
- `java.time` comes from core-library desugaring. `Process.waitFor(timeout, unit)`
  does not exist below 26: it compiles and then throws.
- Release APKs are built and signed only in CI, on a `v*` tag. Never publish a
  debug APK.
- Backups are off and cleartext HTTP is allowed app-wide
  (`res/xml/network_security_config.xml`) because ONVIF/LAN devices speak nothing
  else. Both are deliberate — do not "fix" them.
- New tests: pure logic in a sibling `*LogicTest`, anything touching `Context`/
  `SharedPreferences` in Robolectric (`AndroidTierTest` or a new
  `@Config(sdk = [34])` file — 34 is deliberate: the emulator is API 34 while
  `compileSdk` is 35).
- Runtime receivers listen only to protected system broadcasts (Wi-Fi scan results,
  `ACTION_FOUND`), so they need no `RECEIVER_EXPORTED` flag on target 35.
- Secrets and captured credentials live in `SecureStore`, never in `AppLog` (it
  feeds `LogsScreen`). Screens showing them call `SecureWindow()` from
  `util/SecureFlag.kt` — which also blanks screenshots (see `device-interaction.md`).
- Log through `AppLog`, not `android.util.Log`: `LogsScreen` reads only `AppLog`.
- Effects that must apply app-wide are applied in `MainActivity` (theme in
  `setContent`, keep-screen-on in `onResume`); applying them inside the screen looks
  right until the process is recreated and then silently reverts.

## Adding a screen

`view/XScreen.kt` + `viewmodel/XViewModel.kt` if stateful + a `composable("<route>")`
block in `Main.kt` + a `FeatureItem` in `HomeScreen.kt`. Screens take a
`NavController` and supply their own `TopAppBar` + `navigateUp()`.

Traps that compile and then fail silently:

- **Sections render in the order of the private `CATEGORY_ORDER` list** in
  `HomeScreen.kt`, and items are grouped by their `category` string: an unknown or
  misspelled category renders **nowhere**. Adding a section means editing that list.
- **`FeatureItem(enabled = false)` renders** (dimmed) but the tap does nothing.
- **Two ViewModel patterns, do not unify.** A ViewModel that receives hardware
  intents or must be shared is declared in `MainActivity` with `by viewModels()` and
  passed in (`nfc`, `emvReader`, `emvCardEmulation`, `badUsb`, `bluetooth`,
  `bleSpam`, `networkTools`, `wifiDeauther`, `theme`); every other screen defaults
  it with `= viewModel()` inside its `composable` block.
- NFC tags are arbitrated in `MainActivity.handleNfcIntent`, not in the screens:
  `nfcBusy = emuCaptureArmed || cloneArmed` decides whether `EmvReaderViewModel` also
  sees the tag. A new NFC consumer that opens `IsoDep` on its own silently breaks
  capture/clone.
- A module that cannot really work must say so (root/tooling/emitter missing)
  instead of failing silently or faking it.

## Root, native code, formats

- All root goes through `util/RootShell`, never a raw `su`: it probes the device's
  dialect once (`su -c` vs the AOSP `su 0 sh -c`) and blocks, so call it off the
  main thread. Every command string is built with `ShellArgs`; unquoted user data
  in a root command is injection.
- **nmap** ships in `assets/nmap/`, is copied once to
  `/data/local/tmp/flipperdroid/` (`NetworkToolsViewModel.NMAP_DIR`) and runs as
  root with `LD_LIBRARY_PATH`/`RESOLV_CONF`/`NSSWITCH_CONF` and a 120 s timeout.
  `ShellArgs.splitSafeOptions` refuses `-o*`, `-i*`, `--script`. The "traceroute"
  is honestly a TCP reachability probe.
- **Wi-Fi deauth** needs root *and* `aireplay-ng`/`mdk4` and reports the missing
  half; most phone chipsets cannot do monitor mode. MAC spoof is
  `ip link set … address …`.
- **BadUSB (root gadget)** needs a kernel HID gadget: 8-byte reports written over
  root to `/dev/hidgX`. `RootBadUsbViewModel.charToKeycode` is the shared US-keycode
  map, also used by the BLE HID keyboard.
- **BLE spam** payloads are generators under `model/object`, selected by
  `BleSpamViewModel.BleSpamBrand`. Advertising picks
  `ModernAdvertisementService` or `LegacyAdvertisementService` in
  `BluetoothHelpers.getAdvertisementService`, whose `useLegacy` parameter
  **defaults to `true`**: pass `useLegacy = false` to get extended advertising, or a
  new feature silently stays on 31-byte legacy payloads. Those `model/` subpackages
  are Kotlin keywords, so imports backtick-escape them (`` `class` ``, `` `enums` ``,
  `` `interface` ``, `` `object` ``).
- **IR**: `.ir` files store `address`/`command` as little-endian hex. `NECext`
  (Roku, Epson and NEC-family brands) is a 16-bit address plus an 8-bit command and
  its inverse — *not* a 16-bit command.
