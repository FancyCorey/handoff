<p align="center"><img src="docs/brand/handoff-logo-512.png" width="112" alt="Handoff logo"></p>

# Handoff

**Move your Bluetooth headphones between your Android phones, tablets and Windows PCs with one tap.**

Press **Move here** on the device you want to listen on. Handoff asks your other device to let go of the headphones, then connects them here and checks that audio really arrived. You don't need to unlock the other device, open Bluetooth settings or pair anything again.

<p align="center">
  <img src="docs/screenshots/android-home.png" width="260" alt="Handoff on Android: Aurora Buds connected here at 80% battery, with Studio PC online">
  &nbsp;
  <img src="docs/screenshots/android-move.png" width="260" alt="A finished move: Connecting, Checking the connection, Connected, in 2.1 seconds">
</p>

> The screenshots use Handoff's built-in demo mode: "My Phone", "Studio PC", "Aurora Buds" and "Studio Headphones" are made-up test devices. No personal data appears in them.

> **Status: 0.4.0, early release.** Both apps build and pass their automated tests, and they have been linked with each other on an Android 15 emulator and a Windows 11 PC. Moving a *real* headset is still being validated across phones and headsets, so expect rough edges. See [What is and isn't verified](#what-is-and-isnt-verified).

## How it works

1. **Pair your headphones** with each device as usual, in its Bluetooth settings. Handoff never pairs, unpairs or scans for Bluetooth devices.
2. **Link your devices once.** One device shows a code and the other scans it. Both screens then show the same 6-digit number, and you approve the link. A link is bound to the two devices, not to a Wi-Fi network, so it keeps working at home, at work or on a hotspot.
3. **Add the headset** on each device. Handoff recognises the same headset across your devices.
4. **Press Move here.** Your device finds which linked device holds the headset and asks it to release it. That device disconnects media and calls and confirms. Then this device connects and verifies the audio link. If the other device can't be reached, Handoff connects directly; most headsets then drop the old connection on their own.

If a move fails, Handoff retries once automatically, then tells you in plain words what happened and what to try next, for example *Couldn't reach Studio PC*, *The headset didn't connect* or *On another network*.

Audio always goes straight from your device to your headphones. Handoff only coordinates *which* device is connected. It never streams or forwards audio, and it does not turn a headset into a real multipoint headset.

<p align="center">
  <img src="docs/screenshots/android-link-code.png" width="220" alt="Android showing a one-time link code that expires in five minutes">
  &nbsp;
  <img src="docs/screenshots/windows-link-approval.png" width="260" alt="Windows asking to link My Phone after checking the 6-digit code 745 320">
</p>

## No shared Wi-Fi? Use a hotspot

The two devices need to share a local network when you move the headset. If there is no Wi-Fi, or the Wi-Fi blocks devices from talking to each other (common on guest, hotel and campus networks), turn on the **hotspot** on one device and join it from the other. Handoff finds your devices on that network by itself. Mobile data isn't needed, and nothing goes over the internet.

When none of your linked devices can be reached, Handoff shows this option, with a shortcut to the hotspot settings.

<p align="center"><img src="docs/screenshots/windows-no-shared-wifi.png" width="300" alt="Windows app with My Phone offline and a 'No shared Wi-Fi?' hint with a button to open Mobile hotspot settings"></p>

## Security and privacy

* **No account, no cloud, no telemetry, no ads.** Handoff talks only to your own linked devices on your local network.
* **Only devices you approve.** Linking needs a one-time code that expires after 5 minutes, *and* a matching 6-digit number approved on screen. Unlinked devices are refused before they can send anything.
* **Encrypted and authenticated.** Every connection is end-to-end encrypted (P-256 key agreement, AES-256-GCM) and pinned to the linked device's key. Commands are bound to that session, checked for freshness and can't be replayed.
* **Not reachable from the internet.** Handoff only accepts connections from local-network addresses, and it rate-limits and temporarily blocks addresses that keep failing.
* **Nothing identifying in network announcements.** Devices announce themselves under a name that changes every day, which only your linked devices can recognise. Device names and headset details only ever go to linked devices, encrypted.
* **Diagnostics stay on the device** unless you export them, and exports hide Bluetooth addresses and keys.

Details: [docs/SECURITY.md](docs/SECURITY.md).

## Background mode is opt-in

By default Handoff runs only while it is open, with no permanent notification. While Handoff is open on a device, your other devices can take the headset from it.

If you also want that to work while the device is locked, turn on **Settings → Stay reachable in the background**. Android requires a silent, permanent notification for this; you can hide it with **Hide the notification**.

<p align="center"><img src="docs/screenshots/android-settings.png" width="260" alt="Settings: Stay reachable in the background, off by default"></p>

## Download and install

* **Android 12 or newer:** install `Handoff-<version>-debug.apk` (allow installs from that source), or run `adb install -r <apk>`.
* **Windows 10 or 11 (x64):** run `Handoff-<version>.exe`. It installs for your user only, with no admin rights, and starts minimized to the tray with Windows (you can turn that off in Settings). Windows Firewall may ask once to allow Handoff on **private** networks: allow it.

### Quick start

1. Pair the headset with each device in Bluetooth settings.
2. Open Handoff on each device, and on Android grant *Nearby devices*.
3. Link: on Windows choose **Link a phone**; on Android choose **My devices → Scan a Handoff code** (or **Show my code**). Check that both screens show the same 6-digit number, then tap **Link**.
4. Tap **Add headset** on each device and pick the headset.
5. Press **Move here** on the device you want to listen on. Android also has a Quick Settings tile, and the Windows tray menu has **Move … here**.

## Platform notes

**Android.** Android has no public API that lets a normal app connect or disconnect a Bluetooth headset. Like [PodSwitch](https://github.com/Felip6499/PodSwitch), Handoff uses hidden framework methods (`BluetoothA2dp.connect/disconnect`, `BluetoothHeadset.disconnect`). A manufacturer or Android update can restrict them. Handoff checks for them, never crashes when they're missing, and shows **Supported**, **Experimental** or **Unsupported on this Android build**. Some manufacturers stop background apps aggressively; if a locked device doesn't respond, set Handoff's battery usage to *Unrestricted*.

**Windows.** Handoff uses the documented Win32 call `BluetoothSetServiceState`. Releasing turns the headset's audio services off for the PC, which also stops Windows from grabbing it back. **Move here** turns them on again. Reconnecting can take a few seconds while Windows reinstalls the audio device.

See [docs/BLUETOOTH_COMPATIBILITY.md](docs/BLUETOOTH_COMPATIBILITY.md).

## Android permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Nearby devices) | List paired headsets, read their state, connect and disconnect them. **Required.** |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to your linked devices on the local network and notice network changes. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Used only if you turn on background mode. |
| `POST_NOTIFICATIONS` | Asked only when you turn on background mode or "Ask" prompts. |
| `RECEIVE_BOOT_COMPLETED` | Restart background mode after a reboot, if it is on. |
| `CAMERA` | Scan a link code. Asked only when you tap **Scan**; you can paste the code instead. |

**Not requested:** location, and `BLUETOOTH_SCAN` (Handoff never searches for new devices).

## What is and isn't verified

| Verified | Still being validated |
|---|---|
| Builds; Android lint clean; 142 unit and integration tests pass | Moving a real headset between physical devices |
| Android 15 emulator: setup, linking, moving (demo headsets), diagnostics, tile | Hidden Android Bluetooth methods on manufacturer builds (Samsung, Pixel, …) |
| Windows 11: the app runs, reads paired headsets and their battery, and links with Android | `BluetoothSetServiceState` release and connect across headsets |
| Linking, encryption, replay protection, refusal of unlinked devices, local-address filtering | Background reachability of locked phones; hotspot mode on real devices |

## Build from source

Requires JDK 17+ and, for Android, an Android SDK with platform 36 (`local.properties` → `sdk.dir=...`).

```bash
./gradlew test assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/Handoff-<version>-debug.apk`.

```bash
./gradlew :desktop:packageExe :desktop:packageMsi
```

The Windows installers are written to `desktop/build/compose/binaries/main/`. To run the Windows app from source, use `./gradlew :desktop:run`.

**Demo mode** uses made-up headsets and names, for screenshots or for trying the UI without hardware:

```bash
./gradlew :desktop:run -Phandoff.dataDir=build/demo -Phandoff.demo=true "-Phandoff.demo.name=Studio PC"
```

On Android (debug builds only), run `adb shell run-as dev.handoff.app.debug touch files/demo`, then restart the app.

```
core/        Pure Kotlin: domain, ownership, protocol, crypto, LAN transport, shared read model.
bluetooth/   Android library: the only code that touches android.bluetooth.
app/         Android app (Compose).
desktop/     Windows app (Compose Desktop): Win32 Bluetooth via JNA, DPAPI identity, tray.
docs/        Architecture, security, Bluetooth compatibility, hardware test plan, screenshots, brand.
```

More: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/HARDWARE_TEST_PLAN.md](docs/HARDWARE_TEST_PLAN.md) · [CONTRIBUTING.md](CONTRIBUTING.md)

## License

MIT, see [LICENSE](LICENSE). Parts of the Bluetooth approach (Android and Windows) are adapted from PodSwitch (MIT); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
