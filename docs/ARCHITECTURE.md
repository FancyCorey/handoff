# Architecture

## Modules and layers

```
┌──────────────────────────────── :app (Android) ─────────────────────────────────┐
│ UI (Compose, Material 3): Setup · Home · Device detail · Map · Peers · Add/Scan │
│    · Transfer · Settings · Diagnostics · Bluetooth test                          │
│ Android services: HandoffService (FGS connectedDevice) · BootReceiver ·          │
│    NotificationActionReceiver · MoveHereTileService · PlaybackWatcher            │
│ Runtime: HandoffRuntime (lifecycle) · HandoffActions (single "move" entry)       │
│ Mesh (Android): NsdPeerDiscovery · NetworkAddresses · NetworkMonitor             │
│ Persistence: Room (peers, logical devices, transfer history) · DataStore         │
│    (settings) · Android Keystore (identity key)                                  │
├──────────────────────────────── :bluetooth (Android lib) ───────────────────────┤
│ AndroidBluetoothAudioController ─ strategy chain:                                │
│    PublicApiStrategy → ReflectionA2dpStrategy (non-SDK) → FutureOemStrategy      │
│ CompanionProfileReleaser (HFP / LE Audio release, non-SDK)                       │
│ ProfileProxyProvider · SystemBluetoothEvents · DeviceClassifier ·                │
│    HiddenMethodInvoker                                                           │
├──────────────────────────────── :desktop (Windows, Compose Desktop) ────────────┤
│ DesktopApp (composition root) · UI (HomeScreen, dialogs) · tray · Autostart      │
│ WindowsBluetoothAudioController ─ Win32 BluetoothSetServiceState via JNA          │
│ DesktopIdentityProvider (DPAPI) · JSON stores · JmdnsDiscovery                   │
├──────────────────────────────── :core (pure Kotlin/JVM) ────────────────────────┤
│ handoff:   HandoffCoordinator · TransferStateMachine · HandoffPolicy ·           │
│            AudioReleaseHandler · HandoffRequestHandler · OwnershipBroadcaster ·  │
│            AutoSwitchPolicy · MeshSync (push / reconcile / refresh jobs)         │
│ ownership: OwnershipResolver · OwnershipRepository · MeshOwnershipRepository ·   │
│            MappingReconciler                                                     │
│ bluetooth: BluetoothAudioController (port) · diagnostics model · throttle        │
│ mesh:      protocol (PeerMessage, codec) · security (handshake, channel, guard,  │
│            invitation) · transport (PeerTransport, LanPeerTransport, PeerServer, │
│            PeerDirectory) · pairing (PairingManager, PairingClient)              │
│ overview:  OverviewRepository (dashboard read model, shared by both apps)        │
│ text:      HandoffTexts (shared wording for steps and results)                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

Dependency direction: `app → bluetooth → core`, `app → core`, `desktop → core`. The Windows app runs the *same* coordinator, protocol, crypto, pairing and sync code as Android; only Bluetooth, storage and discovery are platform-specific. `:core` has no Android dependency, so the whole transfer algorithm, protocol and crypto are tested on the plain JVM, including over real TCP sockets.

**Isolation of hidden APIs.** Only `ReflectionA2dpStrategy` + `HiddenMethodInvoker` perform reflection, and both are `internal` to `:bluetooth`. The domain sees `BluetoothAudioController`, which returns structured results (`Requested`, `AlreadyInState`, `Failed(BluetoothError, strategy, detail)`), and never exceptions.

## Identity, trust and peers

* **Identity:** a random UUID `PeerId`, a display name, and a P-256 signing key generated in the Android Keystore (non-exportable).
* **Trust:** `TrustedPeer(peerId, name, publicKey)` rows in Room. Only these peers can open a session (see [SECURITY.md](SECURITY.md)).
* **Discovery:** NSD advertises `_handoff._tcp` / `handoff-<daily tag>` (`DiscoveryTags`; see SECURITY.md). `PeerDirectory` merges endpoints from four sources:
  1. the last successful contact
  2. mDNS
  3. the pairing QR code
  4. the source address of authenticated inbound sessions

  Online status comes from mDNS plus recent authenticated contact.
* **Transport:** one short-lived TCP connection per request. Each one does a handshake, one encrypted request and one encrypted reply. `PeerTransport` is an interface, so a relay can implement it later.

## Logical devices

The same headset is a different `BluetoothDevice` on every host. A `LogicalAudioDevice` has these fields:

* `logicalId`: shared by all hosts
* `displayName`, `deviceType`
* `fingerprint`: SHA-256 of `"handoff-audio-device-v1|" + address`, truncated
* `localDeviceId`: this host's bonded address, never sent to peers
* `multipoint`
* `lastKnownOwner`, `ownershipGeneration`
* `hostMappings`: the hosts known to map it

On mapping, peers' announced headsets with an equal fingerprint are pre-selected. Names are never used for matching. If two hosts mapped the same headset independently, `MappingReconciler` makes both adopt the lexicographically smaller logical id.

## Ownership

Each host is authoritative for its own A2DP state and reports it in `STATUS_RESPONSE` and `OWNERSHIP_CHANGED`. `OwnershipResolver` combines local state with fresh reports (≤ 2 min old, peer online):

| Holders | Result |
|---|---|
| this host only | `LOCAL` |
| one peer | `PEER(id)` |
| several, headset declared multipoint | `MULTIPOINT` |
| several, single-point | `CONFLICT` (a report is stale or racing) |
| none, and every mapped host reported | `NONE` |
| none, but a mapped host is silent / local state unreadable | `UNKNOWN(lastKnownOwner)` |

`ownershipGeneration` increases on every successful transfer. The highest generation wins when hosts disagree.

## Transfer algorithm

`DefaultHandoffCoordinator.moveToThisDevice` is the only switching path:

```kotlin
mutex(logicalId).tryLock() ?: return InProgress          // repeated presses
RESOLVING_OWNER
  no local mapping          -> MissingLocalMapping
  Bluetooth off / no perm   -> Failed(BLUETOOTH_OFF / PERMISSION_DENIED)
  already connected         -> COMPLETE (AlreadyConnected)
  refresh peer status (≤1.5 s), resolve ownership
