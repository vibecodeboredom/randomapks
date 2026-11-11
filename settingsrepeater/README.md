# Settings Repeater

Opens a chosen Settings page X times in a row, waiting Y ms between
each opening. One screen, three inputs:

- **X** — how many times to open the page (e.g. `5`).
- **Y** — delay in milliseconds between each opening (e.g. `500`).
- **Z** — which Settings page, from a picker. The list mirrors
  Settings Launcher's shortcuts (same repo) so the two apps stay
  consistent — Settings home, Wi-Fi, Bluetooth, Network & Internet,
  Apps, Display, Sound, Location, Security, Developer options, About
  phone, Date & time, Language, Storage, Battery, Accessibility,
  Home app — plus a **Custom…** entry with a text field for any
  Settings action string (e.g. `android.settings.WIFI_SETTINGS`).

Tap **Start** and the first page opens straight away; the rest follow
Y ms apart. **Stop** cancels whatever is left at once. The status line
counts the opens as they fire ("Opened 3 of 5"). A page the device
can't open shows a toast instead of crashing, and the run stops.

No permissions, no network, no root.

## Honest limits

- Opening a page that is already on screen just brings it to the
  front — Android doesn't visibly re-open it. The Intent is still
  fired X times and the count still advances; press Back to come back
  here and see the final count.
- The run lives in the app process: if Android kills the app
  mid-run, the remaining opens stop. Keep the app in the foreground
  (or at least don't swipe it away) while it runs.
- The delay is honest — it is the Y value you typed, in ms, between
  one opening and the next. Very small delays (under ~100 ms) may
  bunch up, because starting an activity takes time of its own.

## Build

```bash
cd settingsrepeater && ./gradlew assembleDebug
```

APK: `settingsrepeater/app/build/outputs/apk/debug/app-debug.apk`
(release asset name: `SettingsRepeater-1.0.0.apk`).

## License

MIT — see the repo root LICENSE.
