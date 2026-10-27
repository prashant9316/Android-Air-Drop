# Architecture: AirDrop-style sharing for Android and Windows

## 1. How AirDrop actually works (and what Android/Windows can use instead)

| AirDrop piece | What Apple uses | What we use |
|---|---|---|
| Discovery | Bluetooth LE advertisements with a short contact hash | BLE advertising + scanning (Android & Windows both support it) |
| Fast link | AWDL (Apple Wireless Direct Link), a proprietary peer-to-peer Wi-Fi | **Wi-Fi Direct** (Android↔Android, Android↔Windows), **Wi-Fi Aware/NAN** where supported, **same-LAN TCP** when both are on one network |
| Security | TLS with Apple ID certificates | TLS 1.3 (or Noise XX) with ephemeral keys + optional paired-device keys |
| "Bring phones together" (NameDrop) | Proximity detection on iPhone hardware | NFC tap (HCE), UWB on supported phones, BLE RSSI + motion fallback |
| Share UI | Share sheet + radar of nearby avatars | Android share target + Direct Share shortcuts; Windows Share target + Explorer "Send to" |

You **cannot** talk to real Apple AirDrop devices: AWDL is proprietary and needs
radio support Android phones don't expose. This project is its own protocol that
runs on Android and Windows.

## 2. System overview

```
             ┌─────────────────────── Protocol spec (protobuf) ──────────────────────┐
             │ discovery payload · handshake · manifest · chunk frames · resume       │
             └───────────────────────────────────────────────────────────────────────┘
  Android app (Kotlin + Jetpack Compose)          Windows app (C# + WinUI 3, packaged MSIX)
  ├─ UI: share sheet target, radar, tile           ├─ UI: tray app, Share target, Send to
  ├─ Discovery: BLE adv/scan, NSD (mDNS)           ├─ Discovery: BluetoothLEAdvertisementWatcher/
  ├─ Proximity: NFC HCE, UWB, RSSI+accelerometer   │            Publisher, DNS-SD
  ├─ Link: Wi-Fi Direct, Wi-Fi Aware, LAN          ├─ Link: join Android's Wi-Fi Direct group
  └─ Transfer engine (shared core, see §7)         │        as a legacy client, or LAN
                                                   └─ Transfer engine (shared core)
```

Recommended split: **native shells per platform** (radios and UI must be native to
get the APIs and the animation quality), plus **one transfer/crypto core** written in
Rust and bound to Kotlin and C# with [UniFFI](https://github.com/mozilla/uniffi-rs).
That keeps the wire protocol, encryption and speed tuning identical on both sides.
If you'd rather not add Rust, write the core twice (Kotlin and C#) against the
protobuf spec and keep a shared conformance test suite.

## 3. Discovery (the "radar")

**BLE advertising** (both platforms advertise when "Receiving" is on):

- 128-bit custom service UUID, service data ≤ ~20 bytes (fits legacy 31-byte ads):
  `version(1) | flags(1: device type, receive mode, supports 5 GHz/Aware) | rotatingId(8) | contactHint(4–8)`
- `rotatingId` changes every ~15 minutes so devices can't be tracked.
- `contactHint`: for "Contacts only" mode. Do **not** use a plain hash of a phone
  number. AirDrop's hash-based scheme was shown to leak numbers by brute force. Use
  keys exchanged at pairing time (HMAC of the rotating ID with a shared secret).

**Sender side**: scan with a `ScanFilter` on the service UUID
(`SCAN_MODE_LOW_LATENCY` only while the share sheet is open). Sort devices by
smoothed RSSI so the closest appears first, like AirDrop.

**Same Wi-Fi**: also publish `_yourapp._tcp` via Android NSD / Windows DNS-SD. If both
devices are on the same LAN, skip creating a P2P link and connect over TCP directly.

**Device name & avatar**: fetched over a short BLE GATT read (or the TLS handshake)
after the user taps, not broadcast in the advert.

## 4. Picking the fastest link

After the user taps a device, the sender opens a BLE GATT connection (or uses the LAN)
for a ~1 KB **out-of-band exchange**: capabilities, ephemeral public key, and the
link credentials. Then it picks the first option that works:

1. **Same LAN**: TCP to the mDNS address. Zero setup and often the fastest on Wi-Fi 6 routers.
2. **Wi-Fi Aware** (Android↔Android, if `PackageManager.FEATURE_WIFI_AWARE` on both):
   no group negotiation, IPv6 link-local socket.