PEER / CONFLICT (/ MULTIPOINT if exclusive):
  REQUESTING_RELEASE: PING (≤1.5 s)   unreachable -> DIRECT_TAKEOVER
  WAITING_RELEASE: RELEASE_AUDIO_DEVICE (≤9 s)
    RELEASED | NOT_CONNECTED -> settle 600 ms -> CONNECTING
    BUSY                     -> FAILED(CONTENTION)   (never steal mid-transfer)
    TIMEOUT / refused / error -> DIRECT_TAKEOVER
UNKNOWN -> DIRECT_TAKEOVER;  NONE -> CONNECTING;  MULTIPOINT -> CONNECTING (join)
CONNECTING -> VERIFYING (A2DP CONNECTED within 8 s) -> COMPLETE
            \-> RETRYING (1.5 s; abort with CONTENTION if another host connected meanwhile)
                -> CONNECTING (attempt 2) -> VERIFYING -> COMPLETE | FAILED
COMPLETE: generation+1, persist owner, OWNERSHIP_CHANGED to peers (background)
```

`UNSUPPORTED`, `PERMISSION_DENIED`, `BLUETOOTH_OFF`, `DEVICE_NOT_BONDED` and `RATE_LIMITED` are never retried.

**Automatic retry and error classification (0.3).**

* A peer that doesn't answer is probed once more (after 1.5 s) before the transfer falls back to a direct takeover.
* If both connect attempts of a round fail, the coordinator waits 3 s and runs **one more full round**: owner lookup, release, connect and verify. The progress shows *"That didn't work. Trying once more…"*.
* The retry round is skipped if Bluetooth went off, or if another device took the headset during the pause (that is `CONTENTION`, never a steal-back).
* After the last round, the failure is classified by *why* the transfer fell back:

  | Result | When |
  |---|---|
  | `OWNER_UNREACHABLE` | The holder couldn't be reached (another Wi-Fi, asleep, not running, firewall). The detail is its name. |
  | `OWNER_REFUSED` | The holder answered but couldn't let go. The detail is its reason, e.g. a call profile Android refused to drop. |
  | `HEADSET_NOT_RESPONDING` | Nobody else held the headset, or it was released, but it still didn't connect. |

* `HandoffDiagnosis` turns transport rejections into plain language: clock skew over 5 minutes, a different app version, a no-longer-linked device, a reinstalled device.
* A host holding the headset through *any* profile (A2DP, HFP or LE Audio) reports it as connected, so a call-only link is never mistaken for "free". The legal transitions are declared in `TransferStateMachine.TRANSITIONS`. An illegal one throws, and the coordinator turns that into `Failed(INTERNAL)`.

**Deviation from the original pseudocode:** a `BUSY` reply does *not* trigger direct takeover. `BUSY` means the owner is mid-transfer or has just released to another peer. Taking over then would make two hosts fight over the headset.

### Remote side (`AudioReleaseHandler`)

The handler runs headless, so it works with the screen off:

1. Look up the local mapping. If there is none, reply `DEVICE_UNKNOWN`.
2. Take the same per-device mutex without waiting. If it's held, reply `BUSY`.
3. If the headset was released to *another* peer within 8 s, reply `BUSY` (anti-ping-pong).
4. If it isn't connected, reply `NOT_CONNECTED`.
5. Disconnect A2DP **and every other audio profile the headset holds (HFP calls, LE Audio)**, then verify that all of them are down within 5 s.
   * If they are, reply `RELEASED`.
   * If only a call profile is stuck, reply `FAILED` with a detail naming it.
   * Otherwise, reply `TIMEOUT`.

   A single-point headset stays attached to a host while *any* profile is connected. The first hardware report showed that an A2DP-only release is not enough.
6. If the disconnect call itself failed, reply `UNSUPPORTED`, `PERMISSION_DENIED` or `FAILED`.

Duplicate command ids get the cached reply (`CommandGuard`), which makes release idempotent.

### Concurrency cases

| Case | Handling |
|---|---|
| Repeated Move here | `tryLock` → `InProgress` |
| Two hosts request simultaneously | Owner serves one (mutex); the other gets `BUSY` → `CONTENTION` |
| Headset off mid-transfer | Verify times out → one retry → `VERIFY_TIMEOUT` |
| Bluetooth disabled | Pre-check; controller returns `BLUETOOTH_OFF`, not retried |
| Peer disappears | Ping/timeout → direct takeover; peer marked offline |
| Old host reconnects / third device steals | `HandoffRuntime` pushes local state changes; the retry path checks for new holders |
| Multipoint | Joins by default; never disconnects others unless `releaseOthersOnMultipoint` |
| Hammering | `OperationThrottle` (12 ops/min) in the controller; `AutoSwitchPolicy` cooldowns |

## Background execution

`HandoffService` is a foreground service of type `connectedDevice`. It holds the runtime (peer server + NSD) while *Stay reachable in the background* is on (off by default; otherwise the runtime runs only while the UI is open). It runs no timers or polling; a process with a foreground service keeps network access while the device is idle.

While the UI is visible, the runtime refreshes peer status every 10 s. In the background, updates are event-driven:

* discovery events
* this host's A2DP changes, pushed to peers
* inbound requests

`BootReceiver` restarts the service only when the user enabled it.

## Automatic switching (optional)

`PlaybackWatcher` registers `AudioManager.registerAudioPlaybackCallback` while the service runs and the mode is not OFF. When media starts, `AutoSwitchPolicy` decides:

* **ASK:** show a notification.
* **AUTO:** call `HandoffActions.moveHere(..., AUTOMATIC)`, the same coordinator.
* **Ignore:** do nothing.

It ignores playback when the headset is already here, on multipoint or conflict, within 30 s of the last trigger, or within 60 s of handing the headset to a peer.

## Networks

A link is bound to two identity keys, never to a network, SSID or IP address, so a pair of devices is linked once and works on every network they share.

* **Discovery:** mDNS on each network (Android `NsdManager`, Windows JmDNS).
* **Network changes:**
  * Android's `NetworkMonitor` (a ConnectivityManager callback) and the Windows app (polling its LAN addresses every 3 s) detect a new network.
  * On a change they call `PeerDirectory.onNetworkChanged()`. This drops every address learned on the old network and clears online/failing flags.
  * They then restart mDNS advertisement and discovery on the new network, and refresh peer status.
* **Fallback addresses:** the last successful address and the source address of authenticated inbound sessions. These help on networks where multicast is flaky.
* **Limitation:** devices on *different* networks cannot reach each other until a relay exists (below).

## Windows host

`WindowsBluetoothAudioController` implements the same `BluetoothAudioController` port with the documented Win32 API. There is no hidden API on Windows.

* **Release:** `BluetoothSetServiceState(DISABLE)` for A2DP sink, hands-free and headset. The link drops, and Windows cannot auto-reconnect while the services are off, which prevents the old host from stealing the headset back. The released headset is remembered, so the UI can offer *Restore Windows audio*.
* **Connect:** disable, then enable those services (calls first, media last). Windows pages the headset and reinstalls its audio endpoints. `HandoffPolicy` gives Windows a longer verify timeout (15 s).
* **State:** `BLUETOOTH_DEVICE_INFO.fConnected` (link level), polled every 1.5 s.
* **Background:** the app keeps running in the tray, starts with Windows (per-user `Run` key, `--minimized`), and refreshes peers every 15 s.

## Future remote mode

Planned; nothing is implemented yet. `PeerTransport` is the seam for it:

* A `RelayPeerTransport` would carry the same encrypted `SecureChannel` frames through a relay.
* The relay would see only opaque bytes and peer routing ids.
* FCM could wake a sleeping peer.
* The handshake, trust store, protocol and coordinator would stay as they are.
