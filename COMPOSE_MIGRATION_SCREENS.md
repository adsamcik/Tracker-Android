# Jetpack Compose Migration Analysis

This document lists all screens/activities/fragments in the Tracker Android app that need to be migrated to Jetpack Compose.

## Migration Status Summary

### ✅ Already Migrated to Compose

1. **OnboardingActivity** - Modern Compose-based onboarding flow that replaced the old first-run dialog system
2. **ImportExportComposeActivity** - Compose version of the import/export functionality
3. **CrashViewerActivity** - Debug crash viewer using ComposeDetailActivity
4. **Map Fragment (Partial)** - Some UI components are Compose-based (MapSheet, MapScreen) but fragment container is still traditional

### 🔄 In Progress / Partial

1. **Map Module** - Transitioning to Compose architecture with new MapScreen and MapSheet components, but FragmentMap container still uses traditional Views

### ❌ Needs Migration to Compose

## Core App Screens

### 1. MainActivity

**File:** `app/src/main/java/com/adsamcik/tracker/app/activity/MainActivity.kt`  
**Current State:** Traditional View-based (extends CoreUIActivity)  
**Purpose:** The main screen users spend most time in. Contains the core app UI with draggable navigation buttons for Stats, Game, and Map modules. Hosts the tracker fragment and manages module switching.  
**Context:** This is the central hub of the app - users launch tracking, navigate between modules (Statistics, Game, Map), and access core functionality from here. Contains complex draggable UI elements and module loading logic.  
**Complexity:** High - Complex layout with draggable components, fragment management, and module integration

### 2. SettingsActivity

**File:** `app/src/main/java/com/adsamcik/tracker/preference/activity/SettingsActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Settings and preferences screen using PreferenceFragmentCompat. Handles all app configuration including tracking settings, privacy options, debug features, and module-specific settings.  
**Context:** Critical for app configuration - users set tracking preferences, privacy settings, debug options, and customize app behavior. Uses Android's preference system with custom page navigation.  
**Complexity:** High - Preference-based UI with complex navigation and multiple setting categories

### 3. LicenseActivity

**File:** `app/src/main/java/com/adsamcik/tracker/license/LicenseActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Displays open source licenses for all dependencies used in the app. Shows manually curated licenses and Google's automatically generated license list.  
**Context:** Legal compliance screen - shows all open source licenses for transparency and legal requirements. Important for app store compliance.  
**Complexity:** Medium - RecyclerView with license data, some dynamic content loading

### 4. ModuleActivity

**File:** `app/src/main/java/com/adsamcik/tracker/module/activity/ModuleActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Manages dynamic feature modules (Statistics, Game, Map). Allows users to install/uninstall optional app components using Google Play's Dynamic Delivery.  
**Context:** Module management for the app's modular architecture - users can download/remove optional features to save space or add functionality.  
**Complexity:** High - Complex module installation flow with progress tracking and error handling

### 5. StatusActivity (Debug)

**File:** `app/src/main/java/com/adsamcik/tracker/app/activity/debug/StatusActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Debug screen showing app status, system information, and debugging tools for development and troubleshooting.  
**Context:** Development and debugging tool - helps developers and power users diagnose issues and view system state.  
**Complexity:** Medium - Information display with some interactive debugging features

## Fragment Components

### 6. FragmentTracker

**File:** `tracker/src/main/java/com/adsamcik/tracker/tracker/ui/fragment/FragmentTracker.kt`  
**Current State:** Traditional View-based (extends CorePermissionFragment)  
**Purpose:** Core tracking interface - the main screen for location/activity tracking. Shows current tracking status, permissions, data collection components (location, WiFi, cellular, activity, steps), and tracking controls.  
**Context:** Primary user interface for the app's core functionality - users start/stop tracking, view current data, manage permissions, and see real-time tracking information. This is where users spend most of their active time in the app.  
**Complexity:** Very High - Complex tracking state management, permissions, real-time data display, RecyclerView with multiple component types

