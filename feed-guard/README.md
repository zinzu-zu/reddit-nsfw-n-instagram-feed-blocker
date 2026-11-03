# Feed Guard

A small Android app that makes the Reddit NSFW Blocker add-on pointless to switch off on the phone.

Firefox for Android has no way to lock an add-on: anyone can turn it off in a few taps. Feed Guard closes that gap. **Instagram and Reddit only open in Firefox while the add-on is running on the page.** Turn the add-on off, remove it, or open those sites in a private tab where it isn't allowed, and Feed Guard covers the page within a few seconds.

It's built to sit next to OwnDroid and the "Tighter Android lock" setup, where a friend holds the only key.

## What it does

- **Covers Instagram and Reddit in Firefox without the add-on.** The add-on (version 1.2.0 or newer) puts an invisible marker on every Instagram and Reddit page, with a timestamp it refreshes every 2 seconds. The marker only shows while the add-on's stylesheet is applied, and the timestamp only moves while its script runs. Feed Guard reads Firefox's address bar and looks for the marker through Android's accessibility service. Firefox removes both as soon as the add-on is turned off, so on an Instagram or Reddit page Feed Guard finds the marker gone or stale and covers the screen. The cover has two buttons: **Open a new tab** and **Go to the home screen**. Back works too.
- **Pauses other browsers and the Instagram and Reddit apps.** Any app that opens web links, apart from Firefox, is paused when it's installed. So are the Instagram and Reddit apps and the common third-party Reddit and Instagram apps. A paused app is greyed out and shows "blocked by your admin". This needs OwnDroid's "Change package state" delegation.
- **Pauses Firefox if Feed Guard itself is switched off.** Turning off its accessibility access pauses Firefox until the access is back on. A check every 15 minutes, and one after each restart, catch the case where the switch-off happened while Feed Guard wasn't running.
- **Only starts covering pages once it has seen the add-on.** Until it first sees the marker, it covers nothing. You can install it before the add-on update reaches the phone.

It never lifts a pause it didn't make, so it can't undo a pause set in OwnDroid or by Watch Limit. If your friend unpauses an app in OwnDroid, Feed Guard leaves that app alone until it's uninstalled and installed again.

## What it reads

Only Firefox's windows: the address bar and the page's accessibility tree. That's the same information a screen reader gets. It stores the last time it saw the add-on and the address bar, and the list of apps it paused. Nothing leaves the phone, and the app has no internet permission.

The status screen shows what it last saw in Firefox: the page's host (never the full address), whether the marker was there, and whether the page was covered. If something doesn't work, a screenshot of that screen is the quickest way to find out why.

## Setup

Do this during the OwnDroid setup in the "Tighter Android lock" guide. **Install it before that guide's Step 3**, which turns off USB debugging. **Do the OwnDroid part before Step 4**, which locks OwnDroid behind your friend's code.

1. **Install it from your Mac**, while USB debugging is still on:
   `adb install FeedGuard-0.1.0.apk`
   Installing over adb avoids Android's "restricted setting" block on accessibility for downloaded apps, and Google's sideloading checks.
2. **Turn it on.** Open Feed Guard, tap **Open accessibility settings**, choose **Feed Guard** and turn it on. Allow notifications when it asks.
3. **Settings › Apps › Feed Guard:** turn off **Pause app activity if unused**.
4. **In OwnDroid:**
   - Tap the shield icon › **Delegated admins** › add `io.github.zinzuzu.feedguard` with **Change package state**.
   - **Applications**: open Feed Guard and turn on **Block uninstall** and **Disable user control**. Do the same for **Firefox**, so it can't be uninstalled or have its data cleared, either of which would remove the add-on.
5. **Make Firefox your default browser** (Settings › Apps › Default apps › Browser), so links from other apps open in Firefox rather than in a paused browser.
6. **Arm it.** Once Firefox has add-on version 1.2.0 or newer, open instagram.com in Firefox. Feed Guard says "Feed Guard is on" and the status screen shows **Add-on check: Active**.
7. **Test it.** On instagram.com, turn the add-on off in Firefox's menu › Extensions. Within a few seconds the page is covered. Turn the add-on back on. Then turn Feed Guard off in Accessibility settings: Firefox should show as paused. Turn it back on and Firefox opens again.

### Optional: managed configuration

In OwnDroid's managed configuration for Feed Guard, your friend can set:
- **Apps to leave alone** (`allowed_apps`): package names Feed Guard won't pause. It also lifts its own pause on them. Example: `com.android.chrome`.
- **Extra apps to pause** (`blocked_apps`): more package names to pause the same way.

## What it doesn't cover

- **Browsers built into other apps.** Telegram, Messenger and similar apps have their own in-app browsers. Feed Guard doesn't watch them. Instagram there means logging in again each time, which is slow, but possible.
- **Your friend's code and a factory reset.** Both undo everything, on purpose.
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

The signing key isn't in this repo, and must never be. Android lets anyone holding it install an update over the locked app, and **Block uninstall** doesn't stop an update. It lives in the project's shared files.

## How the pieces fit

| File | Job |
|---|---|
| `GuardService.java` | The accessibility service: checks Firefox, shows the cover, dead-man switch |
| `PageInspector.java` | Finds Firefox's address bar, loading bar and the add-on marker |
| `CoverDecision.java` | Decides when to cover: grace period, loading, memory, arming |
| `UrlText.java`, `MarkerText.java` | Read the address and the marker |
| `AppGate.java`, `AppRules.java` | Which apps to pause, and pausing them through OwnDroid |
| `WatchdogJob.java`, `BootReceiver.java` | The 15-minute and after-restart safety net |
| `MainActivity.java` | Status screen, with no off switch |
| `../extension/guard-marker.js`, `.css` | The marker, in the add-on |
