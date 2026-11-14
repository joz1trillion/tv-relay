# TV Relay

Stream your Android TV's audio to your phone over Wi-Fi with low delay: headphones on the couch without Bluetooth to the TV.

One APK runs on both ends. The TV captures what other apps are playing (Plex, Kodi, YouTube, …) and sends it over UDP; the phone plays it back through a jitter buffer tuned for low latency.

## Features

- **Low latency:** uncompressed PCM in 5 ms packets, a small adaptive jitter buffer, Android's fast audio path, and optional AAudio exclusive (MMAP) output.
- **Fewer skips:** Wi-Fi low-latency/high-performance locks on both devices, packets marked as voice traffic (WMM), optional "send every packet twice" redundancy, and packet-loss concealment.
- **Clock drift and spike recovery:** inaudible resampling for drift, plus Gentle / Normal / Aggressive catch-up after Wi-Fi spikes.
- **Optional Opus compression** (96–256 kbps) for weak Wi-Fi.
- **Live latency graph** split into network, codec, buffer and output delay.
- **Kodi lip sync:** with Kodi 21+ on the TV, TV Relay sets Kodi's audio delay over JSON-RPC so the sound on your phone lines up with the picture. Automatic or manual, with 5 ms precision.
- **Remote-friendly TV screen** and a picture-in-picture mini window on the phone.

## Requirements

- Android 10 or newer on both the TV and the phone (Android's playback capture API).
- Apps can opt out of audio capture; many DRM streaming apps do. Plex and Kodi work.

## Install

1. Install the same APK on the TV and the phone (sideload, or `adb install tv-relay.apk`). After that, both update themselves: the version number in the bottom-right corner shows a blue dot when a new version is out. Select it to install that version, or pick a specific newer one. The phone can also use [Obtainium](https://github.com/ImranR98/Obtainium) (turn on pre-releases).
2. **TV:** open TV Relay, press **Start streaming**, and allow the recording prompt. Then press **Back to my show**.
3. **Phone:** open TV Relay and tap **Listen**. It finds the TV automatically; if not, enter the TV's IP under **Connect directly** on the Listen tab.

All settings live in the phone app; the TV follows whatever the phone asks for. Every setting has an info button explaining what it does.

### Kodi lip sync (optional)

1. Install Kodi 21 or newer on the TV from the Play Store.
2. In Kodi on the TV: Settings → Services → Control → **Allow remote control via HTTP**, and set a password.
3. In TV Relay on the TV: **Connect to Kodi**, enter the same port, username and password. It checks the connection right away.
4. On the phone: **Kodi sync** tab → Mode **Automatic**, then fine-tune with the slider.

New to Kodi? The [full Kodi guide](docs/kodi.md) covers installing it, getting Plex or Jellyfin into it, and troubleshooting.

## Build

Needs JDK 17 and the Android SDK (platform 34, NDK 29 for the AAudio exclusive-mode library).

```bash
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

## How it works

| Piece | File |
| --- | --- |
| Wire format (UDP port 48100) | `Protocol.java` |
| TV capture, Opus encode, send / resend | `SenderService.java`, `OpusCodec.java` |
| Phone receive, decode, playback, Wi-Fi locks | `ReceiverService.java` |
| Jitter buffer, drift correction, catch-up, concealment | `JitterBuffer.java` |
| AudioTrack and AAudio outputs | `TrackOut.java`, `AAudioOut.java`, `jni/aaudio_out.c` |
| Kodi JSON-RPC sync | `KodiSync.java` |
| Screens | `MainActivity.java` (phone), `TvActivity.java` (TV) |
