# Bluetooth compatibility

## The core problem

Android offers third-party apps **no public API to connect or disconnect** an A2DP (Bluetooth audio) device that is already bonded.

| API | Visibility | Usable by Handoff? |
|---|---|---|
| `BluetoothAdapter.getBondedDevices()` | public (`BLUETOOTH_CONNECT`) | yes: device list |
| `BluetoothAdapter.getProfileProxy(ctx, l, A2DP)` | public | yes: A2DP proxy |
| `BluetoothA2dp.getConnectionState(device)` / `getConnectedDevices()` | public (`BLUETOOTH_CONNECT`) | yes: observation and verification |
| `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`, `BluetoothAdapter.ACTION_STATE_CHANGED`, `BluetoothDevice.ACTION_BOND_STATE_CHANGED` / `ACL_*` | public broadcasts | yes: change notifications |
| `BluetoothA2dp.connect(BluetoothDevice)` | `@hide @UnsupportedAppUsage` | **only by reflection** |
| `BluetoothA2dp.disconnect(BluetoothDevice)` | `@hide @UnsupportedAppUsage` | **only by reflection** |
| `BluetoothHeadset.disconnect(BluetoothDevice)` (HFP, calls) | `@hide @SystemApi` | **only by reflection**; resolves on the Android 15 emulator |
| `BluetoothLeAudio.disconnect(BluetoothDevice)` | `@hide` | best effort by reflection (Android 13+) |
| `BluetoothA2dp.setConnectionPolicy(...)` | `@SystemApi`, `BLUETOOTH_PRIVILEGED` | no |
| `BluetoothDevice.connect()/disconnect()` | `@SystemApi`, privileged | no |

We verified the hidden method signatures and annotations against the **API 34 framework source** (`sources-34`, `android/bluetooth/BluetoothA2dp.java`):

* Both `connect` and `disconnect` are `@UnsupportedAppUsage` with no `maxTargetSdk`.
* Both are guarded by `@RequiresPermission(BLUETOOTH_CONNECT)`.
* Both perform a **blocking** binder call (`SynchronousResultReceiver`), so Handoff never invokes them on the main thread.

