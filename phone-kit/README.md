# phone-kit

Puts the whole Android lock on a new or factory-reset phone from your Mac, over a USB cable. OwnDroid becomes the device owner, Feed Guard and Watch Limit go on and switch on, Private DNS is locked to your NextDNS address, Firefox becomes the browser, OwnDroid's restrictions go on, and at the end owndroid-key makes OwnDroid's new code. Your choices are in `my-phone.conf`, so every phone gets the same setup.

A phone takes about 20 minutes. About 10 taps stay yours, because Android and OwnDroid only take them on the phone's screen. The kit says each one, waits, and checks that it happened.

There's no image to flash instead. Android's backups and images never carry a device owner, its restrictions or the permissions adb grants. A custom image would also need an unlocked bootloader and would fail Google's certification, which banking apps and Wallet check.

## What it does

| | Step | Who |
|---|---|---|
| 1 | OwnDroid 8.4 installed and made the device owner | the kit |
| 2 | The secure settings permission taken from every app but Feed Guard, since with it an app can switch Private DNS off | the kit |
| 3 | Feed Guard and Watch Limit installed, their accessibility access on, Feed Guard's permissions granted, **Pause app activity if unused** off | the kit |
| 4 | Private DNS set to your NextDNS address, after checking the phone can look names up through it. Automatic date, time and time zone on | the kit |
| 5 | Firefox installed and made the default browser. Picture-in-picture off for the YouTube apps | you install Firefox from the Play Store, unless its APK is in `apks/extra` |
| 6 | In OwnDroid's settings, **Shortcuts** off and the **API** on, with a key made for this setup | you, about 5 taps. The kit types the key |
| 7 | Feed Guard and Watch Limit as delegated admins with **Change package state** | you, in OwnDroid |
| 8 | Feed Guard's settings: your app list, black and white | the kit writes the file. You import it in OwnDroid |
| 9 | Watch Limit's daily limit, locked | you |
| 10 | OwnDroid's restrictions, the hidden apps, and **Block uninstall** and **Disable user control** for Feed Guard, Watch Limit and Firefox | the kit, through OwnDroid's API |
| 11 | OwnDroid's API and Shortcuts off | you. The kit checks that neither answers any more |
| 12 | A check of all of the above, in Android's own reports | the kit |
| 13 | In Firefox: the NextDNS test page, the add-on, then instagram.com so Feed Guard starts guarding | you |
| 14 | **Debug features** on, the last switch: it ends USB debugging for good | you. The kit waits for the phone to go |
| 15 | A new OwnDroid code from owndroid-key, on your usual dates | owndroid-key |

The restrictions are: Configure private DNS, Configure VPN, Reset network, Add user, Switch user, Create private space (Android 15 and newer), Configure date or time, Safe boot, and Configure default apps (Android 14 and newer, with `LOCK_DEFAULT_APPS`). Debug features comes last of all. Factory reset stays allowed on purpose.

If it stops, because the cable came out, you pressed Ctrl-C or a check didn't pass, run `phone-kit setup` again. It goes on from where it was. A factory-reset phone starts from the beginning.

## Before the first phone

1. adb: `brew install android-platform-tools`. Or skip it: **Set up a phone.command** offers to download Android's platform tools from Google into this folder.
2. owndroid-key, installed as [its README](../owndroid-key/README.md) says. If it was installed before `seal --new-phone` existed, an administrator runs `sudo bash install.sh` in the new owndroid-key folder once, which keeps the sealed key.
3. This folder, next to owndroid-key in /Users/Shared. Its `apks` folder holds the apps:
   - `FeedGuard-0.2.0.apk` and `WatchLimit-1.0.apk` come with the kit.
   - OwnDroid: on the first run the kit downloads the regular OwnDroid v8.4 APK (never the testkey one) from its GitHub release. It keeps the APK and its fingerprint in `apks/OwnDroid.apk.sha256`, so every phone gets the same copy. You can also download it yourself and put it in `apks`.
   - `apks/extra`: any other APK, such as Firefox or your own apps. Each one is installed once on every phone.
4. Your settings: the first run makes `my-phone.conf` from `my-phone.conf.example`. Put in your NextDNS address and your apps. **Set up a phone.command** opens it in TextEdit for you.

## A new or reset phone

1. Factory-reset it (Settings › System › Reset options › Erase all data), or take it out of the box. In the setup screens, **don't sign in to Google** or add any account: Android only takes a device owner on a phone with no accounts. Do connect to Wi-Fi. You sign in at step 5.
2. Settings › About phone: tap **Build number** 7 times. Then Settings › System › Developer options: turn on **USB debugging**.
3. Plug it into the Mac, unlock it, and double-click **Set up a phone.command** in this folder. (Or, in Terminal: `/Users/Shared/phone-kit/phone-kit setup`.) On the phone, tap **Allow** on "Allow USB debugging?".
4. Do what it says. Keep the phone unlocked and the cable in. When it stops for something you need to do, press Enter in its window once that's done, and it goes on from there.