3. **Wi-Fi Direct group, receiver or Android side is the group owner**:
   - Android 10+: `WifiP2pManager.createGroup(channel, WifiP2pConfig.Builder()
     .setNetworkName("DIRECT-xy-…").setPassphrase(random)
     .setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ).build())`.
   - Send SSID + passphrase to the peer **over the encrypted BLE channel**.
   - Android peer: `connect()` with the same config. This avoids the system
     "accept invitation" dialog.
   - **Windows peer**: joins the group as a normal Wi-Fi client
     (`WiFiAdapter.ConnectAsync` with a `PasswordCredential`). Most Windows Wi-Fi
     drivers can stay on the home network at the same time.
4. **Bluetooth RFCOMM / L2CAP CoC**: last-resort fallback (~0.1–0.3 MB/s). Good enough
   for a contact card or a small photo.

Prefer making **Android the group owner** when talking to Windows. Windows-hosted
groups (`WiFiDirectAdvertisementPublisher` with `LegacySettings`) make Android 10+
show a network-request dialog, which breaks the "one tap" feel.

Real-world throughput on 5 GHz Wi-Fi Direct is about 20–60 MB/s depending on chipset,
versus about 0.2 MB/s over plain Bluetooth.

## 5. Handshake and security

1. Both sides generate ephemeral X25519 keys. Public keys go over BLE/OOB.
2. Open TLS 1.3 (or a Noise XX session) over the TCP socket, bound to those keys,
   so a device can't silently swap in a different key mid-handshake.
3. Show a **4-digit verification code** derived from the transcript hash, as an
   optional check for unknown devices.
4. Paired/"contacts" devices keep a long-term identity key. They skip the code and
   can auto-accept if the user allows it.
5. The receiver gets an **Accept / Decline** sheet with sender name, avatar, file
   count and a thumbnail before any file bytes are written.
6. Receive modes like AirDrop: *Off*, *Contacts only*, *Everyone for 10 minutes*.

## 6. Transfer engine: making it fast

- **Manifest first**: file names, sizes, MIME types, thumbnails, total bytes.
- **Framing**: length-prefixed protobuf frames. Data frames carry `fileId, offset, bytes`.
- **Large chunks** (1–4 MiB) and **large socket buffers** (`SO_SNDBUF/SO_RCVBUF`
  ≥ 4 MiB). Turn off Nagle (`TCP_NODELAY`) for control frames only.
- **Parallel streams**: 2–4 TCP connections for big batches. One connection rarely
  fills a 5 GHz P2P link on mid-range chipsets. Many small files are pipelined, not
  sent one by one with a round-trip each.
- **Zero-copy I/O**: `FileChannel.transferTo` on Android. Read from `ContentResolver`
  file descriptors, not byte arrays.
- **No compression** for already-compressed media (JPEG/HEIC/MP4/ZIP). Use zstd only
  on text-like data.
- **Encryption cost**: AES-GCM (hardware-accelerated on ARMv8 and x86) or ChaCha20.
  Hash with BLAKE3 for end-to-end integrity.
- **Resume**: the receiver reports its received offsets after a reconnect, so a dropped
  link continues instead of restarting.
- **Keep the link awake**: hold a `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` on
  Android 10+) during transfer. Pause BLE scanning at the same time, because it
  shares the antenna and slows Wi-Fi.

## 7. "Bring phones together" gesture (NameDrop-style)

Use layered detection so it works on as many phones as possible:

| Method | Devices | How |
|---|---|---|
| **NFC tap** (best, most reliable) | Most Android phones with NFC | One phone runs **Host Card Emulation** (`HostApduService`) serving a small APDU with its ephemeral key + BLE rotating ID. The other uses `NfcAdapter.enableReaderMode`. Role is chosen by a coin flip/alternation while the app or tile is active. (Android Beam was removed in Android 10, so HCE is the way now.) |
| **UWB** | Pixel 6 Pro+, Galaxy S21+/Ultra and newer flagships | `androidx.core.uwb`: range-and-angle. Trigger when distance < ~10 cm. Needs the BLE OOB exchange first. |
| **BLE RSSI + motion** (fallback, all phones) | Everything | Both phones are scanning. Trigger when smoothed RSSI rises above a calibrated threshold (about −30 to −40 dBm, varies by model) **and** the accelerometer shows the phones were moved toward each other and are now still. Expect some false positives, so always show a confirmation sheet. |