### 7. FragmentStats

**File:** `statistics/src/main/java/com/adsamcik/tracker/statistics/fragment/FragmentStats.kt`  
**Current State:** Traditional View-based (extends CoreUIFragment)  
**Purpose:** Statistics overview showing recent tracking sessions in a list format. Users can view session summaries, dates, distances, and tap to see detailed statistics.  
**Context:** Key module for data analysis - users review their tracking history, see patterns in their data, and access detailed session information.  
**Complexity:** High - Complex paged data loading, RecyclerView with sectioned adapters, ViewFlipper for different states

### 8. FragmentGame

**File:** `game/src/main/java/com/adsamcik/tracker/game/fragment/FragmentGame.kt`  
**Current State:** Traditional View-based (extends CoreUIFragment)  
**Purpose:** Gamification interface showing challenges, goals, points, and achievements. Displays daily/weekly step goals, distance challenges, exploration challenges, and user progress.  
**Context:** Motivational module that encourages usage through game mechanics - users track progress on challenges, see achievement points, and compete with personal goals.  
**Complexity:** High - Multiple game component types, RecyclerView with different item types (challenges, points, steps), complex data aggregation

### 9. FragmentSettings

**File:** `app/src/main/java/com/adsamcik/tracker/preference/fragment/FragmentSettings.kt`  
**Current State:** Traditional View-based (extends PreferenceFragmentCompat)  
**Purpose:** Settings fragment that loads preferences from XML. Handles the actual preference UI and initializes preference observers.  
**Context:** The actual settings UI component used by SettingsActivity - displays preference categories and manages preference state.  
**Complexity:** Medium - Android Preference system integration, but relatively straightforward

## Detail/Management Screens

### 10. StatsDetailActivity

