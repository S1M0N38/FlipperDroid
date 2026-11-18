# Development environment

CLI-only on `pro`: no Android Studio and no Gradle-sync advice. Code shape lives
in [`01-context.md`](01-context.md); driving a phone in
[`03-device.md`](03-device.md).

- JDK 21 and the Android SDK come from `mise` (`ANDROID_HOME`). There is **no
  `local.properties`** and none is needed — do not create one. Reinstall the SDK
  with `mise run android:sdk`.
- Lint's `GradleDependency` and `AndroidGradlePluginVersion` checks are disabled
  because no newer AndroidX/AGP line fits `compileSdk 35`: leave them disabled, and
  delete the two entries only when the SDK bump actually happens.
- **Only one system image, on purpose.** T3 Code offers the newest installed
  runtime per emulator model and `agent-device open` passes only the model, so a
  second image silently moves every test to a different Android version.
- Lint is a gate: errors fail the build (`abortOnError`, release variant included),
  warnings are report-only.
- The CI release job runs only on a `v*` tag and fails when a `RELEASE_KEYSTORE_*`
  secret is missing; debug APKs are never releases.

## One debug keystore

AGP signs debug builds with `$ANDROID_USER_HOME/debug.keystore`, which falls back to
`~/.android` unless `XDG_CONFIG_HOME` is set — and a non-interactive shell never
sources the `~/.bashrc` that exports it. Two shells then sign with two keys:
alternating installs fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the APK
digest changes for no visible reason. This machine pins `XDG_CONFIG_HOME`,
`XDG_DATA_HOME` and `ANDROID_USER_HOME=~/.config/.android` in
`~/.config/mise/config.toml` `[env]`, and `scripts/adb-env.sh` re-exports them for
shells that skipped mise. If a build signs with an unexpected key: check
`echo $ANDROID_USER_HOME`, then `./gradlew --stop` (a daemon keeps its old env).

## Build, install, drive

```bash
LAUNCHER="<the agent-device path device_open printed>"   # per lease, per thread
./gradlew assembleDebug
"$LAUNCHER" install com.example.flipperdroid.debug build/outputs/apk/debug/Flipperdroid-debug.apk

eval "$(scripts/adb-env.sh)"   # ANDROID_ADB_SERVER_PORT + ANDROID_SERIAL
./gradlew testDebugUnitTest --tests "com.example.flipperdroid.LogicUnitTest.totp_rfc6238_vectors"
```

- Lease semantics — launcher, wiped leases, which device an install reaches — are in
  [`03-device.md`](03-device.md). What matters when building: Gradle
  installs to **every** device its adb server sees, so pin
  `-Pandroid.injected.device.serial="$ANDROID_SERIAL"`, and remember `adb-env.sh`
  resolves the *first* booted emulator rather than the panel's lease.
- `t3.json` is the source of truth for the project scripts (`install`, `test`,
  `lint`). There is deliberately **no launch script**: the launcher path cannot be
  static.
- Instrumented tests install `com.example.flipperdroid.debug`; the release id stays
  `com.example.flipperdroid`, so `logcat`/`apps` filters differ.

## Test tiers

| Change | Where it runs |
|---|---|
| pure logic: a new `*LogicTest` beside the existing ones | `./gradlew testDebugUnitTest` |
| anything touching `Context`, `SharedPreferences`, `Log`: Robolectric `AndroidTierTest` or a new `@Config(sdk = [34])` file — 34 is deliberate, the emulator is API 34 while `compileSdk` is 35 | same task |
| real UI | `connectedAndroidTest`, or `$LAUNCHER replay … --maestro` |
| radios, IR blaster, USB gadget, root on real silicon | a rooted phone; the emulator only proves the "unsupported"/"no root" paths |

Two rules the suite already earned: keep logic in a pure core with a thin ViewModel
(pattern: `camera/OnvifTools`), because logic that sits `private` inside a ViewModel
cannot be tested; and assert the value, not `size` — an inverted IR burst layout once
passed every test that only checked array lengths.

## Root on the emulator

The API 34 `google_apis` image is `userdebug` and ships the AOSP `su`: it rejects
`su -c '<cmd>'` with `su: invalid uid/gid '-c'` and wants `su 0 sh -c '<cmd>'`.
`RootShell` probes both, so root modules are testable there — but there is no HID
gadget and no injection-capable Wi-Fi chip, so BadUSB-gadget and deauth stay
real-phone tests.
