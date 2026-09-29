# Getting started with Handoff

This guide takes you from download to your first move, and explains everything you'll see along the way.

## What you need

- **An Android phone or tablet** with Android 12 or newer, and/or **a Windows 10 or 11 PC** with Bluetooth.
- **Bluetooth headphones** (or earbuds, or a headset).
- **Handoff installed on every device** you use your headphones with.
- **A shared network** when you move the headphones: the same Wi-Fi, or a hotspot from one of your devices.

## 1. Download and install

Go to the [latest release](https://github.com/FancyCorey/handoff/releases/latest) and download the file for each device.

**Android phone or tablet:** download `Handoff-<version>.apk` and open it. The first time, Android asks you to allow installing apps from your browser or file manager. Allow it, then tap **Install**.

**Windows PC:** download `Handoff-<version>-setup.exe` and run it. Handoff installs for your account only and needs no administrator rights. From then on it starts with Windows and waits in the tray (you can turn that off in its settings).

- If Windows says **"Windows protected your PC"**, choose **More info → Run anyway**. Windows shows this for new apps that aren't code-signed yet.
- If Windows asks about network access, allow Handoff on **private networks**, so your other devices can reach your PC.
- Prefer not to install anything? Download `Handoff-<version>-windows-portable.zip`, unzip it anywhere and run `Handoff.exe`. (Prefer an `.msi` installer? That's in the release too.)

## 2. Pair your headphones

Pair your headphones with each device the usual way, in its Bluetooth settings. Handoff uses the pairings you already have; it never pairs or unpairs anything itself.

## 3. Set up Handoff

**On Android**, a short setup runs the first time:

1. Allow **Nearby devices**. Handoff needs this to see and connect your headphones. It's the only permission setup asks for.
2. Give your phone or tablet a name, so your other devices can show it.
3. Handoff checks whether your phone supports switching headphones, and shows the result. **Supported** means it's working on your phone. **Experimental** means everything Handoff needs is there, but no move has been confirmed on this phone yet.

**On Windows**, there's nothing to set up. Your PC appears under its own name, which you can change in **Settings**.

## 4. Link your devices

You link each pair of devices once.

1. On your PC, choose **Link a phone**. A code appears.
2. On your phone, go to **My devices → Scan a Handoff code** and point the camera at it. No camera? Choose **Copy code as text** on the PC and paste it on the phone.
3. Both screens now show the same 6-digit number. If they match, tap **Link**.

To link two Android devices, choose **Show my code** on one and **Scan a Handoff code** on the other.

A code works only once and expires after five minutes. Only link devices that you own and have in front of you.

## 5. Add your headphones

On each device, tap **Add headset** and choose your headphones from the list. If another of your devices already knows them, Handoff selects them for you.

## Moving your headphones

Tap **Move here** on the device you want to listen on. You'll see each step as it happens:

1. The device that has your headphones is asked to let go.
2. It disconnects them.
3. Your device connects and checks that sound really comes through.

It usually takes a few seconds. While it's running, you can leave the screen and come back. Tap your headphones on the home screen to see the progress, or tap **Cancel** to stop.

You can also move your headphones from the **Quick Settings tile** on Android, or from the **tray icon** menu on Windows.

## Wi-Fi, travel and hotspots

Links belong to your devices, not to a network, so they keep working wherever you are. To move your headphones, your devices just need to be on the same network at that moment. They find each other again by themselves after you change networks.

**No Wi-Fi, or a network that blocks devices from seeing each other** (common in hotels, offices and campuses)? Turn on the hotspot on one device and connect the other to it. Handoff works over it without using mobile data. When Handoff can't find your other devices, it shows a shortcut to the hotspot settings.

## Running in the background (Android)

By default, Handoff on Android runs while the app is open and never leaves a notification behind. Your other devices can take the headphones from your phone while Handoff is open on it.

If you want them to take the headphones even while your phone is locked, turn on **Settings → Stay reachable in the background**. Android requires a small permanent notification for this. You can hide it with **Hide the notification**.

Some phone makers close background apps to save battery. If your locked phone doesn't respond, set Handoff's battery usage to **Unrestricted** in the phone's app settings.

## Automatic switching (Android, optional)

Handoff can react when you start playing something on your phone while the headphones are on another device. In **Settings → Automatic switching**, choose **Ask with a notification** or **Move automatically**. This needs **Stay reachable in the background**.

## Keeping Handoff up to date

**Settings → Check for updates** looks for a new version. Turn on **Check automatically** to have Handoff look once a day. When an update is available, **Download and install** fetches it and checks that it really comes from this project before installing it. Android and Windows then ask you to confirm the installation.

Checking for updates is the only time Handoff uses the internet, and it sends nothing about you or your devices.

## If something goes wrong

If a move doesn't work, Handoff tries once more by itself. If it still can't finish, it tells you why:

| Handoff says | What to do |
|---|---|
| **Couldn't reach …** or **On another network** | Make sure the other device is on, on the same Wi-Fi as this one, and running Handoff. No shared Wi-Fi? Use a hotspot. |
| **The other device couldn't let go** | Disconnect the headphones on that device yourself, then tap **Move here** again. |
| **The headset didn't connect** | Check that your headphones are on, charged and close by, and not connected to a device without Handoff. |
| **Bluetooth permission needed** | Tap **Open app settings** and allow **Nearby devices**. |
| **Bluetooth is off** | Turn Bluetooth on and try again. |

On Windows, after your headphones move to another device, they no longer appear in the PC's sound settings. That's on purpose: it stops Windows from pulling them back. Tap **Move here** on the PC to get them back, or choose **Restore Windows audio** in the headphones' options.

Still stuck? **Diagnostics** (the bug icon in the app) shows what Handoff sees on your device. You can share a report (the share button on Android, **Copy & close** on Windows) when you [open an issue](https://github.com/FancyCorey/handoff/issues). Reports hide most of your headphones' Bluetooth address and never include your keys.

## What Handoff is allowed to do on Android

| Permission | Why Handoff needs it |
|---|---|
| Nearby devices | See your paired headphones, and connect or disconnect them. |
| Network access | Talk to your other devices on your network, and check for updates when you ask. |
| Install apps | Install an update you've downloaded. Android asks you to confirm each time. |
| Run in the background | Only if you turn on **Stay reachable in the background**. |
| Notifications | Only if you turn on background mode or **Ask with a notification**. |
| Start at boot | Only to restart background mode after a reboot, if it's on. |
| Camera | Only when you scan a link code. You can paste the code instead. |

Handoff never asks for your location and never searches for new Bluetooth devices.
