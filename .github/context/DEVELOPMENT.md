<!-- context-init:version:3.0.2 -->
<!-- context-init:generated:2026-01-30 -->

# Development Guide

<!-- context-init:managed -->
Quick reference for building, testing, and debugging Tracker Android.

## Prerequisites

<!-- context-init:managed -->

| Requirement | Version | Notes |
|-------------|---------|-------|
| Android Studio | Latest stable | Ladybug or newer |
| JDK | 17+ | Bundled with Android Studio |
| Kotlin | 2.2.20-RC | Via version catalog |
| Android SDK | 36 (API 36) | Compile SDK |
| Min SDK | 26 | Android 8.0 Oreo |
| Gradle | 8.x | Wrapper included |

## Setup

<!-- context-init:managed -->

1. **Clone repository**
   ```bash
   git clone https://github.com/adsamcik/Tracker-Android.git
   cd Tracker-Android
   ```

2. **Create local.properties** (if not exists)
   ```properties
   sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk
   ```

3. **Create google-services.json**
   - Copy `google-services.json.example` to `app/google-services.json`
   - Or configure Firebase for your project

4. **Create keys.xml** (optional, for API keys)
   - Copy `keys.xml.example` to `app/src/main/res/values/keys.xml`
   - Add your Google Maps API key

5. **Sync and build**
   ```powershell
   ./gradlew.bat :app:assembleDebug
   ```

## Common Commands

<!-- context-init:managed -->

### Build

| Task | Command |
|------|---------|
| Debug APK | `./gradlew.bat :app:assembleDebug` |
| Release APK | `./gradlew.bat :app:assembleRelease` |
| Clean build | `./gradlew.bat clean :app:assembleDebug` |
| All modules | `./gradlew.bat assembleDebug` |

### Test

| Task | Command |
|------|---------|
| All unit tests | `./gradlew.bat testDebugUnitTest` |
| Module tests | `./gradlew.bat :tracker:testDebugUnitTest` |
| Connected tests | `./gradlew.bat :app:connectedDebugAndroidTest` |
| Specific test class | `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.tracker.app.StandardFlowsTest` |

### Quality

| Task | Command |
|------|---------|
| Lint | `./gradlew.bat lint` |
| Dependency updates | `./gradlew.bat dependencyUpdates -Drevision=release` |

### Useful Flags

| Flag | Purpose |
|------|---------|
| `--no-daemon` | Clean state, complete output |
| `--console=plain` | No ANSI codes (better for logs) |
| `--rerun-tasks` | Force re-run cached tasks |
| `--no-parallel` | Sequential execution |

## Test Output Capture

<!-- context-init:managed -->

**Terminal output truncates!** Always capture to file for analysis:

```powershell
# Run tests with full output capture
./gradlew.bat :tracker:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log

# Build with output capture
./gradlew.bat :app:assembleDebug --no-daemon --console=plain 2>&1 | Tee-Object -FilePath build-output.log

# Read the output file
cat test-output.log
```

### Finding Errors in Output

```powershell
# Search for failures
Select-String -Path test-output.log -Pattern "FAILED|FAILURE|BUILD FAILED"

# Search for exceptions
Select-String -Path test-output.log -Pattern "Exception|Error:|Caused by:"

# Search for assertions
Select-String -Path test-output.log -Pattern "expected|actual|AssertionError"
```

## Environment Variables

<!-- context-init:managed -->

| Variable | Purpose | Required |
|----------|---------|----------|
| `ANDROID_HOME` or `ANDROID_SDK_ROOT` | SDK location | Usually auto-detected |
| `JAVA_HOME` | JDK location | Usually auto-detected |
| `GOOGLE_MAPS_API_KEY` | Maps API key | For map features |

## Module Testing

<!-- context-init:managed -->

| Module | Test Command | Test Type |
|--------|--------------|-----------|
| `app` | `:app:testDebugUnitTest` | Unit |
| `tracker` | `:tracker:testDebugUnitTest` | Unit + Robolectric |
| `map` | `:map:testDebugUnitTest` | Unit |
| `sbase` | `:sbase:testDebugUnitTest` | Unit |
| `statistics` | `:statistics:testDebugUnitTest` | Unit |
| `game` | `:game:testDebugUnitTest` | Unit |
| `impexp` | `:impexp:testDebugUnitTest` | Unit |

## Database Migrations

<!-- context-init:managed -->

Every database schema change requires:

1. **Increment version** in `AppDatabase.kt`
2. **Add migration** (e.g., `MIGRATION_12_13`)
3. **Write migration test**
4. **Export schema** to `schemas/` folder

```kotlin
// Example migration
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE location_data ADD COLUMN accuracy REAL")
    }
}
```

## Debugging

<!-- context-init:managed -->

### ADB Logcat
```powershell
# Filter by app package
adb logcat --pid=$(adb shell pidof -s com.adsamcik.tracker)

# Filter by tag
adb logcat -s TrackerService

# Clear and watch
adb logcat -c && adb logcat
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `Unresolved reference` | Missing import/dependency | Check module dependencies |
| `Cannot access class` | Visibility issue | Check `internal` vs `public` |
| Room migration crash | Missing migration | Add migration, run migration test |
| Build cache stale | Gradle cache issue | `./gradlew.bat clean` |
| Tests timeout | Bad test setup | Check coroutine dispatchers |

### VS Code Tasks

Pre-configured tasks available in `tasks.json`:
- Run map unit tests
- Build app debug
- Run connected tests

## Version Catalog

<!-- context-init:managed -->

All dependencies are in `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.2.20-RC"
compose-bom = "2025.08.00"
room = "2.8.0-rc01"

[libraries]
compose-material3 = { group = "androidx.compose.material3", name = "material3" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

Usage in `build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.compose.material3)
}
```

## Release Builds

<!-- context-init:managed -->

1. **Update version** in `app/build.gradle.kts`
2. **Build release**
   ```powershell
   ./gradlew.bat :app:assembleRelease
   ```
3. **Sign APK** (keystore required)
4. **Test on device** before publishing

## Troubleshooting

<!-- context-init:managed -->

| Problem | Solution |
|---------|----------|
| Gradle sync fails | Invalidate caches, restart Android Studio |
| Build hangs | Stop daemon: `./gradlew.bat --stop` |
| Tests flaky | Check for shared state, use test dispatchers |
| Out of memory | Increase Gradle heap in `gradle.properties` |
| Module not found | Check `settings.gradle.kts` includes |

### Clean State

```powershell
# Full clean
./gradlew.bat --stop
./gradlew.bat clean

# With fresh daemon
./gradlew.bat --stop
Remove-Item -Recurse -Force .gradle
./gradlew.bat :app:assembleDebug
```

<!-- context-init:user-content-below -->
