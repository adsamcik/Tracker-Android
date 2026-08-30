# Tracker Application Functionality & Features

> **Complete Feature Reference**  
> This document provides comprehensive coverage of all functionality in the Tracker Android application.

---

## Table of Contents
0. [First-Time Onboarding](#0-first-time-onboarding)
1. [Core Tracking System](#1-core-tracking-system)
2. [Tracker Dashboard & UI](#2-tracker-dashboard--ui)
3. [Map Visualization](#3-map-visualization)
4. [Statistics & History](#4-statistics--history)
5. [Gamification, Challenges & Goals](#5-gamification-challenges--goals)
6. [Notifications](#6-notifications)
7. [Settings & Configuration](#7-settings--configuration)
8. [Data Management](#8-data-management)
9. [Background Features](#9-background-features)
10. [Crash Reporting & Debugging](#10-crash-reporting--debugging)
11. [Accessibility & UX](#11-accessibility--ux)

---

## 0. First-Time Onboarding

### Streamlined Setup Flow

Following Apple-style progressive disclosure philosophy, the app's onboarding is designed for **time-to-first-track < 30 seconds**:

#### Welcome Screen
- **Animated icon**: Material You theming with entry animation
- **Value propositions**: Three key benefits presented with icons:
  - **Privacy**: 100% offline, all data stays on device
  - **Insights**: Detailed statistics and visualizations
  - **Gamification**: Challenges and achievements for motivation
- **Single CTA**: "Get Started" button advances to location precision choice

#### Location Precision Selector
Users choose their preferred tracking mode:

| Mode | Accuracy | Battery | Use Case |
|------|----------|---------|----------|
| **Approximate** | ~100-500m | Lower | General activity tracking, privacy-conscious users |
| **Precise** | ~5-50m | Higher | Detailed route mapping, accurate distance |

- **Visual comparison**: Side-by-side cards showing trade-offs
- **Battery impact indicator**: Honest disclosure of power consumption
- **Upgrade path**: Users can change later; app prompts after 2 sessions to upgrade if beneficial

#### Smart Defaults Applied
After precision selection, the app immediately applies:
- **Balanced tracking preset**: Moderate battery/accuracy trade-off
- **Essential sensors enabled**: GPS, Activity Recognition, Steps
- **Optional sensors disabled**: WiFi/Cell (user can enable later)
- **Auto-tracking OFF**: Manual control initially, user can enable transition detection in settings

**Result**: User can start first tracking session immediately

---

## 1. Core Tracking System

### 1.1 Data Sources

The app collects data from multiple device sensors simultaneously:

| Source | Metrics Collected | Permission Required |
|--------|-------------------|---------------------|
| **GPS Location** | • Latitude/Longitude<br>• Altitude<br>• Speed<br>• Bearing<br>• Accuracy (meters) | `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` |
| **Activity Recognition** | Transport mode detection:<br>• Still<br>• Walking<br>• Running<br>• On Bicycle<br>• In Vehicle | Google Play Services |
| **Pedometer** | • Step count<br>• Cadence calculation | `ACTIVITY_RECOGNITION` (Android 10+) |
| **Wi-Fi Scanner** | • BSSID (MAC address)<br>• SSID (network name)<br>• Signal strength (RSSI)<br>• Frequency<br>• Count per location | `ACCESS_FINE_LOCATION` |
| **Cell Towers** | • Cell ID (CID)<br>• Location Area Code (LAC)<br>• Signal strength<br>• Network operator | `READ_PHONE_STATE` (optional) |

The rows above describe the released legacy radio product. The unreleased v28 source-event
candidate currently persists identity-free Wi-Fi/Cell coverage, band/technology, signal quality,
and provider-time evidence only. Unique-network, distinct-cell, and radio-map products remain
legacy-owned until a purpose/consent-epoch keyed identity with rotation/deletion and shadow parity
is explicitly approved; v28 containment rows are not silently treated as equivalent raw history.

### 1.2 Tracking Modes

#### Manual Tracking
- User explicitly starts/stops sessions via the dashboard FAB (Floating Action Button)
- Session persists across app backgrounding and device reboots
- Foreground service ensures reliable collection

####  Automatic Tracking (Transition Detection)
- **Background Monitor**: `ActivityWatcherService` passively detects motion
- **Auto-Start Trigger**: Begins tracking when activity transitions from `Still` to:
  - `Walking`
  - `Running`
  - `On Bicycle`
  - `In Vehicle`
- **Auto-Stop**: Ends session after prolonged inactivity
- **Configuration**: Enable/disable in *Settings → Tracking → Auto-tracking*

#### Activity Recognition Settings
- **Confidence Threshold**: 75% minimum (internal, ensures reliable detection)
- **Update Frequency**: Configurable interval in *Activity settings*
  - **Frequent**: Every 30 seconds (higher battery usage)
  - **Balanced**: Every 2 minutes (default)
  - **Infrequent**: Every 5 minutes (power-saving)
- **Activity Requirement**: Choose which detected activities trigger auto-tracking. Each option is inclusive *downward* (a higher setting also covers the activities below it), not a simple minimum:
  - **On Foot**: Starts for walking/running only (does **not** start for vehicle)
  - **In Vehicle**: Starts for driving/cycling **and** on-foot
  - **Still**: Disabled (manual tracking only)

### 1.3 Tracking Parameters

Fine-grained control over data density vs. battery consumption:

| Parameter | Description | Default | Range |
|-----------|-------------|---------|-------|
| **Min Distance** | Minimum distance change (meters) between location updates | 10m | 0-200m |
| **Min Time** | Minimum time interval (seconds) between updates | 2s | 0-60s |
| **Required Accuracy** | Discard GPS points with accuracy worse than X meters | 50m | 10-200m |

### 1.4 Tracking Presets

Quick configuration profiles balancing battery vs. accuracy:

| Preset | Location | WiFi | Cell | Activity | Steps | Battery Impact |
|--------|----------|------|------|----------|-------|----------------|
| **High Accuracy** | ✅ (frequent) | ✅ | ✅ | ✅ | ✅ | **High** |
| **Balanced** | ✅ (moderate) | ✅ | ⚠️ (optional) | ✅ | ✅ | **Moderate** |
| **Power Save** | ✅ (infrequent) | ❌ | ❌ | ✅ | ⚠️ | **Low** |
| **Custom** | User-configured | — | — | — | — | Calculated |

*Preset selection automatically applies min distance/time/accuracy values optimized for the use case.*

### 1.5 Session Lock (Pause Tracking)

Temporarily disable tracking without losing configuration:

- **Duration Lock**: Pause for 30 min, 1 hour, 2 hours, 4 hours, or until manually unlocked
- **Recharge Lock**: Automatically resume when device is plugged into power and charging
- **Visual Indicator**: Lock icon badge appears in dashboard top bar when active
- **Use Case**: Prevents tracking during commutes, errands, or when battery is low

### 1.4 Adaptive Tracking State Machine (NEW)
The app features a sophisticated state machine (`TrackingPolicyManager`) that automatically adjusts tracking intensity based on movement heuristics:
- **States**: `PASSIVE_LOW` → `MOVEMENT_SUSPECTED` → `ACTIVE_MODERATE` → `ACTIVE_ELEVATED`.
- **Escalation Triggers**:
    - **Step Rate**: Escalates at 10, 40, and 80 steps per minute.
    - **Activity**: STILL → MOVING transition (with >50% confidence).
    - **Displacement**: >50m displacement detected by location.
- **De-escalation**: Automatic "cooldown" de-escalation after **5 minutes** of inactivity.
- **Policy Behavior**: Lower states disable GPS and fallback to Wi-Fi/Cell tracking only to save battery.

### 1.5 Weighted Wi-Fi Location Estimation (NEW)
A custom-built geolocation engine (`DefaultWifiLocationEstimator`) predicts user location without GPS:
- **Weighted Centroid**: Uses RSSI and frequency-aware weighting (2.4GHz given higher weight than 5GHz/6GHz due to propagation characteristics).
- **Outlier Rejection**: Uses spatial standard deviation (Welford's algorithm) to filter out "jumpy" access points.
- **Sample History**: Maintains a recent sample window (8 samples) for temporal smoothing.
- **Accuracy Estimation**: Dynamically calculates horizontal error in meters based on sample variance.

---

## 2. Tracker Dashboard & UI

### 2.1 Dashboard Layout

The dashboard adapts to device size using `WindowSizeClass`:
- **Compact** (phones): Single column grid
- **Medium** (tablets portrait): 2-column grid
- **Expanded** (tablets landscape): 3-column grid

### 2.2 When NOT Tracking

#### Today's Progress Card
- **Daily Distance**: Aggregated from all sessions today
- **Daily Steps**: Total pedometer count
- **Daily Duration**: Cumulative active time
- **Goal Progress Bars**: Visual indicators if goals are set (from gamification module)

#### Last Session Overview Card
Displays the most recent completed session:
- **Duration** (e.g., "1h 23m 45s")
- **Distance** (formatted per user's length system preference)
- **Steps** (formatted with thousands separator)
- **Collection Count** (number of data updates recorded)
- **Relative Time** (e.g., "2 hours ago")
- **Activity Breakdown**: Distance on foot vs. in vehicle
- **Actions**:
  - **Long-press** to copy session summary to clipboard
  - **Share button** to send summary via Intent (SMS, email, etc.)
  - **Tap on map thumbnail** to open session in Map view

#### Component Cards (GPS, Activity, WiFi, Cell)
Individual cards showing last known values for each sensor, e.g.:
- **GPS**: Latitude/Longitude (tap to toggle Decimal Degrees ↔ DMS format), accuracy
- **Activity**: Current detected activity with confidence percentage
- **WiFi**: Count of visible access points
- **Cell**: Connected cell tower information

### 2.3 When Tracking

#### Status & Quick Stats Card
Real-time metrics in a prominent card:
- **Live Duration** (updates every second)
- **Live Distance** (formatted)
- **Live Speed** (current, formatted per speed preference)
- **Collection Updates** (data point count)
- **"View on Map"** quick action button

#### Collapsible Sensor Details
- **Collapsed by default** to reduce clutter per Apple-style progressive disclosure
- **"Show sensor details"** expands individual component cards
- Prevents overwhelming users during active tracking while details remain accessible

### 2.4 Interactive Features

#### Milestone Haptic Feedback
- Subtle vibration feedback when hitting milestones during tracking:
  - Every **1 km** of distance
  - Every **1000 steps**
  - Every **10 minutes** of duration
- Provides tactile progress confirmation without needing to look at screen

#### Empty State
When no data exists (fresh install or after data wipe):
- Friendly "Start tracking to see data" message
- Illustration of tracking benefits

### 2.5 Additional UI Features

#### Dialogs & Bottom Sheets
- **Summary Statistics Dialog**: Lifetime aggregated stats
- **Week Statistics Dialog**: Week-specific data comparison
- **Export Format Dialog**: Choose export file format
- **Precision Upgrade Prompt**: Contextual permission upgrade

#### Navigation
- **FloatingNavigationBar**: Bottom navigation with glassmorphism effect
- **Type-Safe Routes**: Kotlin Serialization-based navigation
- Routes: Tracker, Map, Statistics, Game, Settings

#### Debug Screens
- **Tracebox Diagnostics**: Review local diagnostic captures and create disclosure-gated
  packages for save/share
- **Debug Route**: Developer settings interface

---

## 3. Map Visualization

### 3.1 Map Layers

The map supports multiple visualization layers that can be enabled/disabled individually:

#### Location Path Layer
- **Polylines**: Draws connected GPS points as a path
- **Color Coding**: Can represent activity type (e.g., blue for walking, red for driving)
- **Adjustable Weight**: Line thickness configurable

#### Location Heatmap Layer
- **Density visualization**: Shows where you've spent the most time
- **Gradient colors**: Blue (low) → Green → Yellow → Red (high)
- **Tile-based**: Uses Google Maps tile overlay for performance

#### Speed Heatmap Layer
- **Speed zones**: Visualizes where you traveled fastest vs. slowest
- **Custom gradients**: Cool colors (slow) → Warm colors (fast)

#### Wi-Fi Heatmap Layer
- **Signal density**: Shows areas with high Wi-Fi access point visibility
- **Network discovery**: Useful for mapping Wi-Fi coverage

#### Wi-Fi Count Heatmap Layer
- **Access point concentration**: Highlights zones with many networks (urban areas)

#### Cell Tower Heatmap Layer
- **Cellular coverage**: Visualizes cell signal strength and tower density
- **Dead zone identification**: Gaps reveal areas with poor coverage

### 3.2 Map Controls

#### Date Filtering
- **Single Day**: View tracks from a specific date
- **Date Range**: Aggregate data across multiple days
- **All Time**: Entire tracking history

#### Layer Picker
- **Multi-select**: Enable multiple layers simultaneously
- **Transparency adjustment**: Adjust heatmap opacity to see underlying map

#### Search Bar
- **Location search**: Jump to address or POI
- **Integrated with Android Places API**

#### Floating Action Buttons
- **Layers button**: Quick access to layer picker
- **Current location button**: Re-center on current position
- **Date picker button**: Open calendar dialog

### 3.3 Map Sheet (Bottom Drawer)
- **Peek height**: Shows minimal controls when collapsed
- **Swipe to expand**: Reveals filtering options and layer settings
- **Respects navigation bar**: Content padding ensures no overlap with system UI

---

## 4. Statistics & History

### 4.1 Session List
- **Chronological view**: All sessions with date headers ("Today", "Yesterday", "Jan 15")
- **Quick stats**: Distance, duration, steps per session
- **Tap to view details**: Opens detailed session view

### 4.2 Session Detail View
- **Metrics grid**: All recorded data points
- **Activity timeline**: Time-series graph showing activity changes
- **Map preview**: Mini map showing session path
- **Export session**: Quick export as GPX

### 4.3 Aggregated Statistics
- **Weekly summaries**: Total distance/steps for the week
- **Monthly trends**: Historical graphs
- **Personal records**: Longest session, fastest speed, most steps, etc.

### 4.4 Statistics Dialogs

#### Summary Statistics Dialog
Accessed from Statistics screen, displays aggregated lifetime stats:
- **Overall Metrics**: Total distance, steps, active time, sessions
- **Loading States**: Shows spinner while calculating
- **Empty State**: Friendly message if no data exists
- **Icon-Based Display**: Each stat has custom icon for visual clarity
- **Scrollable List**: LazyColumn for long stat lists

#### Week Statistics Dialog
Accessed by selecting a specific week:
- **Week-Specific Aggregation**: Shows totals for selected 7-day period
- **Comparison**: Can compare different weeks
- **Same UI Pattern**: Consistent design with Summary dialog

---

## 5. Gamification & Challenges

### 5.1 Challenge System

#### Active Challenge Slots
- **3 concurrent challenges** at any time
- **Auto-rotation**: Completed or expired challenges are replaced with new random challenges

#### Challenge Types

| Type | Goal | Duration | Example |
|------|------|----------|---------|
| **Explorer** | Visit X new location tiles (fog of war mechanic) | 7 days | "Explore 700 new tiles" |
| **Walk Distance** | Walk a target distance | Varies | "Walk 10 km" |
| **Step Count** | Achieve step goal | Varies | "Take 10,000 steps" |
| **Active Time** | Maintain activity for duration | Varies | "Stay active for 2 hours" |

#### Progression & Rewards
- **Real-time tracking**: Challenges update as sessions complete
- **Progress bars**: Visual feedback on completion percentage
-### 5.3 Points System
- **Terrain-Aware Scoring**: Points calculation (`PointsWorker`) is not strictly linear with distance.
- **Slope Bonus**: On-foot activities receive a points multiplier based on the square root of the positive altitude slope.
- **Hysteresis Filtering**: Altitude changes below **10.0m** are ignored to filter out sensor noise.
- **Speed Integration**: Points are scaled by speed (Meters per Second) to reward intensity.

### 5.4 Points History & Goalstem

- **Daily Point Display**: Total points earned today shown prominently in Game screen
- **Earning Points**: Points awarded for completing challenges
- **Persistence**: Points tracked in database, accessible via Game module
- **Leaderboards**: Currently internal tracking (no global/friend leaderboards)

---

## 6. Notifications

### 6.1 Tracking Notification (Foreground Service)

When tracking is active, a persistent notification displays:

#### Notification Styles
- **Styled Notification** (default): Rich, colorful notification with custom layout
  - Live duration and distance
  - Activity type icon
  - Wi-Fi and cell count indicators
  - Speed (if moving)
- **Standard Notification**: Simple Android system notification (lightweight)

#### Toggle: *Settings → Tracking → Styled notifications*

### 6.2 Notification Customization

#### Drag-and-Drop Ordering
- **Notification Management Activity**: Accessible via *Settings → Tracking → Customize notification*
- **Long-press to drag**: Reorder notification components
- **Visual feedback**: Item highlights during drag

#### Component Visibility
Per-component configuration for **Title** and **Content** sections:

| Component | Show in Title | Show in Content |
|-----------|---------------|-----------------|
| Duration | ✅ | ✅ |
| Distance | ✅ | ✅ |
| Speed | ❌ | ✅ |
| Activity | ✅ | ✅ |
| Steps | ❌ | ✅ |
| WiFi Count | ❌ | ✅ |
| Cell Count | ❌ | ✅ |
| Collections | ❌ | ✅ |

*Users can hide irrelevant metrics to reduce notification clutter.*

---

## 7. Settings & Configuration

### 7.1 Tracking Settings

#### Presets & Sources
- **Preset Selector**: High Accuracy, Balanced, Power Save, or Custom
- **Battery Impact Warning**: Displayed when High Accuracy is selected
- **Source Toggles**: Enable/disable GPS, Activity, Steps, WiFi (with sub-options), Cell

#### Advanced Parameters
- **Minimum Distance** slider (0-200m, steps of 10m)
- **Minimum Time** slider (0-60s, steps of 5s)
- **Required Accuracy** slider (10-200m, steps of 10m)
- **Contextual Help**: Info icons explain trade-offs

### 7.2 General Settings

#### Length System
- **Metric** (km, m)
- **Imperial** (mi, ft)
- **Automatic Unit Switching**: Adapts to activity type (e.g., metric for walking, imperial for driving if configured)

#### Speed Format
- km/h
- mph
- m/s
- knots

#### Language
- Links to system language settings (app respects system locale)

### 7.3 Module Settings

#### Map Settings
- **Heatmap Quality**: Low, Medium, High (affects tile resolution)
- **Max Zoom Level**: Controls detail level
- **Tile Cache Size**: Storage allocation for offline tiles

#### Game Settings
- **Enable Challenges**: Master toggle
- **Notification for Challenge Completion**: Toast or silent

#### Activity Recognition Settings
Accessible via *Settings → Activity*:
- **Frequency**: Control how often activity is detected (affects battery)
- **Confidence Threshold**: Displayed for transparency (fixed at 75%)
- **Auto-Tracking Trigger**: Choose which activities initiate tracking

#### Statistics Settings
- **Unit Preferences**: Override length/speed for stats module
- **Display Options**: Show/hide specific metrics

#### Session Activity Manager
Customize activity type labels for your sessions:
- **Access**: *Settings → Activity → Manage Activities*
- **Add Custom Activities**: Define your own activity types (e.g., "Skateboarding", "Kayaking")
- **Edit/Delete**: Swipe-to-delete with undo, tap to rename
- **Visual Design**: Material 3 glass card UI with smooth animations
- **Usage**: Custom activities appear in session detail views and filtering

### 7.4 Data Settings

#### Auto-Cleanup
- **Enable Auto-Cleanup**: Background worker deletes old data
- **Retention Period**: 1 year, 2 years, 3 years, 5 years, Forever

#### Danger Zone
- **Delete All Data**: Wipes all tracking history (confirmation dialog required)

### 7.5 Debug Settings

#### Developer Mode
- **Activation**: Tap version number 7 times in Debug settings
- **Features Unlocked**:
  - **Tracebox Diagnostics**: Review captures and save/share diagnostic packages
  - **Dummy Data Generator** (debug builds only): Create fake sessions for testing

### 7.6 Additional UI Components

#### Settings Components
- **ExpandableSection**: Collapsible settings groups
- **BatteryImpactIndicator**: Visual battery usage warning (Low/Moderate/High)
- **PresetSelector**: Tracking preset chooser with descriptions
- **DialogListPreference**: List selection dialogs
- **SettingsItemWithHelp**: Setting with inline help icon
- **ExportFormatDialog**: Format selection for exports
- **TrackingPolicySelector**: Comprehensive tracking configuration UI

#### Precision Upgrade Flow
- **UpgradeToPrecisePrompt**: Dialog explaining benefits of precise location
- **LocationPrecisionSelector**: Onboarding component for precision choice
- Visual comparison of Approximate vs Precise modes

---

## 8. Data Management

### 8.1 Export Formats

| Format | Use Case | Contains |
|--------|----------|----------|
| **GPX 1.1** | Universal GPS standard | Tracks, waypoints, routes |
| **KML** | Google Earth visualization | Stylized paths, placemarks |
| **JSON** | Custom analysis/scripts | Raw database export (all fields) |
| **SQLite** | Full backup | Entire database file |

### 8.2 Import Support

#### Supported Formats

| Format | Implementation | Notes |
|--------|----------------|-------|
| **GPX** | Full track/segment parsing | Automatically creates sessions from track segments, extracts activity type, calculates distance |
| **KML** | Via GPS importer | Parses LineString coordinates |
| **ZIP** | Archive extractor | Auto-detects and processes GPX/KML files inside, batch import |
| **SQLite (.db)** | Direct database merge | Topological sort of foreign keys, schema-aware column matching |

#### GPX Import Details
- **Track Segments**: Each GPX track segment becomes a separate session
- **Activity Type**: Reads `<type>` tag and maps to session activity (creates custom activity if needed)
- **Waypoints**: Parses latitude, longitude, altitude, speed, timestamp
- **Distance Calculation**: Automatically calculates session distance from waypoint sequence
- **Time Range**: Derives session start/end from first/last waypoint timestamps

#### Database Import Process
1. **Schema Analysis**: Reads SQLite master table to discover structure
2. **Topology Sorting**: Uses graph-based topological sort to order tables by foreign key dependencies
   - Ensures referenced tables are imported before referencing tables
   - Prevents constraint violations
3. **Column Matching**: Imports only columns that exist in both source and destination databases
4. **Constraint Handling**:
   - **Activity table**: Silently skips duplicate entries (constraint violations expected)
   - **Other tables**: Reports constraint violations for manual review
5. **Transaction Safety**: Entire import wrapped in database transaction (all-or-nothing)

#### ZIP Archive Support
- **Extraction**: Temporary extraction to file system
- **Auto-Detection**: Identifies GPX/KML files by extension within archive
- **Batch Processing**: Imports all supported files sequentially
- **Cleanup**: Removes temporary files after import completes

### 8.3 Export Automation

**Backup Plans** allow scheduled, automatic exports:

#### Configuration
- **Access**: *Settings → Data → Automated Exports*
- **Frequency**: Daily, Weekly, Monthly, or custom interval
- **Format**: GPX, KML, JSON, or SQLite database backup
- **Destination**: Local storage directory (user-selectable)
- **Compression**: Optional ZIP compression for smaller file sizes

#### Plan Management
- **Create Multiple Plans**: Different schedules for different formats
- **Enable/Disable**: Toggle plans without deleting configuration
- **Last Export Timestamp**: View when each plan last ran
- **Manual Trigger**: Force immediate export from any plan

#### WorkManager Integration
- Runs as background job (doesn't require app to be open)
- Respects battery optimization settings
- Automatic retry on failure
- Notification on completion (optional)

### 8.5 Data Optimization & Performance (NEW)
- **Simplify3D Algorithm**: Uses the Simplify3D algorithm with a **500.0m tolerance** to optimize path data for visualization, significantly reducing memory and rendering overhead.
- **Database Write Batching**: Locations are buffered in memory and flushed to the database in batches of **10 points** or every **5 seconds** to minimize disk I/O and wake-locks.
- **Session Resumption**: Background sessions have a **15-minute auto-resume window**, allowing the app to merge fragmented tracking cycles into single logical sessions.
- **Anti-Mocking**: Built-in protection against mock location providers (GPS spoofing) ensures data integrity.

---

## 9. Background Features & Reliability

### 9.1 Dynamic Shortcuts (Android 7.1+)

Home screen shortcuts update based on tracking state:
- **When NOT tracking**: "Start Tracking" shortcut
- **When tracking**: "Stop Tracking" shortcut
- **Long-press app icon** to access

### 9.2 Precision Upgrade Flow

Progressive permission disclosure aligned with Apple-style UX:

1. **Initial State**: User grants `ACCESS_COARSE_LOCATION` (approximate)
2. **Demonstrate Value**: App works for 2-3 sessions
3. **Contextual Prompt**: After session #2, prompt explains benefits of precise location:
   - More accurate distance tracking
   - Better route visualization
   - Improved heatmap quality
4. **User Choice**:
   - **Upgrade**: Requests `ACCESS_FINE_LOCATION`
   - **Dismiss**: Sets flag to never ask again (respects user preference)

**Rationale**: Avoids overwhelming new users with permission requests; earns trust first.

### 9.3 Goals System (Separate from Challenges)

**Goals** are longer-term targets tracked independently from the dynamic challenge system:

#### Types of Goals

| Goal | Period | Target | Configurable |
|------|--------|--------|-------------|
| **Daily Step Goal** | 24 hours | X steps per day | Yes (*Settings → Game → Goals*) |
| **Weekly Step Goal** | 7 days | X steps per week | Yes (*Settings → Game → Goals*) |

#### Goal Tracking
- **Automatic Updates**: Goals update as sessions complete throughout the day/week
- **Progress Display**: Shown in Game screen UI with visual progress bars
- **Today vs Week**: Display both daily and weekly progress simultaneously
- **Achievement Notifications**: When goal is reached, shows motivational notification with random encouragement message
- **Notification Action**: Tapping notification opens Game screen

#### Implementation Details
- **Persistence**: Goal completion timestamps stored to prevent duplicate notifications
- **New Day Detection**: `NewDayGoalWorker` checks daily at midnight for goal resets
- **Database Aggregation**: Sums steps from all sessions within the period
- **Goal Periods**: Daily (resets each day), Weekly (resets each Monday)

**UI Difference**: Goals appear at the top of the Game screen in a dedicated "Steps & Goals" card, while Challenges are listed below as individual challenge cards.

### 9.4 WorkManager Jobs

| Worker | Frequency | Purpose |
|--------|-----------|---------|
| **DatabaseMaintenanceWorker** | Retired compatibility shell | Performs no database mutation; UI maintenance startup requests cancellation and collected-data deletion awaits it, while lifecycle-owned empty-session reclamation remains pending |
| **DataRetentionWorker** | Weekly | Deletes data older than retention period |
| **ActivityRecognitionWorker** | On-demand | Classifies activity type for historical sessions |
| **ChallengeExpiredWorker** | Scheduled | Removes expired challenges and activates new ones |
| **ChallengeWorker** | On session completion | Updates challenge progress |
| **DisableTillRechargeWorker** | On charging | Unlocks tracking lock when device is charged |
| **NewDayGoalWorker** | Daily at midnight | Resets daily goals, checks completions |
| **PointsWorker** | On session completion | Calculates and awards points |
| **ImportWorker** | On-demand | Processes file imports in background |
| **ExportPlanWorker** | Scheduled (per plan) | Executes automated export backup plans |

### 9.5 Services

| Service | Type | Purpose |
|---------|------|---------|

| **TrackerService** | Foreground | Main tracking service - GPS, sensors, data collection |
| **ActivityWatcherService** | Background | Monitors activity changes for auto-tracking |
| **CoreService** | Base class | Foundation for foreground services with notification management |

### 9.6 Broadcast Receivers

| Receiver | Trigger | Action |
|----------|---------|--------|
| **BootReceiver** | `BOOT_COMPLETED` | Restart tracking if session was active before reboot |
| **OnAppUpdateReceiver** | `MY_PACKAGE_REPLACED` | Re-initialize shortcuts, schedule workers after app update |
| **PrecisionUpgradeReceiver** | `ACTION_SESSION_FINAL` | Increment session count and trigger precision upgrade prompt if threshold reached |
| **TrackerTimeUnlockReceiver** | Alarm fires | Unlocks time-based tracking lock |
| **TrackerNotificationReceiver** | Notification actions | Handles stop tracking from notification |
| **PointsSessionReceiver** | Session completion | Triggers points calculation |
| **ChallengeSessionReceiver** | Session completion | Updates challenge progress |
| **ActivityReceiver** | Activity recognition updates | Processes detected activity changes |
| **ActivitySessionReceiver** | Session info needed | Provides activity context to sessions |

---

## 10. Crash Capture & Diagnostics

### 10.1 Tracebox Runtime

Tracebox is Tracker's sole crash and diagnostic backend. The main process
installs it during `Application.attachBaseContext()`, before content providers
and `Application.onCreate()`, while Tracebox's private handler process skips the
Tracker application graph to avoid recursive installation.

Tracker enables optional native capture and keeps managed capture operational when native setup is
unavailable. The readiness/health UI reports that condition as degraded. Production release
evidence requires `libtracebox_crashpad.so` for Tracker's supported phone ABI (`arm64-v8a`) and
binds native symbols to the exact build identity. Debug-only emulator ABIs are not shipped.

Application code emits only static templates with bounded structural values such
as counts, durations, and enums. Precise coordinates, tracked identifiers,
exception messages, and arbitrary object rendering are excluded. The
`:core:diagnostics` module re-exports the Tracebox API as a dependency boundary;
it does not own a parallel facade or data store. Tracker has no diagnostic Room
storage, file-fallback migration, or legacy crash/log viewer.

The standard diagnostics policy keeps performance observations disabled. If the user enables the
separate performance category, Tracker records bounded process-start elapsed/CPU time; battery,
charging, power-mode, process-memory, and memory-pressure snapshots at app foreground/background
boundaries; and tracking-session frame wake-lock duration. Structural diagnostics and the local
technical-status UI also expose counts for coalesced in-process source timers. Memory work runs off
the main thread, unsupported battery counters are marked unavailable, and no periodic polling or
system-wide wakeup claim is made.

### 10.2 Diagnostics Controls

The Tracebox settings screen exposes the supported diagnostic workflow:

- inspect readiness and health;
- enable or disable the diagnostics profile;
- persist the requested policy across restarts and restore Tracker's standard defaults;
- delete all Tracebox-owned data;
- prepare a standard diagnostic package;
- review and explicitly approve its disclosure; and
- save or share the approved package through Android system UI.

Tracker does not render raw crash records or free-form application logs in its
own UI. The app-wide collected-data deletion transaction also deletes all
Tracebox-owned data. A partial handler-process deletion keeps a durable marker
and is retried during startup before the transaction is considered complete.

All Tracebox status, capture, duration, review/approval, failure, save/share, and deletion text is
resource-backed. Tracker packages every declared app locale and supplies localized diagnostics
title/summary resources; untranslated Tracebox library strings use the library's default resource.
Android backup and device-to-device transfer are disabled for all Tracker and Tracebox storage.

Approved package bytes are bounded capabilities. Tracebox retires them after save/share, package
replacement, policy change, explicit deletion, or diagnostics-screen disposal.

---

## 11. Accessibility & UX

### 10.1 Haptic Feedback

- **Button presses**: Gentle tap feedback for all interactive elements
- **Tracking start**: Confirm haptic when starting
- **Tracking stop**: Reject haptic when stopping
- **Milestones**: Subtle feedback at distance/step/time milestones (see §2.4)
- **Long-press actions**: Long-press haptic for drag-to-reorder, copy actions

### 10.2 Semantic Content Descriptions

All UI elements have accessibility labels for TalkBack:
- "Start tracking button"
- "Session overview: 1 hour 30 minutes duration, 5.2 kilometers distance"
- "Copy session summary to clipboard"

### 10.3 Material 3 Theming

- **Dynamic Color** (Android 12+): App colors adapt to wallpaper
- **Dark Mode Support**: Fully implemented across all screens
- **Large Text Support**: Scales with system font size settings

### 10.4 Progressive Disclosure

Following Apple-style UX philosophy:
- **Essential first**: Primary actions (Start/Stop) are immediately visible
- **Details on demand**: Advanced settings/sensor details hidden behind taps
- **No overwhelming walls of text**: Settings grouped into cards, collapsible sections
- **Smart defaults**: App works well out-of-the-box without configuration

---

## Quick Reference

### Key Entry Points

| Action | Path |
|--------|------|
| **Review local diagnostics** | Settings → Debug → Crash diagnostics |
| **Start tracking** | Dashboard → FAB button |
| **View map** | Bottom navigation → Map |
| **View statistics** | Bottom navigation → Stats |
| **View challenges** | Bottom navigation → Game |
| **Configure tracking** | Settings → Tracking |
| **Export data** | Settings → Data → Export |
| **Customize notification** | Settings → Tracking → Customize notification |

### Permissions

| Permission | Purpose | Required? |
|------------|---------|-----------|
| `ACCESS_FINE_LOCATION` | Precise location and Wi-Fi scan results | Optional unless those sources are enabled |
| `ACCESS_COARSE_LOCATION` | Approximate location | Yes |
| `ACCESS_BACKGROUND_LOCATION` | Track when app is closed | Optional |
| `ACTIVITY_RECOGNITION` | Detect walking/driving/etc. | Optional |
| `POST_NOTIFICATIONS` | Show tracking notification | Yes (Android 13+) |
| `FOREGROUND_SERVICE` | Keep tracking alive | Yes |
| `READ_PHONE_STATE` | Cell tower details | Optional |

### Data Storage

- **Database**: Room (SQLite)
- **Location**: `/data/data/com.adsamcik.tracker/databases/`
- **Size**: Varies (100 MB for several months of daily tracking)
- **Privacy**: 100% local; zero cloud sync
- **Platform backup/transfer**: Disabled for every app-storage domain

---

*This documentation reflects the diagnostics integration as of August 2026.*
