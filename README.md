# Déjà

Know when something comes back.

Déjà is an Android app that remembers the Bluetooth and Wi-Fi devices around you. It tells you when one turns up again, or seems to be following you from place to place.

**[Download the latest APK](https://github.com/yeetfactory/deja/releases/latest/download/Deja-0.0.3.apk)** · Android 15 or newer

## What it does

- **Remembers devices.** Sees what's nearby, and where and when it was heard.
- **Warns about following.** Alerts only after repeated readings at places that are clearly apart, in sequence, over at least 30 minutes. Each alert shows the evidence as a timeline, and you can export it as a CSV file.
- **Sums up what's around you.** One line says whether anything is unusual, with counts for devices that have been with you today, are new here, or are regulars.
- **Watches a device live.** Open a device's page to see its signal strength and whether it's getting stronger or weaker.
- **Says what it doesn't know.** A Find My broadcast shows as `Apple Find My broadcast · Model unknown` unless the device gives away more.
- **Respects private addresses.** Phones rotate their Bluetooth address on purpose. Each address is kept as its own short-lived encounter, and Déjà never guesses that two addresses are the same device.
- **Shows its own health.** Bluetooth, Wi-Fi, location and alerts each report their state, with a fix when something stops working.

## Privacy

Everything stays on your phone. Déjà doesn't have the Android internet permission, so it can't send anything anywhere. There are no accounts, analytics or ads.

Backups and device transfer are turned off for its data. You choose how long history is kept, and you can delete a device, its history, or everything.

## Install

1. Download the APK from the [latest release](https://github.com/yeetfactory/deja/releases/latest).
2. Open it and allow installing from this source. Play Protect may warn you, because the app isn't from the Play Store.
3. Tap **Continue** and allow the permissions below.

| Permission | Why |
| --- | --- |
| Nearby devices | To hear Bluetooth devices |
| Precise location | Android requires it for scan results, and it tells your places apart |
| Notifications | Optional. Scanning works without it, but alerts can't reach you |

## Limits

- It's a helpful signal, not a safety guarantee. If you think you're being tracked, contact local authorities or a support service.
- Signal strength isn't distance. Walls and bodies change it as much as moving does.
- With the screen off, Android only lets it hear known brands and trackers.
- It can miss a tracker that keeps changing its private address, and it can't identify every model.
- Advertisements are only claims, because any transmitter can broadcast any model code.

## Acceptable use

Déjà is for noticing devices around you, including ones that may be tracking you. Don't use it to follow, monitor or locate other people. That may be illegal where you live.

## Disclaimer

Déjà is an experimental project, provided as is, with no warranty, and you use it at your own risk. It isn't a safety product. It can miss trackers and it can raise false alarms, so don't rely on it for your safety. The people involved in this project aren't responsible for any harm, loss or misuse. If you think you're being tracked, contact local authorities or a support service.

## Build from source

You need JDK 21 and the Android SDK with API level 37.

```sh
./gradlew check                    # formatting, lint and tests
./gradlew spotlessApply            # fix formatting
./gradlew :app:testDebugUnitTest   # run the tests
./gradlew :app:assembleDebug       # build a debug APK
```

A release build needs your own signing key. Without one, it builds unsigned. Add a `keystore.properties` file to the project root, and never commit it:

```properties
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `./gradlew :app:assembleRelease`.

Before changing the code, read [AGENTS.md](AGENTS.md). It explains how the code is laid out, and the rules it must keep, such as never gaining the internet permission. It's written for AI coding agents, but it works for people too.

## Credits

Find My status bits are read the way [AirGuard](https://github.com/seemoo-lab/AirGuard) reads them, and Apple advertisement formats come from [furiousMAC/continuity](https://github.com/furiousMAC/continuity). The [Inter](https://rsms.me/inter/) typeface is used under the SIL Open Font License, included in `third_party/`.

## Licence

Déjà is released under the [MIT License](LICENSE).