**File:** `statistics/src/main/java/com/adsamcik/tracker/statistics/detail/activity/StatsDetailActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Detailed statistics view for individual tracking sessions. Shows comprehensive analytics including charts, maps, session information, activity breakdowns, and location data.  
**Context:** Deep analytics view - users examine individual sessions in detail, see route maps, elevation charts, speed analysis, and activity classifications.  
**Complexity:** Very High - Complex data visualization, charts, embedded maps, multiple data display types, heavy data processing

### 11. ExportActivity

**File:** `impexp/src/main/java/com/adsamcik/tracker/impexp/exporter/activity/ExportActivity.kt`  
**Current State:** Traditional View-based (extends DetailActivity)  
**Purpose:** Data export interface allowing users to export tracking data in various formats (GPX, KML, JSON, SQLite) with date range selection and format options.  
**Context:** Data portability - users export their data for backup, analysis in other tools, or migration. Critical for data ownership and privacy.  
**Complexity:** High - File system operations, multiple export formats, date selection, progress tracking

### 12. NotificationManagementActivity

**File:** `tracker/src/main/java/com/adsamcik/tracker/tracker/notification/NotificationManagementActivity.kt`  
**Current State:** Traditional View-based (extends ManageActivity)  
**Purpose:** Manages tracking notification content. Allows users to customize what information appears in the persistent notification during tracking (location, speed, distance, etc.).  
**Context:** Notification customization - users control what tracking data is visible in notifications for privacy and personalization.  
**Complexity:** High - Drag-and-drop reordering, dynamic notification preview, complex preference management

### 13. WifiBrowseActivity

**File:** `statistics/src/main/java/com/adsamcik/tracker/statistics/wifi/WifiBrowseActivity.kt`  
**Current State:** Traditional View-based (extends ManageActivity)  
**Purpose:** Browse and filter collected WiFi network data. Shows SSID, BSSID, signal strength, frequency, and other WiFi metadata with advanced filtering options.  
**Context:** Data exploration for WiFi tracking component - users can browse collected WiFi data for analysis or debugging.  
**Complexity:** High - Database queries, filtering UI, large dataset management, horizontal scrolling

### 14. ShortcutActivity

**File:** `tracker/src/main/java/com/adsamcik/tracker/tracker/shortcut/ShortcutActivity.kt`  
**Current State:** Traditional View-based (extends AppCompatActivity)  
**Purpose:** Handles app shortcuts for quick actions like starting/stopping tracking. Provides system integration for launcher shortcuts and quick tiles.  
**Context:** Quick access functionality - users can start tracking without opening the full app interface.  
**Complexity:** Low - Simple activity for handling shortcuts and redirects

## Base Activity Classes (Infrastructure)

### 15. DetailActivity

**File:** `sutils/src/main/java/com/adsamcik/tracker/shared/utils/activity/DetailActivity.kt`  
**Current State:** Traditional View-based base class  
**Purpose:** Base class for detail screens providing common UI elements like elevation, title bars, and styling. Most secondary screens extend this.  
**Context:** Infrastructure component - provides consistent styling and behavior across detail screens.  
**Complexity:** Medium - Base class requiring careful migration to maintain consistency

### 16. ManageActivity

**File:** `sutils/src/main/java/com/adsamcik/tracker/shared/utils/activity/ManageActivity.kt`  
**Current State:** Traditional View-based base class  
**Purpose:** Base class for management screens with RecyclerView, FAB, and CRUD operations. Provides common patterns for list management with add/edit/delete functionality.  
**Context:** Infrastructure for data management screens - provides consistent patterns for managing lists of data.  
**Complexity:** High - Complex base class with FAB, dialogs, RecyclerView management, drag-and-drop

### 17. CoreUIActivity

**File:** `sutils/src/main/java/com/adsamcik/tracker/shared/utils/activity/CoreUIActivity.kt`  
**Current State:** Traditional View-based base class  
**Purpose:** Base UI activity providing style management and common UI functionality. Root of the UI activity hierarchy.  
**Context:** Core infrastructure - provides consistent styling and lifecycle management for all UI activities.  
**Complexity:** Medium - Critical base class requiring careful migration strategy

## Migration Priority Recommendations

### High Priority (Core User Experience)

1. **MainActivity** - Central hub, most user interaction
2. **FragmentTracker** - Primary app functionality
3. **FragmentStats** - Key data viewing
4. **FragmentGame** - User engagement

### Medium Priority (Secondary Features)

1. **StatsDetailActivity** - Detailed analytics
2. **SettingsActivity** - Configuration
3. **ExportActivity** - Data export
4. **NotificationManagementActivity** - Customization

### Lower Priority (Infrastructure & Utilities)

1. **Base Activity Classes** - After main screens
2. **ModuleActivity** - Administrative
3. **LicenseActivity** - Legal compliance
4. **WifiBrowseActivity** - Data exploration
5. **StatusActivity** - Debug tools
6. **ShortcutActivity** - System integration

## Migration Considerations

### Challenges

- **Complex State Management:** Many screens have complex state (tracking, permissions, data loading)
- **Custom Views:** Lots of custom styling and dynamic theming
- **Fragment Integration:** Need to maintain fragment lifecycle compatibility
- **Base Class Dependencies:** Many activities depend on common base classes
- **RecyclerView Complexity:** Many screens use complex RecyclerView implementations

### Opportunities

- **Modern Architecture:** Move to MVVM with Compose
- **Better Performance:** Eliminate view binding overhead
- **Improved Theming:** Leverage Compose's Material Design 3
- **Simplified Layouts:** Reduce complex XML layouts
- **Better Testing:** Compose UI testing capabilities

### Existing Compose Infrastructure

- **TrackerTheme** - Custom theming system already in place
- **ComposeDetailActivity** - Base class for Compose detail screens
- **StyleUtils** - Compose utility functions for styling
- **OnboardingActivity** - Reference implementation of modern Compose patterns
