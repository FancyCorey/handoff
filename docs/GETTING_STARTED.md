# Getting started

## Requirements

| | |
|---|---|
| **Android** | Android 12 (API 31) or newer |
| **Windows** | Windows 10 or 11, x64, with Bluetooth |
| **Headphones** | Any Bluetooth headset or headphones, already paired with each device |
| **Network** | The devices share a local network (Wi-Fi, Ethernet or a hotspot) when the headset moves |

## Install

* **Android:** install the APK from the release page. Android asks once to allow installs from that source.
* **Windows:** run `Handoff-<version>.exe`. It installs for the current user without admin rights, adds a Start-menu entry and starts minimized to the tray with Windows (this can be turned off in Settings). When Windows Firewall asks, allow Handoff on **private** networks.

## Set up

1. **Pair the headphones** with each device in its normal Bluetooth settings. Handoff never pairs, unpairs or scans for devices.
2. **Open Handoff** on each device. On Android, allow *Nearby devices*, which is the only permission setup asks for.
3. **Link the devices** once:
   * Windows: **Link a phone** shows a one-time code.
   * Android: **My devices → Scan a Handoff code**, or **Show my code** to be scanned.

   Both screens then show the same 6-digit number. Approving it completes the link. The code expires after 5 minutes and works once.
4. **Add the headset** on each device with **Add headset**. When another device already knows the headset, Handoff pre-selects it.

From then on, **Move here** moves the headset. It is also on the Android Quick Settings tile and in the Windows tray menu.

## Networks

A link belongs to the two devices, not to a network. It keeps working at home, at work and on a hotspot, with nothing to redo.

When the headset moves, both devices must be on the same local network. After a network change they find each other again automatically, and on a network they have used before they reconnect straight to the last working address.

**No shared Wi-Fi?** Turn on the hotspot on one device and join it from the other. Handoff works over that local network by itself; mobile data isn't needed and nothing goes over the internet. When no linked device can be reached, Handoff offers a shortcut to the hotspot settings.

Some guest, hotel and campus networks block devices from talking to each other. A hotspot works there too.

## Background mode (Android)

By default, Handoff runs while it is open and shows no permanent notification. A device can hand the headset over while Handoff is open on it.

**Settings → Stay reachable in the background** keeps Handoff reachable while the device is locked. Android requires a silent, permanent notification for this; **Hide the notification** opens its setting. Some manufacturers stop background apps aggressively, so if a locked device doesn't respond, set Handoff's battery usage to *Unrestricted*.

## Automatic switching (Android, optional)

**Settings → Automatic switching** can react when audio starts playing on a device while the headset is elsewhere: either **Ask with a notification** or **Move automatically**. It needs background mode.

## Android permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Nearby devices) | List paired headsets, read their state, connect and disconnect them. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Reach linked devices on the local network and follow network changes. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Background mode only. |
| `POST_NOTIFICATIONS` | Asked only when background mode or "Ask" prompts are turned on. |
| `RECEIVE_BOOT_COMPLETED` | Restart background mode after a reboot, when it is on. |
| `CAMERA` | Scan a link code; asked only when scanning. A code can also be pasted. |

Handoff never requests location or `BLUETOOTH_SCAN`.

## When a move doesn't succeed

Handoff retries once automatically. If the move still doesn't succeed, it says what happened and what to do next:

| Message | Meaning |
|---|---|
| *Couldn't reach …* / *On another network* | The other device is off, asleep, on a different network or not running Handoff. |
| *The other device couldn't let go* | Its Bluetooth refused to disconnect; releasing it there usually works. |
| *The headset didn't connect* | The headset is off, out of range, or held by a device outside Handoff. |
| *Bluetooth permission needed* | Allow *Nearby devices* in the app settings (the button opens them). |

**Diagnostics** (the bug icon) shows the Bluetooth details for this device and exports a report with Bluetooth addresses and keys removed.

## How Handoff controls Bluetooth

* **Android** has no public API for an app to connect or disconnect a Bluetooth headset. Handoff uses framework methods that Android keeps internal (`BluetoothA2dp.connect/disconnect`, `BluetoothHeadset.disconnect`). It checks for them on each device and labels the device **Supported**, **Experimental** or **Unsupported on this Android build**. See [BLUETOOTH_COMPATIBILITY.md](BLUETOOTH_COMPATIBILITY.md).
* **Windows** uses the documented `BluetoothSetServiceState` API. Releasing turns the headset's audio services off for the PC, which also stops Windows from reconnecting on its own, and **Move here** turns them back on. While a headset is handed away it doesn't appear as a Windows audio device; **Move here** or **Restore Windows audio** brings it back.