When triggered: strong haptic (`VibrationEffect.Composition` with
`PRIMITIVE_THUD` + `PRIMITIVE_SPIN`), the edge-glow animation, then the
"Share / Receive" sheet. With the screen off, only NFC can wake the app reliably. RSSI
and UWB need the app, a Quick Settings tile or a foreground service to be active.

Windows has no tap gesture. Use the tray "radar" window and the Share target instead.
A "send to phone" hotkey is a nice extra.

## 8. Animations and UX (Jetpack Compose / WinUI 3)

- **Radar/ripple**: concentric rings on a `Canvas`, animated with
  `rememberInfiniteTransition`, expanding radius + fading alpha, staggered.
- **Device bubbles**: avatars appear with spring physics (`spring(dampingRatio = 0.6f)`),
  ordered by distance (RSSI) and laid out on an arc.
- **Progress ring** around the selected avatar (`drawArc`) with
  "Waiting… → Sending… → Sent" states. The checkmark morphs in with
  `AnimatedVectorDrawable`.
- **Edge glow / liquid merge** for the tap gesture: an **AGSL `RuntimeShader`**
  (Android 13+) with a blurred gradient that spreads from the top edge. Fall back to
  `RenderEffect` blur + gradients on Android 12, and a plain gradient below that.
- **Haptics** at each step: discover, tap, accepted, done.
- **Windows**: Composition APIs / `Microsoft.UI.Composition` expression animations and
  Mica/Acrylic backgrounds for the same look.
- Keep the *feel* (timing, springs, haptics) close to AirDrop, but design your own
  icons, colors and shapes (see Legal).

## 9. OS integration and background behavior

**Android**
- Share target: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters, plus
  **sharing shortcuts** (`ShortcutManagerCompat`) so recent devices show in the
  system share sheet row.
- Quick Settings tile: "Receiving: Contacts / Everyone 10 min".
- Receiving: a **foreground service** with type `connectedDevice` while advertising.
  Run the transfer itself as a **user-initiated data transfer job** (Android 14+,
  `JobInfo.Builder.setUserInitiated(true)`).
- Permissions: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` (13+), `NFC`, `UWB_RANGING`,
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Save received files via `MediaStore` (Downloads / Pictures) and open the right app
  after "Accept", like AirDrop does.

**Windows**
- Packaged WinUI 3 app (MSIX), started at login, running in the tray.
- Capabilities: `bluetooth`, `wiFiControl`, `radios`, `privateNetworkClientServer`.
- Share target contract + Explorer context menu ("Send with …").
- Requires Bluetooth LE + a Wi-Fi adapter. Most laptops have both. Desktops may need
  a USB adapter, so fall back to LAN mode.

## 10. Build plan (milestones)

1. **Protocol + core**: protobuf spec, handshake, framing, resume. Test the core
   Android↔desktop over plain LAN/TCP first.
2. **Android↔Android MVP**: BLE discovery → Wi-Fi Direct group → transfer, share-sheet
   entry, accept dialog. Measure MB/s on 3–4 phone brands.
3. **Windows app**: BLE watcher/publisher, join the Android group as a client, tray UI.
4. **Speed pass**: parallel streams, buffer tuning, Wi-Fi Aware path, 5 GHz forcing.
   Target ≥ 25 MB/s on mid-range hardware.
5. **Animations + haptics**: radar, bubbles, progress ring, edge glow.
6. **Tap gesture**: NFC HCE → BLE RSSI fallback → UWB.
7. **Contacts mode + paired devices**, privacy review, battery profiling.

## 11. Things to know up front

- **Prior art to study**: Google **Quick Share** already does Android↔Windows. Its
  engine (Nearby Connections) is open-source C++ at
  [google/nearby](https://github.com/google/nearby), and the protocol has been
  documented by [NearDrop](https://github.com/grishka/NearDrop) and
  [rquickshare](https://github.com/Martichou/rquickshare). Also see
  [OpenDrop](https://github.com/seemoo-lab/opendrop) for how AirDrop itself works.
  Your edge has to be speed, the gesture and the polish.
- **OEM fragmentation**: Wi-Fi Direct and BLE behavior vary by chipset. Budget real
  device testing (Samsung, Pixel, Xiaomi, OnePlus at minimum).
- **Legal**: "AirDrop" and its icons are Apple trademarks. Use your own name, icon
  and visual identity. Matching the *interaction* is fine; copying the artwork is not.
