#!/usr/bin/env bash
#
# Print the environment that points adb and Gradle at the Simlock lease that is
# currently open (the device T3 Code's Device panel is showing).
#
#   eval "$(scripts/adb-env.sh)"
#   ./gradlew installDebug            # or connectedAndroidTest, logcat, …
#
# Why this exists: Simlock runs its own adb server on a non-default port
# (5038 here, from `~/.simlock/adb-server.json`) and boots each lease on a
# random emulator port, so both ANDROID_ADB_SERVER_PORT and ANDROID_SERIAL
# change every session. Neither may be hardcoded.
#
# Scope: builds, installs, logs and test runners. UI actions and screenshots
# belong to the `agent-device` launcher that `device_open` returns — T3 Code's
# lease is bound to that agent-device session.
set -euo pipefail

simlock_home="${SIMLOCK_HOME:-$HOME/.simlock}"
adb_server="$simlock_home/adb-server.json"

fail() {
  # Prints shell code, because the caller evals this script.
  printf 'echo %q >&2\n' "adb-env: $1"
  exit 1
}

[ -r "$adb_server" ] || fail "no $adb_server; is the Simlock daemon running? (simlock daemon status)"

port="$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$adb_server" | head -1)"
[ -n "$port" ] || fail "could not read the adb server port from $adb_server"

serial="$(ANDROID_ADB_SERVER_PORT="$port" adb devices 2>/dev/null | awk '$2 == "device" { print $1; exit }')"
[ -n "$serial" ] || fail "no booted emulator on port $port; open a device in the T3 Code Device panel first"

printf 'export ANDROID_ADB_SERVER_PORT=%s\n' "$port"
printf 'export ANDROID_SERIAL=%s\n' "$serial"

model="$(ANDROID_ADB_SERVER_PORT="$port" adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
sdk="$(ANDROID_ADB_SERVER_PORT="$port" adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
printf 'echo %q >&2\n' "adb-env: ${model:-device} on Android ${sdk:-?} ($serial)"
