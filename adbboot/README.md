# AdbBoot

Enables ADB over Wi-Fi on TCP port 5555 automatically at boot, so a
reboot doesn't strand a device — no one has to run `adb tcpip 5555`
from a PC afterwards. One screen, one big button ("Enable ADB on port
5555 now"), and a status block. It also runs on its own at every boot.

No root. No VPN — Tailscale auto-connect is handled separately by
Android's Always-on VPN setting.

## How it works

A normal app cannot run `adb tcpip 5555`: there is no `adb` binary
on the device, and restarting adbd needs more privilege than an app
has. This app goes through **Shizuku** (free, open-source), which
runs commands as the *shell* user (uid 2000) — the same identity
`adb shell` uses. Through Shizuku it runs, as shell:

```
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop ctl.restart adbd
```

- `service.adb.tcp.port` + the restart takes effect immediately for
  this boot.
- `persist.adb.tcp.port` is the permanent one: adbd reads it at
  startup, so if the device's SELinux policy lets the shell user set
  it, port 5555 comes back on its own after every reboot and this app
  becomes a no-op. The app checks afterwards (`getprop`) and says so
  on screen. Some builds refuse the persist property — then the
  per-boot restart is what carries it, and the app says that too.
- If Shizuku is not running, the fallback (Android 11+ only) turns
  wireless debugging back on, so the device is at least reachable on
  a TCP port (system-assigned, not 5555) and the other end can run
  `adb tcpip 5555` itself. That needs `WRITE_SECURE_SETTINGS`,
  granted once from a PC (below).

The Shizuku API is pinned to **12.2.0** on purpose:
`Shizuku.newProcess()` is public there; from 13.0 it is private and
deprecated (the replacement is a UserService, far more code for one
shell command).

## One-time setup (per device)

1. **Install Shizuku** (`moe.shizuku.privileged.api`) from
   https://shizuku.rikka.app/download/ (Play Store or the APK).
2. **Start Shizuku once:**
   - Android 11+: Settings → Developer options → Wireless debugging
     ON → in Shizuku tap "Start via wireless debugging" and pair
     with the code. Tick "Always allow on this network" for the
     Wi-Fi network if it asks — that is what lets Shizuku start
     itself at boot on Android 13+.
   - Android 10 and older (no wireless debugging): connect the
     device to a PC once and run
     `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`.
3. **Open AdbBoot** and tap "Allow in Shizuku" when it shows, then
   tap "Enable ADB on port 5555 now". Done — from here it runs at
   boot by itself.
4. **Optional, for the fallback:** grant the secure-settings
   permission once from a PC (only needed if Shizuku is ever not
   running at boot):
   `adb shell pm grant com.opentv.adbboot android.permission.WRITE_SECURE_SETTINGS`

## The shortcut (try this first)

If the device lets the shell user set the persist property, none of
the above is needed after one command from a PC:

```
adb shell setprop persist.adb.tcp.port 5555
```

Then reboot and check `adb connect <ip>:5555`. If it connects,
adbd will listen on 5555 at every boot forever, and AdbBoot is not
needed on that device. If the device refuses the property (the
`getprop` afterwards comes back empty), use the app instead.

## What works where (honest limits)

- **Onn TV (Android 14) / A16 (Android 16):** full. Shizuku
  13.6.0+ auto-starts on boot on Android 13+ on a trusted Wi-Fi
  network, so the boot receiver can use it. (Note: on Android 16
  QPR1+ some users report the stock Shizuku needs its fork instead —
  if Shizuku won't start there, the one-time persist command above
  is the way.)
- **AiPlus4K (Android 11):** partial. Shizuku does **not** auto-start
  on boot below Android 13, so the receiver can only use Shizuku if
  it happens to be running. The reliable path here is the one-time
  persist command; the wireless-debugging fallback at least leaves
  the TV reachable on a TCP port after boot.
- **J2 Core (Android 9):** persist-only. No wireless debugging, and
  Shizuku can only start from a PC. If the one-time persist command
  works on it, 5555 survives reboots; if the build refuses the
  property, nothing no-root can switch it on at boot, and the manual
  `adb tcpip 5555` from a PC is still needed after a reboot.

## Build

```bash
cd adbboot && ./gradlew assembleDebug
```

APK: `adbboot/app/build/outputs/apk/debug/app-debug.apk`
(release asset name: `AdbBoot-1.0.0.apk`).

## License

MIT — see the repo root LICENSE.
