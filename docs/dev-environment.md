# Development environment

How this project is built and tested on the `pro` machine. Everything here is
CLI-only: no Android Studio. For what the code is made of, read
[`context.md`](context.md); for driving a phone, [`device-interaction.md`](device-interaction.md).

## Toolchain

| Piece | Where it comes from |
|---|---|
| JDK 21 (Temurin) | `mise` (`java = "21"`); CI uses the same major version |
| Android SDK | `mise` (`android-sdk = "23.0"`) → `ANDROID_HOME`; **no `local.properties`** |
| Gradle 8.11.1 | `./gradlew` (wrapper committed, sha256 of the official jar) |
| Emulator image | `system-images;android-34;google_apis;arm64-v8a` — **API 34 only, on purpose** |
| Emulator lifecycle | Simlock, driven by T3 Code's Device panel |
| Restore the SDK | `mise run android:sdk` |

`compileSdk`/`targetSdk` stay at 35 (that is the `platforms/android-35` package);
the emulator runs Android 14 because that is the device class the app targets.

### Why the toolchain is frozen (and two lint checks are disabled)

The SDK levels and the toolchain move together, so freezing one freezes the
other. Concretely, as of AGP 8.9 / `compileSdk 35`:

| Want | Needs |
|---|---|
| `core-ktx` 1.19, Compose 1.12, navigation 2.10 | `minCompileSdk=37` (`activity` 1.13 wants 36) |
| AGP 9.5 | Gradle 9.6, JDK 17+, Build Tools 36, max API 37 |
| AGP 9 built-in Kotlin | drop `org.jetbrains.kotlin.android`, port `kotlinOptions{}` |

So the "newer version available" nags cannot be cleared without raising
`compileSdk` past 35 — and raising it while keeping `targetSdk 35` just moves
the nag to `OldTargetApi` ("Not targeting the latest versions of Android").
That is why `build.gradle.kts` disables `GradleDependency` and
`AndroidGradlePluginVersion`: the report is supposed to show real findings, not
33 lines of version noise nobody is going to act on. When the bump does happen,
delete those two `disable` entries together with this section.

Raising `compileSdk` would not drop old devices by itself (`minSdk` is the
install floor and it stays 24; the manifest merger fails the build if a library
ever raises its own floor above that). It is the *`targetSdk`* half — and the
`OldTargetApi` nag that comes with it — that this project is deliberately not
doing yet.

### One debug keystore, under XDG

AGP signs debug builds with `$ANDROID_USER_HOME/debug.keystore`, and that
defaults to `$XDG_CONFIG_HOME/.android` — but only when `XDG_CONFIG_HOME` is set,
otherwise `~/.android`. A non-interactive shell (agent, script, runner) never
sources `~/.bashrc`, which is where this machine exports `XDG_CONFIG_HOME`, so
two shells could sign the same APK with two different keys: alternating installs
then fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the APK digest changes
between `apksigner verify --print-certs` runs for no visible reason.

This machine pins it in `~/.config/mise/config.toml` `[env]` (`XDG_CONFIG_HOME`,
`XDG_DATA_HOME`, `ANDROID_USER_HOME=~/.config/.android`), and `scripts/adb-env.sh`
re-exports those for shells that did not go through mise activation. The only
debug keystore is `~/.config/.android/debug.keystore` (SHA-256 `a6d12f5b…`); if a
build suddenly signs with something else, check `echo $ANDROID_USER_HOME` and
`./gradlew --stop` (a daemon started under the old environment keeps it).

### Why only one system image

T3 Code lists one device per emulator model, always at the **newest installed**
runtime (`modelRuntimes[model].at(-1)` in `SimlockDeviceHost.ts`), and
`agent-device open` passes only the model. With two images installed, Android 34
becomes unreachable from the Device panel. Installing a second image therefore
silently changes which Android version every test runs on.

## Running the app on an emulator

Driving the UI is documented once, in
[`device-interaction.md`](device-interaction.md): the `open` → `--settle` diff loop,
ref pins, `batch`, replayable flows, and the full gotcha list. This section keeps
only what belongs to *this machine*.

```bash
LAUNCHER="<the agent-device path device_open printed>"
./gradlew assembleDebug
"$LAUNCHER" install com.example.flipperdroid.debug build/outputs/apk/debug/Flipperdroid-debug.apk
"$LAUNCHER" replay docs/flows/home-smoke.yaml --maestro
```

Environment facts:

- **The launcher is the only handle on the lease, and it has no stable name.** It
  lives under `~/.local/share/t3/userdata/device/targets/t3-<hash>/agent-device`
  and supplies `--config <host>.json` + `--session t3-<thread>`, both per-thread
  and both changing every session — which is why a static project script cannot
  launch the app and why `t3.json` ships only `install`, `test` and `lint`.
