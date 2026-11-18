# Docs

One directory, one naming rule: **lowercase kebab-case** (the root `README.md`
and `CHANGELOG.md` keep their conventional names). These files exist to stop a
mistake, not to teach Android, Kotlin or Gradle: if a competent agent would get a
statement right on its own, it does not belong here.

New to the code? [`context.md`](context.md). Setting up or running things?
[`dev-environment.md`](dev-environment.md). Driving a phone?
[`device-interaction.md`](device-interaction.md).

| File | What it answers |
|---|---|
| [`context.md`](context.md) | only the constraints and silent-failure traps: API floor, ViewModel ownership, `CATEGORY_ORDER`, root/`su`, nmap, BadUSB, BLE/IR specifics |
| [`dev-environment.md`](dev-environment.md) | this machine's facts: mise toolchain, single API 34 image, keystore/XDG, install pinning, test tiers, CI policy |
| [`device-interaction.md`](device-interaction.md) | agent-device: the lease, the `--settle` loop, ref pins, tree shapes, replay flows, evidence, what the emulator cannot do |
| [`android-capabilities.md`](android-capabilities.md) | what a phone can do natively, with root, with external hardware, or never |
| [`roadmap.md`](roadmap.md) | implemented vs planned, and the honest limits of a phone |
| [`audit-report.md`](audit-report.md) | frozen 2.1.0 quality/defect audit — history, not current state |
| [`flows/home-smoke.yaml`](flows/home-smoke.yaml) | replayable smoke flow (`replay … --maestro`) |

`README.md` at the repo root is the front door (what the app does, how to build);
`CHANGELOG.md` is the released history.
