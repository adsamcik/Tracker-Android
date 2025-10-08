# String Resource Migration Plan

**Date**: October 8, 2025  
**Status**: In Progress  
**Target**: Migrate all hardcoded UI strings to `strings.xml` resources

## Objective

Per evergreen instructions:
> "Avoid string concatenation for user-visible messages; use string resources with placeholders. Compose format via `stringResource(id, arg1)`."

This migration ensures:
1. All user-visible strings are in resource files (i18n-ready)
2. No hardcoded strings in Compose UI code
3. Proper use of `stringResource()` in Compose
4. Placeholders for dynamic content instead of string concatenation

## Scope

### Modules to Migrate
- ✅ `app` (onboarding, debug screens, main nav)
- ✅ `map` (MapSheet controls, filters, dialogs)
- ✅ `game` (GameScreen progress display)
- ✅ `tracker` (TrackerDashboard, notification management)
- ✅ `statistics` (dialogs)

### Exclusions
- Test files (assertions can use hardcoded strings)
- Animation/transition labels (internal identifiers)
- Accessibility contentDescription (migrate selectively)
- Debug/developer-only placeholders

## Findings

### High Priority (User-Visible Text)

#### App Module - Onboarding Screens

**LocationSetupScreen.kt**
- `"Enable Location Access"` → `onboarding_location_enable_button`
- `"Skip for now"` → `onboarding_skip_button`
- `"Enable Background Location"` → `onboarding_background_location_enable_button`
- `"Continue without background tracking"` → `onboarding_continue_without_background_button`
- `"Continue"` → `onboarding_continue_button`
- `"What you'll get:"` → `onboarding_location_benefits_title`
- `"Your privacy is protected"` → `onboarding_privacy_title`
- `"All location data stays on your device. We never upload or share your routes."` → `onboarding_privacy_message`
- `"Background Location Required"` → `onboarding_background_location_required_title`
- `"Since automatic tracking is enabled, background location permission is needed for the app to track movements when minimized."` → `onboarding_background_location_required_message`
- `"Background tracking benefits:"` → `onboarding_background_benefits_title`
- `"Battery Usage"` → `onboarding_battery_usage_title`
- `"Background tracking uses additional battery, but the app is optimized to minimize impact."` → `onboarding_battery_usage_message`
- `"Privacy Protected"` → `onboarding_privacy_protected_title`

**EnhancedFeaturesScreen.kt**
- `"Enhanced Features"` → `onboarding_enhanced_features_title`
- `"Optional features to improve tracking accuracy and user experience."` → `onboarding_enhanced_features_subtitle`
- `"Wi‑Fi permission needed"` → `onboarding_wifi_permission_title`
- `"Grant Wi‑Fi permission to improve indoor accuracy."` → `onboarding_wifi_permission_message`
- `"Grant Wi‑Fi permission"` → `onboarding_wifi_grant_button`
- `"Back"` → `onboarding_back_button`
- `"Continue"` → `onboarding_continue_button`
- `"Skip enhanced features"` → `onboarding_skip_enhanced_features`
- `"Grant Permission"` → `onboarding_grant_permission_button`
- `"All these features are optional and can be enabled or disabled anytime in settings. Your privacy remains protected."` → `onboarding_enhanced_features_privacy_note`

**BasicPreferencesScreen.kt**
- `"Preferences"` → `onboarding_preferences_title`
- `"Adjust defaults. You can always change these later in Settings."` → `onboarding_preferences_subtitle`
- `"Additional Settings"` → `onboarding_additional_settings_title`
- `"Auto-start tracking"` → `onboarding_auto_start_title`
- `"Start tracking automatically when you move"` → `onboarding_auto_start_summary`
- `"Smart pause"` → `onboarding_smart_pause_title`
- `"Pause tracking when stationary for extended periods"` → `onboarding_smart_pause_summary`
- `"Back"` → `onboarding_back_button`
- `"Continue"` → `onboarding_continue_button`

**ActivitySetupScreen.kt**
- `"Automatically detects:"` → `onboarding_activity_detects_title`
- `"Why this helps:"` → `onboarding_activity_why_title`
- `"• Better insights into your daily movement patterns\n..."` → `onboarding_activity_benefits`
- `"Activity detection is ready!"` → `onboarding_activity_ready_title`
- `"The app will now automatically detect your movement type and provide better insights."` → `onboarding_activity_ready_message`
- `"Enable Activity Detection"` → `onboarding_activity_enable_button`
- `"Skip for now"` → `onboarding_skip_button`
- `"Continue Setup"` → `onboarding_continue_setup_button`
- `"Back"` → `onboarding_back_button`
- `"Continue"` → `onboarding_continue_button`

**PlaceholderScreens.kt**
- `"Start Tracking!"` → `onboarding_start_tracking_button`
- `"Back"` → `onboarding_back_button`
- `"Continue"` → `onboarding_continue_button`
- `"Skip for now"` → `onboarding_skip_button`

**BackgroundLocationScreen.kt**
- `"Complete Setup"` → `onboarding_complete_setup_button`

