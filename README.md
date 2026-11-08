# Settings Launcher

A tiny plain-Android app that does one thing: **open it and tap a big button to jump straight to a specific Settings screen** — About, Developer Options, Wi-Fi, Bluetooth, Display, Storage, and more.

Built for a plain-Android device that hides most of its Settings options. Instead of digging through menus, one tap opens the screen you want. Buttons are large and remote-friendly, and a screen that can't be opened on the device shows a toast instead of crashing.

⚠️ Vibecoded — built quickly for personal use.

## Install

Grab the debug APK from the [latest pre-release](https://github.com/vibecodeboredom/settingslauncher/releases) and sideload it with ADB:

```bash
adb install -r SettingsLauncher-debug.apk
```

It shows up on the home screen / app drawer as **Settings Launcher**.

## Build

```bash
./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
