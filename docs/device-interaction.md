# Driving a device with agent-device

How an agent (or a human with a shell) operates a phone for this project. Two
tools exist and they are not interchangeable:

| Tool | Owns |
|---|---|
| **`agent-device`** | the UI: open/relaunch, snapshots, taps, text, scrolls, screenshots, screen recording, permission grants, OS settings, app install/uninstall, session logs, HTTP traffic, perf, replayable flows, telephony, clipboard, `takeover` |
| **`adb`** | only what agent-device has no command for: Gradle builds and installs, `logcat`, `adb shell` one-offs, port forwards, pulling files |

**agent-device is the first choice; adb is the fallback.** Every UI action and
every screenshot goes through agent-device, because the Simlock lease is bound to
an agent-device session — and because the conversation then has a diff to quote
instead of a picture to describe.

## The lease

A device is a **lease**: `device_open` (T3 Code Device panel, or the `device_*`
tools) boots an emulator and prints a launcher bound to it, under
`~/.local/share/t3/userdata/device/targets/t3-<hash>/agent-device`.

- That path is **per-lease and per-thread** and changes every session. Nothing
  may hardcode it; a plain `agent-device` from `mise` answers `DEVICE_NOT_FOUND`
  because it lacks the `--config <host>.json` + `--session t3-<thread>` pair the
  launcher supplies.
- The lease **starts with no apps installed** and is **wiped when it ends**:
  install each session (`agent-device install <pkg> <apk>` from the repo root
  always hits the panel's device; `./gradlew installDebug` installs to *every*
  device the adb server sees).
- **`agent-device close` ends the lease**, it is not a pause: the device is
  wiped and the panel loses it. Use `device_close` (with `shutdown: true`) for
  the panel entry, and treat `close` as the last line of the last session.
- The host allows **two devices at once**; stale panel entries block a new
  `device_open` with `pro is limited to 2 devices at once`.

## The loop

```bash
AD=<path device_open printed>

$AD open com.example.flipperdroid.debug --foreground   # session + initial snapshot
$AD open com.example.flipperdroid.debug --relaunch     # restart the app instead
$AD press @e7 --settle                                 # act; the diff is the evidence
$AD scroll down --until 'text="Password Generator"' --settle
$AD press @e19~s438969 --settle                        # refs carry their ~sN pin
$AD find "Copy"                                        # act by label (contains)
$AD wait text "Copied!" 3000                           # verify an expectation
$AD is exists 'text="Settings"'                        # or assert existence
$AD screenshot /tmp/shot.png                           # only to show a human
```

Rules that fall out of it:

- **`--settle` returns a UI diff**, which is the proof a step worked:
  `[unchecked]` → `[checked]` needs no screenshot. Diffs are **truncated**
  (`… changed lines truncated`) on big screens — when the next target is missing
  from the diff, read the whole tree instead of guessing. `--settle` is only for
  `press|click|fill|longpress|hover|scroll|back`; `find`, `open` and `snapshot`
  reject it.
- **A diff can be a no-op read.** `back --settle` answering `+0 -0` does not
  mean the command failed *or* that the screen changed — confirm with `snapshot`
  or `appstate` before choosing the next direction.
- `scroll <dir> --until <selector>` reaches an off-screen target in one command.
  `scroll bottom|top` take no `--until`; `--until` wants a plain selector
  (`text="Settings"`), and inside `batch` it is a **string**, not an object.
- **Refs die with the frame.** Copy them exactly as printed. `open` and
  `snapshot` emit bare refs (`@e7`) — use those bare; a `--settle` diff emits
  pinned refs (`@e21~s472609`) — those must keep their pin, because a bare
  `@e19` then fails with "needs a complete snapshot". Refs from a *different*
  snapshot fail the same way, `batch` steps included.
