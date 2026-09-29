# Third-party notices

Handoff is released under the [MIT License](LICENSE). It builds on the open-source work below, and every component keeps its own license.

Both apps ship the complete notices, including the full license texts, under **Settings → Open-source licenses**. They are generated from [`core/src/main/resources/dev/handoff/core/NOTICES.txt`](core/src/main/resources/dev/handoff/core/NOTICES.txt).

## Credit: PodSwitch

Handoff's approach to connecting and releasing Bluetooth audio devices was adapted from [PodSwitch](https://github.com/Felip6499/PodSwitch) by Felip6499 (MIT License, reviewed at commit `967e9727cd509777cfd688ef1fe28f9db38bb1ad`).

No PodSwitch source file was copied; the techniques were re-implemented. Because Handoff's Bluetooth layer is derived from that work, PodSwitch's copyright notice and license are reproduced below and in both apps, and the adapted source files credit PodSwitch in their headers.

| PodSwitch | Handoff |
|---|---|
| `android/.../platform/BluetoothConnector.kt`: the A2DP profile proxy via `BluetoothAdapter.getProfileProxy(A2DP)` | `bluetooth/.../ProfileProxyProvider.kt` |
| `android/.../platform/BluetoothConnector.kt`: calling the hidden `BluetoothA2dp.connect(BluetoothDevice)` through reflection | `bluetooth/.../reflection/ReflectionA2dpStrategy.kt`, `HiddenMethodInvoker.kt` (also applied to `disconnect()`) |
| `android/.../platform/BluetoothConnector.kt`: verifying with `getConnectionState(device) == STATE_CONNECTED` and a bounded retry | `AndroidBluetoothAudioController.verifyConnected` and the coordinator's retry policy |
| `android/.../platform/AudioMonitor.kt`: `AudioManager.registerAudioPlaybackCallback` to detect playback start | `app/.../automation/PlaybackWatcher.kt` |
| Foreground service type `connectedDevice` | `app/.../service/HandoffService.kt` |
| `windows/PodSwitch.App/Platform/WindowsBluetoothConnector.cs`: `BluetoothFindFirstDevice` enumeration and `BluetoothSetServiceState` (disable, then enable) to make Windows connect | `desktop/.../bluetooth/Win32Bluetooth.kt`, `WindowsBluetoothAudioController.kt` (disable-only is also used to release) |
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

## Components shipped in the apps

This list is taken from the release runtime classpaths of both apps. Licenses are as declared in each component's published POM.

| Component | License | Copyright | Ships in |
|---|---|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | JetBrains s.r.o. | Android, Windows |
| JetBrains annotations | Apache-2.0 | JetBrains s.r.o. | Android, Windows |
| ZXing core | Apache-2.0 | ZXing authors | Android, Windows |
| JSpecify | Apache-2.0 | The JSpecify Authors | Android, Windows |
| AndroidX (Annotation, Collection, Lifecycle, SavedState, Compose runtime) | Apache-2.0 | The Android Open Source Project | Android, Windows |
| AndroidX (Core, Activity, Navigation, Room, SQLite, DataStore), Jetpack Compose, Material 3, Material Icons | Apache-2.0 | The Android Open Source Project | Android |
| Koin | Apache-2.0 | Kotzilla and Koin contributors | Android |
| zxing-android-embedded | Apache-2.0 | ZXing authors, Journey Mobile | Android |
| Okio | Apache-2.0 | Square, Inc. | Android |
| Stately | Apache-2.0 | Touchlab | Android |
| ListenableFuture (Guava) | Apache-2.0 | The Guava Authors | Android |
| Compose Multiplatform, Material 3, Material Icons | Apache-2.0 | JetBrains s.r.o., The Android Open Source Project | Windows |
| Skiko | Apache-2.0 | JetBrains s.r.o. | Windows |
| Skia (inside Skiko) | BSD-3-Clause | Google Inc. | Windows |
| JetBrains Runtime API | Apache-2.0 | JetBrains s.r.o. | Windows |
| JmDNS | Apache-2.0 | JmDNS contributors | Windows |
| JNA, JNA Platform | Apache-2.0 (dual-licensed with LGPL-2.1+; used under Apache-2.0) | Timothy Wall and JNA contributors | Windows |
| SLF4J API | MIT | QOS.ch | Windows |
| Java runtime built from OpenJDK | GPL-2.0 with the Classpath Exception | Oracle and OpenJDK contributors | Windows (license files in the installed app's `runtime/legal` folder) |

The Handoff logo (`docs/brand`, app icons) is original artwork for this project, under the project's MIT License.