- **`install` must run from the install directory** (the thread workspace root),
  and the lease starts with no apps installed because it is wiped when it ends.
- Each `device_open` returns its own launcher; opening another device does not
  retarget the first one.

## Project scripts (`t3.json`)

The repo ships three scripts to T3 Code, defined in [`t3.json`](../t3.json) and
shown in the thread's scripts menu as "From t3.json":

| Script | Command |
|---|---|
| Build + install | `eval "$(scripts/adb-env.sh)" && ./gradlew installDebug -Pandroid.injected.device.serial="$ANDROID_SERIAL"` |
| Unit tests | `./gradlew testDebugUnitTest` (also `runOnSettle` in worktree threads) |
| Lint | `./gradlew lint` |

The install script pins `android.injected.device.serial`, because Gradle otherwise
installs to every device its adb server sees. That serial is whatever
`scripts/adb-env.sh` resolved — the *first* booted emulator — which is not always
the lease the Device panel is showing, so with two leases open the app can land on
the wrong one. When more than one device is attached, prefer
`"$LAUNCHER" install <pkg> <apk>` from the repo root.

There is deliberately **no launch script**: the agent-device launcher path is
per-lease and per-thread, so a static command cannot carry it (see
[`context.md`](context.md) and `device-interaction.md`). Edits to `t3.json` are
picked up by the card directly — the file is the source of truth, the per-machine
project settings are a mirror.

Lint is a gate, not advice: `./gradlew lint` has `abortOnError = true` (warnings
stay report-only) and also checks the release variant, so run it before
pushing anything that touches resources or manifests.

## CI and release

`.github/workflows/build.yml`: on every push to `main` and every PR it runs
`testDebugUnitTest`, `lintDebug`, `assembleDebug` on JDK 21 with SDK 35 and
uploads the APK plus reports. On a `v*` tag it additionally signs a release APK
with the keystore from repository secrets (`RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) and
publishes a GitHub Release; a missing secret fails the job rather than shipping an
unsigned build. Debug APKs are build artifacts only, never releases.

## Test runners, logs, builds

Simlock runs its own adb server on a non-default port, and every lease boots on a
random emulator port, so both values change per session:

```bash
eval "$(scripts/adb-env.sh)"     # prints ANDROID_ADB_SERVER_PORT + ANDROID_SERIAL
./gradlew connectedAndroidTest -Pandroid.injected.device.serial="$ANDROID_SERIAL"
adb logcat | grep -i flipperdroid
```

One test only: `./gradlew testDebugUnitTest --tests "com.example.flipperdroid.LogicUnitTest.totp_rfc6238_vectors"`
(class or method; the same selector works for `connectedAndroidTest` class names).

Pin the serial for `installDebug`/`connectedAndroidTest` too: Gradle targets
**every** device its adb server sees, and opening a second lease in the Device
panel silently adds one. `adb` itself stays single-device because `adb-env.sh`
exports `ANDROID_SERIAL`.

## Test matrix

| Tier | Command | Needs | Covers |
|---|---|---|---|
| JVM, pure logic | `./gradlew testDebugUnitTest` | JDK | IrProtocols, `.ir`/`.nfc`/`.sub` parsing, TOTP, ShellArgs, networking, Mifare, DuckyScript, BLE payloads |
| JVM, Android framework | same task (`AndroidTierTest`) | JDK + Robolectric | `Context`, `SharedPreferences`, `android.util.Log` — anything Android-facing but radio-free |
| Emulator | `./gradlew connectedAndroidTest` | a Simlock lease | `HomeSmokeTest`: legal gate + Home grid. Extend for navigation and non-radio screens |
| Device flow (agent) | `$LAUNCHER replay docs/flows/home-smoke.yaml --maestro` | a Simlock lease | same path, driven through agent-device — see [`device-interaction.md`](device-interaction.md) |
| Real phone | `./gradlew installDebug` per device | rooted Pixel 7 | NFC/EMV, IR, BLE spam/beacon/HID, Wi-Fi deauth/monitor, USB-gadget BadUSB, GPS/wardriving |

Instrumented tests install the app as `com.example.flipperdroid.debug`; the
release id stays `com.example.flipperdroid`.

The emulator has no NFC controller, no IR blaster, no real Bluetooth or Wi-Fi
radio and no USB gadget, so those modules only prove their honest
"unsupported / no root" paths there.

## Root on the emulator

The `google_apis` API 34 image is `userdebug` (`ro.debuggable=1`) and ships
`/system/xbin/su`, but that is the AOSP `su`: it takes `su 0 sh -c '<cmd>'`
and rejects `su -c '<cmd>'` with `su: invalid uid/gid '-c'`. `util/RootShell`
probes both forms, so the root modules are testable on the emulator. There is
still no HID gadget and no injection-capable Wi-Fi chip, so BadUSB-gadget and
deauth remain real-phone tests.
