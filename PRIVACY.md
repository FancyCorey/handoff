# Privacy policy

*Last updated: 30 September 2026. Applies to Handoff 0.6.0 and later, on Android and Windows.*

Handoff moves your Bluetooth headphones between your own phones, tablets and PCs. It works on your local network: your devices talk directly to each other, and Handoff has no servers, accounts or cloud service of its own.

Handoff for Android comes in two editions built from the same source code:

- the **GitHub edition** (downloaded from [GitHub Releases](https://github.com/FancyCorey/handoff/releases)), which contains no advertising or analytics code;
- the **Google Play edition**, which shows one banner ad and uses Google's advertising and consent libraries (see [Advertising in the Google Play edition](#advertising-in-the-google-play-edition)).

Handoff for Windows is only distributed on GitHub and contains no advertising or analytics code.

This policy separates **what Handoff itself does** from **what Google's advertising SDK does in the Google Play edition**.

## What Handoff itself does

### The short version

- No Handoff account, and no Handoff server, cloud or backend.
- No analytics, telemetry, crash reporting or tracking by Handoff, in either edition.
- Handoff never sends your data to the developer. The developer can't see your devices, headsets or how you use the app.
- Your devices only talk to other devices you have linked yourself, on your local network, encrypted.

### Stored on your device

Handoff keeps the following in its private app storage. It is not included in Android backups (backup and device transfer are turned off for Handoff), and uninstalling Handoff deletes it.

| Data | Why |
|---|---|
| The name you give this device (for example "My Phone") | So your other devices can show it |
| An identity key pair. On Android the private key is created inside the Android Keystore and can't be exported. On Windows it's encrypted for your Windows account. | To prove to your other devices that they are talking to this one |
| Your linked devices: their names, their public keys, and the local addresses where they were last reached | To reconnect to them and check they are genuine |
| The headphones you added: their name, their Bluetooth address and a type (headphones, earbuds…) | To connect and disconnect them |
| A history of recent moves (which headset, when, the result) | For the transfer screen and diagnostics |
| Your settings | To remember your choices |

### Bluetooth

Handoff reads the list of Bluetooth devices already paired with this device, their connection state and, when the headset reports it, its battery level. It connects or disconnects only the headphones you added, and only when you (or a linked device, on your behalf) ask it to move them. It doesn't scan for nearby devices and doesn't use your location.

### Your linked devices

Linking is always approved by you on screen, with a code that expires after five minutes and a 6-digit number to compare. After that, linked devices exchange, over an encrypted connection on your local network:

- device name, platform (Android phone, Android tablet or Windows) and Handoff version;
- whether Bluetooth is on;
- for each headset you added: its name, type, whether it's connected there, its battery level, and a one-way fingerprint of its Bluetooth address (so two devices can recognise the same headset without sending the address itself);
- requests to let go of a headset, and their results.

Every message is encrypted and authenticated (AES-GCM, with keys agreed per connection). Handoff only accepts connections from local-network addresses, and only from devices you linked. To find each other, devices announce themselves on the local network (mDNS) under a name that changes every day and doesn't contain your device name or identity.

### Camera

The camera is used only while you scan a link code, after you tap **Scan**. Images are processed on the device to read the code and are never stored or sent anywhere.

### Notifications and background mode

Background mode (**Stay reachable in the background**) is off unless you turn it on. When it is on, Android shows a notification for as long as Handoff runs in the background, so your other devices can reach this one while it is locked. You can turn it off at any time in Handoff's settings. Notifications are also used for the optional "Move here?" prompt.

### Diagnostics

The diagnostics screen shows a log of recent events, kept only in memory. Handoff never sends it anywhere. If you choose **Share**, Android's share sheet lets you send a copy to the app or person you pick. The report contains your phone's make, model and Android version, device and headset names, and recent events; Bluetooth addresses are shortened and link codes and keys are removed.

### Update checks

- **GitHub edition (Android) and Windows:** when you tap **Check for updates**, or once a day if you turned on **Check automatically** (off by default), Handoff downloads a small signed file from github.com and, if you choose to update, the new version from GitHub. Like any website, GitHub receives your IP address and a standard request header. No information about you, your devices or your headsets is sent. See [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Google Play edition:** Handoff doesn't check for or download updates itself. Google Play updates it, under [Google's privacy policy](https://policies.google.com/privacy).

### Opening links

**Source code and releases**, **Privacy policy** and similar entries open a web page in your browser. Handoff sends nothing with them.

## Advertising in the Google Play edition

The Google Play edition shows **one banner ad** at the bottom of the home screen. It never appears during setup, linking, a move, an error, diagnostics, the Quick Settings tile or notifications, and Handoff works the same whether the ad loads or not.

The ad is provided by Google AdMob, through the **Google Mobile Ads SDK** and Google's **User Messaging Platform** (consent) SDK. These are Google's code, not Handoff's, and Google processes the data they collect under the [Google Privacy Policy](https://policies.google.com/privacy) and its [advertising policies](https://policies.google.com/technologies/ads).

According to [Google's disclosure for the Mobile Ads SDK](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure), the SDK automatically collects and shares with Google, encrypted in transit:

- your **IP address**, which may be used to estimate your general location;
- **product interactions**, such as app launches and taps or views on the ad;
- **diagnostic information**, such as app launch time and hang rate;
- **device identifiers**: the Android advertising ID, the app set ID and similar identifiers.

These are used for advertising, analytics and fraud prevention. You can reset or delete your advertising ID in Android's settings (**Settings → Privacy → Ads** on most phones).

What Handoff does to limit this:

- Handoff passes **nothing** to the ad SDK: no headset names, Bluetooth addresses, device names, linked devices, network addresses, move history or diagnostics. The ad request contains only the ad placement and its size.
- The SDK's own crash reporting is turned off.
- **Consent:** where the law requires it (for example in the EEA, the UK and Switzerland), Google's consent form is shown before any ad is requested, and **Settings → Privacy → Ad privacy choices** lets you change your answer later. Ads wait for consent; Handoff's features never do.
- The ad library is only in the Google Play edition. If you'd rather have no ads and no advertising SDK at all, install the GitHub edition. It has the same features and is free.

## Children

Handoff is a general-purpose utility and isn't directed at children.

## Your choices

- Everything Handoff stores is on your devices. **Unlink** a device or use **Forget on this device** on a headset to delete that record, or uninstall Handoff to delete everything.
- Background mode, automatic update checks and "Move here?" notifications are all optional and can be turned off in Settings.
- In the Google Play edition: change ad consent under **Settings → Privacy → Ad privacy choices** (where offered), and reset or delete your advertising ID in Android's settings.

Because the developer doesn't receive any data about you, there's nothing held by the developer to access or delete. For data collected by Google in the Google Play edition, use [Google's privacy tools](https://myaccount.google.com/data-and-privacy).

## Changes

Changes to this policy are published in this file, with the date at the top. Its history is visible in the [repository](https://github.com/FancyCorey/handoff/commits/main/PRIVACY.md).

## Contact

Questions about privacy: open an issue at [github.com/FancyCorey/handoff/issues](https://github.com/FancyCorey/handoff/issues).
