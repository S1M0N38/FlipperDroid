# Docs

One directory, one naming rule: **lowercase kebab-case** (the root `README.md`
and `CHANGELOG.md` keep their conventional names).

New to the code? [`context.md`](context.md). Setting up or running things?
[`dev-environment.md`](dev-environment.md). Driving a phone?
[`device-interaction.md`](device-interaction.md).

| File | What it answers |
|---|---|
| [`context.md`](context.md) | what the app is made of: package map, ViewModel patterns, invariants, BLE/IR/Flipper specifics, conventions a new screen must follow |
| [`dev-environment.md`](dev-environment.md) | toolchain, debug keystore, emulator leases, test tiers, `t3.json` scripts, CI and release |
| [`device-interaction.md`](device-interaction.md) | driving a device with agent-device: the `--settle` loop, refs, trees, `batch`, flows, evidence, gotchas |
| [`android-capabilities.md`](android-capabilities.md) | what a phone can do natively, with root, with external hardware, or never |
| [`roadmap.md`](roadmap.md) | implemented vs planned, and the honest limits of a phone |
| [`audit-report.md`](audit-report.md) | frozen 2.1.0 quality/defect audit — history, not current state |
| [`flows/home-smoke.yaml`](flows/home-smoke.yaml) | replayable smoke flow (`replay … --maestro`) |

`README.md` at the repo root is the front door (what the app does, how to build);
`CHANGELOG.md` is the released history.
