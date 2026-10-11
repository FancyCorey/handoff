# Interface text

Every message people see in Handoff, as agreed in the wording review (see [DESIGN.md](DESIGN.md#words) for the rules). English (US) only for now; the apps will read these from one shared, translatable catalogue.

`{x}` is the name of another device; `{me}` is "This phone", "This tablet" or "This PC". Text in [brackets] is a button.


## Words

| Where | Text | Was |
|---|---|---|
| Everywhere | headphones (and "them") | headset / headphones (mixed) |
| Everywhere | This phone / This tablet / This PC | This device |
| Everywhere | connect once in Bluetooth settings | pair / paired |

## Stage status

| Where | Text | Was |
|---|---|---|
| Headphones on one other device | On {x} | On Studio PC |
| Connected here | Here | Connected here |
| Here and elsewhere | Here and on {x} | Here and on Tab |
| Unknown | Last seen on {x} | Last seen on another device / Not here · last seen on another device |
| Holder offline | {x} is offline | Last seen on Studio PC (offline) |
| Nobody has them | Not connected | Not connected |
| Bluetooth off | Bluetooth is off | Bluetooth unavailable / Bluetooth is off |
| Not added here | Not added on {me} | Not set up on this device / Not mapped on this device |
| Moving | Moving here | Moving… |

## Stage line

| Where | Text | Was |
|---|---|---|
| Can move here (first 3 moves only) | {x} lets go, then they connect here. | Studio PC lets go first, then it connects here. |
| Here and elsewhere | Sounds on {x} can interrupt your audio. | Also connected to Tab. A sound there, like a notification or a video, can interrupt your audio here. |
| On two others, Stay connected off (first 3 moves only) | Both let go, then they connect here. | Both let go first, then it connects here. |
| Stay connected on, one other | {x} stays connected too. | (new) |
| Stay connected on, two others | The others stay connected too. | This device joins. Studio PC and Tab stay connected. |
| Unknown | Handoff asks {x} first. | Handoff asks Studio PC, or connects directly if it doesn't answer. |
| Holder offline | If they're still on {x}, open Handoff there first. | Studio PC can't be reached, so this device connects directly. |
| Bluetooth off | (none) | Turn Bluetooth on to see and move your headphones. |

## Buttons

| Where | Text | Was |
|---|---|---|
| Stage | Move here | Move here |
| Stage, multipoint join | Connect here too | Move media here / Add this device |
| Stage, here and elsewhere | Use only here | Use only here |
| Stage, Bluetooth off | Turn on Bluetooth | Turn on Bluetooth |
| Moving | Cancel | Cancel |
| Headphones not added here | Add here | Set up on this device / Choose this headset on this PC |
| Details | Choose different headphones | Choose a different paired device |
| Details | Remove from {me} | Forget on this device / Forget on this PC |

## Move steps

| Where | Text | Was |
|---|---|---|
| Step | Asking {x} to let go… | Asking Studio PC to let go… |
| Step | {x} let go | Studio PC let go. |
| Step | {x} didn't have them | Studio PC wasn't connected to it. |
| Step | {x} didn't answer | Couldn't reach Studio PC. |
| Step | {x} didn't answer | Studio PC didn't answer in time. |
| Step | {x} couldn't let go | Studio PC couldn't let go: <detail>. |
| Step | {x} is moving them elsewhere | Studio PC is handing it to another device. |
| Step | Connecting directly… | Connecting directly instead… |
| Step | Connecting… | Connecting… |
| Step | Trying again… | Trying again… / That didn't work. Trying once more… |
| Step | Making sure it worked… | Checking the connection… |
| Step | Connected | Connected. |
| Result | Moved here | Connected. Handed over cleanly. |
| Result | Moved here | Connected directly. / Connected. |
| Result | Connected here too | Connected alongside the other devices. |
| Result | Already here | Already connected here. |
| Result | Already moving | A move is already in progress. |
| Result | Cancelled | Move cancelled. |
| Help after cancel | If {x} already let go, tap Move here where you want them. | If the other device had already let go, the headset is free: press Move here on the device you want. |
| Moving screen | You can leave. The move keeps going. | You can leave this screen; the move keeps going. Tap the headset on the home screen to come back. |
| Timings | Let go / Connect / Total | Release / Connect / Total |

## Errors

| Where | Text | Was |
|---|---|---|
| Other device unreachable | {x} didn't answer  +  "They're probably still connected to {x}."  +  "Open Handoff on {x}, or choose Stay reachable there so it can answer while locked. Both need the same Wi-Fi. Or disconnect them in its Bluetooth settings."  +  [Try again]  ·  Help and diagnostics | Couldn't reach Studio PC  +  "The headset is probably still connected to Studio PC. Open Handoff there (or turn on Stay reachable in the background on it), and make sure both devices are on the same Wi-Fi. Or disconnect the headset there yourself." |
| Other device refused | {x} couldn't let go  +  "Disconnect them on {x}, then try again."  +  [Try again] | The other device couldn't let go  +  "Disconnect the headset in that device's Bluetooth settings, then try again." |
| Link only on one side | {x} doesn't recognize {me}  +  [Link again]  ·  More: "Unlink {x}, then link again. Remove any older copy of Handoff first." | Studio PC doesn't recognise this device  +  "The link between the two devices only exists on this side, so it can't ask Studio PC to let go of the headset. In My devices, unlink it and link the two devices again. If an older copy of Handoff is installed on either device, uninstall it first." |
| Headphones didn't connect | The headphones didn't connect  +  "Check they're on, nearby and out of the case."  +  [Try again] | The headset didn't connect  +  "Make sure it's switched on and nearby, and not connected to a phone or computer that doesn't run Handoff. Some earbuds only connect when they're out of the case." |
| Not added here | Not added on {me}  +  [Add here] | Not set up on this device  +  "Add this headset on this device first." |
| Bluetooth off | Bluetooth is off  +  [Turn on Bluetooth] | Bluetooth is off  +  "Turn Bluetooth on and try again." |
| No permission | Handoff needs Bluetooth access  +  "Allow Nearby devices."  +  [Allow] | Bluetooth permission needed  +  "Allow Handoff to use Nearby devices (Bluetooth) in its app settings." |
| Unsupported phone | {me} can't move headphones  +  [Open Bluetooth settings] | This device can't switch headsets  +  "This system doesn't let apps switch Bluetooth audio. Connect the headset from Bluetooth settings instead." |
| Not paired here | Not connected to {me} yet  +  "Connect them once in Bluetooth settings."  +  [Open Bluetooth settings] | Not paired with this device  +  "Pair the headset in Bluetooth settings first, then choose it again in Handoff." |
| Someone else moving them | {x} is moving them  +  [Try again] | Another device is taking it  +  "Wait for it to finish, then try again." |
| Too many tries | Too many tries. Wait a minute.  +  [Try again] (enabled after a minute) | Too many attempts  +  "Wait a minute, then try again." |
| Unknown error | Something went wrong  +  [Try again]  ·  More: [Share a report] | Something went wrong  +  "Try again. If it keeps happening, share the Diagnostics report." |

## Home

| Where | Text | Was |
|---|---|---|
| Sections | Your headphones  [+ Add]  /  Your devices  [+ Link a device] | Your audio [Add headset] / Your devices [Manage] |
| First run | Add your headphones  +  "Choose them from the ones connected to this phone."  +  [Add headphones] | No headset yet  +  "Choose headphones that are already paired with this device in Android Bluetooth settings." |
| First run, no links | Link your PC or tablet (row in the device list; on Windows: Link your phone or tablet) | Link your other phone, tablet or Windows PC so they can hand the headset to each other. |
| Device list | This phone (grey, not a pill) / Online / Offline / Other Wi-Fi | This device / Online / Offline / On another network |
| Moving | Cancel  ·  Details | View progress  ·  Cancel |
| Just moved | ✓ Moved here | (none) |
| Failed move | the error title in red, one reason, one fix button | Couldn't reach Studio PC  +  long help  ·  Details |
| Bluetooth off | Bluetooth is off  +  "Handoff can't see your headphones."  +  [Turn on]; each card: Last seen on {x} / Last seen here | Bluetooth unavailable on every card |
| Hotspot tip | No shared Wi-Fi?  +  "Turn on the hotspot on one device and join it from the other."  +  [Hotspot settings] | No shared Wi-Fi?  +  "Turn on the hotspot on one device and join it from the other. Handoff finds your devices on that local network by itself; mobile data is not needed and nothing goes over the internet." |
| Update banner | Update available  +  [Update] | Handoff 0.6.3 is available  +  [Update] |
| Older copy warning | An old copy of Handoff is installed  +  "It can confuse your other devices."  +  [Remove it] | Another copy of Handoff is installed  +  "An older test version of Handoff is also on this device. Your other devices can end up linked to that copy instead of this one. Uninstall it, then link your devices again here." |

## Add headphones

| Where | Text | Was |
|---|---|---|
| Title | Add headphones | Add headset |
| Intro | Choose them from the list. Add them on each of your devices. | Pick the headset as it appears on this device. It must already be paired in Android Bluetooth settings. Add the same headset on each of your devices. |
| List title | On this phone | Paired audio devices |
| Empty | Nothing here yet  +  "Connect your headphones once in Bluetooth settings."  +  [Open Bluetooth settings] | No paired audio devices found. Pair your headset in Bluetooth settings, or grant the Bluetooth permission. |
| Match step title | Already added on another device? | Is this a headset your other devices already know? |
| Match option | Aurora Buds on {x}  +  "Recommended" | Recommended · same Bluetooth address · on Studio PC |
| New option | No, these are new | New headset  +  "Not added on any other device yet" |
| Row subtitle | Headset · also on {x} | Headset · already added · also on Studio PC |

## Details

| Where | Text | Was |
|---|---|---|
| Rows | Type / Added on | Status / On this device / Type / Added on |
| Tile option | Quick Settings tile  +  "The tile moves these headphones." | Use for the Quick Settings tile  +  "The tile moves this headset." |
| Stay connected | Stay connected to other devices  +  "Leave off unless you want your headphones on several devices at once." | Stay connected to other devices  +  "Off: Move here disconnects the headset from your other devices, so their sounds can't interrupt you. On: for headphones that connect to two devices at once, Move here adds this device and leaves the others." |
| Remove dialog | Remove from {me}?  +  "They stay connected in Bluetooth settings."  [Remove] [Keep] | Forget headset?  +  "Handoff stops managing it on this device. The Bluetooth pairing is not changed." |
| Removed elsewhere | These headphones were removed. | This headset was removed from Handoff on this device. |

## Linking

| Where | Text | Was |
|---|---|---|
| My devices intro | Show a code on one device and scan it on the other. | Link another phone, tablet or Windows PC running Handoff. Show the code on one device and scan it on the other. |
| Buttons | Scan a code / Show my code | Scan a Handoff code / Show my code instead |
| Empty | No devices yet. You only link once. | No linked devices yet. Links work on any Wi-Fi both devices share; you only link once. |
| Unlink note | (moved into the unlink dialog) | Linked devices can ask this device to release a headset. Unlinking stops that immediately. Unlink on both devices to remove the link completely. |
| Unlink dialog | Unlink {x}?  +  "It can't move your headphones any more. Unlink it on {x} too."  [Unlink] [Keep] | Unlink Studio PC?  +  "It will no longer be able to move headsets to or from this device." |
| Show code | Scan this from your other device.  ·  Expires in 4:56 | On the other device open Handoff → My devices → Scan a Handoff code.  ·  Works once · expires in 4:56  ·  You'll confirm a 6-digit code on this device before anything is linked. |
| Scan screen | Scan the code on your other device  +  "On a PC it's under Link a phone." | Scan the code on your other device  +  "On the other phone or tablet: Handoff → My devices → Show my code. On a Windows PC: Handoff → Link a phone." |
| Paste field | Or paste the code | …or paste a HANDOFF: code |
| Confirm (this side) | Make sure {x} shows this number:  622 670  /  Then tap Link on {x}. | Confirm on Studio PC  +  "Check that Studio PC shows this code, then tap Link there:" |
| Approve (other side) | Link {x}?  +  "Does {x} show  622 670 ?"  +  "Only link your own devices."  [Link] [Decline] | Link My Phone?  +  "Only continue if the other device shows this code:"  +  "Only link devices you own and have in front of you. A linked device can move your headphones and see their names and battery level. Nobody else ever needs this code." |
| Linked | Linked with {x}  +  "You won't need to do this again." | Linked with Studio PC  +  "You won't need to link again, even on another Wi-Fi." / "It can now move headsets to and from this device, on any Wi-Fi you both use." |
| Errors | That's not a Handoff code. / That code has expired. Show a new one. / That's this device's own code. / {x} declined. | That isn't a Handoff code. / That code has expired or was already used. Show a new one on the other device. / That's this device's own code. / The other device declined the link. |
| Error, unreachable | Couldn't reach {x}. Are both on the same Wi-Fi?  ·  More: "On a PC, allow Handoff in Windows Firewall." | Couldn't reach the other device. Make sure both are on the same Wi-Fi and the code is still showing. If it's a Windows PC, allow Handoff through Windows Firewall (private networks). |
| No Wi-Fi | Connect to Wi-Fi first. | Connect to Wi-Fi first. Both devices must be on the same network. |

## Help and diagnostics

| Where | Text | Was |
|---|---|---|
| Headline, failed | {headphones} didn't move  +  "{x} didn't answer, so they're probably still connected to it." | (raw diagnostics only) |
| Headline, fine | ✓ No problems found  +  "Last move: {headphones} moved here." | (none) |
| Sections | What to do  /  What Handoff checked | Bluetooth / Preferred headset / Peers / Last transfer |
| Steps, a phone didn't answer | On {x}, open Handoff  +  "So it answers even when locked: Settings → When this phone is locked → Stay reachable."  /  Connect both to the same Wi-Fi (only after a different network was seen) | (none) |
| Steps, a PC didn't answer | On {x}, start Handoff  +  "It stays running near the clock. To start it with Windows: Settings → Start with Windows."  /  If Windows asks about network access, choose Allow | (none) |
| Checks | {x} is answering / Handoff on {x} isn't answering  +  "Last answer: 2 hours ago, on a different Wi-Fi."  ·  Bluetooth is on  ·  {headphones} are set up in this tablet's Bluetooth  ·  This tablet can move headphones  ·  This tablet is on Wi-Fi | Online / Offline, BONDED, A2DP… |
| Folded checks | 4 other checks are fine | (none) |
| Report | Share a report  +  "Includes your device model, device and headphone names, and what Handoff tried. Use it when you report a problem." | Share (icon) |
| Developer section | Technical details | (the whole screen) |
| Tip | Tip: choose Stay reachable  +  "So your other devices can take the headphones while this tablet is locked."  [Change] | (none) |

## Settings

| Where | Text | Was |
|---|---|---|
| Identity | My Phone  +  "Tap to rename" | Device name / Identity key 61:FB:79:45 |
| Background choice | When {me} is locked:  [Only while open] [Stay reachable]  +  "Stay reachable shows a small notification." | Stay reachable in the background  +  "Off: this device hands the headset over only while Handoff is open. On: your other devices can take it while this one is locked. Android requires a silent, permanent notification for this." |
| Hide notification | Hide the notification  +  "Handoff keeps working." | Hide the notification  +  "Turn off the “Handoff service” notification category. Handoff keeps running." |
| Reboot | Start after restart | Start after reboot |
| Battery | Battery use  +  "If moves fail while locked, set Handoff to Unrestricted." | Battery optimization  +  "Some manufacturers stop background apps aggressively. If other devices can't reach this one while it is locked, set Handoff's battery usage to “Unrestricted” in app settings." |
| Tile | Quick Settings tile  +  "Add headphones first." | Quick Settings tile  +  "Add a headset first." |
| Automatic switching | When something starts playing here:  [Off] [Ask me] [Move here] | Automatic switching (experimental)  +  "When something starts playing on this device while your Quick Settings headset is on another device. Move here always works too."  +  Off / Ask with a notification / Move automatically |
| Automatic note | Works only with Stay reachable on. | Automatic switching only runs while “Stay reachable in the background” is on. |
| Privacy | Privacy policy | Privacy policy  +  "What Handoff stores, what it sends to your other devices, and what it never collects." |
| Help | Help and diagnostics  +  "Share a report if something goes wrong" | (Diagnostics icon on Home) |
| About | Handoff 0.6.3 / Source code / Licenses / Bluetooth settings | Handoff 0.6.3 · GitHub edition · MIT License / Source code and releases / Open-source licenses / Android Bluetooth settings |

## Updates

| Where | Text | Was |
|---|---|---|
| Check (GitHub) | Check for updates  +  "Version 0.6.3" | Check for updates  +  "Version 0.6.3. Updates come from Handoff's GitHub releases and are signature-checked." |
| States | Checking… / Up to date / 0.6.4 is available / Downloading… | Checking… / You're up to date (0.6.3). / Handoff 0.6.4 is available. / Downloading Handoff 0.6.4… |
| Permission | Allow Handoff to install updates.  [Allow] | Allow Handoff to install updates, then tap Install.  [Allow installs] [Install] |
| Buttons | Update / What's new | Download and install / Release page |
| Auto | Check automatically  +  "Once a day" | Check automatically  +  "Once a day, when Handoff opens. This contacts GitHub; nothing about you or your devices is sent." |
| Errors | This update isn't for Android. / The download was damaged. Try again. | This release has no Android download. / The download didn't match the signed release, so it was discarded. |
| Play edition | Updates  +  "Through Google Play" | Google Play  +  "Version 0.6.3. Updates are managed by Google Play." |

## Setup

| Where | Text | Was |
|---|---|---|
| Welcome | Your headphones, on whichever device you pick up  +  "Tap Move here. Your other device lets go and this one connects." | Move your headphones between your devices  +  "Handoff coordinates which of your devices is connected to an already-paired Bluetooth headset. Press Move here and the other device lets go, then this one connects." |
| Before you start | 2 bullets: "Connect your headphones to each device once." / "Install Handoff on each device." | 5 bullets (pair, install and link, no real multipoint, non-public Android functions, privacy line) |
| Permission | Allow Bluetooth access  +  "So Handoff can see and move your headphones. Android calls this Nearby devices." | Nearby devices (Bluetooth) — required  +  "To see your paired headphones, their connection state, and to connect or release them."  +  "Handoff does not ask for location or notifications, and does not scan for new Bluetooth devices." |
| Name | Name this phone  +  "Your other devices see this name." | Name this device  +  "Your other devices will show this name." |
| Check title | Can this phone move headphones? | Can this device switch headphones? |
| Check result | Yes, it works / Should work. Try your first move. / No. Your other devices can still take them from here. | Supported: "Handoff has already moved headphones on this device." / Experimental: "Everything Handoff needs is available. It will show “Supported” after your first successful move." / Not supported: "This device doesn't let apps connect headphones, so Handoff can't move them here. Your other devices can still take the headphones from it." |
| Check rows | Connect / Let go | Connect headphones / Let go of headphones |
| Background step | When this phone is locked:  [Only while open] [Stay reachable]  +  "Stay reachable shows a small notification." | Stay reachable in the background  +  "Lets your other devices take the headset from this one while Handoff is closed or the screen is locked. Otherwise, open Handoff here before moving the headset away. Android shows a small notification, which you can hide in Settings." |

## Windows

| Where | Text | Was |
|---|---|---|
| Header | Studio PC  (and a warning only when Bluetooth is off or missing) | Studio PC · Bluetooth on / no Bluetooth adapter / Bluetooth is off |
| Empty devices | [Link a phone] | Link your phone or tablet: open Handoff there, go to My devices → Scan a Handoff code, and scan the code from “Link a phone”. |
| Link dialog | Scan this with your phone  +  "In Handoff, tap Link a device."  ·  Expires in 4:57 | Link a phone or tablet  +  "On your phone open Handoff → My devices → Scan a Handoff code, and point it at this code."  +  "Works once · expires in 4:57 · you'll confirm a 6-digit code here" |
| Restore Windows audio | Windows won't reconnect them by itself while they're on another device.  [Connect to this PC] | Handoff turned off this headset's Windows audio services when it moved it away, so Windows doesn't grab it back. Move here, or restore Windows audio to use it from Windows directly.  [Restore Windows audio (connects it here)] |
| Settings | Start with Windows  +  "So your phone can always reach this PC" | Start with Windows  +  "Starts minimized to the tray so your phone can always reach this PC." / "Available in the installed app." |
| Settings note | "Closing keeps Handoff in the tray."  (Firewall advice appears only when a link fails.) | Closing the window keeps Handoff running in the tray. Quit from the tray menu. Windows Firewall may ask to allow Handoff on private networks; allow it so linked devices can reach this PC. |
| About | Handoff 0.6.3 | Handoff 0.6.3 · identity key 9F:5C… · MIT License |
| Tray | Move Aurora Buds here / Open Handoff / Quit | Move Aurora Buds here / Open Handoff / Quit Handoff |

## Notifications

| Where | Text | Was |
|---|---|---|
| Background notification | Staying reachable  +  "Your other devices can take your headphones from this phone."  [Turn off] | Handoff is ready  +  "Linked devices can move your headphones here."  [Turn off] |
| Ask notification | Move Aurora Buds here?  +  "They're on {x}."  [Move here] | Audio started on this device  +  "Aurora Buds is connected to Studio PC."  [Move here] |
| Channels | Staying reachable / Move suggestions | Handoff service / Move prompts |
| Tile | Set up / Connected here / On {x} / Moving… / Not connected | Set up Handoff / Connected here / On Studio PC / Moving… / Not connected |

## Play edition

| Where | Text | Was |
|---|---|---|
| Ad label | Ad | Sponsored |
| Ad setting | Ads  +  "One small ad at the bottom of Home. Handoff shares nothing about your headphones or other devices with it." | Advertising  +  "This edition shows one banner from Google AdMob at the bottom of the home screen. Handoff gives it nothing about your headsets, devices or network. The GitHub edition has no ads." |
| Ad choices | Ad privacy choices | Ad privacy choices  +  "Review or change your consent for ads." |
