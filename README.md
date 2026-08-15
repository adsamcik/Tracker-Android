![Build Status](https://github.com/adsamcik/Tracker-Android/workflows/Android%20CI/badge.svg)
[![Crowdin](https://badges.crowdin.net/advention/localized.svg)](https://crowdin.com/project/advention)

<a href='https://play.google.com/store/apps/details?id=com.adsamcik.tracker&utm_campaign=Github&pcampaignid=pcampaignidMKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height="50px"/></a>

# Tracker Android (Advention)

Tracker is a free, open-source, offline location and activity tracker. All tracked data stays on the device; there is no cloud sync or telemetry.

## Features

- Track location, Wi-Fi networks, cell networks, activity and steps (everything can be enabled or disabled)
- Automatically start tracking based on your activity (You can choose between disabled, on foot and in motion options)
- Lock tracking for specified amount of time (proper time control tbd, currently only preset times are supported) or until phone is connected to a charged
- Show your data on the map
- Show you details about your tracking sessions (enabled or manually triggered)
- Fully customizable color of the interface with options for static color, day night switching or smooth morning-day-evening-night transition. Colors for all are fully customizable.
- Add your own custom activities for sessions or edit detected activities for a given session
- Games: Challenges (currently there are only 3 challenges available but more are planned)
- Features are split into modular Statistics, Game, Map, Dashboard, Activity, and Import/Export components
- Export data to GPX, KML, JSON and Sqlite
- Import data from GPX and batch import from zip
- Record local crash, ANR, and fixed-code diagnostics with Tracebox; nothing is uploaded automatically
- Supported languages: English, Czech
- Supported length systems: metric, imperial (USC), ancient roman, sailing, flying
- Does not upload your tracked data anywhere. Android cloud backup and device-to-device transfer
  are disabled for all Tracker app storage; data leaves only through an export, save, or share
  action you choose.

## Development

### Prerequisites

- Android Studio (latest stable)
- JDK 21 for Gradle (application bytecode targets Java 17)
- Android SDK 37 (compile and target)
- Tracebox artifacts at the version declared by `tracebox` in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml). CI resolves only that immutable package
  from GitHub Packages. For unpublished Tracebox work, use the explicit disposable-repository
  workflow in [`docs/TRACEBOX_INTEGRATION.md`](docs/TRACEBOX_INTEGRATION.md); do not publish a
  candidate to the user's global Maven Local cache. The local validation seams fail closed in CI.

Tracebox is built into every Tracker variant and is the sole crash and diagnostics recorder; there
is no migration flavor or legacy logger fallback.
The complete integration and release activation contract is documented in
[`docs/TRACEBOX_INTEGRATION.md`](docs/TRACEBOX_INTEGRATION.md).

### Build & Test

```bash
# Build debug APK
./gradlew.bat :app:assembleDebug

# Run every repository-owned JVM/host unit-test suite
./gradlew.bat ciUnitTest

# Run the complete repository quality-gate contract
./gradlew.bat ciCheck --continue

# Run specific module tests
./gradlew.bat :tracker:engine:testDebugUnitTest
./gradlew.bat :feature:map:testDebugUnitTest

# Run connected tests (requires device/emulator)
./gradlew.bat :app:connectedDebugAndroidTest
```

The exact task, workflow, artifact, baseline, and branch-protection contract is documented in
[QUALITY_GATES.md](QUALITY_GATES.md).

### Tech Stack

- Kotlin 2.4.10 with Coroutines & Flow
- Jetpack Compose (Material 3)
- Room database
- Hilt + KSP and AppGraph for DI

For architecture details, see [docs/ARCHITECTURE_OVERVIEW.md](docs/ARCHITECTURE_OVERVIEW.md). The module migration record is in [docs/MODULE_REARCHITECTURE_STATUS.md](docs/MODULE_REARCHITECTURE_STATUS.md).

## Contributions

Contributions to Tracker are welcome. If you want any new feature (even if it's in later milestone or no milestone at all) you are free to do so. It is recommended to consult on larger issues as they might collide with some future system revamps and might not be merged because of it.

### Translations

Tracker uses Crowdin to crowdsource translations. You can help with translating the application today at [https://crowdin.com/project/advention](https://crowdin.com/project/advention).

## Support and diagnostics

For issue-reporting steps, diagnostic privacy guidance, deletion behavior, and degraded native
capture guidance, see [`docs/SUPPORT.md`](docs/SUPPORT.md). Tracker never sends a diagnostic package
automatically: you review its disclosure and choose an Android save/share destination. Do not post
tracking databases, routes, coordinates, network names, or other personal exports in a public issue.

## Versioning

For readability it's separeted by spaces and underscores however these are not present it the actual versioning, see example.
YEAR.VERSION RELEASE BUILD_NUMBER

Eg. 2020.1β1

### Releases

New releases will be developed in seperate branches and always have up to date version codes so it is easier to know in what state the release is.

#### Iota ι

Early internal builds after significant changes. Not recommended for use by anyone else but developers. In the future these should be built under different id. These builds might not even have migrations in place.

#### Alpha α

Early test builds usually bringing new features. These builds are not yet feature complete but should have migrations for database and be stable in a way that some basic actions do not crash them every time.

#### Beta β

Late test builds that are feature complete (no new features should be added in these builds, only bugfixes). These builds are meant for general testers that want to test the app but also use it regularly. While there will probably be some issues and crashes, these should be rather sparse and mostly contain edge cases and issues that were not found in the Alpha or Iota builds.

#### Release

Most stable releases meant for general public

## Legal

Google Play and the Google Play logo are trademarks of Google LLC.

Tracker is licensed under GPL-3.0. Tracebox is licensed under Apache-2.0; its pinned Crashpad and
native-component notices are bundled in the app under **Settings → Open source licenses**.
