# Interface design

The rules every Handoff screen follows, on Android and Windows. They came out of repeated wireframe and critique rounds; change them the same way (wireframe, critique, fix) before changing the apps.

## The idea: the stage

Handoff answers one question: *where is my sound, and can it come here?* Every headset is shown on a **stage**: your devices as tiles in a row, the headset as a dot above the device that has it, and an arc (the logo's arrow) towards this device when it can move here.

```text
   ●  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ▸ ◌
  [Studio PC]              [This phone]
```

The stage appears on Home (full for the main headset, compact for others), on the move screen (animated) and at the top of the headset's details.

Both apps draw it from one description built in `:core` (`dev.handoff.core.stage.StageBuilder`), so they always agree; the states below are its unit tests. Debug builds of the Android app include a gallery of every state, for checking changes against this page: `adb shell am start -n dev.handoff.app.debug/dev.handoff.app.StageGalleryActivity`.

## Stage states

| State | Picture | Status | Line under the stage | Action |
|---|---|---|---|---|
| On another device | Dot over that device; dashed arc to an empty spot over this device | "On Studio PC" (indigo) | "Studio PC lets go, then they connect here." (first 3 moves only) | **Move here** (primary) |
| Here only | Compact stage (short row): dot over this device | "Here" | none | none |
| Here and on another device | A dot over each holder | "Here and on Tab" (amber) | "Sounds on Tab can interrupt your audio." | **Use only here** (tonal) |
| On two other devices, *Stay connected* off | Dots over both; two dashed arcs converging on this device | "On Studio PC and Tab" | "Both let go, then they connect here." (first 3 moves only) | **Move here** |
| On two other devices, *Stay connected* on | Dots over both; a ⊕ on this device's spot; no arcs | "On Studio PC and Tab" | "The others stay connected too." | **Connect here too** |
| Not sure where they are | Dashed "?" dot over the device that had them last | "Last seen on Studio PC" | "Handoff asks Studio PC first." | **Move here** |
| Holder offline | Dashed tile labelled "Offline", "?" dot over it | "Studio PC is offline" (amber) | "Connects directly instead." | **Move here** |
| Bluetooth off | Whole stage dimmed; no battery | "Bluetooth is off" (amber) | none | **Turn on Bluetooth** (primary) |
| Moving | Solid arc drawing towards this device; the dot travels along it | "Moving here" | The current step ("Studio PC let go") | **Cancel** (outline) |
| Four or more devices | Holder(s) and this device always shown; the rest fold into a "+N" tile | as above | as above | as above |

Rules:

- **Arcs:** dashed indigo with an arrowhead = it can move here; solid indigo = moving now; no arc = nothing to do. An arc always ends on the empty spot above this device: one destination.
- **Dashed shapes mean three different things, always drawn differently:** dashed line = possible move; dashed tile = unreachable device; dashed dot with "?" = location unknown. Never show a solid dot where Handoff can't know the headset is.
- **Tiles are not buttons.** The only action is the button under the stage.
- **Device label follows the platform:** "This phone", "This tablet", "This PC". Never "This device".

## Layout

- **Headset card:** headset name (title) → status line → stage → one explanatory line → one button.
- **Home:** slim header (mark, "Handoff", this device's name as a chip, Settings icon). The main headset (the Quick Settings one, else the first) gets the full stage; others get compact cards with a small stage picture, name (may wrap to two lines), status, and a tonal **Move here** when it's elsewhere. Then **Your devices** as chips, then tips.
- **First run:** one empty stage in dashed outline with the two steps: **Add headset** (primary) and **Link a device** (outline).
- **Device chips:** name and a dot. Words only for exceptions ("Other Wi-Fi", "Offline"); "Online" is the dot, and screen readers announce it. Chips scroll sideways with a fade at the edge.
- **Settings:** identity card at the top (tile, name, key), then grouped cards. Choices with consequences are segmented controls with one explanatory sentence: *When this phone is locked* (Only while open / Stay reachable, mentioning the notification) and *Automatic switching* (Off / Ask / Automatic). Diagnostics lives in Settings under **Help and diagnostics**, not in the Home header.

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

- One filled (primary) button per screen. Secondary actions are tonal (light indigo fill) or outline.
- 48 dp tall on phones (40 dp with a 48 dp touch area inside compact cards), pill-shaped.
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

- The stage is read as one sentence, e.g. "Aurora Buds is on Studio PC, battery 80 percent. Move here is available." Tiles and the compact cards' small pictures are hidden from screen readers.
- Status never relies on colour alone: warnings have words, and exceptions on chips have words.
- Motion (arc drawing, the dot travelling) stops when "Remove animations" is on; the step list always shows progress in words.
- Touch targets are at least 48 dp; contrast follows the token table above (text at least 4.5:1, component edges at least 3:1).
