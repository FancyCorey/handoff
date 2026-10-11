# Interface design

The rules every Handoff screen follows, on Android and Windows. They came out of repeated wireframe and critique rounds; change them the same way (wireframe, critique, fix) before changing the apps.

## The idea: a list you can act on

Handoff answers one question: *where are my headphones, and can they come here?* People open it for a few seconds, tap one button and leave, so Home is a plain list: every pair of headphones with its status and one button, then every linked device. Nothing has to be decoded. (A version built around a picture of the devices, 0.7.0, was tried and dropped after usability rounds: it repeated the status, hid devices in a sideways row and buried the button.)

Each card's status, line and button come from one description built in `:core` (`dev.handoff.core.stage.StageBuilder`), so both apps always agree; the states below are its unit tests.

## Headphones card

Every pair gets the same card: headphones icon → name (up to two lines) and, below it, a **status pill** and the battery → a chevron to the details. Under that, at most one of these, in this order:

1. **Moving:** a four-part step bar (asking, let go, connecting, checking), the current step in words, **Cancel** (outline) and **Details**. The move happens in the card: tapping **Move here** never changes screen.
2. **Just moved:** "✓ Moved here" for four seconds.
3. **Last move failed** (for five minutes): the error title in red, one short reason, and one filled button that fixes it ("Try again", "Link again", "Turn on Bluetooth"…).
4. Otherwise: one optional line, then the button, full width at the bottom of the card.

| State | Status pill | Line | Button |
|---|---|---|---|
| On another device | "On Studio PC" (indigo) | "Studio PC lets go, then they connect here." (first 3 moves only) | **Move here** (filled) |
| Here | "Here" (green) | none | none |
| Here and on another device | "Here and on Tab" (amber) | "Sounds on Tab can interrupt your audio." (amber) | **Use only here** (tonal) |
| On two other devices, *Stay connected* off | "On Studio PC and Tab" | "Both let go, then they connect here." (first 3 moves only) | **Move here** |
| On two other devices, *Stay connected* on | "On Studio PC and Tab" | "The others stay connected too." | **Connect here too** |
| Not sure where they are | "Last seen on Studio PC" (grey) | "Handoff asks Studio PC first." | **Move here** |
| Holder offline | "Studio PC is offline" (amber) | "If they're still on Studio PC, open Handoff there first." | **Move here** |
| Not connected anywhere | "Not connected" (grey) | none | **Move here** |
| Not added on this device | "Not added on this phone" (amber) | none | **Add here** |
| Bluetooth off | no pill: "Last seen on Studio PC" / "Last seen here" as plain text, no battery | none | none (one banner at the top says it) |

Pill colours mean one thing each: indigo = elsewhere and can come here, green = here, amber = needs attention, grey = unknown or idle. Identity labels ("This phone") are never pills.

## Layout

