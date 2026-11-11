# Useful APKs

A small collection of tiny Android apps that each do one useful thing — no big projects here, just the few APKs that earn their keep. Each app lives in its own folder and builds on its own; one CI run builds them all.

⚠️ Vibecoded — built quickly for personal use.

## Apps

### Settings Launcher (`settingslauncher/`)
One screen of big, remote-friendly buttons that jump straight to a specific Settings screen — About, Developer Options, Wi-Fi, Bluetooth, Network & Internet, Display, Sound, Storage, Apps, Security, Location, Accessibility, Date & Time, Language, Home App, and Settings Home. Built for a plain-Android TV box (Android 9) that hides most of its Settings options. A screen the device can't open shows a toast instead of crashing.

### DevOptions (`devoptions/`)
An Android TV app that does one thing: open it and it jumps straight to the system Developer options screen, then closes itself. If Developer options aren't enabled yet, it shows a fallback screen with the steps to enable them and a button to open Settings.

### App Disabler (`appdisabler/`)
Disables system apps without ADB. Pick the apps, tap Disable, and it uses an accessibility service to tap the Disable button in Settings for each one. Built for the cheap Android 9 TV box, where ADB over Wi-Fi doesn't exist and the stock launcher keeps coming back. Needs the App Disabler accessibility service turned on (the app walks you through it). It can only tap Disable where Settings offers it — a system app whose Disable button is greyed out stays enabled.

### AdbBoot (`adbboot/`)
Enables ADB over Wi-Fi on TCP port 5555 automatically at boot, so a reboot doesn't strand a device — no one has to run `adb tcpip 5555` from a PC afterwards. No root: it runs the switch through Shizuku (free), which gives it shell-level commands, and it also tries the permanent `persist.adb.tcp.port` setting so the fix can survive reboots on its own. One screen with a big "Enable ADB on port 5555 now" button and a status block. Needs a one-time setup per device (install Shizuku, start it once, allow this app in it) — see `adbboot/README.md` for the steps and the honest per-device limits (Shizuku auto-starts on boot only on Android 13+; the J2 on Android 9 and the AiPlus4K on Android 11 lean on the one-time permanent setting).

### Settings Repeater (`settingsrepeater/`)
Opens a chosen Settings page X times in a row, waiting Y ms between each opening. Three inputs: X (how many times), Y (delay in ms), Z (which Settings page — a picker mirroring Settings Launcher's list, plus Battery and a Custom… action string). Big Start and Stop buttons and a status line ("Opened 3 of 5"). No permissions, no network, no root.

### Admin Tools (`admintools/`)
Lists the Device Admin features and gives each one a button to trigger it — lock now, set lock screen password, password quality/length/expiration, maximum time to lock, disable camera, keyguard features, storage encryption, and permitted accessibility services. The app is its own device admin (Enable device admin button opens the system Activate prompt); the feature buttons stay disabled until it is active. Every call is guarded — a failure is a toast, never a crash. There is deliberately no way to erase the device in this app.

## Install

Grab the APKs from the [latest pre-release](https://github.com/vibecodeboredom/usefulapks/releases) and sideload with ADB:

```bash
adb install -r SettingsLauncher-1.0.1.apk
adb install -r DevOptions-1.0.1.apk
adb install -r AppDisabler-1.0.0.apk
adb install -r AdbBoot-1.0.0.apk
adb install -r SettingsRepeater-1.0.0.apk
adb install -r AdminTools-1.0.0.apk
```

No ADB? Copy the APK to a USB flash drive, plug it into the device, open it with the file manager, and install.

## Build

```bash
cd settingslauncher && ./gradlew assembleDebug
cd devoptions && ./gradlew assembleDebug
cd appdisabler && ./gradlew assembleDebug
cd adbboot && ./gradlew assembleDebug
cd settingsrepeater && ./gradlew assembleDebug
cd admintools && ./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