On Android 15 the hidden A2DP and HFP methods resolve at runtime (checked on the Android 15 emulator image). Each Android release can tighten non-SDK access, which is why Handoff probes the methods on every device instead of assuming them; see [Android 16 non-SDK changes](https://developer.android.com/about/versions/16/changes/non-sdk-16).

PodSwitch uses the same hidden `connect()` path. Handoff also uses `disconnect()` for coordinated release.

Note that the Bluetooth app's service may enforce additional permissions on some builds. That can only be confirmed on hardware, so Handoff maps any `SecurityException` to `PERMISSION_DENIED`.

## Strategy chain

`AndroidBluetoothAudioController` checks these preconditions first:

* permission granted
* adapter present and on
* device bonded
* A2DP proxy available
* not already in the target state
* throttle not tripped

It then tries the strategies in order:

1. **PublicApiStrategy.** Succeeds only when the public state already matches the request; otherwise returns `NotApplicable`. If Android ever adds a public API, it goes here and automatically takes precedence.
2. **ReflectionA2dpStrategy** (non-SDK, experimental). Resolves `connect` / `disconnect` once per process and caches the result. A method that doesn't resolve (`NoSuchMethodException`, blocked by the hidden-API policy, wrong return type) is marked `MISSING` and never tried again. Every invocation runs on `Dispatchers.IO` with a 6 s cap. Failure modes map as follows:

   | Outcome | Result |
   |---|---|
   | returned `false` | `REJECTED` |
   | method missing | `UNSUPPORTED` |
   | `SecurityException` | `PERMISSION_DENIED` |
   | other exception | `INTERNAL` |
   | hang | `TIMEOUT` |

3. **FutureOemStrategy.** A placeholder, always unsupported. It's the extension point for OEM SDKs or an opt-in Shizuku mode.

A "requested" result is never treated as success. The coordinator always calls `verifyConnected`, which waits for A2DP `STATE_CONNECTED`. It is broadcast-driven, plus a 400 ms poll for OEMs that drop broadcasts.

## Support levels shown to the user

| Level | Meaning |
|---|---|
| **Supported** | A connect through the reflection strategy was *verified* on this device and OS build. The flag resets when the OS build fingerprint changes. |
| **Experimental** | The hidden methods resolve, but no verified move has happened yet. |
| **Not supported on this device** | No adapter, or hidden `connect()` is missing or blocked. Handoff explains this and offers Android Bluetooth settings. |

## Failure behaviour

* No crash: every reflection path returns a value, and this is unit-tested with hostile stand-in proxies.
* No loops: at most 2 connect attempts per transfer, with a 12-operations-per-minute throttle.
* Clear messages, plus **Open Bluetooth settings** and **Diagnostics** buttons on failure.

## Compatibility

**Systems**

| System | Versions | Status |
|---|---|---|
| Android | 16 | ✅ Works (tested) |
| Android | 12, 13, 14, 15 | ✅ Supported. Handoff checks your device the first time it opens. |
| Android | 11 and older | ❌ Not supported |
| Windows | 11 (64-bit) | ✅ Works (tested) |
| Windows | 10 (64-bit) | ✅ Supported |
| Windows on ARM, macOS, Linux, iPhone, iPad | – | ❌ Not available yet |

**Devices**

| Device | Status |
|---|---|
| Android phones and tablets with Bluetooth | ✅ |
| Windows laptops and desktops with built-in Bluetooth or a USB Bluetooth adapter | ✅ |
| Chromebooks, Android TV, Wear OS watches | ❌ Not supported |

**Headphones**

| Headphones | Status |
|---|---|
| Bluetooth headphones, earbuds and headsets that connect to one device at a time | ✅ Works best. Handoff hands them from one device to the next. |
| Headphones that connect to two devices at once (multipoint) | ✅ Handoff moves the sound without disconnecting the other device. |
| Bluetooth speakers and car audio | ⚠️ Should work, as they connect the same way as headphones, but haven't been tested yet. |
| Headphones that only support Bluetooth LE Audio | ⚠️ Not supported yet. Most LE Audio headphones also support classic Bluetooth, which works. |

Android doesn't give apps an official way to connect headphones, so some phone makers can restrict what Handoff needs. When you first open Handoff, it shows whether your device is **Supported**, **Experimental** (everything is there, but no move has been confirmed on it yet) or **Unsupported**.

Some phone makers close background apps aggressively to save battery. If a locked phone doesn't respond, set Handoff's battery usage to *Unrestricted*.

**Tried Handoff on your device?** Share the report from **Diagnostics** in an [issue](https://github.com/FancyCorey/handoff/issues). Reports from more devices help everyone. The [hardware test plan](HARDWARE_TEST_PLAN.md) lists the scenarios worth trying.

## Windows

| API | Visibility | Use |
|---|---|---|
| `BluetoothFindFirstDevice` / `BluetoothFindNextDevice` | public Win32 (bluetoothapis.h) | paired devices, class of device, link state (`fConnected`) |
| `BluetoothFindFirstRadio` | public Win32 | adapter present / on |
| `BluetoothSetServiceState` | public Win32 | release (disable A2DP sink, HFP, HSP and AVRCP) and connect (disable → enable) |
| `BluetoothEnumerateInstalledServices` | public Win32 | which of those services are currently on, so services that are already off count as released |
| `CM_Get_DevNode_PropertyW` (cfgmgr32) | public Win32 | headset battery level |

Paired devices, link state, enabled services and battery level are read correctly on Windows 11. The release and connect path can be exercised on any PC with `WindowsHeadsetHardwareTest` (see [CONTRIBUTING.md](../CONTRIBUTING.md)). On some systems `BluetoothSetServiceState` may need rights the user lacks, in which case Handoff reports "Windows denied changing Bluetooth services".

## Local network

* NSD needs no location permission.
* Some routers or guest networks isolate clients or drop multicast. Linking can then fail, or devices show as offline. Handoff remembers each device's last working address per network (also across restarts) and each device's inbound address, which covers most such networks. Where devices can't talk to each other at all, a hotspot from one of them works.
* Handoff targets SDK 36. A future target SDK may require Android's upcoming local-network permission; re-check this when raising `targetSdk`.
