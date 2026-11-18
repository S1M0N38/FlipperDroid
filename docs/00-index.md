# Docs

Technical documentation for FlipperDroid: what to watch out for when changing
the code, how to build and test it, how to drive a device, what the phone can do
natively or with external hardware, and where the project stands.

| File | What it answers |
|---|---|
| [`01-context.md`](01-context.md) | constraints and silent-failure traps when you change code: API floor, ViewModel ownership, `CATEGORY_ORDER`, root/`su`, nmap, BadUSB, BLE/IR |
| [`02-environment.md`](02-environment.md) | toolchain, debug keystore, emulator leases, test tiers, project scripts, CI and release |
| [`03-device.md`](03-device.md) | driving a phone with agent-device: the `--settle` loop, refs, trees, flows, evidence, traps |
| [`04-capabilities.md`](04-capabilities.md) | what a phone can do natively, with root, with external hardware, or never |
| [`05-roadmap.md`](05-roadmap.md) | shipped vs planned, and the honest limits |