**ValueDemoScreen.kt**
- `"Back"` → `onboarding_back_button`
- `"Continue"` → `onboarding_continue_button`

**AutoTrackingSetupScreen.kt**
- `"%d m"` format → needs placeholder `onboarding_distance_meters`

**OnboardingScreen.kt** (progress indicators)
- `"Step %d of %d"` → `onboarding_progress_step`
- `"%d%%"` → (can use string formatting)

#### App Module - Debug Screens

**DebugRoute.kt**
- `"System Status"` → `debug_system_status_title`
- `"true"` / `"false"` → can stay (boolean display)
- `"Log Viewer"` → `debug_log_viewer_title`
- `"No logs available"` → `debug_no_logs`
- `"Showing last %d logs:"` → `debug_showing_logs_count`

**CrashViewerActivity.kt**
- `"Loading crashes..."` → `debug_loading_crashes`
- `"No crashes found!"` → `debug_no_crashes`
- `"Crash #%d"` → `debug_crash_number`
- `"File: %s"` → `debug_file_name`
- `"Modified: %s"` → `debug_file_modified`
- `"Failed to read file"` → `debug_failed_read_file`

**CrashManagerActivity.kt**
- `"Loading crash data..."` → `debug_loading_crash_data`
- `"Crash Statistics"` → `debug_crash_statistics_title`
- `"Total crashes: %d"` → `debug_total_crashes`
- `"These crashes are stored locally and can be exported for analysis."` → `debug_crash_storage_note`
- `"ℹ️ Information"` → `debug_information_title`
- `"• Crashes are automatically captured when the app unexpectedly terminates\n..."` → `debug_crash_info_message`
- `"Clear All Crashes"` → `debug_clear_crashes_title`
- `"Are you sure you want to clear all crash data? This action cannot be undone."` → `debug_clear_crashes_confirm`
- `"Clear"` → `debug_clear_button`
- `"Cancel"` → `debug_cancel_button`

**CrashExportActivity.kt**
- `"OK"` → `ok_button`

#### Map Module

**MapSheet.kt**
- `"Search..."` → `map_search_placeholder`
- `"Date range"` → `map_date_range_button`
- `"OK"` → `ok_button`
- `"Cancel"` → `cancel_button`
- `"Map Controls"` → `map_controls_title`
- `"Filters"` → `map_filters_title`
- `"Map Layers"` → `map_layers_title`
- `"Legend"` → `map_legend_button`
- `"Generating %d tiles..."` → `map_generating_tiles` (use plural)

#### Game Module

**GameScreen.kt**
- `"%d / %d"` (progress format) → `game_progress_format`
- `"%d%%"` (percentage) → already covers via formatting

#### Tracker Module

**TrackerDashboard.kt**
- `"🔒"` → can stay (emoji badge, no i18n needed)
- `"--:--"` → `tracker_time_placeholder`

**NotificationManagementActivity.kt**
- Various contentDescription strings already present

#### Statistics Module

**StatisticSummaryDialog.kt**
- `"Loading..."` → `loading_text`

### Medium Priority (ContentDescription)

Most contentDescription strings are already acceptable (accessibility labels), but a few user-facing ones should be migrated:
- Map sheet controls
- Navigation icons (already have string resources in app module)

### Low Priority (Keep as-is)

- Animation labels (`label = "fab"`, `label = "rot"`, etc.) - internal identifiers
- Test assertion strings
- Spring stiffness labels
- Semantics test tags

## Migration Strategy

### Phase 1: Add String Resources
1. Add all new strings to appropriate module `strings.xml` files
2. Use descriptive keys following existing convention
3. Group by screen/feature

### Phase 2: Update Compose Code
1. Import `androidx.compose.ui.res.stringResource`
2. Replace hardcoded strings with `stringResource(R.string.key)`
3. Use `stringResource(R.string.key, arg1, arg2)` for formatted strings

### Phase 3: Validation
1. Build all modules
2. Visual regression check (onboarding flow, map, debug screens)
3. Test string formatting with various locales

## Example Transformations

### Before
```kotlin
Button(onClick = { /* ... */ }) {
    Text("Continue")
}
```

### After
```kotlin
Button(onClick = { /* ... */ }) {
    Text(stringResource(R.string.onboarding_continue_button))
}
```

### Before (with formatting)
```kotlin
Text("Step $currentStep of $totalSteps")
```

### After
```kotlin
Text(stringResource(R.string.onboarding_progress_step, currentStep, totalSteps))
```

## Completion Criteria

- [ ] All user-visible strings in resource files
- [ ] Zero hardcoded Text("...") for UI strings in production code
- [ ] Build successful across all modules
- [ ] No visual regressions in onboarding flow
- [ ] No visual regressions in map controls
- [ ] Debug screens render correctly

## Notes

- Some buttons like "Back", "Continue", "OK", "Cancel" are repeated across screens - can reuse common string keys
- Maintain existing string resource naming conventions (snake_case, descriptive)
- contentDescription for icons can be resource-based for consistency
- Formatted strings (plurals, positional args) require careful handling
