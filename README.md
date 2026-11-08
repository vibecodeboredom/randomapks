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

## Install

Grab the APKs from the [latest pre-release](https://github.com/vibecodeboredom/usefulapks/releases) and sideload with ADB:

```bash
adb install -r SettingsLauncher-1.0.1.apk
adb install -r DevOptions-1.0.1.apk
adb install -r AppDisabler-1.0.0.apk
```

No ADB? Copy the APK to a USB flash drive, plug it into the device, open it with the file manager, and install.

## Build

```bash
cd settingslauncher && ./gradlew assembleDebug
cd devoptions && ./gradlew assembleDebug
cd appdisabler && ./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
