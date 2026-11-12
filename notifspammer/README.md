# Notification Spammer

Posts a bunch of notifications on demand. One screen, four inputs:

- **Title** — the notification title (e.g. `Spam`).
- **Message** — the notification text (e.g. `Hello!`).
- **Count (X)** — how many notifications to post (e.g. `10`).
- **Delay (Y)** — delay in milliseconds between each post (e.g. `500`).

Tap **Start** and the first notification posts straight away; the
rest follow Y ms apart. Each one gets its own ID, so they stack in
the notification shade instead of replacing each other. **Stop**
cancels whatever is left at once. The status line counts the posts
as they fire ("Posted 3 of 10").

Your last inputs (title, message, count, delay) are remembered —
reopening the app brings them back instead of resetting to
defaults.

On Android 13+ the app asks for the notification permission when
you tap Start — allow it or nothing shows up.

## Staying alive

The posting runs in `SpamService`, a foreground service with a
visible notification, so Android treats it as work the user can see
and doesn't kill it mid-run. The app also asks (once) to ignore
battery optimisations, so Doze / App Standby can't throttle it.

## Root mode (su)

On a rooted device a **Use root (su)** checkbox appears (it is
hidden on non-rooted devices, and on by default when root is
found). When it is on, each notification is posted from a root
shell — `su -c "cmd notification post -t '<title>' '<tag>'
'<text>'"` — instead of the app's own NotificationManager. Posting
as the shell bypasses the per-app notification rate limit, so it
can spam faster than the normal path, and it works even while the
app is in the background. Each root post gets its own tag (spam-1,
spam-2, …) so they stack. The foreground service still hosts the
loop and reports progress; the root shell just does the posting.

If a root post fails, that notification falls back to the normal
path instead of stopping the run. That matters on Android 7.1.2:
`cmd notification` doesn't exist there at all (it came in Android
8), so on the LG the root posts fail and the normal path does the
work — root mode only really spams faster on Android 8+.

Android 7.1.2 (API 25) is supported: minSdk is 25, and the
notification channel, the POST_NOTIFICATIONS request, and the
broadcast-receiver registration are all guarded so they only use
the newer APIs where they exist.

No network. No ads. The only permission is the notification one.

## Honest limits

- Android rate-limits how fast one app can post notifications, so
  on the normal path a very fast / very large run may get throttled
  and some posts dropped. Root mode bypasses that (Android 8+).
- The run lives in the app process: if Android kills the app
  mid-run, the remaining posts stop. Keep the app in the foreground
  (or at least don't swipe it away) while it runs.
- The delay is honest — it is the Y value you typed, in ms, between
  one post and the next. Very small delays (under ~100 ms) may bunch
  up, because posting takes time of its own.

## Build

```bash
cd notifspammer && ./gradlew assembleDebug
```

APK: `notifspammer/app/build/outputs/apk/debug/app-debug.apk`
(release asset name: `NotificationSpammer-1.0.0.apk`).

## License

MIT — see the repo root LICENSE.
