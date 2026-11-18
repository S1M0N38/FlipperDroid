# What an Android phone can do — pentest capabilities for Flipper-style tooling

A survey of what an Android smartphone can do in the context of FlipperDroid,
organized by **required level of access**. Marked if FlipperDroid already does it (✅),
could do it natively (🟢), needs root (🔴), needs external hardware (🔌) or is infeasible (⛔).

> **Status:** many of the 🟢/🔴 items below are already implemented (BLE HID, Evil Portal, 2FA/TOTP,
> `.nfc`/`.sub` decoders, Wardriving, Skimmer, WiFi Analyzer + MAC spoof, BadUSB gadget, IR base).
> See the current "✅ Implemented" list in [`05-roadmap.md`](05-roadmap.md). Full U2F/FIDO2 remains
> out of scope (it requires a relying-party backend + FIDO2/Credential Provider APIs and on-device testing).

---

## A) Native — no root, Android APIs only

### NFC / RFID 13.56 MHz
- ✅ **Read Mifare Classic** (UID, dump with keys), key **dictionary attack**, **NDEF** read.
- ✅ **Read EMV card** (masked PAN, expiry) via IsoDep.
- ✅ **HCE — card emulation** (`HostApduService`) — already registered in the app.
- 🟢 **NDEF write / formatting** of tags.
- 🟢 **UID cloning** to "magic" cards (already implemented).
- ⚠️ **NFC/HCE relay**: would require two devices (HCE on one side, reader on the other) with bidirectional forwarding. The former "NFC Relay Attack" module did not forward anything and was removed in 2.2.0; APDU dialogue capture/replay lives on in **Card Emulation**.
- ⛔ Nested/Darkside key recovery (the NFC controller does not expose the radio).
- ⛔ 125 kHz (LF) RFID — different hardware.

### Bluetooth / BLE
- ✅ **BLE Spam** (Apple, Samsung, Microsoft SwiftPair, Google Fast Pair).
- ✅ **BLE Scanner + GATT Explorer** (services/characteristics, read).
- ✅ **Skimmer detector** (Classic + BLE by serial-module signature).
- 🟢 GATT write/notify/subscribe, MTU, bonding.
- 🟢 Custom **advertiser/beacon** (iBeacon, Eddystone) and BLE peripheral emulation.
- 🟢 Scan Bluetooth Classic (discovery, SDP, names).
- 🟢 BLE HID host (the phone controls a BLE keyboard/mouse) and **BLE HID device** (the phone as a BLE keyboard for a PC — via the BluetoothHidDevice API).
- ⛔ BLE link sniffing / capture of other pairs (needs a radio in promiscuous mode).

### Wi-Fi
- ✅ **Network scan** (SSID/BSSID/channel/RSSI/security).
- ✅ **Wardriving** with GPS → Wigle CSV.
- 🟢 **Channel analyzer** (2.4/5 GHz occupancy graph).
- 🟢 **Local hotspot** (`startLocalOnlyHotspot`) → basis for an **Evil/Captive Portal** with an HTTP server (partial: no NAT/internet).
- 🟢 Current connection info, signal strength, IP, gateway, DNS.
- ⛔ **Deauth / beacon spam / evil twin / handshake capture** — these require monitor mode + injection (the radio blocks it).

### Network (IP layer) — already a strong point in the app
- ✅ **nmap** (via root, bundled), ping, portscan, DNS, traceroute.
- 🟢 Without root: socket-based portscan, mDNS/SSDP/UPnP discovery, banner grabbing, ARP via `/proc/net/arp`, HTTP recon, DNS lookups, WHOIS.
- 🟢 **Lightweight Responder/LLMNR poisoning**, local HTTP/phishing server, MITM on your own network (proxy).
- 🟢 Wake-on-LAN, subnet scanning, device fingerprinting.

