<p align="center"><img src="docs/brand/handoff-logo-512.png" width="112" alt="Handoff logo"></p>

# Handoff

Move an already-paired Bluetooth headset between your Android phones, tablets and Windows PCs with one tap.

```
OnePlus Buds
On Galaxy Tab

[ ⇄ MOVE HERE ]
```

Press **Move here** on your phone, and the tablet in your bag lets go of the earbuds. Then the phone connects and checks that audio is really connected. You don't need to unlock the tablet, open Bluetooth settings or re-pair anything.

> **Status: 0.3.0, not yet hardware-validated.** Everything builds, all 123 tests pass, and both apps have been smoke-tested: Android on an Android 15 emulator, Windows on a real Windows 11 PC, and the two linked with each other. Moving a real headset has **not** been validated yet on either platform. See [docs/HARDWARE_TEST_PLAN.md](docs/HARDWARE_TEST_PLAN.md) and [What is and isn't verified](#what-is-and-isnt-verified).

## What Handoff is (and is not)

* Handoff **coordinates** which of your devices is connected to a headset.
* Audio always flows directly: `phone → earbuds` or `PC → earbuds`. Handoff never streams, proxies or forwards audio.
* Handoff **does not create real Bluetooth multipoint.** For headsets that support multipoint themselves, Handoff detects it and never disconnects the other devices on its own.
* The headset **must already be paired** with every device in normal Bluetooth settings. Handoff never pairs, unpairs or scans for devices.
* **No root. No ADB after installation. No account. No cloud.** Local mode stays on your own network.

## How it works

1. Each Handoff install has its own identity: a random id plus a P-256 key.
   * On Android the key is in the Android Keystore.
   * On Windows it is protected with DPAPI.
2. You **link** two installs **once**: scan a QR code and confirm a 6-digit code on both screens.
3. You **map** the same headset on each device. Handoff recognises it across devices by a hash of its Bluetooth address, never by name alone.
4. Linked devices find each other on the local network (mDNS) and talk over an authenticated, encrypted TCP channel.
5. **Move here** on device B:
   * B asks the linked devices who holds the headset.
   * If device A holds it, B sends `RELEASE_AUDIO_DEVICE` to A.
   * A disconnects every audio profile (media *and* calls), verifies the disconnect and confirms `RELEASED`.
   * B connects and verifies, retrying once if needed.
   * B tells the other devices it is the new owner.
6. If A is offline or doesn't answer, B tries a **direct takeover**: it just connects. Many single-point headsets then drop the old connection by themselves.

### When something goes wrong

* A failed move gets **one automatic retry** of the whole handoff a few seconds later.
* Errors say what actually happened, with a next step:
  * *Couldn't reach Galaxy Tab* (another Wi-Fi, asleep, or Handoff not running)
  * *The other device couldn't let go*
  * *The headset didn't connect*
  * *Bluetooth permission needed*, and so on.
* A linked device that is offline and was last seen on a different network is shown as **On another network**.
* **Battery:** headset cards show the battery level when the headset reports it. When the headset is on another device, the level comes from that device.

### Different Wi-Fi networks

**You never have to link again.** A link is tied to the two devices' identity keys, not to a network. It keeps working at home, at work or on a hotspot.

Two things matter on any network:

* **Both devices must be on the same network when you move the headset.** They find each other again automatically after you switch Wi-Fi: both apps watch for network changes, forget the old addresses and look again on the new network.
* **Some networks block device-to-device traffic** (many guest, hotel and campus networks). Handoff can't reach the other device there; use a phone hotspot or your own network instead.

Moving between devices on *different* networks (for example, the PC at home and the phone on mobile data) needs an Internet relay. That isn't built yet; see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#future-remote-mode).

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Platform limitations

**Android:**
* Android has no public API that lets a normal app connect or disconnect a Bluetooth audio device. Handoff, like [PodSwitch](https://github.com/Felip6499/PodSwitch), calls hidden framework methods through reflection:
  * `BluetoothA2dp.connect()` / `disconnect()` for media;
  * `BluetoothHeadset.disconnect()` to release the call profile.
* These are **non-SDK interfaces**. Any Android version or manufacturer build may restrict them. Handoff isolates this code, never crashes when it's missing, and shows **Supported**, **Experimental** or **Unsupported on this Android build**.
* Some manufacturers aggressively stop background apps. If a locked device doesn't respond to Move here, set Handoff's battery usage to *Unrestricted*.

**Windows:**
* Handoff uses the documented Win32 call `BluetoothSetServiceState`.
  * **Release** turns the headset's audio services off for this PC. That also stops Windows from grabbing the headset back.
  * **Move here** turns them on again, which makes Windows connect. Reinstalling the audio endpoints can take several seconds.
* While a headset is handed away, it doesn't appear as a Windows audio device. Press **Move here** in Handoff (or *Restore Windows audio* in the headset's options) to use it on the PC again.
* The PC must keep Handoff running (tray icon) for your phone to reach it. Windows Firewall may ask once to allow Handoff on private networks: allow it.

See [docs/BLUETOOTH_COMPATIBILITY.md](docs/BLUETOOTH_COMPATIBILITY.md).

## Requirements

* **Android:** Android 12 (API 31) or newer.
* **Windows:** Windows 10 or 11 (x64) with a Bluetooth adapter. Tested on Windows 11 only.
* Devices on the same network (Wi-Fi or Ethernet) when moving the headset.
* The headset paired with each device beforehand.

## Android permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Nearby devices) | List paired headsets, read their state, connect/disconnect. **Required.** |
| `INTERNET` | TCP connection to your other Handoff devices on the local network. |
| `ACCESS_NETWORK_STATE` | Read this device's LAN address for the pairing QR code, and notice Wi-Fi changes. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Stay reachable while the device is locked, so another device can ask it to release the headset. |
| `POST_NOTIFICATIONS` | The (silent) service notification, and optional "Move here?" prompts. |
| `RECEIVE_BOOT_COMPLETED` | Optionally restart the service after reboot, if you enabled that. |
| `CAMERA` | Scan the pairing QR code. Requested only when you tap **Scan**; you can paste the code instead. |

**Not requested:** `BLUETOOTH_SCAN` (Handoff never discovers new devices), and location (bonded devices and mDNS don't need it).

## Build

Requires JDK 17+ and, for Android, an Android SDK with platform 36 (`local.properties` → `sdk.dir=...`).

```bash
./gradlew test assembleDebug
```

Android APK: `app/build/outputs/apk/debug/Handoff-<version>-debug.apk` (application id `dev.handoff.app.debug`).

```bash
./gradlew :desktop:packageExe :desktop:packageMsi
```

Windows installers: `desktop/build/compose/binaries/main/exe/Handoff-<version>.exe` and `…/msi/Handoff-<version>.msi`. The WiX toolset is downloaded automatically. To run the Windows app from source, use `./gradlew :desktop:run`.

## Install

* **Android:** copy the APK to the device and open it (allow installs from that source), or run `adb install -r <apk>`.
* **Windows:** run `Handoff-<version>.exe`. It installs for your user only, with no admin rights needed, and adds a Start-menu entry. The first launch registers Handoff to start with Windows, minimized to the tray; you can turn that off in Settings.

## Quick start

1. **Pair** the headset with each device in its Bluetooth settings.
2. **Install and set up** Handoff everywhere. On Android, grant *Nearby devices* and allow notifications.
3. **Link** each pair of devices once. Show the code on one device and scan it on the other:
   * Phone or tablet: *My devices → Show my code*, or *Scan a Handoff code*.
   * Windows PC: *Link a phone* shows a code for your phone to scan.

   Check that both screens show the same 6-digit code, then tap **Link**.
4. **Map:** on each device, tap *Add headset* and pick the headset. Handoff pre-selects the matching headset from your other devices.
5. **Move:** with the headset on one device, press **Move here** on another.

Android also has a Quick Settings tile *Move here*. On Windows, the tray icon's menu has *Move … here*.

## Project structure

```
core/        Pure Kotlin (JVM): domain, ownership, protocol, crypto, LAN transport, shared read model.
bluetooth/   Android library: the only code that touches android.bluetooth, including all hidden-API use.
app/         Android app: Compose UI, Room/DataStore, Keystore identity, NSD, service, tile, automation.
desktop/     Windows app (Compose Desktop): Win32 Bluetooth via JNA, DPAPI identity, JmDNS, tray.
docs/        Architecture, Bluetooth compatibility, hardware test plan, security, brand assets.
tools/       IconGen (renders the logo PNG/ICO from the same geometry as the Android vectors).
```

## What is and isn't verified

| Verified | Still needs real hardware |
|---|---|
| Builds; Android lint clean; 123 unit/integration tests pass | Moving a real headset (Android ↔ Android, Android ↔ Windows) |
| Android 15 emulator: setup, permissions, foreground service, mDNS, hidden A2DP *and* HFP methods resolve, diagnostics, tile, no crashes | Hidden-API calls on real OEM builds (Samsung, Pixel, …) |
| Windows 11 PC: the packaged app (bundled runtime) runs; paired headsets read correctly via Win32; DPAPI identity; no crashes. Installers are built but were not installed here | `BluetoothSetServiceState` release/connect on a real headset |
| **Windows app ↔ Android app linked** over real TCP: matching 6-digit codes, approval on the PC, the phone shows the PC online with a PC icon | Two physical devices on a real Wi-Fi, incl. switching networks |
| Pairing, encrypted transport, replay/freshness/sender checks, unlinked peers refused | Background reachability of a locked phone on OEM builds |
| Transfer state machine, retries, takeover, contention, call-profile release (regression test for the first field report) | The 20-transfer validation run |

## Privacy

There is no account, no cloud, no advertising, no telemetry and no third-party analytics. Peer traffic stays on your network and is end-to-end encrypted between your devices. Diagnostics stay on the device unless you export them, and exports redact Bluetooth addresses and keys.

## License

MIT, see [LICENSE](LICENSE). Parts of the Bluetooth approach (Android and Windows) are adapted from PodSwitch (MIT), see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
