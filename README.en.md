# NicoTV for Fire TV

English · [日本語](README.md)

NicoTV is an unofficial, MIT-licensed Android app for watching Niconico videos with comments using a Fire TV remote.
It provides a TV-oriented interface for browsing videos, watching series, and resuming from a locally saved playback position.
The project aims to maintain an open, inspectable Niconico viewing client for Android-based Fire TV devices.

**This is development software distributed as source code.**
It is not affiliated with or endorsed by Dwango, Niconico, or Amazon.
Service changes and device differences may break login or playback.
The application UI and detailed guides are currently in Japanese.

## Build and install

Requirements: JDK 17, Android SDK Platform 35, Build-Tools 34.0.0, and Platform-Tools (`adb`).
Set `JAVA_HOME` to JDK 17 and configure the SDK through Android Studio, `ANDROID_HOME`, or a local `local.properties` file.
The project pins its build environment through the Gradle Wrapper; do not replace it with a global Gradle installation.

```sh
git clone https://github.com/sutaa12/nico-firetv.git
cd nico-firetv
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.
The output is `app/build/outputs/apk/debug/app-debug.apk`, signed with your local development key.

Enable ADB debugging on your Fire TV and connect from a trusted local network.
Replace `FIRE_TV_IP` with the device address, accept the authorization prompt on the TV, and check that `get-state` returns `device` before installing.

```sh
adb connect FIRE_TV_IP:5555
adb -s FIRE_TV_IP:5555 get-state
adb -s FIRE_TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FIRE_TV_IP:5555 shell am start -n app.nicotv.firetv/.MainActivity
```

Disable ADB debugging when finished.
See [Amazon's ADB connection guide](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html) and the [detailed build guide (Japanese)](docs/BUILDING.md).
Keep the same signing key for updates; uninstalling to resolve a signature mismatch deletes local app data.
Release builds are unsigned until a maintainer configures their own signing process.
No installable release APK is promised by this source-only distribution.

## Features and scope

- Rankings, keyword/tag search, video details, and continuous playback from series, uploads, or playlists.
- Regular and past comments, comment display controls, local filtering, and an optional side panel.
- Local viewing history, resume positions, and favorites.
- Login through the official login page; reading account history, playlists, and Watch Later.
- Playback speed, looping, and seek thumbnails where the video provides them.

These features are implemented; this list does not certify every account, entitlement, device, or service condition.
The target is Android-based Fire OS, with a minimum Android API level of 25 (Android 7.1).
Devices that cannot execute Android APKs are outside the scope.
Live broadcasts, timeshift, DRM-protected content, comment posting, playlist editing, follows/likes, and official playback-position synchronization are unsupported.
The app does not grant viewing entitlements or bypass account restrictions.

## Maintenance and verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Tests cover comment loading and cache bounds, account separation, persistence, pagination, and watch-page parsing.
See [validation status](docs/VALIDATION.md) for dated results and unverified areas, [changes](CHANGELOG.md), and the [release checklist](docs/RELEASING.md).
Automated checks do not replace real Fire TV remote, login, playback, or long-duration testing.
A [device-test template](docs/DEVICE_TESTING.md) is available for reproducible reports.

To report a bug, open a [GitHub issue](https://github.com/sutaa12/nico-firetv/issues) with your device model, Fire OS version, app version/commit, reproduction steps, expected/actual behavior, and login status (yes/no only).
Do not include passwords, cookies, access tokens, device addresses, or private account data.
Read the [contribution guide](docs/CONTRIBUTING.md) and [data handling guide](docs/PRIVACY.md), both in Japanese.

## License

Original project code and documentation are under the [MIT License](LICENSE).
Fonts, the Gradle Wrapper, referenced implementations, and dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
Niconico's [terms](https://account.nicovideo.jp/rules/account) apply separately.
The MIT License does not grant access to the service or rights to its videos, comments, or trademarks.
