# Feed Guard

A small Android app that makes the Reddit NSFW Blocker add-on pointless to switch off on the phone.

Firefox for Android has no way to lock an add-on: anyone can turn it off in a few taps. Feed Guard closes that gap. **Instagram and Reddit only open in Firefox while the add-on is running on the page.** Turn the add-on off, remove it, or open those sites in a private tab where it isn't allowed, and Feed Guard covers the page within a few seconds.

It's built to sit next to OwnDroid and the "Tighter Android lock" setup, where OwnDroid's lock holds the only key.

Since 0.2.0 it can also turn the phone into a dumbphone: **the screen stays black and white** (maps come back in colour by themselves), **only the apps on your list open**, and **switching Feed Guard off doesn't stick**. All of it runs on stock Android, without unlocking the bootloader.

## What it does

- **Covers Instagram and Reddit in Firefox without the add-on.** The add-on (version 1.2.0 or newer) puts an invisible marker on every Instagram and Reddit page, with a timestamp it refreshes every 2 seconds. The marker only shows while the add-on's stylesheet is applied, and the timestamp only moves while its script runs. Feed Guard reads Firefox's address bar and looks for the marker through Android's accessibility service. Firefox removes both as soon as the add-on is turned off, so on an Instagram or Reddit page Feed Guard finds the marker gone or stale and covers the screen. The cover has two buttons: **Open a new tab** and **Go to the home screen**. Back works too.
- **Pauses other browsers and the Instagram and Reddit apps.** Any app that opens web links, apart from Firefox, is paused when it's installed. So are the Instagram and Reddit apps and the common third-party Reddit and Instagram apps. A paused app is greyed out and shows "blocked by your admin". This needs OwnDroid's "Change package state" delegation.
- **Pauses Firefox if Feed Guard itself is switched off** and can't switch itself back on (see below). Firefox stays paused until the access is back on. A check every 15 minutes, and one after each restart, catch the case where the switch-off happened while Feed Guard wasn't running.
- **Only starts covering pages once it has seen the add-on.** Until it first sees the marker, it covers nothing. You can install it before the add-on update reaches the phone.

It never lifts a pause it didn't make, so it can't undo a pause set in OwnDroid or by Watch Limit. If an app paused for being a browser is unpaused in OwnDroid, Feed Guard leaves that app alone until it's uninstalled and installed again.

### Dumbphone locks (0.2.0)

These need the **secure settings permission** (`WRITE_SECURE_SETTINGS`), which only adb can give. Feed Guard uses it for two settings and nothing else: colour correction, and its own accessibility access.

- **Black and white.** Feed Guard sets Android's colour correction to Grayscale and puts it back within a moment if anything changes it: the Quick Settings tile, the accessibility shortcut, Settings. Colour comes back only while nothing but a colour app is on screen, by default Google Maps, CoMaps or Organic Maps. Open the keyboard, pull down the shade, lock the phone or switch to any other app, and it's black and white again. When in doubt it chooses black and white. On by default once the permission is there.
- **Only apps on your list.** Off by default. Turned on, it pauses every app with an icon in the app drawer and every app you installed yourself, unless it's on your list. The phone's basics are never paused: calls, texts, contacts, the clock, the keyboard, the file picker, settings, emergency info, OwnDroid and Watch Limit. Unlike the browser rule, this one keeps re-pausing: an app unpaused in OwnDroid, or by Watch Limit, is paused again within a second. A newly installed app is paused as soon as it's installed, so sideloading something doesn't get it past the list.
- **The list is pinned to signatures.** For each listed app you installed yourself, Feed Guard remembers the certificate it was signed with. If that app is later replaced by one with the same name but a different signer, such as a browser repackaged as `com.whatsapp`, it doesn't count as listed. To accept a new copy, take it off the list, save, and put it back.
- **Switching it off doesn't stick.** If its accessibility access is switched off, Feed Guard switches it straight back on, and checks again a few seconds later and every 15 minutes. Without the permission it falls back to the old dead-man switch and pauses Firefox.
- **It points out holes.** The status screen lists apps Android won't let it pause, listed apps that aren't the copy that was listed, and any other app holding the secure settings permission, since that permission can switch Private DNS off.

