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

We did not verify API 35/36 source in this environment. Treat newer releases as unverified until hardware results say otherwise; see [Android 16 non-SDK changes](https://developer.android.com/about/versions/16/changes/non-sdk-16).

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
| **Unsupported on this Android build** | No adapter, or hidden `connect()` is missing or blocked. Handoff explains this and offers Android Bluetooth settings. |

## Failure behaviour

* No crash: every reflection path returns a value, and this is unit-tested with hostile stand-in proxies.
* No loops: at most 2 connect attempts per transfer, with a 12-operations-per-minute throttle.
* Clear messages, plus **Open Bluetooth settings** and **Diagnostics** buttons on failure.

## Known and expected OEM variation

To be filled from hardware runs; see [HARDWARE_TEST_PLAN.md](HARDWARE_TEST_PLAN.md).

| OEM / skin | Android | Hidden connect | Hidden disconnect | Notes |
|---|---|---|---|---|
| Samsung Galaxy Tab (SM-X520) ← Galaxy S22 Ultra, OnePlus Bullets Wireless Z2 | 16 (API 36) | AVAILABLE, connect *accepted* | AVAILABLE | **0.1.0 failed:** A2DP released in 383 ms, but the headset stayed attached through HFP and refused the tablet (2× VERIFY_TIMEOUT). Fixed in the next build: release now drops HFP and LE Audio too, and verifies that all profiles are down. Needs a retest. |
| Google emulator (sdk_gphone64_x86_64) | 15 (API 35) | AVAILABLE (resolves) | AVAILABLE (resolves); HFP disconnect() also resolves | App targets SDK 36. Resolution only; no headset was bonded, so no call was made. |

What we expect, but have **not** verified:

* Stock Pixel and Samsung One UI expose both methods to apps targeting SDK 36.
* Some OEM stacks auto-reconnect the previous host after a release. Handoff's retry path aborts if it sees that, rather than fighting it.
* Aggressive OEM battery managers (Xiaomi/HyperOS, Huawei, some OnePlus/Oppo/Vivo builds) may freeze the service. The user can set battery usage to *Unrestricted*.

## Windows

| API | Visibility | Use |
|---|---|---|
| `BluetoothFindFirstDevice` / `BluetoothFindNextDevice` | public Win32 (bluetoothapis.h) | paired devices, class of device, link state (`fConnected`) |
| `BluetoothFindFirstRadio` | public Win32 | adapter present / on |
| `BluetoothSetServiceState` | public Win32 | release (disable A2DP sink, HFP, HSP) and connect (disable → enable) |

We verified these on a Windows 11 PC with a Realtek adapter: enumeration read both paired headsets correctly (class `0x240404`, names, link state). **Release and connect have not yet been exercised on a real headset.** On some systems `BluetoothSetServiceState` may need rights the user lacks, in which case Handoff reports "Windows denied changing Bluetooth services".

## Local network

* NSD needs no location permission.
* Some routers or guest networks isolate clients or drop multicast. Linking then fails, or peers show offline. To mitigate this, Handoff remembers the last working address and each peer's inbound address.
* Handoff targets SDK 36. A future target SDK may require Android's upcoming local-network permission; re-check this when raising `targetSdk`.
