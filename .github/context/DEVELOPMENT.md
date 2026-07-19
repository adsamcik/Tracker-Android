<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Development Guide

<!-- context-init:managed -->
Reference for building, testing, and debugging Tracker Android.

## Prerequisites

<!-- context-init:managed -->

| Requirement | Version | Notes |
|-------------|---------|-------|
| Android Studio | Latest stable | Ladybug or newer |
| JDK | 17 | Toolchain auto-provisioned via Foojay (`jvmToolchain(17)`) |
| Kotlin | 2.4.0 | Via version catalog (K2 compiler) |
| KSP | 2.3.8 | No KAPT for new code |
| AGP | 9.3.0-rc02 | Android Gradle Plugin |
| Compile SDK | 37 (API 37) | Target SDK also 37 |
| Min SDK | 26 | Android 8.0 Oreo |
| Build Tools | 36.0.0 | Via version catalog |
| Gradle | 9.5.1 | Wrapper included |

## Setup

<!-- context-init:managed -->

1. Clone: `git clone https://github.com/adsamcik/Tracker-Android.git`
2. Create `local.properties` with `sdk.dir=<path-to-sdk>`
3. Copy `google-services.json.example` to `app/google-services.json`
4. (Optional) Copy `keys.xml.example` to `app/src/main/res/values/keys.xml`
5. Build: `./gradlew.bat :app:assembleDebug`

## Common Commands

<!-- context-init:managed -->

### Build
| Task | Command |
|------|---------|
| Debug APK | `./gradlew.bat :app:assembleDebug` |
| Release APK | `./gradlew.bat :app:assembleRelease` |
| Clean build | `./gradlew.bat clean :app:assembleDebug` |
| All modules | `./gradlew.bat assembleDebug` |
| Emulator install (OpenGL) | `./gradlew.bat :app:installDebug -PuseOpenGlMapRenderer=true` |

**Map renderer on emulators:** MapLibre Android 13 defaults to the Vulkan renderer, which
segfaults inside `libmaplibre.so` on the software-emulated GPUs that Android emulators expose
(any map heatmap crashes the process). Pass `-PuseOpenGlMapRenderer=true` to swap the native
MapLibre SDK for its drop-in OpenGL build when building for an emulator. Leave the flag unset for
device and release builds so they keep the default (hardware) Vulkan renderer.

### Test
| Task | Command |
|------|---------|
| All unit tests | `./gradlew.bat testDebugUnitTest` |
| Module tests | `./gradlew.bat :tracker:engine:testDebugUnitTest` |
| Connected tests | `./gradlew.bat :app:connectedDebugAndroidTest` |
| With output capture | `./gradlew.bat :module:testDebugUnitTest --no-daemon --console=plain 2>&1 \| Tee-Object -FilePath test-output.log` |

**Test reports:** `<module>/build/reports/tests/testDebugUnitTest/index.html`

### Quality
| Task | Command |
|------|---------|
| Lint | `./gradlew.bat lint` |
| Release lint gate | `./gradlew.bat :app:lintRelease :app:checkReleaseLintReport` (fails on unbaselined fatal/error app issues) |
| Room schema drift | `./gradlew.bat checkRoomSchemaDrift` |
| Detekt | Configured via `detekt.yml` (maxIssues: 10) |
| Dep updates | `./gradlew.bat dependencyUpdates -Drevision=release` |

### Useful Flags
| Flag | Purpose |
|------|---------|
| `--no-daemon` | Fresh Gradle process |
| `--console=plain` | Machine-readable output |
| `--stacktrace` | Full stack traces |
| `-x test` | Skip tests during build |

## Environment

<!-- context-init:managed -->