### Infrared (only devices with a blaster)
- ✅ **IR TX** (NEC) — universal remote + numeric keypad.
- 🟢 More protocols (RC5/RC6/SIRC/Samsung) + a **code database** (Flipper-IRDB, `.ir` format).
- ⛔ **IR RX/learning** — almost no phone has an IR receiver.

### Sensors / capture / utilities
- 🟢 Camera: ✅ **QR/barcode scan** (done); OCR (ML Kit), hidden-camera detection by IR reflection.
- 🟢 GPS/IMU/magnetometer (magnetic field detector, compass).
- 🟢 Microphone: spectrum analysis, ultrasound detection (ultrasonic beacons).
- 🟢 Password generator (✅), vault, TOTP/2FA, **U2F/FIDO2** (Credential Manager).
- 🟢 Flipper format reader/decoder (`.nfc`, `.ir`, `.sub`) for interoperability.

---

## B) Requires ROOT (rooted device)

- 🔴 **nmap / native binaries** (already used that way), tcpdump, aircrack-suite (offline), scapy via Termux.
- 🔴 **Monitor mode + injection on the built-in chip via Nexmon** — only specific Broadcom chips (Nexus 5/6P, some older Galaxy devices). Enables **real deauth/handshake** on the internal radio.
- 🔴 **Real BadUSB (USB Gadget)** — configure HID via `configfs` (`/dev/hidgX`) + DuckyScript. (The app's current implementation uses USB *Host* and must be rewritten.)
- 🔴 Packet-level firewall/VPN, local traffic sniffing, MAC spoofing, iptables/NAT (full Evil Portal with internet).
- 🔴 Arbitrary NFC/HCE routing (relay without AID restriction).

---

## C) Requires EXTERNAL HARDWARE (USB-OTG or BLE) — the phone becomes the "brain"

This is the path to **real parity with the Flipper Zero**: the phone drives a module.

- 🔌 **USB Wi-Fi adapter** (AR9271/RT3070/RTL8812AU) → real **deauth, evil twin, handshake, injection** (Kali NetHunter model).
- 🔌 **ESP32 / ESP8266** (Marauder, Evil-M5, DSTIKE) over USB-serial or BLE → deauth, beacon spam, karma, evil portal, wardriving on the ESP.
- 🔌 **Proxmark3** (USB-OTG or BLE) → **full 125 kHz LF + HF RFID**, cloning, sniffing, key attacks.
- 🔌 **RTL-SDR / HackRF / Sub-GHz dongles (CC1101)** via OTG → **receive (and, with HackRF, transmit) radio**: Sub-GHz 315/433/868/915 MHz, ADS-B, POCSAG, etc. (apps: SDR Touch, SDR++).
- 🔌 **Arduino / microcontroller** over a serial bridge (Termux has no native OTG serial driver — use `usb-serial-for-android` in the app or a TCP bridge).
- 🔌 **iButton/1-Wire, GPIO** → only with a dedicated USB adapter.

Key libraries for the app: `usb-serial-for-android` (CDC/FTDI/CP210x/CH34x), `UsbManager` (host), BLE GATT.

---

## D) Infeasible on a smartphone (even with root)

- ⛔ **Native Sub-GHz** (no sub-1 GHz radio) — only with an external SDR/CC1101.
- ⛔ **Native 125 kHz RFID** — only with a Proxmark/external LF reader.
- ⛔ Native **iButton/1-Wire, GPIO** — no pins.
- ⛔ **Sniffing third-party BLE/Wi-Fi radio** without monitor mode.
- ⛔ **IMSI catcher / baseband manipulation** — the modem is closed; infeasible and illegal in most cases.

---

## The one rule to remember

> **Rule of thumb:** anything involving **raw radio** (Sub-GHz, LF RFID, Wi-Fi injection, BLE sniffing) requires **external hardware or a special chip/firmware**. Anything that is a **high-level protocol** (NFC/HCE, BLE GATT, IP/network, IR TX, camera/sensors) the phone can do on its own.
