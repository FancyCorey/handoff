# Third-party notices

## PodSwitch

Portions of the Android Bluetooth audio connection implementation were adapted from PodSwitch by Felip6499:

https://github.com/Felip6499/PodSwitch (reviewed at commit `967e9727cd509777cfd688ef1fe28f9db38bb1ad`)

PodSwitch is licensed under the MIT License.

**What was adapted.** No PodSwitch source file was copied verbatim. The following techniques were adapted and re-implemented:

| PodSwitch (`android/app/src/main/java/com/podswitch/...`) | Handoff |
|---|---|
| `platform/BluetoothConnector.kt`: getting the A2DP profile proxy with `BluetoothAdapter.getProfileProxy(A2DP)` | `bluetooth/.../ProfileProxyProvider.kt` |
| `platform/BluetoothConnector.kt`: calling the hidden `BluetoothA2dp.connect(BluetoothDevice)` via `proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)` | `bluetooth/.../reflection/ReflectionA2dpStrategy.kt`, `HiddenMethodInvoker.kt`. Also applied to `disconnect()`. |
| `platform/BluetoothConnector.kt`: verifying by polling `getConnectionState(device) == STATE_CONNECTED`, with bounded retry (2 attempts) | `AndroidBluetoothAudioController.verifyConnected` plus the coordinator's retry policy |
| `platform/AudioMonitor.kt`: `AudioManager.registerAudioPlaybackCallback` for playback-start detection | `app/.../automation/PlaybackWatcher.kt` |
| Foreground service type `connectedDevice` | `app/.../service/HandoffService.kt` |
| `windows/PodSwitch.App/Platform/WindowsBluetoothConnector.cs`: enumerating paired devices with `BluetoothFindFirstDevice` and toggling audio services with `BluetoothSetServiceState` (disable, then enable) to make Windows connect | `desktop/.../bluetooth/Win32Bluetooth.kt`, `WindowsBluetoothAudioController.kt` (also used, disable-only, to release) |
| `windows/PodSwitch.App/Platform/Autostart.cs`: start at login via the per-user `Run` registry key | `desktop/.../Autostart.kt` |

```
MIT License

Copyright (c) 2026 Felip6499

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries distributed in the APK and the Windows app

All of the following are licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0):

| Library | Copyright |
|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, Room, DataStore) and Jetpack Compose / Material 3 | The Android Open Source Project |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | JetBrains s.r.o. and Kotlin Programming Language contributors |
| Koin | Kotzilla and Koin contributors |
| ZXing (`com.google.zxing:core`) | ZXing authors |
| zxing-android-embedded (`com.journeyapps:zxing-android-embedded`) | ZXing authors, Journey Mobile |
| Compose Multiplatform (Windows app UI) | JetBrains s.r.o. and contributors |
| JmDNS (`org.jmdns:jmdns`, Windows mDNS discovery) | JmDNS contributors |
| JNA and JNA Platform (`net.java.dev.jna`, Windows API access) — dual-licensed LGPL 2.1 / Apache 2.0; used under Apache 2.0 | Timothy Wall and JNA contributors |

The Windows app bundles a Java runtime built from OpenJDK (GPL v2 with the Classpath Exception).

The Handoff logo (docs/brand, app icons) is original artwork for this project, under the project's MIT license. In-app icons come from Material Icons (Apache License 2.0, Google).
