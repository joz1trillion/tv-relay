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
3. **Phone:** open TV Relay and tap **Listen**. It finds the TV automatically; if not, enter the TV's IP (shown on the TV under **Start streaming**) under **Connect directly** on the Listen tab.

All settings live in the phone app; the TV follows whatever the phone asks for. Once TV Relay has been opened on the TV, the phone's Listen tab shows whether it found the TV and whether the TV is streaming. If the TV Relay screen is open on the TV, tapping **Listen** brings up the TV's Allow prompt for you. With **Start mode: Auto** on the TV (the button under Start streaming; it uses Android's "Display over other apps" permission), that works even while you're in another app: the prompt pops up over your show. Tapping **Stop** on the phone offers to stop the TV too, which works with TV Relay in the background. Every setting has an info button explaining what it does.

**More than one TV?** Tap the TV line on the Listen tab to pick which TV to listen to.

**Advanced mode:** tap the version text in the update dialog 7 times. It adds *TV mode / Client mode* to the TV screen and the Listen tab, for devices the app guesses wrong (a TV box that shows the phone screen, or a tablet you want to stream from). Turning it off (Settings → Advanced, at the bottom) makes you a normal user again; on a TV in TV mode, switch to Client mode first to reach it.

### Kodi lip sync (optional)

1. Install Kodi 21 or newer on the TV from the Play Store.
2. In Kodi on the TV: Settings → Services → Control → **Allow remote control via HTTP**, and set a password.
3. On the phone: **Kodi sync** tab → open **Kodi login**, enter the same port, username and password, and tap **Test connection**.
4. Tap **Listen**, set Mode to **Automatic**, then fine-tune with the slider.

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
| Kodi JSON-RPC sync (runs on the phone) | `KodiSync.java` |
| Phone ↔ TV status link (UDP port 48101) | `LinkService.java` (TV), `TvLink.java` (phone) |
| Screens | `MainActivity.java` (phone), `TvActivity.java` (TV) |
