# Watch Limit

An Android app that gives YouTube a daily time limit counting only the time you actually watch: the screen is on, the phone is unlocked and a YouTube app is showing. Listening with the screen off, or with YouTube playing behind another app, never counts and keeps playing after the limit. It's built to be hard to undo on a phone where OwnDroid is the device owner.

## What counts

| Counts | Never counts |
| --- | --- |
| Screen on, phone unlocked, and a YouTube app on screen: full screen, split screen, a floating window or picture-in-picture | Screen off, even while a video or podcast plays |
| | The lock screen, including its media controls |
| | YouTube playing in the background while another app is open |

Watched apps share one limit: YouTube ReVanced, YouTube, RVX, Morphe, Vanced, YouTube Kids, NewPipe, Tubular, PipePipe, LibreTube and Grayjay. More can be added by package name. YouTube Music is left out on purpose.

At the limit, a block screen covers the app. It doesn't pause anything, so audio keeps playing. A few short audio passes a day (3 × 1 minute by default) let you open YouTube to pick something to listen to.

## Why it's hard to undo

- **Delayed loosening.** In setup mode every change applies at once. After **Lock the settings**, stricter changes apply at once, but anything that allows more YouTube waits a set time first (24 hours by default, up to 30 days) and can be cancelled meanwhile. The wait runs on the monotonic clock, so changing the date doesn't shorten it.
- **Clock changes are ignored.** The day resets at local midnight on a trusted clock. Manual clock jumps are detected against the time since boot, Android's network time (Android 13+) corrects the rest, and a time zone change can't bring the reset forward by more than 4 hours.
- **Turning the watcher off pauses YouTube.** With OwnDroid's "Change package state" delegation, switching the accessibility service off suspends the installed YouTube apps until it's back on. A background check every 15 minutes also pauses any YouTube app installed or reinstalled while it's off.
- **OwnDroid closes the rest.** Block uninstall, Disable user control (no Force stop or Clear storage) and the "Configure date or time" restriction. A force stop matters most: Android silently switches off the accessibility services of a force-stopped app.

## Setup

1. Install `WatchLimit-<version>.apk`. If Android blocks the install, use `adb install`.
2. Turn on Watch Limit under Settings → Accessibility. If Android calls it a restricted setting, open App info → ⋮ → **Allow restricted settings** first.
3. Test with a 1-minute limit, then set the real limit and tap **Lock the settings**.
4. In OwnDroid (before locking OwnDroid itself):
   - shield icon → Delegated admins → Add delegated admin → Watch Limit with **Change package state**
   - Applications state → **Block uninstall** → Watch Limit
   - Applications state → **Disable user control** → Watch Limit
   - User restriction → Other → **Configure date or time** (turn on automatic date, time and time zone first)
5. Turn off picture-in-picture for YouTube, so leaving it during a video plays in the background instead of in a small window that counts.

The app's Protection section shows which of these are in place.

## Building

Needs a JDK (17 or newer), curl, unzip and zip, on Linux or macOS. No Android Studio or Gradle.

```sh
KEYSTORE=path/to/watch-limit.p12 KEYSTORE_PASS=... bash build.sh
```

The first run downloads aapt2 (from the apktool library), dx, apksig and an API 36 `android.jar`, each checked against a pinned SHA-256. The script runs the unit tests, compiles, signs with APK Signature Scheme v2 and writes `build/WatchLimit-<version>.apk`.

Android installs an update only if it's signed with the same key as the installed copy, and Watch Limit can't be uninstalled once OwnDroid blocks it, so keep the key. Without `KEYSTORE`, the script makes a throwaway key in `.tools/`, which only suits a first install.

## How it works

- `WatchService` is an accessibility service that lists the windows on screen (`getWindows()`) and reads only each app window's package name. It has no internet permission and reads no content.
- `Engine` adds time once a second while the screen is interactive, the keyguard is down and a watched app has an application window. Over the limit it covers the app with `BlockActivity`, started with `FLAG_ACTIVITY_NO_USER_ACTION` so YouTube doesn't jump into picture-in-picture. If the app is still visible 1.5 s later it goes home first; after three tries it suspends the app for 1.5 s.
- `GuardJob` is the 15-minute background check for when the watcher is off.
- `core/` holds the logic that decides what counts and when limits change, in plain Java: `Rules`, `Policy` (delayed loosening), `Ledger` (daily reset), `ClockGuard` (trusted time) and `State` (persistence). `src/test` checks it with `java` alone.

## Limits

- YouTube in a web browser isn't counted.
- A YouTube app under a package name it doesn't know isn't counted until it's added.
- Without OwnDroid's protections, it's only as strong as an ordinary app.
