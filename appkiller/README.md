# App Killer

A floating overlay that shows the app currently in the foreground
(label + package name, updated about once a second) and kills it —
as root, on demand.

Two buttons on the overlay:

- **Kill** — force-stops the foreground app once
  (`su -c "am force-stop <package>"`).
- **Spam Kill x100** — force-stops it 100 times in a row, ~50 ms
  apart, so it stays dead even if it tries to restart itself.

The overlay is draggable — grab its header and move it out of the
way of whatever is underneath. It runs in a foreground service, so
Android doesn't kill it while it sits on top of other apps.

## Setup

The setup screen shows root status and the two permissions the app
needs:

- **Root (su) is required.** Without it nothing can be killed, and
  the Start button stays disabled. The overlay shows the root path
  that was found (e.g. `/su/bin/su`).
- **Draw over other apps** — the overlay can't appear until this is
  allowed. The button opens the system overlay-permission screen.
- **Usage access (optional fallback)** — the foreground detector
  falls back to UsageStatsManager if the root dumpsys path ever
  fails; that needs usage access granted in Settings. Root is the
  main path, since the app needs root anyway.

## How the foreground app is found

Primary path, via root: `su -c "dumpsys activity activities"`,
parsed for the resumed / focused ActivityRecord
(`ResumedActivity:` / `mFocusedActivity:` / `mResumedActivity:`).
That runs on a background thread about once a second; the overlay
updates on the main thread.

Fallback: UsageStatsManager (needs usage access granted) — the most
recently used package in the last 10 seconds.

## Safety

It never kills its own package — that would kill the overlay
itself. Everything else is fair game, including system apps; that
is the point of the app, so be careful what is in front when you
tap Kill.

Android 7.1.2 (API 25) is supported: minSdk is 25. The overlay
window type is `TYPE_PHONE` on Android 7 and
`TYPE_APPLICATION_OVERLAY` from Android 8, and the notification
channel is only created where it exists (API 26+).

No network. No ads.

## Honest limits

- A force-stop is final for that moment, but Android can restart a
  killed app if something else asks for it (an alarm, another app,
  a system trigger). That is what Spam Kill x100 is for — it keeps
  killing for ~5 seconds straight.
- Killing a system app can make the device behave oddly until it
  restarts (e.g. killing the launcher leaves a black screen until
  the home button brings it back). The overlay itself keeps running,
  so you can always tap its ✕ to close it.
- `dumpsys` parsing depends on Android's output format, which
  varies a bit between versions; if the label ever shows the wrong
  app, the UsageStats fallback is the backup.

## Build

```bash
cd appkiller && ./gradlew assembleDebug
```

APK: `appkiller/app/build/outputs/apk/debug/app-debug.apk`
(release asset name: `AppKiller-1.0.0.apk`).

## License

MIT — see the repo root LICENSE.