| Variable | Purpose | Required |
|----------|---------|----------|
| `ANDROID_HOME` / `sdk.dir` | Android SDK location | Yes (in local.properties) |
| `JAVA_HOME` | JDK location | No (AS bundled) |
| `TRACKER_RELEASE_STORE_FILE` | Release keystore path | Release signing only |
| `TRACKER_RELEASE_STORE_PASSWORD` | Release keystore password | Release signing only |
| `TRACKER_RELEASE_KEY_ALIAS` | Release key alias | Release signing only |
| `TRACKER_RELEASE_KEY_PASSWORD` | Release key password | Release signing only |

**No API keys required for core functionality.** The app is fully offline.
Release signing values may also be supplied in ignored `local.properties` as
`tracker.release.storeFile`, `tracker.release.storePassword`,
`tracker.release.keyAlias`, and `tracker.release.keyPassword`.
Only the app `release` variant is minified; library release variants remain
unminified so app R8 can perform whole-program shrinking across module
boundaries.

## Project Structure

<!-- context-init:managed -->

```
Tracker-Android/
  app/              Main application module
  tracker/api-module/  Tracking API contracts
  tracker/engine/      Core tracking engine
  feature/map/         Map visualization (MapLibre)
  feature/statistics/  Session analytics
  feature/dashboard/   Tracker dashboard, live stats, widgets
  feature/game/        Gamification (challenges, goals)
  feature/activity/    Activity-type management
  feature/import-export/ Import/export
  core/base/           Shared database & entities
  core/ui/             Shared utilities & AppTheme
  data/preferences/    Typed preferences
  core/logging/        Logging implementation
  core/logging-api/    Logger-facing contracts
  domain/points/       Points system
  stats/api/            Stats API contracts
  stats/engine/        Stats processing algorithms
  stats/data/           Stats data layer
  core/testing/        Test utilities & fakes
  gradle/libs.versions.toml  Version catalog
  detekt.yml        Code quality config
```

## Testing Framework

<!-- context-init:managed -->

| Framework | Purpose | Catalog Key |
|-----------|---------|-------------|
| JUnit 5 (Jupiter) | Unit tests (new) | `libs.junit5.jupiter` |
| JUnit 4 | Instrumented/legacy | `libs.junit4` |
| MockK | Kotlin mocking | `libs.mockk` |
| Robolectric | Android context in JVM | `libs.robolectric` |
| Turbine | Flow testing | `libs.turbine` |
| Kotest Assertions | Expressive matchers | `libs.kotest.assertions.core` |
| Compose UI Test | Semantic UI testing | `libs.compose.ui.test.junit4` |
| coroutines-test | `runTest`, dispatchers | `libs.kotlinx.coroutines.test` |

**Fakes in `core/testing`:** FakeTrackerSettingsRepository, FakeLocationSource, FakeFileResolver

## Troubleshooting

<!-- context-init:managed -->

| Problem | Solution |
|---------|----------|
| `google-services.json` missing | Copy `google-services.json.example` to `app/` |
| Build fails on KAPT | Ensure KSP; check for `kapt()` in build.gradle.kts |
| Robolectric NoClassDefFound | Add `@Config(sdk = [34])` |
| Room migration fails | Check AppDatabase version, add migration + test |
| Hilt injection error | Add `@AndroidEntryPoint` to Activity/Service |
| Terminal truncated | Pipe to `Tee-Object -FilePath output.log` |
| Gradle sync slow | `./gradlew.bat --stop` then resync |
| Map not rendering | Check MapLibre layer registry and tile source |

## Key Config Files

<!-- context-init:managed -->

| File | Purpose |
|------|---------|
| `gradle/libs.versions.toml` | **All** dependency versions |
| `build.gradle.kts` (root) | Root plugins, allprojects |
| `settings.gradle.kts` | Module includes |
| `app/build.gradle.kts` | App config, Hilt, Compose |
| `detekt.yml` | Static analysis rules |

## Quality Gates (Every PR)

<!-- context-init:managed -->
- Build all modules successfully
- Detekt + lint clean
- All tests green
- No inline dependency versions
- No privacy regressions
- No new KAPT, SharedPreferences, or XML layouts

<!-- context-init:user-content-below -->
