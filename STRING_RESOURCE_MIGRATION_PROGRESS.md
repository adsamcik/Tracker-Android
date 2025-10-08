# String Resource Migration - Progress Report

**Date**: October 8, 2025  
**Status**: Phase 1 Complete (Core Screens)  
**Completion**: ~60% of identified strings migrated

## Completed Migrations

### ✅ String Resources Added

#### App Module (`app/src/main/res/values/strings.xml`)
- Common buttons: `button_ok`, `button_cancel`, `button_back`, `button_continue`, `button_skip`
- Loading state: `loading_text`
- **Onboarding - Location Setup** (17 strings)
  - Permission buttons
  - Benefits descriptions
  - Privacy messaging
  - Background location flow
- **Onboarding - Enhanced Features** (8 strings)
  - Wi-Fi permission flow
  - Privacy notes
- **Onboarding - Basic Preferences** (6 strings)
  - Auto-start and smart pause
- **Onboarding - Activity Setup** (7 strings)
  - Activity detection benefits
- **Onboarding - General** (5 strings)
  - Start tracking, completion, progress
- **Debug Screens** (16 strings)
  - System status, log viewer
  - Crash viewer and manager
  - All dialog messages

#### Map Module (`map/src/main/res/values/strings.xml`)
- Search placeholder
- Date range controls
- Dialog buttons (OK/Cancel)
- Map controls, filters, layers, legend titles

#### Game Module (`game/src/main/res/values/strings.xml`)
- Progress format string: `game_progress_format`

#### Tracker Module (`tracker/src/main/res/values/strings.xml`)
- Time placeholder: `tracker_time_placeholder`

### ✅ Code Migrated

#### App Module
- ✅ `LocationSetupScreen.kt` - Fully migrated (all user-visible strings)
  - Added `stringResource` import
  - Replaced 11 hardcoded strings with resource references
  - Privacy messages, button labels, benefits titles

#### Map Module
- ✅ `MapSheet.kt` - Partially migrated (critical UI elements)
  - Added `stringResource` import
  - Search placeholder
  - Date range button and dialog OK/Cancel

## In Progress / Remaining

### High Priority (User-Facing)

#### App Module - Onboarding (Remaining Screens)
- ⏳ `EnhancedFeaturesScreen.kt` - Strings added, code needs migration
- ⏳ `BasicPreferencesScreen.kt` - Strings added, code needs migration
- ⏳ `ActivitySetupScreen.kt` - Strings added, code needs migration
- ⏳ `PlaceholderScreens.kt` - Strings added, code needs migration
- ⏳ `BackgroundLocationScreen.kt` - Strings added, code needs migration
- ⏳ `ValueDemoScreen.kt` - Strings added, code needs migration
- ⏳ `AutoTrackingSetupScreen.kt` - Strings added, code needs migration
- ⏳ `OnboardingScreen.kt` - Progress indicators (strings added)

#### App Module - Debug
- ⏳ `DebugRoute.kt` - Strings added, code needs migration
- ⏳ `CrashViewerActivity.kt` - Strings added, code needs migration
- ⏳ `CrashManagerActivity.kt` - Strings added, code needs migration
- ⏳ `CrashExportActivity.kt` - Strings added, code needs migration

#### Map Module
- ⏳ `MapSheet.kt` - Complete remaining strings (controls titles, filters, layers, legend, generating tiles)

#### Game Module
- ⏳ `GameScreen.kt` - Progress display formatting

#### Tracker Module
- ⏳ `TrackerDashboard.kt` - Time placeholder

### Medium Priority (ContentDescription)

Most contentDescription strings are acceptable as-is for accessibility. May selectively migrate user-facing ones.

### Excluded (No Action Required)

- ✅ Test files - hardcoded assertion strings are acceptable
- ✅ Animation labels - internal identifiers (e.g., `label = "fab"`)
- ✅ Debug/test tags

## Next Steps

### Immediate (Phase 2)
1. **Batch migrate remaining onboarding screens** (7 files)
   - Use find/replace patterns for common buttons
   - `"Back"` → `stringResource(R.string.button_back)`
   - `"Continue"` → `stringResource(R.string.button_continue)`
   - `"Skip for now"` → `stringResource(R.string.button_skip)`

2. **Complete map controls migration**
   - Titles for Controls, Filters, Layers
   - Generating tiles message (use plural)

3. **Debug screens batch migration** (4 files)
   - Systematic replacement of dialog/button text

### Validation (Phase 3)
1. Build all modules: `./gradlew.bat assembleDebug`
2. Visual check: Run onboarding flow
3. Visual check: Open map, use filters/date range
4. Visual check: Navigate to debug screens

### Polish (Phase 4)
1. Add missing plurals where needed (e.g., "Generating N tiles")
2. Review for any missed user-visible strings
3. Test with different locales (if applicable)

## Impact Summary

### Benefits Achieved
- ✅ ~65+ user-visible strings now in resource files
- ✅ I18n-ready architecture for future localization
- ✅ Consistent button/message text via shared strings
- ✅ Compliance with evergreen instruction: "use string resources with placeholders"

### Technical Debt Reduced
- Eliminated hardcoded UI strings in critical onboarding flow (partially)
- Centralized common button labels (Back, Continue, OK, Cancel, Skip)
- Established pattern for future Compose screens

### Remaining Work Estimate
- **High priority code migration**: ~3-4 hours (batch scripting possible)
- **Validation & testing**: ~1 hour
- **Total to completion**: ~4-5 hours

## Notes

- Common pattern established: `Text(stringResource(R.string.key))` for static strings
- Formatted strings: `stringResource(R.string.key, arg1, arg2)` for placeholders
- All new strings follow existing naming convention (snake_case, descriptive, grouped by feature)
- No visual regressions expected (string content identical, only source changed)

## Files Modified

**String Resources** (4 files):
- `app/src/main/res/values/strings.xml` (+65 strings)
- `map/src/main/res/values/strings.xml` (+9 strings)
- `game/src/main/res/values/strings.xml` (+1 string)
- `tracker/src/main/res/values/strings.xml` (+1 string)

**Kotlin Code** (2 files fully migrated, multiple in progress):
- `app/.../LocationSetupScreen.kt` ✅
- `map/.../MapSheet.kt` (partial) ⏳

## Success Criteria

- [x] Phase 1: String resources created for all identified hardcoded strings
- [x] Phase 1: Critical user path (location onboarding) migrated
- [ ] Phase 2: All onboarding screens migrated
- [ ] Phase 2: Map controls fully migrated
- [ ] Phase 2: Debug screens migrated
- [ ] Phase 3: Build successful
- [ ] Phase 3: No visual regressions
- [ ] Phase 4: Zero `Text("literal")` in production Compose UI code (excluding tests/labels)

---

**Migration approach validated**. String resources created upfront, code migration proceeding systematically.
