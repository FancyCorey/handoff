# Contributing to Handoff

Thanks for helping! Handoff controls Bluetooth on people's devices from other devices, so reliability and security come before features.

## Ground rules

1. **No invented Android APIs.** Check every framework call against the Android reference docs or the AOSP source. Treat hidden (non-SDK) APIs as unsupported.
2. **Hidden APIs live only in `:bluetooth`**, behind `BluetoothConnectionStrategy`. The strategies are `internal`, and nothing outside that module may know how a connect happens.
3. **One switching path.** Manual, tile, notification and automatic triggers all call `HandoffCoordinator.moveToThisDevice`.
4. **Every wait is bounded, and every retry is bounded.** Add new timeouts to `HandoffPolicy`.
5. **Never accept unauthenticated network input.** New message types go through `PeerServer.respond` (sender check, freshness, dedupe) and must be added to `ProtocolCodecTest`.
6. **No telemetry, analytics, accounts or cloud calls** in Handoff's own code. The only third-party network code is the ad SDK in the Google Play edition, confined to `app/src/play`. Nothing from Handoff (headsets, devices, peers, addresses, transfers, diagnostics) is ever passed to it, and no feature may depend on it.
7. **Both editions keep the same features.** Only update delivery and the Play banner differ, and they live in `app/src/github` and `app/src/play`. Everything else goes in `app/src/main`.
8. **Don't mark something done until it's verified.** If it needs hardware, say so in the PR and add it to `docs/HARDWARE_TEST_PLAN.md`.

## Development

```bash
./gradlew :core:test            # fast JVM tests: domain, protocol, crypto, sockets
./gradlew test                  # all unit tests
./gradlew :app:lintGithubDebug :app:lintPlayDebug :bluetooth:lintDebug   # Android lint
./gradlew :app:assembleGithubDebug     # GitHub edition APK (no ads, GitHub updater)
./gradlew :app:assemblePlayDebug       # Google Play edition APK (Google's test ads)
./gradlew :app:verifyEditions          # checks that keep the two editions apart
```

The Android app has two editions built from the same code, the **GitHub edition** and the **Google Play edition**. See [docs/PLAY_STORE.md](docs/PLAY_STORE.md#two-editions-one-app).

Put domain logic in `:core` with tests that use the fakes in `core/src/test/.../fakes` (`FakeBluetoothAudioController`, `FakePeerTransport`, `FakeOwnershipRepository`, `TestHost`, `SimulatedHeadset`). `TestHost.mesh(...)` builds several fully wired simulated hosts sharing one headset.

## End-to-end check against a real install

`DevicePeerE2ETest` (skipped by default) makes the JVM act as a second Handoff peer:

1. On the device, open **My devices → Show my code instead** and take a screenshot of the code.
2. Run `adb forward tcp:47474 tcp:47474`.
3. Run `./gradlew :core:test --tests '*DevicePeerE2ETest*' -Dhandoff.e2e.qr=shot.png -Dhandoff.e2e.out=out.txt`.
4. Approve on the device when the code in `out.txt` matches.

While the port forward is active, the device's refreshes to the JVM peer loop back to itself and are logged as `PEER_AUTH_FAILED` (expected). Unlink "JVM test peer" afterwards.

## Real headset on Windows

`WindowsHeadsetHardwareTest` (skipped by default) runs Handoff's own release and connect path on the PC running the tests. The headset must be on, in range and not playing on another device:

```bash
./gradlew :desktop:test --tests '*WindowsHeadsetHardwareTest*' -Dhandoff.hw.headset="<headset name>" -Dhandoff.hw.out=hw.txt
```

It performs three release→connect cycles, records timings and battery in `hw.txt`, and leaves the headset connected to the PC.

## Demo mode

Demo mode swaps in made-up headsets ("Aurora Buds", "Studio Headphones") and never touches the real Bluetooth stack. The README screenshots are taken with it, so no personal device names or addresses appear.

* Windows: `./gradlew :desktop:run -Phandoff.dataDir=build/demo -Phandoff.demo=true "-Phandoff.demo.name=Studio PC" -Phandoff.demo.connected=0A:DE:40:00:00:01` (`-Phandoff.port=<port>` if 47474 is taken).
* Android (debug builds only): `adb shell run-as dev.handoff.app.debug touch files/demo`, then restart the app. Each line of that file can list a demo headset address that starts out connected, and a line `connect-ms=8000` makes demo connects slow enough to try leaving, reopening and cancelling a move.

## Releases

Releases are built and signed on the maintainer's machine; no key is ever stored in the repository or on GitHub. The keys, versioning, the GitHub and Google Play steps and CI are described in [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md).

## Pull requests

* Keep changes focused and include tests.
* Bluetooth-behaviour changes need a hardware report: fill in the matrix row from `docs/HARDWARE_TEST_PLAN.md`.
* Protocol changes must stay backward compatible within `protocolVersion` 1 (only add optional fields), or bump the version and handle both.
* If you adapt third-party code or add a dependency, update `THIRD_PARTY_NOTICES.md` and `core/src/main/resources/dev/handoff/core/NOTICES.txt` in the same PR, and credit the source in the file header. A dependency used only by the Google Play edition goes in the Play section of `THIRD_PARTY_NOTICES.md` and in `app/src/play/.../edition/Edition.kt` instead.

## Where you can help

* **Test on your devices.** Reports from more phones, PCs and headphones are the most valuable contribution; see the [hardware test plan](docs/HARDWARE_TEST_PLAN.md).
* **Translations.** Some screen text still lives in code and needs moving to `strings.xml` before the apps can be translated. Notification and tile text already can be.
* **Smaller downloads.** Code shrinking (R8) is off for release builds until its rules are checked on real devices; turning it on would make the Android download much smaller.
* **Moving between networks.** Today both devices must share a local network. An optional relay could remove that limit (see [the architecture notes](docs/ARCHITECTURE.md#future-remote-mode)).
