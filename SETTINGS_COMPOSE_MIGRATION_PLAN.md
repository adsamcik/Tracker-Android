# Settings Compose Migration Plan

## Status: IN PROGRESS (Phase 1 Complete)

## Overview
Migration of `SettingsActivity` and `PreferenceFragmentCompat` to Compose-based `SettingsRoute`. This is the final major user-facing View-based component requiring migration.

---

## Current State (Legacy)

### Components to Remove
1. **SettingsActivity.kt** - DetailActivity subclass with Fragment hosting
2. **FragmentSettings.kt** - PreferenceFragmentCompat implementation
3. **XML Preferences** - `app_preferences.xml` (377 lines)
4. **Preference Pages** (Fragment-based):
   - `PreferencePage.kt` (interface)
   - `RootPage.kt`
   - `TrackerPreferencePage.kt`
   - `DebugPage.kt`
   - `DataPage.kt`
   - `ExportPage.kt`
5. **PreferenceExtensions.kt** - Fragment-specific helpers

### Features to Preserve
- Hierarchical navigation (root → tracking/data/export/debug)
- Module settings integration (Map, Game, Statistics)
- Permission handling callbacks
- Activity launching (SessionActivity, Licenses)
- Complex preference types:
  - DialogListPreference (language, length system, speed format)
  - SliderPreferences (distance, duration)
  - SwitchPreferences (auto-tracking, notifications)
  - Action preferences (export GPX/KML, import, data cleanup)
- Debug mode conditional visibility
- Backstack management

---

## Migration Phases

### ✅ Phase 1: Core Compose Route (COMPLETE)
**Goal:** Replace SettingsActivity with functional Compose route in navigation graph

**Completed:**
- ✅ Expanded `SettingsRoute.kt` with hierarchical navigation
- ✅ Sealed `SettingsScreen` hierarchy for type-safe navigation
- ✅ Scaffold with TopAppBar + back navigation
- ✅ Root settings list with key items:
  - Tracking → nested screen
  - Data → nested screen  
  - Activity → launches SessionActivityActivityCompose
  - Length/Speed system display (current values from ViewModel)
  - Auto unit switch (working toggle)
  - Language settings
  - Licenses → launches LicenseActivity
  - Debug → nested screen
- ✅ Reusable composables:
  - `SettingsItem` (clickable list item)
  - `SettingsItemWithValue` (displays current choice)
  - `SwitchSettingsItem` (inline toggle)
  - `SectionHeader`
- ✅ Placeholder screens for nested categories
- ✅ Material 3 styling (ListItem, Icons)
- ✅ No compilation errors

**Current Navigation:**
- Settings already accessible via `Routes.Settings` in MainRoot.kt
- TrackerRoute has `onOpenSettings` callback invoking nav to settings
- No Activity launch needed; pure Compose navigation

---

### 🔄 Phase 2: Expand Nested Screens (NEXT)
**Goal:** Implement full preference UI for each category

#### 2a. Tracking Settings
Migrate `TrackerPreferencePage.kt` + XML tracking section:
- [ ] Tracking notice info card
- [ ] Location permission warning (conditional)
- [ ] Tracker timer preference (dialog list)
- [ ] Auto-tracking category:
  - [ ] Activity options (dialog multi-select)
  - [ ] Transition detection switch
  - [ ] Activity watcher checkbox + frequency slider
  - [ ] Disable on recharge switch
- [ ] Notification category:
  - [ ] Styled notification switch
  - [ ] Customize notification action (launch system settings)
- [ ] Min distance slider (distance units aware)
- [ ] Min time slider (duration)
- [ ] Required GPS accuracy slider
- [ ] Enable/disable toggles:
  - [ ] Location, Activity, Steps, WiFi, Cell
  - [ ] WiFi sub-options (network name, location count)

**ViewModel expansion:**
```kotlin
// Add to SettingsViewModel or new TrackingSettingsViewModel
val trackingEnabled: StateFlow<Boolean>
val locationEnabled: StateFlow<Boolean>
val activityEnabled: StateFlow<Boolean>
// ... etc., reading from Preferences or new DataStore
fun setLocationEnabled(enabled: Boolean)
```

#### 2b. Data Settings
Migrate `DataPage.kt` + `ExportPage.kt` + XML data section:
- [ ] Export sub-screen:
  - [ ] Export GPX action
  - [ ] Export KML action  
  - [ ] Export SQLite DB action
  - [ ] (JSON export commented out in XML, skip)
