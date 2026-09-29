# Hardware test plan

This plan validates a phone, PC and headset combination end to end. Record each run as a row in the matrix below; the Windows release/connect path can also be checked automatically with `WindowsHeadsetHardwareTest` (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Equipment and matrix

Minimum combinations:

| # | Device A | Device B | Headset | Notes |
|---|---|---|---|---|
| 1 | Samsung phone | Samsung tablet | single-point earbuds | same-OEM baseline |
| 2 | Samsung phone | non-Samsung Android (Pixel/OnePlus/Xiaomi/Lenovo) | single-point earbuds | cross-OEM |
| 3 | Android phone | Android tablet (different OEM) | single-point earbuds | phone ↔ tablet |
| 4 | any pair from above | | **multipoint** headphones (e.g. Sony WH-1000XM5) | multipoint safety |
| 5 | any pair from above | | Bluetooth speaker | non-headset A2DP sink |

For **each** row record:

| Field | Value |
|---|---|
| Device A: OEM / model / Android version / security patch | |
| Device B: OEM / model / Android version / security patch | |
| Headset model / firmware | |
| Compatibility shown (A / B) | Supported / Experimental / Unsupported |
| Bluetooth strategy used (Diagnostics → Last connect) | |
| Reflection connect() / disconnect() (A / B) | AVAILABLE / MISSING / BLOCKED |
| Successful transfers / attempts | |
| Path counts | COORDINATED / DIRECT_TAKEOVER / UNCONTESTED |
| Median, average and slowest total handoff time | |
| Median release time and median Bluetooth connect time | |
| Reconnect failures (headset returned to old host) | |
| Crashes (either app) | |
| Bluetooth stack failures (BT restarted, headset needed power cycle) | |
| Failure modes observed (from Diagnostics / exported report) | |

Attach the exported diagnostics report from both devices to every run. Exports are redacted.

## Preparation (each device)

1. Install: `adb install -r app/build/outputs/apk/debug/Handoff-<version>-debug.apk`, or sideload the APK.
2. Pair the headset with the device in **Settings → Connected devices / Bluetooth**.
3. Open Handoff and complete setup:
   * Grant *Nearby devices*.
   * Allow notifications.
   * Name the device, e.g. "Phone" or "Tab".
   * Note the compatibility level shown.
4. Put both devices on the same Wi-Fi network (not a guest network with client isolation).
5. Optional but recommended for locked-device tests: *Settings → Apps → Handoff → Battery → Unrestricted*.

## Test 0: Bluetooth feasibility (single device, no peers)

Run this first: it shows whether the phone lets Handoff control Bluetooth at all. If it fails, the other tests can't pass on this phone.

1. Open Handoff → Diagnostics (wrench icon) → **Bluetooth test (developer)**.
2. Tap **Probe hidden methods** and record connect()/disconnect() availability.
3. With the headset connected to this device, tap **Disconnect**. Expected: `requested via ReflectionA2dpStrategy … → verified DISCONNECTED`.
4. Tap **Connect**. Expected: `requested via ReflectionA2dpStrategy … → verified CONNECTED`, and audio plays through the headset.
5. Repeat Disconnect/Connect 10 times. Record any `FAILED …` lines verbatim.
6. Turn Bluetooth off and tap Connect. Expected: `FAILED BLUETOOTH_OFF`, and no crash.
7. Revoke *Nearby devices* in system settings, return, and tap Connect. Expected: `FAILED PERMISSION_DENIED`, and no crash.

## Test 1: Link the two devices

1. On A: *My devices → Show my code (Add device)*.
2. On B: *My devices → Scan a Handoff code*, then scan.
3. Verify that both screens show the **same 6-digit code**. Tap **Link** on A.
4. Expected: both devices list each other as **Online** on the Home screen within about 10 s.
5. Negative: repeat with **Decline**. Expected: no link on either side.
6. Negative: scan the same QR code again after linking. Expected: "Couldn't link …" (the token is single use).

## Test 2: Map the headset

1. On A: *Add headset* → pick the headset → **New headset** → Map.
2. On B: *Add headset* → pick the headset. Expected: the dialog pre-selects A's headset, marked *Recommended · same Bluetooth address*. Map it.
3. Expected: the headset appears on both Home screens, and *Details → Mapped on* lists both devices.

## Test 3: First handoff

1. Connect the headset to A (Bluetooth settings, or Move here on A). Play music on A.
2. Lock A and put it face down.
3. On B's Home: the card shows **Connected: A**. Press **Move here**.
4. Expected progress on B:
   * "Requesting release from A…"
   * "Released."
   * "Connecting…"
   * "Verifying…"
   * "Connected."

   Then the timings line.
5. Expected afterwards:
   * Audio from B plays on the headset.
   * A's Home shows **Connected: B** when opened.
   * Diagnostics on B show *Path COORDINATED*.

## Test 4: 20-transfer validation (the MVP target)

Neither app may be restarted during this test.

1. Start with the headset on A, and keep both apps running (the service keeps running while locked).
2. Alternate: press **Move here** on B, wait for "Connected." and confirm audio, then press **Move here** on A. Continue until **20 consecutive handoffs** (`A→B`, `B→A`, … ×10).
3. Alternate which device is locked or in a pocket: the *releasing* device should be locked for at least half of the runs.
4. After the run, export Diagnostics from both devices. From *Recent transfers* record:
   * success rate (x/20)
   * median and slowest total time
   * number of DIRECT_TAKEOVER fallbacks, and why (Event log)
   * reconnect failures, crashes, Bluetooth stack failures
5. Pass criteria:
   * 20/20 successes
   * no crash
   * no retry storms (the Event log shows at most 2 CONNECT_ATTEMPT per transfer)

## Test 5: Fallback and failure behaviour

| Scenario | Steps | Expected |
|---|---|---|
| Peer offline | Headset on A; turn A's Wi-Fi off; Move here on B | "A did not respond." → "Trying direct takeover…" → Connected (single-point) |
| Peer app stopped | Force-stop Handoff on A; Move here on B | Direct takeover |
| Headset off | Turn headset off; Move here | 2 attempts, then "The headset didn't connect…" with Retry / Bluetooth settings / Diagnostics |
| Bluetooth off on B | Move here on B | "Bluetooth is off." immediately |
| Repeated taps | Tap Move here 5× quickly | One transfer; the others are ignored |
| Simultaneous requests | A and B both linked to C holding the headset; press Move here on A and B within 1 s | One succeeds; the other shows "Another device is taking the headset right now." |
| Third unmanaged device | Connect headset from a laptop while A holds it | A's Home shows Not connected; Move here on A works (takeover) |
| Unlink | Unlink B on A; Move here on B | Release refused → takeover; A logs PEER_AUTH_FAILED |

## Test 6: Multipoint headset (row 4)

1. Mark the headset **Multipoint** in *Details* on both devices.
2. Connect it to A and B at the same time (normal multipoint behaviour).
3. Expected: the Home card shows *Connected: This device + B*, with a **Move media here** button only on a device that isn't connected.
4. Expected: Handoff never disconnects the other device by itself.

## Test 7: Quick Settings tile and automation

1. Add the *Move here* tile. With the headset on A, tap the tile on B. Expected: the subtitle reads "Moving…", then "Connected here".
2. Long-press the tile. Expected: Handoff opens.
3. *Settings → Automatic switching → Ask*. Start music on B while the headset is on A. Expected: a "Move here" notification appears, and tapping it moves the headset.
4. *Auto*: start music on B. Expected: the headset moves once. Start music on A within 60 s. Expected: **no** automatic move back (anti-ping-pong).

## Test 8: Reboot restore

1. Enable *Start after reboot*, then reboot A.
2. Without opening the app, press Move here on B while the headset is on A. Expected: a coordinated release, once A has booted and joined Wi-Fi.

## Test 9: Windows PC

1. Install `Handoff-<version>.exe` on the PC and pair the headset with it in *Settings → Bluetooth & devices*.
2. In the Windows app, choose *Link a phone*, scan the code with the phone, and confirm the matching 6-digit code on the PC.
3. On the PC, choose *Add headset* and pick the headset, accepting the pre-selected match from the phone.
4. With the headset on the phone, press **Move here** on the PC. Expected: the phone releases, Windows connects, and audio plays on the PC. Allow up to ~15 s the first time while Windows installs the audio endpoints.
5. Press **Move here** on the phone. Expected: the PC releases, the headset disappears from Windows sound devices, and the phone connects.
6. Check that Windows does **not** reconnect the headset on its own afterwards.
7. Use the tray menu's *Move … here* while the window is closed.
8. Repeat Test 4 (20 transfers) between the phone and the PC.

## Test 10: Changing networks

1. Link two devices on Wi-Fi A and move the headset once.
2. Move both devices to Wi-Fi B (or a phone hotspot). Do **not** re-link.
3. Within about 10 s, each device shows the other as **Online**. Move here works.
4. Put only one device on a different network. Expected: the other shows **Offline**, and Move here falls back to direct takeover.