## What it reads

Firefox's windows: the address bar and the page's accessibility tree. That's the same information a screen reader gets. For every other window it reads only which app it belongs to, to know when a map is the only thing on screen. It stores the last time it saw the add-on and the address bar, the list of apps it paused, and the signature fingerprints of listed apps. Nothing leaves the phone, and the app has no internet permission.

The status screen shows what it last saw in Firefox: the page's host (never the full address), whether the marker was there, and whether the page was covered. If something doesn't work, a screenshot of that screen is the quickest way to find out why.

## Setup

Do this during the OwnDroid setup in the "Tighter Android lock" guide. **Install it after that guide's Step 2 and before its Step 3**: Step 2 takes the secure settings permission away from every app, and Step 3 turns off USB debugging. **Do the OwnDroid part before Step 4**, which locks OwnDroid.

1. **Install it from your Mac**, while USB debugging is still on, and give it the secure settings permission:
   ```
   adb install -r FeedGuard-0.2.0.apk
   adb shell pm grant io.github.zinzuzu.feedguard android.permission.WRITE_SECURE_SETTINGS
   ```
   Installing over adb avoids Android's "restricted setting" block on accessibility for downloaded apps, and Google's sideloading checks. The screen turns black and white once step 2 below is done.
2. **Turn it on.** Open Feed Guard, tap **Open accessibility settings**, choose **Feed Guard** and turn it on. Allow notifications when it asks.
3. **Settings › Apps › Feed Guard:** turn off **Pause app activity if unused**.
4. **In OwnDroid:**
   - Tap the shield icon › **Delegated admins** › add `io.github.zinzuzu.feedguard` with **Change package state**. Feed Guard pauses Chrome, Firefox Focus and any other browser straight away.
   - **Applications**: open Feed Guard and turn on **Block uninstall** and **Disable user control**. Do the same for **Firefox**, so it can't be uninstalled or have its data cleared, either of which would remove the add-on.
5. **Make Firefox your default browser** (Settings › Apps › Default apps › Browser), so links from other apps open in Firefox rather than in a paused browser.
6. **Arm it.** Once Firefox has add-on version 1.2.0 or newer, open instagram.com in Firefox. Feed Guard says "Feed Guard is on" and the status screen shows **Add-on check: Active**.
7. **Test it.** On instagram.com, turn the add-on off in Firefox's menu › Extensions. Within a few seconds the page is covered. Turn the add-on back on. Then turn Feed Guard off in Accessibility settings: it should switch itself back on within a second or two. Try the Color correction tile in Quick Settings: the screen stays black and white. Open Google Maps: it's in colour; open the keyboard in it and it's black and white again.

### Settings, in OwnDroid

OwnDroid › Applications › Feed Guard › **Managed configuration**. A setting nobody has set uses its default.

| Setting | Key | Default | What it does |
|---|---|---|---|
| Apps to leave alone (your list) | `allowed_apps` | empty | Package names, separated by commas. Never paused; Feed Guard lifts its own pause on them. With "Only apps on the list" on, these are the only apps that open. |
| Only apps on the list | `only_listed_apps` | off | Pause every app that isn't on the list, apart from the basics. |
| Black and white | `grayscale` | on | Keep the screen black and white (needs the secure settings permission). Turned off here, Feed Guard gives colour back once and leaves colour correction to you. |
| Apps shown in colour | `color_apps` | Google Maps, CoMaps, Organic Maps | Package names. Write `none` for no colour at all. |
| Extra apps to pause | `blocked_apps` | empty | More package names, paused like browsers. |

### Turning on "Only apps on the list"

