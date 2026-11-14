# Kodi lip sync

Sound sent over Wi-Fi reaches your phone a little after the TV shows the picture. Most apps can't do anything about that, but [Kodi](https://kodi.tv) can: it lets other apps set its audio delay. TV Relay uses this to make Kodi play the audio slightly early, so the sound on your phone lines up with the picture.

You only need this if you watch in Kodi. Everything else in TV Relay works without it.

## 1. Install Kodi on the TV

Open the Play Store on the TV, search for **Kodi** (by the XBMC Foundation) and install it. Lip sync needs **Kodi 21 "Omega" or newer**, which is what the Play Store has.

The first time you open Kodi it may ask for storage access; allow it. The home screen has a menu on the left (Movies, TV shows, Add-ons, …) and a gear icon at the top for settings.

## 2. Get your shows into Kodi

Kodi is only a player, so it needs to know where your media is.

**Plex:** install the **PM4K (PlexMod for Kodi)** add-on, which looks and works much like the Plex app.

1. In Kodi go to **Add-ons → Download → Program add-ons** and look for **PM4K for Kodi**. Install it, open it, and sign in to Plex (it shows a code to enter at [plex.tv/link](https://plex.tv/link)).
2. The copy in Kodi's own catalogue can lag behind. If it's missing or misbehaves, the [PM4K forum thread](https://forums.plex.tv/t/pm4k-plexmod-for-kodi-18-19-20-21-22/481208) explains how to install the current version from its own repository.

An alternative is [PlexKodiConnect](https://github.com/croneter/PlexKodiConnect), which puts your Plex library into Kodi's own menus.

**Jellyfin:** install the official [Jellyfin for Kodi](https://jellyfin.org/docs/general/clients/kodi/) add-on.

**Files on a network share or USB drive:** **Videos → Files → Add videos…** and browse to them.

## 3. Let TV Relay talk to Kodi

1. In Kodi: **Settings (gear) → Services → Control**.
2. Turn on **Allow remote control via HTTP**.
3. Leave **Port** at `8080` and **Username** at `kodi`. Set a **Password**. (If you don't see the username and password fields, change the settings level at the bottom left to Standard or higher.)
4. On your phone, open TV Relay's **Kodi sync** tab. Open **Kodi login** and enter the same port, username and password, then tap **Test connection**. Leave **Kodi IP** blank when Kodi runs on the TV you stream from; fill it in only if Kodi is on another device.

The card at the top of the tab turns green and says **Kodi connected**, or tells you what's wrong: the password, or Kodi couldn't be reached. The phone needs to know where the TV is, so tap **Listen** once first if the test says so.

Kodi has to be open on the TV for TV Relay to reach it. It's fine to leave it running in the background. The phone talks to Kodi over your Wi-Fi; Kodi's remote control isn't encrypted, so its password crosses your home network in plain text.

## 4. Turn it on from your phone

1. In TV Relay on the TV, choose **Start streaming**. On your phone, tap **Listen**.
2. Open the **Kodi sync** tab on the phone. The card at the top should say **Kodi connected**.
3. Set **Mode** to **Automatic**. TV Relay then follows the measured Wi-Fi delay by itself.
4. Play something in Kodi and listen on your phone. If the sound still seems late, move **Adjustment** to the right; if it's early, move it to the left. The **−5 ms / +5 ms** buttons are for fine-tuning.

**Manual** mode ignores the measured delay and uses only your adjustment. With Bluetooth headphones, stay on Automatic and add their delay with the slider: Android doesn't always report it.

If moving the slider right makes the sound *later* instead of earlier, turn on **Flip direction**.

Turn on **Show on Listen tab** to have Mode and Adjustment above the latency graph as well, so you can fine-tune without switching tabs.

TV Relay on the TV shows a green **Kodi server found** under Start streaming when Kodi's remote control is reachable on port 8080.

## Troubleshooting

| What the phone says | What to do |
| --- | --- |
| Can't reach Kodi | Open Kodi on the TV and check **Allow remote control via HTTP** is on. |
| The username or password is wrong | Re-enter them under **Kodi login** on the phone's Kodi sync tab. |
| The phone doesn't know where the TV is yet | Tap **Listen** once, or enter the TV's IP (shown on the TV under **Start streaming**) under **Connect directly**. |
| Waiting for the TV… | Start streaming on the TV and tap Listen on the phone. |

Notes:

- Kodi resets its audio delay for every new video; TV Relay reapplies it automatically.
- Kodi only accepts 25 ms steps. The phone makes up the rest, so the adjustment works in 5 ms steps.
- When you stop listening or turn lip sync off, Kodi's delay goes back to zero.
