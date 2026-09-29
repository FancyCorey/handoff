# Hardware test plan

This plan checks a combination of devices and headphones end to end. It's written for anyone who wants to help test Handoff: run the tests that fit your devices and share the results in an [issue](https://github.com/FancyCorey/handoff/issues). Each report helps fill in the [compatibility list](BLUETOOTH_COMPATIBILITY.md#compatibility).

On Windows, the release and connect steps can also be run automatically with `WindowsHeadsetHardwareTest` (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Combinations worth testing

| # | Device A | Device B | Headphones | What it shows |
|---|---|---|---|---|
| 1 | Android phone | Android tablet from the same maker | one device at a time | the basic case |
| 2 | Android phone | Android device from a different maker | one device at a time | differences between makers |
| 3 | Android phone | Windows PC | one device at a time | Android ↔ Windows |
| 4 | any pair above | | connects to two devices at once (multipoint) | Handoff never cuts off the other device |
| 5 | any pair above | | Bluetooth speaker | non-headphone audio devices |

## What to report

| Field | Value |
|---|---|
| Device A: maker, model, Android or Windows version | |
| Device B: maker, model, Android or Windows version | |
| Headphones: model | |
| What the device check showed on each Android device | Supported / Experimental / Not supported |
| Successful moves / attempts | |
| How each move went (from **Diagnostics → Recent transfers**) | COORDINATED / DIRECT_TAKEOVER / UNCONTESTED |
| Typical and slowest move time | |
| Headphones going back to the previous device by themselves | |
| Crashes | |
| Bluetooth problems (had to restart Bluetooth or the headphones) | |

Attach the diagnostics report from both devices (Android: the share button in **Diagnostics**; Windows: **Copy & close**). Reports hide most of each Bluetooth address and never include keys.

## Preparation

1. Install Handoff from the [latest release](https://github.com/FancyCorey/handoff/releases/latest) on each device (see [Getting started](GETTING_STARTED.md)).
2. Pair the headphones with each device in its Bluetooth settings.
3. Complete setup on each Android device, give it a recognisable name, and note what the device check shows.
4. Put the devices on the same Wi-Fi (not a guest network that isolates devices), or use a hotspot from one of them.
5. For tests with a locked phone, turn on **Settings → Stay reachable in the background**, and set the phone's battery usage for Handoff to *Unrestricted*.

## Test 0: Bluetooth control on one Android device

Run this first: it shows whether the phone lets Handoff control Bluetooth at all. If it fails, the other tests can't pass on this phone.

1. Open **Diagnostics** (the bug icon) → **Bluetooth test (developer)**.
2. Tap **Probe hidden methods** and note whether connect and disconnect are available.
3. With the headphones connected to this device, tap **Disconnect**. Expected: the log ends with `verified DISCONNECTED`.
4. Tap **Connect**. Expected: `verified CONNECTED`, and audio plays through the headphones.
5. Repeat Disconnect and Connect 10 times. Note any `FAILED …` lines exactly.
6. Turn Bluetooth off and tap Connect. Expected: `FAILED BLUETOOTH_OFF`, and no crash.
7. Remove the *Nearby devices* permission in the system settings, return and tap Connect. Expected: `FAILED PERMISSION_DENIED`, and no crash.

## Test 1: Link two devices

1. On A: **My devices → Show my code instead** (on Windows: **Link a phone**).
2. On B: **My devices → Scan a Handoff code**, and scan it.
3. Check that both screens show the **same 6-digit number**, then tap **Link** on A.
4. Expected: within about 10 seconds, each device lists the other as **Online**.
5. Repeat, tapping **Decline** instead. Expected: no link on either device.
6. Scan the same code again after linking. Expected: the link is refused, because a code works only once.

## Test 2: Add the headphones

1. On A: **Add headset** → choose the headphones → **New headset** → **Add**.
2. On B: **Add headset** → choose the headphones. Expected: the list says "also on A", and the next step recommends A's headphones (*Recommended · same Bluetooth address*). Tap **Add**.
3. Expected: the headphones appear on both home screens, and their details list both devices under **Added on**.

## Test 3: The first move

1. Connect the headphones to A and play music on A.
2. Lock A and put it face down.
3. On B's home screen, the headphones show **On A**. Tap **Move here**.
4. Expected progress on B: "Asking A to let go…", "A let go.", "Connecting…", "Checking the connection…", "Connected.", then the timings.
5. Expected afterwards: audio from B plays through the headphones, A shows **On B** when opened, and **Diagnostics** on B shows the move as COORDINATED.

## Test 4: Twenty moves in a row

Don't restart either app during this test.

1. Start with the headphones on A.
2. Tap **Move here** on B, wait for "Connected." and check the audio, then tap **Move here** on A. Continue until you've done **20 moves in a row** (10 each way).
3. For at least half the moves, the device giving up the headphones should be locked.
4. Afterwards, note from **Recent transfers** on both devices: how many succeeded, typical and slowest time, and how many fell back to connecting directly (and why, from the event log).
5. It passes with 20 out of 20, no crash, and at most two connect attempts per move.

## Test 5: When things go wrong

| Situation | Steps | Expected |
|---|---|---|
| Other device offline | Headphones on A; turn off A's Wi-Fi; **Move here** on B | "Couldn't reach A." → "Connecting directly instead…" → connected (for headphones that connect to one device at a time) |
| Handoff closed on the other device | Force-stop Handoff on A; **Move here** on B | Connects directly |
| Headphones off | Turn the headphones off; **Move here** | Two attempts, one automatic retry, then "The headset didn't connect" with **Try again**, **Open Bluetooth settings** and **Diagnostics** |
| Bluetooth off on B | **Move here** on B | "Bluetooth is off" straight away |
| Tapping repeatedly | Tap **Move here** 5 times quickly | One move; the other taps are ignored |
| Two devices at once | A and B both linked to C, which has the headphones; tap **Move here** on A and B within a second | One succeeds; the other shows "Another device is taking it" |
| Cancelling | Tap **Move here**, then **Cancel** straight away | "Move cancelled"; the headphones don't connect |
| Leaving the screen | Tap **Move here**, go back to the home screen, then tap **View progress** | The progress screen shows the move as it continues |
| Another device outside Handoff | Connect the headphones from a device without Handoff while A has them | A shows **Not connected**; **Move here** on A still works |
| Unlinked device | Unlink B on A; **Move here** on B | A refuses; B connects directly |

## Test 6: Headphones that connect to two devices at once

1. Turn on **Multipoint headset** in the headphones' options on both devices.
2. Connect the headphones to A and B at the same time.
3. Expected: the home screen shows **On A + B**, and **Move media here** appears only on a device that isn't connected.
4. Expected: Handoff never disconnects the other device by itself.

## Test 7: Quick Settings tile and automatic switching (Android)

1. Add the **Move here** tile. With the headphones on A, tap the tile on B. Expected: the tile shows the move in progress, then that the headphones are connected here.
2. Long-press the tile. Expected: Handoff opens.
3. **Settings → Automatic switching → Ask with a notification**. Start music on B while the headphones are on A. Expected: a notification offers to move them, and tapping it does.
4. **Move automatically**: start music on B. Expected: the headphones move once. Start music on A within 60 seconds. Expected: they do **not** move back automatically.

## Test 8: After a restart

1. On A, turn on **Stay reachable in the background** and **Start after reboot**, then restart A.
2. Without opening Handoff on A, tap **Move here** on B while the headphones are on A. Expected: once A has started and joined Wi-Fi, A lets go and B connects.
3. Close and reopen Handoff on B. Expected: A appears **Online** again without re-linking.

## Test 9: Windows PC

1. Install Handoff on the PC and pair the headphones in **Settings → Bluetooth & devices**.
2. Link the phone (**Link a phone** on the PC) and add the headphones on the PC, accepting the match from the phone.
3. With the headphones on the phone, tap **Move here** on the PC. Expected: the phone lets go, Windows connects and audio plays on the PC. The first time, Windows can take up to about 15 seconds to set up the audio device.
4. Tap **Move here** on the phone. Expected: the PC lets go, the headphones disappear from Windows' sound devices, and the phone connects.
5. Check that Windows does **not** reconnect the headphones by itself afterwards.
6. Use the tray menu's **Move … here** with the window closed.
7. Open Handoff a second time from the Start menu. Expected: the existing window comes forward; no second copy starts.
8. Repeat Test 4 between the phone and the PC.

## Test 10: Changing networks

1. Link two devices on Wi-Fi network 1 and move the headphones once.
2. Move both devices to Wi-Fi network 2. Don't re-link.
3. Expected: within about 10 seconds each shows the other as **Online**, and **Move here** works.
4. Put only one device on a different network. Expected: the other shows **On another network** or **Offline**, and **Move here** falls back to connecting directly.

## Test 11: Hotspot

1. Turn off Wi-Fi on both devices. Turn on the hotspot on A and connect B to it.
2. Expected: each shows the other as **Online** within about 10 seconds, and **Move here** works in both directions without mobile data.

## Test 12: Updates

1. **Settings → Check for updates** (Android) or **Check now** (Windows). Expected: "You're up to date", or a new version with its release notes.
2. When a newer version is available, tap **Download and install**. Expected: after the download, Android's installer (or Windows Installer) asks you to confirm, and the new version keeps your links and headphones.