- **`find`/`is`/`wait` see what the user could touch.** `snapshot -i` lists the
  interactive nodes — some static text is in it (`[text] "Generated password"`)
  and some is not (screen titles, dialog bodies, settings labels). So the rule is
  one-directional: if a node you need is missing, it may still be in
  `snapshot --json` or `--raw`. Text selectors are **exact** (`text=/regex/`
  never matches; `find` is the contains-match), a `text-field` exposes its value
  as the node's **label**, and a value that can contain `"` or `'` (generated
  passwords, captured payloads) is better addressed by ref than by selector.
- A word that appears twice (tile `group` + its `text` label) is
  `AMBIGUOUS_MATCH`; the **`group`** is the tappable one. Pass that ref, or
  `--first`.
- Compose surfaces some buttons as `kind:"text"`; query by label rather than
  filtering `kind=="button"`.

### Reading trees without guessing

```bash
$AD apps                                   # what is installed on this lease
$AD snapshot --json | jq -r '.. | objects | select(.kind=="text-field") | .label'
$AD snapshot --raw | grep -F 'bundleId":"com.example.flipperdroid.debug' | head -3
```

`--json` is one document, `{success, data: {…}}` — `.nodes[]` does not exist and
`jq` on the wrong path silently yields nothing. `--raw` is **NDJSON, one object
per line, including every `com.android.systemui` status-bar node**, so filter on
`bundleId` before grepping for your own text.

## Flows as code

A validated flow runs through the launcher and is the cheapest regression test
this repo has:

```bash
$AD replay docs/flows/home-smoke.yaml --maestro
# Replayed 4 steps in 2.5s
```

- Maestro YAML: `appId`, then steps (`launchApp`, `tapOn`, `assertVisible`,
  `runFlow: when: visible:` …).
- `replay` **works on a T3 lease**; `agent-device test <suite>` does **not** —
  the suite runner spawns its own session and hits
  `2 devices match this request equally` (with `--serial` it fails as
  `no-session`), and it drops `.agent-device/test-artifacts/` into the working
  tree.
- `session save-script` is refused over the T3 connection ("script publication
  needs a local daemon"), so flows are authored, not exported.
- A script with no terminal `close` leaves the session available for the next
  command; the tool prints the daemon URL and session to keep using.

## Evidence for humans

**Screens with `SecureFlag.SecureWindow()` render black** — by design, not a
tool failure: `view/PasswordGeneratorScreen.kt` and `view/TotpScreen.kt` set
`FLAG_SECURE`, so the status bar appears and the app surface is pure black while
the accessibility tree is perfectly correct. Verify those screens from the tree
(`--settle` diffs, `--json`) and never claim a screenshot proves anything there;
`record` + contact sheet goes through the same block.

```bash
$AD screenshot /tmp/shot.png                       # one frame
$AD record start /tmp/flow.mp4 --scope device      # touch overlay, ≤180 s chunks
$AD record stop  /tmp/flow.mp4
$AD record contact-sheet /tmp/flow.mp4 --out /tmp/sheet.png
# 24 cells over 11.0s (45 sample times, 45 frames decoded)   — macOS hosts only
```

A contact sheet is the right artifact for a PR: one PNG covering the whole flow,
with elapsed time per cell, and the command reports its own sampling coverage.

## Logs, perf, OS state

```bash
$AD logs start && … && $AD logs mark "before settings" && $AD logs stop
# prints the session app.log path plus a `grep -n "Error\|Exception"` hint
$AD perf frames --json            # android gfxinfo; the window is *since boot*
$AD settings permission reset camera      # then drive the OS dialog:
$AD alert --json                          # title, buttons, packageName
$AD alert accept                          # "While using the app"
$AD settings appearance dark|light        # OS chrome, not the app's own theme
```

`perf frames` reports cumulative counters, so reset gfxinfo before measuring a
flow or the percentages describe the boot window, not the interaction.

## What this device cannot do

The API 34 `google_apis` emulator has **no NFC controller, no IR blaster, no
real Bluetooth or Wi-Fi radio and no USB gadget** — modules prove their honest
"unsupported / no root" paths there (`NFC is not available on this device.`), and
the rest needs the rooted Pixel 7. `clipboard read|write` and
`orientation <…>` are also refused or unconfirmed on this image
(`Rotation requested … (unconfirmed)`). `takeover` is how a human takes the
device for the parts an emulator cannot fake (tapping a real NFC tag, pointing an
IR LED).
