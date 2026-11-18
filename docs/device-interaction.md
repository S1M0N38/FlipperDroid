# Driving a device with agent-device

UI actions and screenshots go through the agent-device launcher; `adb` is only for
what it has no command for (Gradle installs, `logcat`, `adb shell` one-offs, port
forwards, pulling files). A `--settle` diff is the evidence you can quote; a
screenshot is not.

## The lease

`device_open` boots an emulator and prints a launcher at
`~/.local/share/t3/userdata/device/targets/t3-<hash>/agent-device` carrying
`--config <host>.json` and `--session t3-<thread>`.

- Never hardcode that path: a plain `agent-device` from `mise` answers
  `DEVICE_NOT_FOUND`. Each `device_open` returns its own launcher; opening a second
  device does not retarget the first.
- A lease starts with **no apps installed** and is wiped when it ends, so install
  each session. `agent-device install <pkg> <apk>` from the repo root targets *this*
  lease; `./gradlew installDebug` installs to every device the adb server sees.
- The host allows two devices at once; a stale panel entry blocks a new
  `device_open` with `pro is limited to 2 devices at once`.
- `close` tears down the session, and on a Simlock lease that ends the lease: the
  device is wiped and the panel's entry goes stale. Keep it for the last line and
  use `device_close` (`shutdown: true`) to give the device back properly.

## The loop

```bash
AD=<path device_open printed>
$AD open com.example.flipperdroid.debug --foreground   # session + initial snapshot; don't probe first
$AD open com.example.flipperdroid.debug --relaunch     # restart the app: proves what survives it
$AD press @e7 --settle                                 # act: the returned diff is the evidence
$AD scroll down --until 'text="Password Generator"' --settle
$AD find "Copy"                                        # act by label (contains match)
$AD wait text "Copied!" 3000                           # verify an expectation
$AD is exists 'text="Settings"'
```

- **`--settle` returns a UI diff** (`[unchecked]` → `[checked]` proves the step).
  It is only for `press|click|fill|longpress|hover|scroll|back`; `find`, `open`,
  `snapshot` and `type` reject it. Diffs are truncated (`… changed lines truncated`)
  on big screens — when the next target is missing, read the whole tree.
- A diff can be a no-op read: `back --settle` answering `+0 -0` means neither
  "failed" nor "changed" — check `snapshot`/`appstate` before choosing a direction.
- `scroll <dir> --until <selector>` reaches an off-screen target in one command;
  `top`/`bottom` take no `--until`, and inside `batch` `until` is a **string**.
- **Refs die with the frame.** Copy them exactly as printed: `open`/`snapshot` emit
  bare refs (`@e7`), a `--settle` diff emits pinned ones (`@e21~s472609`) that must
  keep the pin. A bare or foreign ref fails with "needs a complete snapshot",
  `batch` steps included.
- `find`/`is`/`wait` see only interactive nodes — some static text is missing;
  screen titles and dialog bodies are not there. A missing node may still be in
  `snapshot --json`/`--raw`. Text selectors are **exact** (`text=/regex/` never
  matches, `find` is the contains match), a `text-field` exposes its value as the
  node's **label**, and a value that can contain quotes is better addressed by ref.
- A word appearing twice (tile `group` + its `text`) is `AMBIGUOUS_MATCH`; the
  `group` is the tappable one — pass its ref, or `--first`.
- Compose sometimes surfaces a button as `kind:"text"`, so query by label instead
  of filtering `kind=="button"`.

## Reading trees

```bash
$AD apps
$AD snapshot --json | jq -r '.. | objects | select(.kind=="text-field") | .label'
$AD snapshot --raw | grep -F 'bundleId":"com.example.flipperdroid.debug' | head -3
```

`--json` is one document, `{success, data: {…}}` — `.nodes[]` does not exist and a
wrong `jq` path silently returns nothing. `--raw` is NDJSON, one object per line,
including every `com.android.systemui` node — filter on `bundleId` first.

## Flows as code

```bash
$AD replay docs/flows/home-smoke.yaml --maestro
```

- `replay --maestro` works on a T3 lease; `agent-device test <suite>` does **not** —
  the suite runner spawns its own session and dies with
  `2 devices match this request equally` (and drops `.agent-device/test-artifacts/`
  into the tree).
- `session save-script` is refused over the T3 connection ("script publication
  needs a local daemon"), so flows are authored, not exported.
- A script with no terminal `close` leaves its session usable for the next command.

## Evidence for humans

**Screens calling `SecureWindow()`** (`util/SecureFlag.kt`; used by
`PasswordGeneratorScreen` and `TotpScreen`) **render black** — by design, not a tool
failure: `FLAG_SECURE` keeps the status bar visible over a black surface while the
accessibility tree stays correct. Prove those screens from the tree; a screenshot or
recording proves nothing there.

```bash
$AD screenshot /tmp/shot.png
$AD record start /tmp/flow.mp4 --scope device      # touch overlay, 180 s per chunk
$AD record stop  /tmp/flow.mp4
$AD record contact-sheet /tmp/flow.mp4 --out /tmp/sheet.png   # macOS hosts only
```

## Logs, perf, OS state

```bash
$AD logs start && … && $AD logs mark "before settings" && $AD logs stop
$AD perf frames --json                  # gfxinfo, cumulative *since boot*
$AD settings permission reset camera    # then: $AD alert --json ; $AD alert accept
```

`perf frames` counters run since boot, so reset gfxinfo before measuring a flow.

## What this device cannot do

The API 34 `google_apis` emulator has **no NFC controller, no IR blaster, no real
Bluetooth or Wi-Fi radio and no USB gadget**: modules only prove their honest
"unsupported / no root" paths there (`NFC is not available on this device.`).
`clipboard read|write` and `orientation` are refused or unconfirmed on this image
(`Rotation requested … (unconfirmed)`), and everything else needs the rooted Pixel 7.
`takeover` hands the device to a human for what an emulator cannot fake (a real NFC
tag, a pointing IR LED).
