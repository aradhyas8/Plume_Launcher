<p align="center">
  <img src="docs/plume-icon.png" alt="Plume logo" width="128">
</p>

<h1 align="center">Plume Launcher</h1>

<p align="center">
  <em>Light as a feather.</em> A minimal, ad-free home screen for Android TV and Google TV.
</p>

<p align="center">
  <a href="https://github.com/aradhyas8/Plume_Launcher/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/aradhyas8/Plume_Launcher?label=release"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <img alt="APK size" src="https://img.shields.io/badge/APK-0.4%20MB-blue">
  <a href="https://github.com/aradhyas8/Plume_Launcher/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/aradhyas8/Plume_Launcher/total"></a>
  <a href="LICENSE"><img alt="GPL-3.0 License" src="https://img.shields.io/github/license/aradhyas8/Plume_Launcher"></a>
</p>

<p align="center">
  <img src="docs/home-light.jpg" alt="Plume home screen, light theme" width="49%">
  <img src="docs/home-dark.jpg" alt="Plume home screen, dark theme" width="49%">
</p>

Plume replaces the cluttered stock home screen with the two things you actually use: your apps and what you were watching. There are no ads, no sponsored rows, no account and no tracking, and it stays out of the way of the apps it launches.

## Highlights

- **Tiny and fast.** A 0.4 MB APK with no third-party libraries. It uses about 50–70 MB of RAM and has no extra background services.
- **Home button support on Google TV.** The Home button opens Plume directly, even on devices where the system launcher can't be replaced. Holding Home still opens the Google TV dashboard.
- **Continue Watching.** Resume shows and films from Android TV's Watch Next list and from apps that publish their own "Continue Watching" channel, such as Stremio.
- **Clean apps row.** Monochrome logos (or the same logos in brand colour, or each app's own banner) in a single row, with names on focus, always or never. Rearrange it by holding OK, sort by most used, or hide apps you don't use (they stay in All apps). An optional PIN locks the layout so small hands can't rearrange it.
- **Sources list.** The Sources icon in the top bar opens the list of the TV inputs. A console or set-top box that names itself over HDMI-CEC (for example "PlayStation 5") shows with its logo, and selecting it switches the TV to that input. A port with no signal says so. Hold OK on an input to put it in the apps row, and again to take it out.
- **Remote keys.** Change what a button on your remote does, for example the TV, Netflix or Settings button. A key can do one thing on a press and another when you hold it. See [Remote keys](#remote-keys).
- **Light, dark or automatic theme**, each with its own wallpaper. Auto follows sunrise and sunset where you are (from 7:00 to 19:00 until Plume has a location; it keeps that location if you turn the weather off later). Add your own photos from your phone by scanning a QR code, or show a folder of photos from the TV or a USB drive that changes on a timer.
- **Optional Gallery screensaver.** Public-domain landscape paintings in 16:9, downloaded only if you choose it, or a folder of your own photos.
- **Optional weather** under the clock.
- **Updates itself.** Plume checks GitHub once a day and installs new versions from its settings (from 1.2 on). The first update asks you to allow installs from Plume.

## How it compares

Measured on a Hisense Google TV (Android 12, 1.8 GB RAM):

| | Plume | Projectivy Launcher | Google TV launcher |
|---|---|---|---|
| APK size | **0.4 MB** | ~10–14 MB | ~25 MB |
| RAM while running | **~50–70 MB** | ~65–70 MB | ~93 MB, plus a ~22 MB helper process |
| Extra background services | **None** | 2 | 1 always-on helper |
| Return to home from an app | **~95 ms** | – | – |

## Requirements

- Android TV or Google TV running **Android 8.0 (Oreo) or newer**
- A way to sideload an APK: the *Downloader* app, a USB drive with a file manager, or `adb`

## Installation

1. Download the latest `Plume-<version>.apk` from the [Releases](https://github.com/aradhyas8/Plume_Launcher/releases/latest) page.
2. Install it on your TV, using any of these:
   - **Downloader app:** enter the code **`2125356`**. It always points to the latest release.
   - **USB drive:** copy the APK over and open it with a file manager.
   - **adb:** `adb install Plume-<version>.apk`
3. Open **Plume** from your apps. Its settings panel opens automatically on first launch.

### First-run setup

Until one of them is on, Plume settings shows a **Finish setup** card at the top. Both rows are also under **Plume settings → Remote & Home button**:

| Setting | What to do |
|---|---|
| **Home button** | Press OK. In the Accessibility screen that opens, select **Plume: Home button** and turn it on. |
| **Default launcher** | Press OK and choose **Plume** in the dialog. |

Allow the **TV listings** permission when asked; Continue Watching needs it.

> [!NOTE]
> **Holding Volume moves one step?** Many TVs act on the volume keys before any accessibility service sees them, so with the Home button on, a held Volume key changes the volume only once. Turn on **Plume settings → Remote & Home button → Smooth volume** to get the normal hold back. In exchange, Home shows Google TV for about a second before Plume opens, and Smooth volume stays off while any **Remote keys** are set.

> [!NOTE]
> Android shows the same generic warning for every accessibility service. Plume's service listens for the Home key, for the keys you set under **Remote keys** and for the system launcher appearing. It cannot read screen content.

> [!TIP]
> **Android 13 and newer:** Android blocks sideloaded apps from Accessibility at first. Open **Settings → Apps → Plume**, choose **Allow restricted settings** from the menu, then turn the service on.

### Optional: grant one extra permission over adb

```sh
adb shell pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS
```

This lets Plume:
- set the Gallery screensaver from its own settings
- turn its Home button service back on automatically if the app is ever force-stopped

Everything else works without it.

### Home button on Android 13 and newer

Android 13 and newer grey out the Accessibility switch for apps installed outside the Play Store, and most Google TV builds hide the **Allow restricted settings** menu that would unlock it. If Plume's setup reaches the ADB step, unlock it once from a computer (or a phone ADB app) on the same network:

1. On the TV, open **Settings → System → About** and press **Android TV OS build** seven times to turn on Developer options.
2. In **Settings → System → Developer options**, turn on **Network debugging** (called **USB debugging** on some TVs).
3. Run these, using the address Plume shows:

   ```sh
   adb connect <tv-address>:5555
   adb shell pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS
   ```

4. Accept the debugging prompt on the TV, then choose **Try again** in Plume. Plume turns its Home button service on by itself.

## Using Plume

| Action | How |
|---|---|
| Rearrange apps | Hold **OK** on an app, or use **Plume settings → Apps row → Rearrange apps** |
| Hide a Continue Watching item | Hold **OK** on its card, then **Remove from Continue Watching** |
| Remove an app from the row | Hold **OK** on it, then **Remove from row** (it stays in *All apps*) |
| Choose which apps feed Continue Watching | **Plume settings → Shows row → From apps** |
| Pin an app from *All apps* to the front | Open **All apps**, hold **OK** on the app (this also unhides it) |
| Hide apps from the row | **Plume settings → Apps row → Choose apps** |
| Show app names always, on focus or never | **Plume settings → Apps row → App names** |
| Switch between monochrome icons, colour icons and app banners | **Plume settings → Apps row → Icon style** |
| Update Plume | **Plume settings → Updates** |
| Change what a remote key does | **Plume settings → Remote & Home button → Remote keys** |
| Lock the layout with a PIN | **Plume settings → Lock layout** |
| Move, hide or add a top-bar icon | Hold **OK** on an icon at the top of the home screen, then select **Move**, **Hide** or **Add icon** |
| Open the Google TV dashboard | Hold the **Home** button |

### Remote keys

**Plume settings → Remote & Home button → Remote keys → Add a key**, then press the key on your remote. Plume detects it. For each key, choose what a **Press** does and what a **Hold** does:

- open an app or an HDMI input (**Open an app or input…**)
- a media key: Play / Pause, Next, Previous, Stop, Rewind or Fast-forward
- a system action: Back, Recent apps, Notifications, Quick settings or the Power menu
- open **Android settings** or **Network settings**
- **Do nothing**

Back, OK, the arrows, Home, Power and the volume keys can't be changed, so the remote can't be locked out. While any key is set, **Smooth volume** is off. A key you set stops doing its usual job. **Remove this key** gives it back. The keys work through the Home button service, so **Plume: Home button** must be on in Accessibility settings.

### Custom wallpapers

Open **Plume settings → Wallpaper & theme → Light wallpaper** or **Dark wallpaper** and choose **Add from phone…**. Scan the QR code with your phone (it must be on the same Wi-Fi as the TV), pick a photo and upload it. It becomes the wallpaper straight away. The TV turns it upright, crops it to the screen around its centre and saves a small copy. The upload page only works while that screen is open. **Remove a photo…** in the same list deletes one.

You can also copy `.jpg`, `.png` or `.webp` files to `Android/data/dev.plume/files/wallpapers/` yourself:

```sh
adb push my-wallpaper.jpg /sdcard/Android/data/dev.plume/files/wallpapers/
```

A gentle fade is added automatically so the rows stay readable (**Plume settings → Wallpaper & theme → Photo fade** turns it off).

### Photo folders

Choose **Choose a folder on this TV…** in the wallpaper list to use a whole folder of photos, from the TV's storage or a USB drive. Plume asks for access to your photos the first time. The photos take turns in shuffled order; **Plume settings → Wallpaper & theme → Change photo every** sets how often, from every minute to once a day. The screensaver can show a folder too: **Plume settings → Screensaver → Screensaver → Your photos**.

### Lock layout

**Plume settings → Lock layout** sets a 4-digit PIN. While it's on, Plume settings and anything that changes the home screen (moving, removing or pinning apps, hiding shows) ask for the PIN first; opening apps works as usual. One PIN unlocks changes for five minutes, until you leave the home screen. Use the arrows or the remote's number keys to enter it.

The lock keeps the layout safe, it isn't parental control: apps can still be opened by voice search or from Android settings. Forgot the PIN? **Android Settings → Apps → Plume → Clear data** removes it, along with your layout and settings.

### Gallery screensaver

Select **Plume settings → Screensaver → Screensaver** to switch to Gallery. Plume downloads the paintings (4.7 MB) from this repository's releases and stores them privately. Switching back to another screensaver deletes them again, so they only take space while you use them.

## Privacy

Plume has no analytics, no ads and no account. It only connects to the internet to:
- load poster images for your Continue Watching items
- check this repository's GitHub releases for a new version, once a day, and download it when you choose to update
- download the Gallery paintings from this repository, **only if you choose that screensaver**
- get the Bing picture of the day (about 300 KB), **only if you choose it as your wallpaper**. Plume sends a request to [bing.com](https://www.bing.com) when the home screen opens: once a day, or once an hour until the picture has changed that day, because Bing changes it at a set time for each country. Bing sees the TV's IP address and its language and country, which Plume sends to get the picture for your country. Plume keeps one picture and deletes it when you choose another wallpaper.
- fetch the weather, **only if you turn it on**. It looks up your approximate location from your IP address via [ipwho.is](https://ipwho.is), then gets the forecast from [Open-Meteo](https://open-meteo.com). The Auto theme uses the same location to work out sunrise and sunset on the TV, with no extra request. Plume keeps the location on the TV after you turn the weather off, so Auto still follows the sun; clearing Plume's data removes it.

**Add from phone…** runs a small upload page on the TV itself, reachable only on your home network and only while that screen is open. Photos go straight from your phone to the TV and nowhere else.

## Troubleshooting

<details>
<summary><b>The Home button still opens the old home screen</b></summary>

Check that **Plume settings → Remote & Home button → Home button** shows **On**. If the app was force-stopped, Android turns its accessibility service off; open Plume once (or grant the optional adb permission above) to restore it.
</details>

<details>
<summary><b>Continue Watching is empty</b></summary>

Make sure the TV listings permission is allowed (**Settings → Apps → Plume → Permissions**), and that the app you watch publishes to Android TV's Watch Next list. Check **Plume settings → Shows row → From apps** in Plume too.
</details>

<details>
<summary><b>The screensaver option says it needs an adb permission</b></summary>

Google TV hides third-party screensavers from its own settings, so Plume needs the one-time `WRITE_SECURE_SETTINGS` grant shown above to switch it for you.
</details>

## Building from source

```sh
git clone https://github.com/aradhyas8/Plume_Launcher.git
cd Plume_Launcher
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Without a `keystore.properties` file, release builds are signed with your local debug key, which is fine for installing your own build.

## Contributing

Bug reports, design ideas, translations and pull requests are welcome. Plume stays small on purpose, so please read [CONTRIBUTING.md](CONTRIBUTING.md) first: it lists what's in and out of scope and how to send a change. Open an issue before starting on a feature.

## Credits

- **Screensaver paintings** (public domain, scans via Wikimedia Commons; files in [`screensaver/`](screensaver)):
  - Frederic Edwin Church: *The Heart of the Andes*, *The Icebergs*, *Chimborazo*, *Cotopaxi*
  - Ivan Shishkin: *Rye*
  - Jasper Francis Cropsey: *Autumn on the Hudson River*
  - Martin Johnson Heade: *Thunder Storm on Narragansett Bay*
- **Wallpapers:** "Canada" by Thomas Curryer, via [Unsplash](https://unsplash.com/@tawc20) (CC0)
- **App logos:** [Simple Icons](https://simpleicons.org) (CC0). All trademarks belong to their respective owners.
- **HDMI input and settings icons:** [Material Symbols](https://fonts.google.com/icons) by Google (Apache 2.0)

## License

Copyright (C) 2026 aradhyas8

Plume Launcher is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, modify and redistribute it, but any distributed version, modified or not, must also be released under the GPL-3.0 with its full source code.