A new key at step 15 means the old phone's OwnDroid can't be opened again, only reset. Do it once the old phone is reset, sold or broken.

### Double-clicking Set up a phone.command

It opens a Terminal window and runs `phone-kit setup` for you, so there's nothing to type. The first time, macOS may not open it, because it was downloaded and isn't from the App Store:

- macOS 15 and newer: click **Done**, then open System Settings › Privacy & Security, scroll down, and click **Open Anyway** next to "Set up a phone.command". Then double-click it again.
- macOS 14 and older: Control-click it, choose **Open**, then **Open** again.

That's once per download. To stop, close the window: the next double-click goes on from where it was.

## Your settings

`my-phone.conf`, one `NAME=value` per line. Lists take package names, separated by spaces or commas.

| Setting | Default | What it does |
|---|---|---|
| `NEXTDNS_HOST` | | Your NextDNS configuration's address for Android, from my.nextdns.io › Setup › Android. Private DNS is set to it and locked |
| `APP_LIST` | empty | Apps Feed Guard never pauses. Browsers and Instagram or Reddit apps can't be on it |
| `ONLY_LISTED_APPS` | `no` | `yes` pauses every app that isn't on the list, apart from the phone's basics and Firefox |
| `BLACK_AND_WHITE` | `yes` | The screen stays black and white |
| `COLOR_APPS` | the maps apps | Apps that may show colour. `none` for none |
| `BLOCK_APPS` | empty | More apps to pause, as other browsers are |
| `HIDE_APPS` | Chrome, Firefox Focus, NextDNS | Apps OwnDroid hides. Apps that aren't on the phone are skipped |
| `PROTECT_APPS` | empty | More apps that can't be uninstalled, stopped or have their data cleared |
| `LOCK_DEFAULT_APPS` | as `ONLY_LISTED_APPS` | Locks the default apps on Android 14 and newer. Android never pauses the default home screen or phone app, so a new default would get past the list |
| `ADDON_PAGE` | empty | The add-on's page on addons.mozilla.org, for the kit to open in Firefox |
| `USE_OWNDROID_API` | `yes` | `no` makes step 10 taps: the kit lists each switch and checks it |

Feed Guard gets the list, black and white, colour apps and extra apps through its managed configuration, as in [its README](../feed-guard/README.md). In list mode, install the apps you want to keep before step 8. An app installed later is paused until it's on the list.

## OwnDroid's API

OwnDroid's API lets an app with its key change OwnDroid's settings without opening OwnDroid. The kit only uses it during setup, with a new random key for each phone, kept on this Mac and typed into the phone. The kit doesn't finish while the API answers, and it deletes the key once Debug features is on. OwnDroid's Shortcuts work the same way with an empty key, and they're on until someone turns them off, so the kit has you turn them off at step 6 and checks again at the end.

To check whether either still answers, the kit switches the harmless **Set user icon** restriction on through it and back off. If the phone shows "Shortcuts are disabled" during setup, that was the kit checking.

## Checking a phone

`phone-kit check` lists what's in place on the phone that's plugged in, and what isn't, without setting anything up. It only works until the last step, since nothing reaches the phone over USB after Debug features.

## What it doesn't do

- Delegated admins, Feed Guard's settings, OwnDroid's settings and Debug features stay taps. Android only takes them from OwnDroid's screen, and OwnDroid's API doesn't cover them.
- Signing in to Google, installing from the Play Store, Watch Limit's limit and the add-on are yours.
- Android 14 and older don't report **Block uninstall** and **Disable user control**, so on those the kit asks you to look in OwnDroid.
- After Debug features, nothing can change the phone over USB, the kit included. Later changes are made in OwnDroid while its code is open, and a factory reset starts over.

## Tests

`bash test/run.sh` sets up pretend phones: `test/fake-adb` answers as Android, OwnDroid 8.4 and Feed Guard do, and `test/taps` plays you tapping on the phone. It covers whole setups on Android 10, 14 and 16, list mode, the API and the taps routes, stops and restarts, a pulled cable, every check that stops the setup, and **Set up a phone.command**. It needs bash and python3.

Not yet tried on a real phone or a real Mac. The report formats come from Android's source for versions 14 to 16, and OwnDroid's screens and API from its v8.4 source. The tests ran with bash 5 on Linux, so the Mac's bash 3.2 and its BSD tools haven't run it yet.