- **Home:** the gradient header (mark, "Handoff", this device's name, Settings; Refresh too on Windows) → notices (Bluetooth off, an old copy installed, update available) → **Your headphones** with **+ Add** on the right → one card per pair, the Quick Settings (Android) or tray (Windows) headphones first → **Your devices** with **+ Link a device** on the right → one list of every device: this one first with "This phone" in grey, then each linked device with a pill (Online green, Offline grey, Other Wi-Fi amber) → the hotspot tip when no linked device is online.
- **First run:** an "Add your headphones" card with **Add headphones** (filled), and a "Link your PC or tablet" row at the end of the device list.
- **Headphones' details:** a header card (icon, name, status pill, battery, the same button as on Home), then options (Quick Settings tile, *Stay connected to other devices*, **Choose different headphones**), then **Remove from this phone** in red.
- **Move details** (Android, from **Details** on a moving card, **What to do** on a failed one, or a notification): an animated picture of the devices with the headphones travelling along an arc, and every step listed in words. When the move ends, the result and its full explanation come first, the steps fold away under **Show the steps**, and **Help and diagnostics** sits under the fix button. This is the only place the picture appears.
- **Tablets and landscape:** screen content is at most 720 dp wide, centred.
- **Help and diagnostics** (from Settings, or under the fix button after a failed move): built by `dev.handoff.core.help.HelpBuilder` from data Handoff already has. Top to bottom:
  1. **What happened:** "Aurora Buds didn't move" and why in one sentence ("Galaxy S22 didn't answer, so they're probably still connected to it."), with the time. Nothing wrong: "✓ No problems found" and the last move.
  2. **What to do:** steps for this failure and the other device's platform (a phone: open Handoff, then the Stay reachable path; a PC: start Handoff, allow network access), numbered only when there are several, with a button on the steps this device can do itself (Turn on, Allow, Open) and **Try again** under them. Steps are actions; facts belong in the checks. A step only appears when the checks support it (the Wi-Fi step only after a different network was seen).
  3. **What Handoff checked:** problems first, each with its evidence ("Last answer: 2 hours ago, on a different Wi-Fi."); the passing checks fold into "4 other checks are fine" while there are problems.
  4. **Share a report**, saying what it includes, then **Technical details** folded away for developers.
  A tip unrelated to any failure (choose Stay reachable) appears only when nothing is wrong. Words are plain: "isn't answering" not "offline", "set up in Bluetooth" not "paired", "near the clock" not "tray".
- **Settings:** identity card at the top (tile, name, key), then grouped cards. Choices with consequences are side-by-side options with one sentence: *When this phone is locked* (Only while open / Stay reachable) and *When something starts playing here* (Off / Ask me / Move here). **Help and diagnostics** lives in Settings.
- **Device label follows the platform:** "This phone", "This tablet", "This PC". Never "This device".

## Words

The full text of every message is in [COPY.md](COPY.md). It was agreed in wording rounds, the same way as the design; change it the same way.

- **One word per thing:** *headphones* (always "them"), *device*, *link*, *Move here*, *let go*. "Headset" only as a type label. The core verb is *move*, never *switch*.
- **No technical words outside Help and diagnostics:** no peer, key, profile, multipoint, service, address, A2DP.
- **Status: 2 to 4 words.** "On Studio PC", "Here and on Tab".
- **One line under the stage, at most about 12 words, and only when it adds something:** the first few moves, a warning, or a choice with consequences. Routine explanations disappear after three successful moves.
- **Errors:** a short title saying what happened, one short reason when it helps, and one button that fixes it. Anything longer goes behind **More**.
- **Settings:** one sentence per choice.
- **Buttons start with a verb and say what happens:** "Move here", "Use only here", "Choose different headphones". Confirmations name both choices ("Unlink" / "Keep"), never "OK".
- **Use the words people see elsewhere:** Android's permission is "Nearby devices", so Handoff says so.
- **Tone:** calm and plain. Errors say what to do next, never blame.
- **American English.**
- **Ready for translation:** sentences are never built from pieces; names are inserted with placeholders; short words leave room for languages that run about 30% longer.

## Buttons

- One filled button per card. Secondary actions are tonal (light indigo fill) or outline.
- 48 dp tall, full width at the bottom of their card, pill-shaped. Text actions (**+ Add**, **Details**) still get a 48 dp touch area.
- The **Move here** icon is the logo's curved arrow, not a generic swap icon.
- "Add" is only for adding headphones to Handoff; joining headphones that stay connected elsewhere is **Connect here too**.

## Tokens

| Token | Light | Dark |
|---|---|---|
| Page background | `#F7F8FB` | `#121318` |
| Card / surface | `#FFFFFF` | `#1B1C24` |
| Stage | `#EEF0F6` | `#23253A` |
| Tile | `#FFFFFF`, outline `#D3D6E2` | `#2F3250`, outline `#4A4E70` |
| Headset dot | `#1D1F27` | `#FFFFFF` |
| Accent (indigo) | `#4F46E5` | `#8B85FF` |
| Accent soft (tonal) | `#E7E5FF` on `#4338CA` text | `#2E2B66` on `#C7C4FF` text |
| Text / secondary | `#1D1F27` / `#5D6275` | `#E8E9F2` / `#A3A7BD` |
| Warning text | `#A14F00` | `#FFB366` |
| Online | `#1E9E61` | `#4FD18B` |

- Corner radius: 24 for cards and the stage, full pill for buttons, chips and segmented options, 14 for device tiles.
- Outline buttons use the accent colour for their border (at least 3:1 against the card).
- Chip icons use the tile colour, so they stay visible in dark mode.

## Type

Headset name 17 sp bold; status 13 sp semibold; explanatory line and secondary text 12 sp; section labels 11 sp uppercase. All text scales with the system font size, and the stage reflows at 1.3×.

## Accessibility

- Status never relies on colour alone: every pill has words, and the step bar is announced as "Step 2 of 4" with the current step in words below it.
- The move details' picture is read as one sentence, e.g. "Aurora Buds. Moving here."; its motion stops when "Remove animations" is on.
- Touch targets are at least 48 dp; contrast follows the token table above (text at least 4.5:1, component edges at least 3:1).
