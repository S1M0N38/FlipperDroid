# Docs

Numbered in reading order; `NN-` is the order, the rest is lowercase kebab-case
(the root `README.md` and `CHANGELOG.md` keep their conventional names).

| # | File | What it answers |
|---|---|---|
| 01 | [`01-context.md`](01-context.md) | constraints and silent-failure traps when you change code: API floor, ViewModel ownership, `CATEGORY_ORDER`, root/`su`, nmap, BadUSB, BLE/IR |
| 02 | [`02-environment.md`](02-environment.md) | toolchain, debug keystore, emulator leases, test tiers, project scripts, CI and release |
| 03 | [`03-device.md`](03-device.md) | driving a phone with agent-device: the `--settle` loop, refs, trees, flows, evidence, traps |
| 04 | [`04-capabilities.md`](04-capabilities.md) | what a phone can do natively, with root, with external hardware, or never |
| 05 | [`05-roadmap.md`](05-roadmap.md) | shipped vs planned, and the honest limits |
| — | [`flows/home-smoke.yaml`](flows/home-smoke.yaml) | replayable smoke flow (`replay … --maestro`) |

These files exist to stop a mistake, not to teach Android, Kotlin or Gradle: if a
competent agent would get a statement right on its own, it does not belong here.
