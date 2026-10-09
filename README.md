# Reddit NSFW Blocker

A browser extension for Firefox (Mac and Android) and Edge that keeps adult content off Reddit, plus files to lock it so it can't be switched off on a whim.

## What it blocks

- **Adult-only subreddits and profiles.** Any `r/...` or `u/...` page that Reddit marks as over-18 (checked through Reddit's own `about.json`) is replaced with a "blocked" page. Typing the address, following a link, and new Reddit's in-page navigation are all caught. Multireddits (`r/a+b`) are checked one by one.
- **The home feed.** On the front page (and `/best`, `/hot`, `/new`, `/top`), as well as on `r/popular` and `r/all`, the posts are hidden. In their place is a small box with a Reddit search field. Everything else still works as normal: subreddits, posts, comments, search and profiles. To allow `r/popular` and `r/all` again, set `HIDE_POPULAR_AND_ALL = false` in `content.js`.
- **Instagram's feeds.** The home feed, Explore and the Reels tab are hidden. In their place is a box where you can type a username or open your messages. Profiles, messages, stories, search, and single posts or reels opened from a message all still work. The Instagram app isn't covered, only instagram.com in Firefox.
- **NSFW posts everywhere else, including search**, on new Reddit and old Reddit. Posts carrying Reddit's NSFW flag are hidden. Posts from an adult-only subreddit are also hidden even when nobody tagged the post.
- **Reddit's "are you over 18?" gate** (`/over18`) and `r/randnsfw`.
- **Adult media hosts** (redgifs, erome, pornhub, onlyfans and about 25 others in `extension/rules.json`). These are blocked everywhere in the browser, not just on Reddit: pages redirect to the blocked page, and embedded images and videos don't load.

## What it can't catch

- Explicit posts that weren't tagged NSFW, in subreddits that aren't marked adult-only.
- The Reddit app, and Chrome on Android, where extensions don't run. Reddit's own setting covers those, as described below.
- Safari, and browsers the profile doesn't know about, like Opera, Vivaldi or Arc. Screen Time covers Safari, as described below.
- If Reddit refuses to answer (rate limit, private or banned subreddit), the extension falls back to the subreddit's name (`nsfw`, `gonewild`, `porn`, `hentai` and similar) and checks again an hour later.

## Mac: how it's locked in

The lock is one macOS configuration profile, installed at the system level, so only an administrator can remove it. It does four things, and doesn't touch any DNS settings:

1. **Firefox force-installs the extension.** Firefox installs it by itself, the Remove and Disable buttons go away, and it applies to every profile. Policies are keyed by app, so a second copy of Firefox in your Downloads folder gets them too.
2. **Chrome, Brave and Chromium block Reddit completely.** On a Mac without device management, those browsers only force-install extensions from the Chrome Web Store, which charges $5. Blocking Reddit in them is free and leaves no way around the filter, since Reddit then only opens in Firefox. Edge works the same way, unless you publish the extension to Microsoft's free Edge Add-ons store. In that case Edge force-installs it like Firefox does.
3. **Every window is covered.** Private windows, Guest mode, new profiles and Brave's Tor windows are turned off. In Firefox, Troubleshoot Mode, `about:profiles` and `about:config` are blocked as well, because each of those can start Firefox without the extension.
4. **Your everyday account becomes a Standard account, and a friend holds the admin password.** Removing the profile needs that password, so this is what makes it stick.

## Mac setup

**1. Get the Firefox add-on signed by Mozilla.** It's free. Firefox only installs signed add-ons.
- Go to [addons.mozilla.org/developers](https://addons.mozilla.org/developers/addon/submit/distribution) and upload `extension-upload.zip`.
- Choose **On this site**. That lists the add-on publicly, which you need for Firefox on Android, and gives you a short name ("slug") for its page. The listed version goes through Mozilla's review first.
- If you only want it on the Mac, choose **On your own** instead. Signing is then automatic. Download the signed `.xpi`, rename it `reddit-nsfw-blocker.xpi`, and put it next to this README.

**2. (Optional, free) Publish to Microsoft Edge Add-ons, if you want Reddit filtered in Edge rather than blocked.**
- Upload `extension-upload.zip` at [Partner Center](https://partner.microsoft.com/dashboard/microsoftedge/overview). There's no fee.
- Choose **Hidden** visibility, and copy the extension ID once it's approved.

**3. Build and install the profile.**
- Open Terminal in the `lockdown` folder and run `sudo bash lockdown.sh`.
- Enter the add-on slug, or press Enter to use the `.xpi`. Then enter the Edge Add-ons ID, or press Enter to skip.
- The script writes `RedditNSFWBlocker.mobileconfig` and opens it.
- Go to **System Settings > General > Device Management** (on older macOS, Privacy & Security > Profiles), select **Reddit NSFW Blocker** and click **Install**.

**4. Check it worked.** Quit and reopen your browsers.
- In Firefox, `about:policies` should list the policies, and on `about:addons` the blocker should have no Remove option.
- In Chrome, `chrome://policy` should list them, and reddit.com should show Chrome's "blocked by your organization" page.
- In Firefox, `reddit.com/r/gonewild` should show the blocked page.

**5. Cover Safari with Screen Time.** In **System Settings > Screen Time > Content & Privacy**, set **Limit Adult Websites**. Add `reddit.com` to the never-allowed list if you don't want Reddit in Safari at all. Then let your friend set the Screen Time passcode.

**6. Hand over admin.** In System Settings > Users & Groups, create an admin account for your friend to set a password on. Then turn off **Allow this user to administer this computer** for your own account.

## Android

Android doesn't let an adult's phone be locked by someone else without device-management tools, so the phone plan is mostly filtering rather than a hard lock. Your existing DNS still covers the rest of the phone.

1. **Firefox with the extension.** Once the add-on is listed on addons.mozilla.org (step 1 above, **On this site**), open its page in Firefox for Android and tap **Add to Firefox**. This gives you the same Reddit filtering as on the Mac.
2. **Reddit's own setting.** In Reddit, go to Settings > Account settings > Content and turn off **Show mature (18+) content**. Reddit then filters NSFW on its side, in the Reddit app and in Chrome, as long as you're logged in. Deleting the Reddit app and using Reddit in Firefox is stronger still.
3. **Making it hard to undo.** Nothing on a normal Android phone stops you removing the add-on. For a real lock there are two routes:
   - an accountability or blocker app with uninstall protection and a password your friend holds (BlockerX is one well-known example)
   - setting a device-management app as the phone's "device owner", which can block uninstalling and lock settings. This needs a fresh factory reset and a computer to set up.

## Other deeper options I considered

- **Hosts file blocklist.** It's admin-only to edit, but it adds little over the DNS filter you already have.
- **A local filtering proxy with HTTPS inspection.** It could filter Reddit in every app, but it needs a root certificate installed. It breaks apps that pin their certificates, including the Reddit app, and it weakens the device's security.

## Updating a locked Mac

1. Sign the new version. On your add-on's page at addons.mozilla.org, click **Upload New Version**, choose **On your own**, and upload the new `extension-upload.zip`.
2. Download the signed `.xpi`, rename it `reddit-nsfw-blocker.xpi`, and put it next to this README, replacing the old one.
3. Run `sudo bash update.sh` from the `lockdown` folder. This needs the admin password.
4. Quit Firefox with Cmd+Q and open it again. Firefox picks up the newer version from the locked folder.

## Undo (for the admin)

On the Mac, go to System Settings > General > Device Management, remove **Reddit NSFW Blocker**, and enter the admin password. Then delete `/Library/Application Support/RedditNSFWBlocker`, if it exists. On Android, reverse the settings above.
