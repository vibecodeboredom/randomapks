# Admin Tools

Lists the Device Admin features and gives each one a button to
actually trigger it. One scrollable screen:

- **Enable device admin** — opens the system Activate prompt. The
  feature buttons stay disabled until the app is an active admin.
  It can't be turned on silently, and once on, the app can't be
  force-stopped or uninstalled until the admin is removed first.
- **Lock device now** — locks the screen immediately.
- **Set lock screen password** — text field + button. Deprecated on
  newer Android and may fail if a lock screen is already set.
- **Password quality** — dropdown (None, Numeric, Alphabetic,
  Alphanumeric, Complex) + Apply.
- **Minimum password length** — number + Apply.
- **Maximum time to lock** — seconds + Apply (converted to ms).
- **Password expiration** — days + Apply (converted to ms, 0 = never).
- **Disable camera** — a switch; flips the camera on and off.
- **Keyguard features** — checkboxes (Widgets, Secure camera,
  Fingerprint, Face, Trust agents) + Apply; the checked flags are
  combined. Unchecking all of them re-enables everything.
- **Storage encryption** — "Require encryption" button. Deprecated
  on newer Android; the device may already be encrypted.
- **Permitted accessibility services** — text field for
  comma-separated package names + Apply, plus a "Clear restriction"
  button. This one needs a device or profile owner — a plain device
  admin will get a toast saying so instead of crashing.

When the app is an active admin, the current values are read back
and prefilled where the device lets us (password length, time to
lock, expiration, quality, camera, keyguard flags, permitted
services).

Every admin call is guarded — a failure is a toast, never a crash.
Many of these are restricted or deprecated on newer Android and may
fail; the app says so instead of crashing.

There is deliberately no way to erase the device in this app.

No ads, no tracking, no network, no root.

## Build

```bash
cd admintools && ./gradlew assembleDebug
```

APK: `admintools/app/build/outputs/apk/debug/app-debug.apk`
(release asset name: `AdminTools-1.0.0.apk`).

## License

MIT — see the repo root LICENSE.
