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
- **Optional Gallery screensaver.** Public-domain landscape paintings in 16:9, downloaded only if you choose it, a folder of your own photos, or Aerial Views: Apple's aerial videos.
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

### Set up over adb with one command

[`tools/plume-install.sh`](tools/plume-install.sh) does the adb steps for you. Turn on ADB debugging on the TV, then run it from a computer on the same network:

```sh
./plume-install.sh 192.168.1.20      # your TV's address; add -a plume.apk to install Plume first
```

It finds out what the TV is (Fire TV and its Fire OS version, Google TV, or Android TV), tells you what it found and what it is going to change, and asks before it changes anything. Then it gives Plume the `WRITE_SECURE_SETTINGS` grant, switches on the Home button service and waits until the TV says the service is running. It checks each change on the TV after it makes it, and it stops with a message when one did not work. On a Fire TV with Android 9 to 11 it also looks whether Plume has set the alarm timing that removes the 5-second wait (see [Fire TV](#fire-tv)). A Plume without the alarm change leaves it, and Home then takes about 5 seconds. `-n` only shows the plan.

Before each change, the script writes a line about it in a recovery record on the TV (`/data/local/tmp/plume-install.state`), and it does not make the change if it cannot write and read back that line. `./plume-install.sh --undo` puts back what the record lists and nothing else: it turns the Home button service off only if the script turned it on, waits until Plume has put its alarm setting back, and takes the grant back only if the script gave it. A service that was on already stays on. `--undo` does not uninstall Plume, also when `-a` installed it: remove it in **Settings → Apps**.

The script never turns off the TV's own launcher unless you add `--disable-stock-launcher`, which works on a Fire TV only (it can stop other TVs from starting). It turns the launcher off last, after the recovery record is written and Plume's service runs. Then it opens Settings and presses Home to see that Plume answers. If the service does not run or Home does not open Plume, it turns the launcher on again. `--undo` also turns that launcher on again.

The script is tested against a fake `adb`, with no TV: `sh tools/plume-install-test/run.sh`. It covers the TVs it knows (Fire OS 7, 8 and 14, Google TV), a service that was on already, commands that fail, a recovery record that cannot be written, and the stock launcher with its recovery.

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

### Fire TV

Fire OS never gives the Home key to apps, and it keeps Amazon's home as the home screen. So on a Fire TV the Home button service watches for Amazon's home screen and covers it with Plume. This has limits:

- **Plume shows about 5 seconds after you press Home** on Fire OS 7 (Android 9). Amazon's home shows first. Android holds back a start from an app for 5 seconds after Home, and Plume can't skip that wait. See [#153](../../issues/153).
- **Amazon's Network status screen starts with no highlighted button** while **Plume: Home button** is on. The buttons still work: press Down until **Advanced** is highlighted (four presses on one Fire OS 7 TV). If that doesn't work on your TV, turn the Home button off in **Plume settings → Remote & Home button**, use the screen, and turn it on again. It happens when any accessibility service is on. One other Fire TV launcher reports the same on Fire OS 8.
- **Remote keys can't be set.** Fire OS doesn't pass the keys to Plume.

If Home doesn't reach Plume on your Fire TV, run [`tools/plume-firetv-probe.sh`](tools/plume-firetv-probe.sh) and post the result in [#153](../../issues/153).

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

For moving wallpaper, open **Video wallpapers** under Light wallpaper or Dark wallpaper. Choose a NASA, Apple Aerial, or Amazon Fire TV scene to download and preview it, then select **Use this video**. Pick **Surprise me** for a random scene, or browse the catalog by source and category: [NASA Scientific Visualization Studio](https://svs.gsfc.nasa.gov/4019) (4 scenes), [Apple Aerial](https://github.com/theothernt/AerialViews/blob/master/app/src/main/res/raw/tvos26.json) (139 scenes), and [Amazon Fire TV](https://github.com/theothernt/AerialViews/blob/master/app/src/main/res/raw/fireos8.json) (112 scenes). NASA credit: NASA Goddard Space Flight Center Scientific Visualization Studio and Earth Observatory. You can also choose **Add your video from phone…** and upload an H.264 or HEVC MP4 file up to 1080p and 350 MB. Plume mutes and loops the video on the home screen and stops playback when you leave it. Downloads and uploads need free space on the TV. Each theme has its own wallpaper choice.

Open **Plume settings → Wallpaper & theme → Light wallpaper** or **Dark wallpaper** and choose **Add from phone…**. Scan the QR code with your phone (it must be on the same Wi-Fi as the TV), pick a photo and upload it. It becomes the wallpaper straight away. The TV turns it upright, crops it to the screen around its centre and saves a small copy. The upload page only works while that screen is open. **Remove a photo…** in the same list deletes one.

You can also copy `.jpg`, `.png` or `.webp` files to `Android/data/dev.plume/files/wallpapers/` yourself:

```sh
adb push my-wallpaper.jpg /sdcard/Android/data/dev.plume/files/wallpapers/
```

Plume shows the photo as it is. The text picks white or black to suit the picture behind it.

### Photo folders

Choose **Choose a folder on this TV…** in the wallpaper list to use a whole folder of photos, from the TV's storage or a USB drive. Plume asks for access to your photos the first time. The photos take turns in shuffled order; **Plume settings → Wallpaper & theme → Change photo every** sets how often, from every minute to once a day. The screensaver can show a folder too: **Plume settings → Screensaver → Screensaver → Your photos**.

### Lock layout

**Plume settings → Lock layout** sets a 4-digit PIN. While it's on, Plume settings and anything that changes the home screen (moving, removing or pinning apps, hiding shows) ask for the PIN first; opening apps works as usual. One PIN unlocks changes for five minutes, until you leave the home screen. Use the arrows or the remote's number keys to enter it.

The lock keeps the layout safe, it isn't parental control: apps can still be opened by voice search or from Android settings. Forgot the PIN? **Android Settings → Apps → Plume → Clear data** removes it, along with your layout and settings.

### Gallery screensaver

Select **Plume settings → Screensaver → Screensaver** to switch to Gallery. Plume downloads the paintings (4.7 MB) from this repository's releases and stores them privately. Switching back to another screensaver deletes them again, so they only take space while you use them.

### Aerial Views screensaver

Select **Plume settings → Screensaver → Screensaver → Aerial Views**. Select a scene to download it, then select **Use Aerial Views**. The screensaver plays the downloaded scenes in shuffled order. Press ◀ or ▶ to skip a scene. The screensaver and the video wallpaper use the same files, so Plume downloads each scene only once. Select a downloaded scene to delete it. Plume does not delete the scene that the wallpaper uses.

## Privacy

Plume has no analytics, no ads and no account. It only connects to the internet to:
- load poster images for your Continue Watching items
- check this repository's GitHub releases for a new version, once a day, and download it when you choose to update
- download the Gallery paintings from this repository, **only if you choose that screensaver**
- get the Bing picture of the day (about 300 KB), **only if you choose it as your wallpaper**. Plume sends a request to [bing.com](https://www.bing.com) when the home screen opens: once a day, or once an hour until the picture has changed that day, because Bing changes it at a set time for each country. Bing sees the TV's IP address and its language and country, which Plume sends to get the picture for your country. Plume keeps one picture and deletes it when you choose another wallpaper.
- download one NASA Earth video from [svs.gsfc.nasa.gov](https://svs.gsfc.nasa.gov/4019), **only when you select that scene**. NASA sees the TV's IP address. Plume stores the video on the TV.
- download an Apple Aerial video from [sylvan.apple.com](https://sylvan.apple.com) or an Amazon Fire TV video from [Amazon's Fire TV CDN](https://aebcs-cdn-prod-na.services.firetv.amazon.dev), **only when you select that scene**. The source sees the TV's IP address. Plume stores the video on the TV.
- fetch the weather, **only if you turn it on**. It looks up your approximate location from your IP address via [ipwho.is](https://ipwho.is), then gets the forecast from [Open-Meteo](https://open-meteo.com). The Auto theme uses the same location to work out sunrise and sunset on the TV, with no extra request. Plume keeps the location on the TV after you turn the weather off, so Auto still follows the sun; clearing Plume's data removes it.

**Add from phone…** runs a small upload page on the TV itself, reachable only on your home network and only while that screen is open. Photos go straight from your phone to the TV and nowhere else.
**Add your video from phone…** uses the same local upload page. Videos stay on the TV.

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
- **Wallpapers:** "The Lonely Tree, Garda Lake" by [Giuseppe Milo](https://commons.wikimedia.org/wiki/File:The_Lonely_Tree_Garda_Lake_Italy_Fine_Art_Photography_(188093893).jpeg) (CC BY 3.0), and "Mountain Peak, Alaska" by [Godot13](https://commons.wikimedia.org/wiki/File:Mountain_Peak,_Alaska_(1999).jpg) (CC BY-SA 4.0), via Wikimedia Commons
- **Aerial Views:** the Apple Aerial scene list comes from the [Aerial Views](https://github.com/theothernt/AerialViews) project (GPL-3.0). Plume's Aerial Views screensaver is not part of that app.
- **App logos:** [Simple Icons](https://simpleicons.org) (CC0). All trademarks belong to their respective owners.
- **HDMI input and settings icons:** [Material Symbols](https://fonts.google.com/icons) by Google (Apache 2.0)

## License

Copyright (C) 2026 aradhyas8

Plume Launcher is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, modify and redistribute it, but any distributed version, modified or not, must also be released under the GPL-3.0 with its full source code.