- [ ] Import action
- [ ] Auto cleanup old data switch
- [ ] Data retention years dialog list
- [ ] Remove all collected data action (with confirmation dialog)

**Actions:**
- Export → launch file picker + background export job with progress
- Import → file picker + validation + import flow
- Cleanup → confirmation dialog → background deletion

#### 2c. Debug Settings
Migrate `DebugPage.kt` + XML debug section:
- [ ] Version display (BuildConfig.VERSION_CODE + VERSION_NAME)
- [ ] "Tap version 7 times to enable developer mode" Easter egg
- [ ] Debug enabled switch (controls visibility of debug menu)
- [ ] Crash manager action
- [ ] Log viewer action
- [ ] Status activity action
- [ ] (Other debug features from DebugPage)

**Conditional visibility:**
- Debug section only shown if `settings_debug_enabled_key` is true

#### 2d. Module Settings Integration
Currently modules provide `ModuleSettings` interfaces:
- MapSettings, GameSettings, StatisticsSettings

**Approach:**
1. Each module exposes a `@Composable fun ModuleSettingsContent()`
2. Root settings dynamically includes module items if module enabled
3. Click navigates to module's screen

**Alternative (simpler):**
- Root lists static items: "Map", "Game", "Statistics"
- Click navigates to dedicated screens in respective modules
- Settings route passes navigation lambda

---

### 🔄 Phase 3: DataStore Migration
**Goal:** Replace SharedPreferences reads/writes with DataStore where applicable

**Current state:**
- `TrackerSettingsRepository` already uses DataStore for length/speed/auto-unit
- Most other settings still use `Preferences.getPref(context).getBoolean(...)` etc.

**Strategy:**
- Preferences class is a wrapper around SharedPreferences
- Incrementally migrate hot settings to DataStore repositories
- For less-critical settings, continue using SharedPreferences short-term
- Document each key's migration status

**Priority DataStore migrations:**
1. Tracking enable/disable flags → `TrackingFlagsRepository`
2. Auto-tracking config → `AutoTrackingRepository`
3. Notification preferences → `NotificationPrefsRepository`
4. Data retention → `DataRetentionRepository`

**Low priority (defer):**
- One-time flags (show_tips, debug_enabled)
- Rarely changed (language, error_reporting)

---

### 🔄 Phase 4: Advanced Preference Types
**Goal:** Implement custom dialogs and pickers

#### Dialog List Preferences
Replace `DialogListPreference` XML component:
```kotlin
@Composable
fun DialogListPreferencePicker(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
)
```
- Material 3 AlertDialog with RadioButton list
- Remember dialog open state
- Accessibility: announce selection

#### Slider Preferences
Replace `DistanceValueSliderPreference`, `DurationValueSliderPreference`:
```kotlin
@Composable
fun SliderPreference(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit
)
```
- Material 3 Slider
- Display formatted value (distance/time)
- Semantic label for accessibility

#### Language Picker
Replace XML language preference:
- Use `LocaleManager` from existing codebase
- Material 3 dropdown or bottom sheet with available locales
- Immediate app locale switch

---

### 🔄 Phase 5: Permission Handling
**Goal:** Replicate `PreferencePage.onRequestPermissionsResult` flow in Compose

**Current mechanism:**
- TrackerPreferencePage requests permissions
- SettingsActivity relays results to active page

**Compose approach:**
```kotlin
@Composable
fun TrackingSettings(vm: TrackingSettingsViewModel) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onLocationPermissionResult(granted) }
    
    // UI triggers launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
}
```
- Use `rememberLauncherForActivityResult` in each screen needing permissions
- ViewModel handles result logic

---

### 🔄 Phase 6: Testing & Cleanup
**Goal:** Ensure feature parity and remove legacy code

#### Test Updates
- [x] Identify instrumentation test: `AutoCleanupPreferenceInstrumentedTest.kt`
  - Currently launches `SettingsActivity`
  - Needs migration to Compose UI test using ComposeTestRule
- [ ] Write Compose UI tests:
  ```kotlin
  @Test
  fun toggleAutoCleanup_persistsAcrossRestart() {
      composeTestRule.setContent { SettingsRoute() }
      composeTestRule.onNodeWithText("Data").performClick()
      composeTestRule.onNodeWithText("Auto cleanup old data").performClick()
      // Assert preference persisted
  }
  ```
- [ ] Test hierarchical navigation (root → nested → back)
- [ ] Test permission flows
- [ ] Test export/import actions