1. **Install everything you want to keep first**, while it can still open: banking, authenticator, maps, messengers. An app installed after the list is on gets paused on arrival, and its signature is pinned the moment it's added to the list.
2. **Build the list.** Open Feed Guard › **Pick apps for your list**, tick the apps and tap **Copy**. It copies their package names; it doesn't change anything by itself. Don't forget your authenticator, banking apps, a file manager (to open APKs you sideload) and the camera if you want one. The basics never need listing.
3. **In OwnDroid's managed configuration for Feed Guard**, paste into **Apps to leave alone**, turn on **Only apps on the list**, and save. Feed Guard pauses everything else within a second and lists it on its status screen, with package names, in case something you need is missing.
4. **The Play Store can't be paused.** Android keeps it unpausable because it checks app installs. If you don't want it, hide it in OwnDroid › Applications. You then update your apps by sideloading. If you keep it, anything installed from it is paused on arrival, and your listed apps keep getting updates.
5. **Lock the defaults.** Android won't let any app pause the default home screen or phone app, so a sideloaded app made the default would get past the list. If OwnDroid offers the restriction **Config default apps** (Android 14 and newer), turn it on once Firefox, your launcher and your phone app are set as defaults.

## What it doesn't cover

- **Browsers built into other apps.** Telegram, Messenger and similar apps have their own in-app browsers. Feed Guard doesn't watch them. Instagram there means logging in again each time, which is slow, but possible. With "Only apps on the list" on, this only matters for listed apps, Telegram above all.
- **Apps Android won't let it pause:** the Play Store, the default home screen and phone app, and apps that are device admins. The status screen lists any it tried.
- **A split second of colour.** Leaving a map for another app can show that app in colour for a moment before the grey comes back.
- **OwnDroid's code and a factory reset.** Both undo everything, on purpose.
- **A future Firefox redesign.** If Firefox changes its address bar, Feed Guard warns you that it can't read it and falls back to page titles. That still catches the Instagram and Reddit home feeds, but not every page. It then needs an update.
- **Speed.** Any accessibility service makes Firefox turn on its accessibility support, which costs a little speed on heavy pages. Watch Limit does the same.

## Building

No Gradle needed. On Ubuntu:

```
sudo apt-get install aapt zipalign apksigner android-sdk-platform-23 openjdk-17-jdk-headless
bash fetch-tools.sh        # android.jar (Android 16) and Google's D8 dexer
bash test/run.sh           # off-phone tests
KEYSTORE=/path/feed-guard.p12 KS_PASS=... bash build.sh
```

The signing key isn't in this repo, and must never be. Android lets anyone holding it install an update over the locked app, and **Block uninstall** doesn't stop an update. Since 0.2.0 that matters more: an update would inherit the secure settings permission, which can switch Private DNS off, and sideloading is allowed on the dumbphone. Keep the key out of easy reach, for example sealed with the same time lock as OwnDroid's code. Today it lives in the project's shared files.

## How the pieces fit

| File | Job |
|---|---|
| `GuardService.java` | The accessibility service: checks Firefox, shows the cover, switches itself back on, dead-man switch |
| `PageInspector.java` | Finds Firefox's address bar, loading bar and the add-on marker |
| `CoverDecision.java` | Decides when to cover: grace period, loading, memory, arming |
| `UrlText.java`, `MarkerText.java` | Read the address and the marker |
| `AppGate.java`, `AppRules.java`, `Pins.java` | Which apps to pause (browser rule, list rule, the basics), signature pins, and pausing through OwnDroid |
| `ColorGuard.java`, `ColorRules.java` | Black and white, and when maps may show colour |
| `SecureSettings.java`, `ServiceList.java` | The only two settings the secure settings permission is used for |
| `Config.java` | The settings from OwnDroid's managed configuration |
| `WatchdogJob.java`, `BootReceiver.java` | The 15-minute, after-restart and right-after-switch-off safety net |
| `MainActivity.java` | Status screen and the list picker, with no off switch |
| `../extension/guard-marker.js`, `.css` | The marker, in the add-on |
