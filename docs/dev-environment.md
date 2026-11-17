# Development environment

How this project is built and tested on the `pro` machine. Everything here is
CLI-only: no Android Studio.

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

### Why only one system image

T3 Code lists one device per emulator model, always at the **newest installed**
runtime (`modelRuntimes[model].at(-1)` in `SimlockDeviceHost.ts`), and
`agent-device open` passes only the model. With two images installed, Android 34
becomes unreachable from the Device panel. Installing a second image therefore
silently changes which Android version every test runs on.

## Running the app on an emulator

```bash
# 1. open a device: T3 Code → Device panel, or the device_* tools of the agent.
#    It returns a launcher bound to that lease, plus the install directory.

# 2. build and install (agent-device install needs the thread's workspace root)
./gradlew assembleDebug
"$LAUNCHER" install com.example.flipperdroid.debug build/outputs/apk/debug/Flipperdroid-debug.apk

# 3. drive it
"$LAUNCHER" open com.example.flipperdroid.debug
"$LAUNCHER" snapshot -i                 # @eN accessibility refs
"$LAUNCHER" press @e3 --settle
"$LAUNCHER" screenshot /tmp/shot.png
```

Rules that are easy to get wrong:

- **UI actions and screenshots go through the launcher**, not `adb`. The lease is
  bound to that agent-device session.
- **`install` must run from the install directory** (the thread workspace root).
- **The lease is wiped when it ends** and starts with no apps installed, so the
  install step is not optional.
- Each `device_open` returns its own launcher; opening another device does not
  retarget the first one.

## Test runners, logs, builds

Simlock runs its own adb server on a non-default port, and every lease boots on a
random emulator port, so both values change per session:

```bash
eval "$(scripts/adb-env.sh)"     # prints ANDROID_ADB_SERVER_PORT + ANDROID_SERIAL
./gradlew connectedAndroidTest
adb logcat | grep -i flipperdroid
```

## Test matrix

| Tier | Command | Needs | Covers |
|---|---|---|---|
| JVM, pure logic | `./gradlew testDebugUnitTest` | JDK | IrProtocols, `.ir`/`.nfc`/`.sub` parsing, TOTP, ShellArgs, networking, Mifare, DuckyScript, BLE payloads |
| JVM, Android framework | same task (`AndroidTierTest`) | JDK + Robolectric | `Context`, `SharedPreferences`, `android.util.Log` — anything Android-facing but radio-free |
| Emulator | `./gradlew connectedAndroidTest` | a Simlock lease | `HomeSmokeTest`: legal gate + Home grid. Extend for navigation and non-radio screens |
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