#### Removal Checklist
After all features migrated and tests green:
- [ ] Delete `SettingsActivity.kt`
- [ ] Delete `FragmentSettings.kt`
- [ ] Delete `PreferencePage.kt`, `RootPage.kt`, `TrackerPreferencePage.kt`, etc.
- [ ] Delete `PreferenceExtensions.kt`
- [ ] Delete `app_preferences.xml`
- [ ] Remove SettingsActivity from AndroidManifest.xml
- [ ] Remove PreferenceFragmentCompat dependency (if no other usage)
- [ ] Remove custom preference components:
  - `DialogListPreference`
  - `IndicesDialogListPreference`
  - `DistanceValueSliderPreference`
  - `DurationValueSliderPreference`
- [ ] Audit and remove unused string resources (preference keys/titles may remain for backward compat)

---

## Technical Debt & Future Enhancements

### Immediate Concerns
- **Module settings:** Currently hardcoded static list. Need dynamic registration or explicit imports.
- **Style settings removed:** XML had a "Style" screen (deprecated runtime theming). Confirmed removal intentional per copilot-instructions.md.
- **Preference observers:** `PreferenceObserver.initialize(sharedPreferences)` called in FragmentSettings. Ensure still initialized elsewhere (likely in app startup).

### Future Improvements
- **Search:** Add settings search bar filtering items by title/keywords.
- **Deep links:** Support deep link to specific settings screen (e.g., from notification).
- **Adaptive layout:** Two-pane list/detail on tablets/foldables.
- **Predictive back:** Ensure back gesture preview works with nested navigation.

---

## Migration Checklist Summary

### Phase 1: ✅ COMPLETE
- ✅ Create hierarchical SettingsRoute with navigation
- ✅ Root settings list with key actions
- ✅ Placeholder nested screens
- ✅ Material 3 styling + reusable composables

### Phase 2: 🔄 IN PROGRESS (Next Steps)
- [ ] Implement TrackingSettings screen (full preferences)
- [ ] Implement DataSettings + ExportSettings screens
- [ ] Implement DebugSettings screen
- [ ] Integrate module settings

### Phase 3: 🔜 PLANNED
- [ ] Migrate tracking flags to DataStore
- [ ] Create repositories for settings categories

### Phase 4: 🔜 PLANNED
- [ ] DialogListPreference Compose component
- [ ] SliderPreference Compose component
- [ ] Language picker

### Phase 5: 🔜 PLANNED
- [ ] Permission handling with rememberLauncherForActivityResult
- [ ] Test permission flows

### Phase 6: 🔜 PLANNED
- [ ] Migrate instrumentation tests to Compose
- [ ] Delete legacy files
- [ ] Update AndroidManifest
- [ ] Final verification

---

## Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Breaking existing settings persistence | HIGH | Maintain all preference keys unchanged; read/write to same SharedPreferences keys during transition |
| Module settings not discoverable | MEDIUM | Explicitly list known modules (Map, Game, Stats) in root; document integration pattern |
| Permission flows broken | MEDIUM | Test each permission-requiring feature (location, activity) with launcher approach |
| Instrumentation test failures | LOW | Update tests incrementally as screens migrate; use temporary @Ignore if blocking |
| Missing preference types | MEDIUM | Catalog all unique XML preference types before deletion; ensure Compose equivalents exist |

---

## Acceptance Criteria

Settings migration complete when:
1. ✅ All user-visible settings accessible via Compose UI
2. ✅ No functional regressions (all toggles, actions, dialogs work)
3. ✅ All preference values persist correctly (same keys, same storage)
4. ✅ Module settings (Map, Game, Stats) integrated
5. ✅ Permission flows functional
6. ✅ Tests green (unit + instrumentation)
7. ✅ SettingsActivity + Fragment code deleted
8. ✅ XML preferences deleted
9. ✅ AndroidManifest updated
10. ✅ No PreferenceFragmentCompat dependencies remain

---

## Notes

- **North Star Compliance:** This migration follows copilot-instructions.md principles:
  - Pure Compose (no XML, no Fragments, no AndroidView interop)
  - Material 3 Expressive
  - DataStore preferred over SharedPreferences for new keys
  - No legacy pattern perpetuation
  
- **Backward Compatibility:** Preference keys remain unchanged to avoid data loss. Users upgrading will see identical behavior.

- **Incremental Delivery:** Each phase can be merged independently. Phase 1 (current) provides basic settings access; subsequent phases add richness.

---

**Last Updated:** 2025-01-05  
**Current Phase:** Phase 1 Complete, Phase 2 Next
