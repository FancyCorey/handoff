# Contributing to Handoff

Thanks for helping! Handoff controls Bluetooth on people's devices from other devices, so reliability and security come before features.

## Ground rules

1. **No invented Android APIs.** Check every framework call against the Android reference docs or the AOSP source. Treat hidden (non-SDK) APIs as unsupported.
2. **Hidden APIs live only in `:bluetooth`**, behind `BluetoothConnectionStrategy`. The strategies are `internal`, and nothing outside that module may know how a connect happens.
3. **One switching path.** Manual, tile, notification and automatic triggers all call `HandoffCoordinator.moveToThisDevice`.
4. **Every wait is bounded, and every retry is bounded.** Add new timeouts to `HandoffPolicy`.
5. **Never accept unauthenticated network input.** New message types go through `PeerServer.respond` (sender check, freshness, dedupe) and must be added to `ProtocolCodecTest`.
6. **No telemetry, accounts or cloud calls** in local mode.
7. **Don't mark something done until it's verified.** If it needs hardware, say so in the PR and add it to `docs/HARDWARE_TEST_PLAN.md`.

## Development

```bash
./gradlew :core:test            # fast JVM tests: domain, protocol, crypto, sockets
./gradlew test lintDebug        # all unit tests + Android lint
./gradlew assembleDebug         # APK
```

Put domain logic in `:core` with tests that use the fakes in `core/src/test/.../fakes` (`FakeBluetoothAudioController`, `FakePeerTransport`, `FakeOwnershipRepository`, `TestHost`, `SimulatedHeadset`). `TestHost.mesh(...)` builds several fully wired simulated hosts sharing one headset.

## End-to-end check against a real install

`DevicePeerE2ETest` (skipped by default) makes the JVM act as a second Handoff peer:

1. Screenshot the device's *Add device* QR code.
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

Releases are built and signed on the maintainer's machine; no key is ever stored in the repository or on GitHub.

**Keys** live in `%USERPROFILE%\.handoff-release\` and must be backed up somewhere safe and private:

| File | Purpose | If lost |
|---|---|---|
| `handoff-release.jks` + `signing.properties` | Android release signing. Android only installs an update over an app signed with the same key. | Existing Android installs can't update in place; users must uninstall and reinstall. |
| `update-signing.pk8` | Signs `update.json`, which the apps check before offering or installing an update. Its public half is `UpdateKeys` in `:core`. | Apps can't verify new releases until they are updated to a new public key by hand. |

To create them on a new machine (only for a brand-new project; otherwise restore the backup): `keytool -genkeypair -keystore handoff-release.jks -storetype PKCS12 -alias handoff -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Handoff"`, write `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), and run `./gradlew :core:releaseTool -PtoolArgs="keygen,<dir>"` for the update key.

**Cutting a release:**

1. Set the version in `app/build.gradle.kts` (`versionName`, and increase `versionCode`) and `desktop/build.gradle.kts` (`appVersion`).
2. Write the notes in `docs/releases/v<version>.md`.
3. `powershell -ExecutionPolicy Bypass -File tools\release.ps1` builds and signs everything into `build/release/v<version>/`: the APK, the MSI and setup EXE, a portable zip, `update.json`, `update.json.sig` and `SHA256SUMS.txt`.
4. Check the files, then run it again with `-Publish` to tag the version and create the GitHub release (`HANDOFF_GH` can point to a `gh` executable that isn't on the PATH).

The Windows files are not code-signed yet, so SmartScreen shows "Windows protected your PC" for new downloads and Smart App Control blocks them. Code signing (e.g. through the SignPath Foundation's free program for open-source projects) removes both.

**CI:** `.github/workflows/ci.yml` runs the tests and lint and builds the debug APK and MSI on every push and pull request. It needs no secrets.

## Pull requests

* Keep changes focused and include tests.
* Bluetooth-behaviour changes need a hardware report: fill in the matrix row from `docs/HARDWARE_TEST_PLAN.md`.
* Protocol changes must stay backward compatible within `protocolVersion` 1 (only add optional fields), or bump the version and handle both.
* If you adapt third-party code or add a dependency, update `THIRD_PARTY_NOTICES.md` and `core/src/main/resources/dev/handoff/core/NOTICES.txt` in the same PR, and credit the source in the file header.

## Where you can help

* **Test on your devices.** Reports from more phones, PCs and headphones are the most valuable contribution; see the [hardware test plan](docs/HARDWARE_TEST_PLAN.md).
* **Translations.** Some screen text still lives in code and needs moving to `strings.xml` before the apps can be translated. Notification and tile text already can be.
* **Smaller downloads.** Code shrinking (R8) is off for release builds until its rules are checked on real devices; turning it on would make the Android download much smaller.
* **Moving between networks.** Today both devices must share a local network. An optional relay could remove that limit (see [the architecture notes](docs/ARCHITECTURE.md#future-remote-mode)).
