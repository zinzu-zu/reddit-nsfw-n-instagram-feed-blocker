# Publishing updates

Notes for whoever changes the extension next.

- The add-on ID is `reddit-nsfw-blocker@self-hosted` (in `extension/manifest.json`). Never change it: the Mac lock and every installed copy are tied to it.
- From 1.1.1 on, the add-on is **listed** on addons.mozilla.org. Listed add-ons update themselves in Firefox on the Mac (even while it's locked) and on Android.
- To ship a change:
  1. Raise `version` in `extension/manifest.json`.
  2. Run `bash publish.sh`. It needs the `AMO_JWT_ISSUER` and `AMO_JWT_SECRET` secrets and network access to `addons.mozilla.org`.
  3. Mozilla reviews the version, and devices pick it up within about a day.
- If those secrets aren't available, upload `extension-upload.zip` by hand on the add-on's page with **Upload New Version**, and choose **On this site**.
- The Mac profile must point at the listed add-on. To set that up, run `lockdown.sh` with the add-on's short name instead of the `.xpi`. Until that's done, the Mac only updates through `lockdown/update.sh`.
