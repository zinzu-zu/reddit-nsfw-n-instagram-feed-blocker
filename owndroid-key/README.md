# owndroid-key

Keeps OwnDroid's app-lock key on your Mac, sealed until a date you pick. On that date it opens for 24 hours, and then it seals itself again until the next date. While it's sealed, nothing you can run from your own account can read it, and that includes you.

## How it works

- OwnDroid's app lock opens with a 6-digit TOTP code. The secret behind those codes is sealed with [drand timelock encryption](https://github.com/drand/tlock) (`tle`). A sealed file only opens once drand, a public randomness network run by the League of Entropy, has published the round it was sealed to. Nobody can make drand publish early, and changing the Mac's clock doesn't help.
- The sealed key sits in `/Library/Application Support/OwnDroidKey/sealed`, a folder only root can open. Two root services do everything that touches it:
  - One answers your account on a local socket. It only ever gives out the current 6-digit code, and only while the key is open. The secret never leaves the root side.
  - The other runs every 15 minutes. When the window is over, it seals the same secret again until the next date. It knows the window is over because a second sealed file, which holds nothing secret, opens at that moment. So no clock decides when a window ends.
- Every request also checks first whether the window is over. If the Mac was asleep when the window ended, the key is sealed again before anything else happens. If sealing it again fails, for example on a full disk, codes stop at the end of the window anyway, and it's tried again every 15 minutes.

It only holds while:

- Your everyday account is a **Standard** account, and someone else has the admin password. An administrator can read any file on the Mac with `sudo`. `owndroid-key status` reminds you if your account is an administrator.
- The phone's clock can't be changed. A code only works around the time it was made, going by the phone's clock, so codes saved during a window would work again with the clock set back. OwnDroid's **Configure date or time** restriction stops that.

## Install

An administrator does this once, from Terminal, in this folder:

1. Go, to build `tle`: `brew install go`
2. `sudo bash install.sh`

The installer builds `tle` 1.2.0, the version this was tested with, into the root-only folder, so the services never run anything your account could replace. If you already built `tle` yourself, pass its path instead: `sudo bash install.sh ~/go/bin/tle`. Running the installer again updates the script and keeps the sealed key.

If your account is Standard and a friend holds the admin password, your friend types it: `su FRIEND -c "sudo bash install.sh"`, where `FRIEND` is the admin account's short name.

## Use

From your own account:

- `owndroid-key seal --open "2026-12-01 18:00" --every 30d` sets up the key. It opens on that date for 24 hours, and then once every 30 days. `--window 48h` changes the 24 hours. The script walks you through OwnDroid:
  1. Set up TOTP in OwnDroid, and type the secret into the Mac twice.
  2. Set a temporary password `test` on OwnDroid's app lock, as a way back in while you check.
  3. Open OwnDroid with a code from the Mac. Only after that works is the key sealed.
  4. Remove the `test` password, so that only the code opens OwnDroid.
  5. Check once more.
- `owndroid-key code` shows a code while the key is open.
- `owndroid-key status` shows when the key opens next.
- `owndroid-key seal`, with no options while the key is open, sets up a new key on the same schedule. Do this whenever the secret might have been seen. OwnDroid shows it under Settings → App lock → TOTP secret to anyone who's inside.
- `owndroid-key seal --new-phone` sets up a key for a new or factory-reset phone, on any day, sealed or not. It keeps your schedule: while the key is sealed, the new one opens on the same date, and while it's open, on the next date. `--open`, `--every` and `--window` change that, as for a first key. The current key is deleted, so a phone whose OwnDroid still uses it can't be opened again, only reset. Use it once the old phone is reset, sold or broken. It adds no way in: the new phone's OwnDroid never had the old key, and the new key opens nothing before its date. [`phone-kit`](../phone-kit/README.md) runs it at the end of a new phone's setup.

The script never shows the secret after you type it in.

## What it can't stop

- **Copying by hand.** You type the secret in from OwnDroid's screen when you set it up. During a window, OwnDroid's App lock → TOTP secret screen shows it again. Someone who writes it down keeps a way in until the next new key.
- **An administrator account.** With `sudo`, an admin can copy the sealed file and open it once its date has passed, or open it during a window. The secret then works until the next new key.
- **Recovery.** Its Terminal runs as root, so it can copy the sealed file too. On a Mac with Apple silicon or a T2 chip, Recovery asks for an administrator's password. On an older Mac, a firmware password set by the administrator does the same.
- **Losing the sealed file.** It's the only copy, and it's kept out of Time Machine on purpose, because an old copy opens once its date has passed. If the Mac is wiped or dies, OwnDroid can only be opened again after a factory reset of the phone.
- **drand going away.** If drand ever stops, the key never opens, and the way back is also a factory reset. drand has been running since 2019. If only `api.drand.sh` is down, other relays such as `https://api2.drand.sh` and `https://drand.cloudflare.com` serve the same chain. An administrator can open the key by hand: `sudo "/Library/Application Support/OwnDroidKey/bin/tle" -d -n https://api2.drand.sh "/Library/Application Support/OwnDroidKey/sealed/key.tle"`.
- Opening the key and sealing it need the internet.

## Files

| Where | What |
|---|---|
| `/Library/Application Support/OwnDroidKey/sealed/key.tle` | The secret, sealed until the next opening. Root only. |
| `/Library/Application Support/OwnDroidKey/sealed/end.tle` | Opens when the window is over. Holds nothing secret. |
| `/Library/Application Support/OwnDroidKey/sealed/schedule` | Dates and lengths. |
| `/Library/Application Support/OwnDroidKey/owndroid-key`, `bin/tle` | The script and `tle`, owned by root. |
| `/Library/LaunchDaemons/io.github.zinzuzu.owndroid-key*.plist` | The two services. |
| `/usr/local/bin/owndroid-key` | A link, so your account can run it. |
| `/Library/Logs/owndroid-key.log` | When the key was sealed. |

## Start over or remove it

Only do this when OwnDroid no longer uses this key, for example after a factory reset, or if a new key was half set up and OwnDroid still opens with the password `test`. Otherwise deleting the sealed folder locks OwnDroid for good. For a reset or new phone, `owndroid-key seal --new-phone` does the same without an administrator and keeps your dates.

- Start over (an administrator): `sudo rm -rf "/Library/Application Support/OwnDroidKey/sealed"`, then `owndroid-key seal --open ... --every ...` again.
- Remove everything (an administrator):

  ```
  sudo launchctl bootout system/io.github.zinzuzu.owndroid-key
  sudo launchctl bootout system/io.github.zinzuzu.owndroid-key.service
  sudo rm /Library/LaunchDaemons/io.github.zinzuzu.owndroid-key.plist /Library/LaunchDaemons/io.github.zinzuzu.owndroid-key.service.plist /usr/local/bin/owndroid-key
  sudo rm -rf "/Library/Application Support/OwnDroidKey"
  ```

## Tests

- `bash test/run.sh` runs without a network or a Mac, with a fake `tle` and a fake clock.
- `bash test/real-tle.sh` runs the real `tle` against a stand-in drand relay (`test/fakedrand`). It needs Go.
