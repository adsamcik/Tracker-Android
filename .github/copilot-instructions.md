# Copilot Instructions for Tracker Android (Advention)

## Project Overview
Tracker Android is a privacy-focused, open-source location and activity tracking app with comprehensive data collection, analysis, and gamification features. All data processing is done locally with no cloud uploads.

## Architecture & Modules
- **Modular Design**: Core app with optional modules (Statistics, Game, Map)
- **Multi-module Gradle**: Separate modules for tracker, map, statistics, game, sbase, sutils, etc.
- **Android Services**: Background tracking via TrackerService and ActivityWatcherService
- **Database**: Local SQLite with Room for data persistence

## Key Components

### Core Tracking (`tracker` module)
- **TrackerService**: Main background service for data collection
- **Components**: Location, WiFi, Cell, Activity, Step tracking components
- **Auto-tracking**: Activity-based automatic start/stop with confidence thresholds
- **Locking System**: Time-based and charge-based tracking locks via TrackerLocker

### Data Collection
- **Location**: GPS with configurable accuracy, background location support
- **Networks**: WiFi scanning (SSID, BSSID, signal strength) and cell tower data
- **Activity**: Google Activity Recognition with custom activity support
- **Steps**: Device sensor-based step counting

### UI & Styling (`sutils` module)
- **Dynamic Theming**: 5 color modes including Morning-Day-Evening-Night transitions
- **StyleManager**: Centralized color management with sun position calculations
- **Responsive Design**: Adaptive layouts for different screen sizes

### Data Visualization
- **Statistics Module**: Charts, session analysis, distance/speed/elevation stats
- **Map Module**: Interactive maps with heatmaps and route visualization
- **Session Management**: Detailed tracking session analysis and editing

### Gamification (`game` module)
- **Challenges**: Distance and exploration challenges with difficulty levels
- **Goals**: Daily/weekly step goals with progress tracking
- **Points System**: Achievement-based point awards

### Data Management (`impexp` module)
- **Export**: GPX, KML, JSON, SQLite formats with date range selection
- **Import**: GPX files and batch ZIP processing
- **Local Storage**: All data stays on device

## Development Guidelines

### Code Style
- Kotlin-first with some Java legacy code
- Coroutines for async operations
- LiveData/Observer pattern for reactive UI
- Room database with DAOs for data access

### Key Patterns
- **Component Architecture**: Modular tracking components with enable/disable lifecycle
- **Observer Pattern**: Extensive use of LiveData for state management
- **Service Architecture**: Foreground services for background tracking
- **Permission Management**: Granular permission requests with rationales

### Important Classes
- `TrackerService`: Main tracking service
- `StyleManager`: Global color/theme management
- `TrackerLocker`: Tracking lock management
- `BackgroundTrackingApi`: Auto-tracking logic
- `AppDatabase`: Room database instance

### Testing
- Unit tests with JUnit
- Android instrumentation tests
- Database testing with Room testing utilities

### Configuration
- Extensive SharedPreferences for user settings
- Build variants for different releases (Alpha, Beta, Release)
- Gradle version catalogs for dependency management

### Dependency Management (must follow)
- Use the Gradle Version Catalog exclusively. All dependencies and plugins must be declared in `gradle/libs.versions.toml` and referenced via `libs.*` aliases in Gradle scripts.
- Do not hardcode coordinates or versions inside `build.gradle.kts` files. That includes test dependencies, Compose artifacts, BOMs, and tools.
- When you need a new library:
	1) Add or update a version under `[versions]` in `libs.versions.toml`.
	2) Add a library entry under `[libraries]` (or a plugin under `[plugins]`).
	3) Reference it from module `build.gradle.kts` using the generated alias (e.g., `implementation(libs.androidx.core.ktx)`).
- Prefer BOMs via the catalog as well. Declare a platform entry in the catalog and use `implementation(platform(libs.compose.bom))` (or similar) from modules.
- Keep comments in `libs.versions.toml` when pinning or temporarily overriding versions, and remove the pin when upstream is compatible.

Example (catalog):

```toml
[versions]
compose = "2024.06.00"

[libraries]
compose-foundation-layout = { module = "androidx.compose.foundation:foundation-layout", version.ref = "compose" }
robolectric = { module = "org.robolectric:robolectric", version = "4.15.1" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version = "2.0.0" }
```

Example (usage in build.gradle.kts):

```kotlin
plugins {
		alias(libs.plugins.kotlin.android)
}

dependencies {
		implementation(libs.compose.foundation.layout)
		testImplementation(libs.robolectric)
}
```

Migration note: If you see any hardcoded dependencies in modules (e.g., `implementation("androidx.compose.foundation:foundation-layout")` or explicit versions in tests), migrate them to the catalog in the same change.

## Privacy & Security
- No network data collection - purely local processing
- Optional Android backup only
- Open source for transparency
- Granular permission model

## Performance Considerations
- Background tracking optimization for battery life
- Efficient database queries with proper indexing
- Memory management for large datasets
- Sensor data collection optimization

When working on this project, prioritize user privacy, battery efficiency, and modular design principles.
