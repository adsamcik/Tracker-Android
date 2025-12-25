# Streamlined Onboarding - Next Steps Quick Reference

## Status: 85% Complete ✅

**What's Done:**
- ✅ Single-screen onboarding UI created
- ✅ Contextual permission request components built
- ✅ Smart defaults system implemented
- ✅ Strings added for new flow
- ✅ OnboardingStep simplified
- ✅ OnboardingActivity integrated

**What's Pending:**
- ⚠️ Permission triggers in TrackerRoute (main tracking screen)
- ⚠️ Delete legacy onboarding screen files
- ⚠️ End-to-end testing and validation

---

## TASK 1: Add Contextual Permission Request to TrackerRoute

**Goal:** Request location permission when user taps "Start Tracking" (not during onboarding)

### File to Modify:
`tracker/src/main/java/com/adsamcik/tracker/tracker/ui/TrackerRoute.kt`

### Implementation Steps:

#### 1. Add permission state at top of TrackerRoute composable:

```kotlin
@Composable
fun TrackerRoute(...) {
    // Existing state...
    
    // NEW: Permission request state
    var showLocationPermissionRequest by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionDenied by remember { mutableStateOf(false) }
    
    // Check if location permission is granted
    val context = LocalContext.current
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // ... rest of composable
}
```

#### 2. Add permission request dialog:

```kotlin
// Inside TrackerRoute, before the main Scaffold
if (showLocationPermissionRequest) {
    ContextualPermissionRequest(
        permissionType = PermissionType.LOCATION_FOREGROUND,
        onPermissionResult = { granted ->
            if (granted) {
                // Start tracking
                viewModel.startTracking() // or however tracking is initiated
            } else {
                permissionDenied = true
            }
        },
        onDismiss = { 
            showLocationPermissionRequest = false 
        }
    )
}

// Show snackbar if permission denied
if (permissionDenied) {
    PermissionDeniedSnackbar(
        snackbarHostState = snackbarHostState,
        message = stringResource(R.string.permission_denied_tracking_disabled)
    )
    LaunchedEffect(Unit) {
        permissionDenied = false
    }
}
```

#### 3. Update "Start Tracking" button onClick:

```kotlin
Button(
    onClick = {
        if (!hasLocationPermission) {
            // Show permission request dialog
            showLocationPermissionRequest = true
        } else {
            // Permission already granted, start tracking
            viewModel.startTracking()
        }
    },
    enabled = hasLocationPermission || !isTracking // Disable if no permission
) {
    Text(
        text = if (hasLocationPermission) {
            stringResource(R.string.start_tracking)
        } else {
            stringResource(R.string.permission_required_enable_location)
        }
    )
}
```

#### 4. Add new string resource:

In `app/src/main/res/values/strings.xml`:
```xml
<string name="permission_required_enable_location">Enable Location to Track</string>
```

#### 5. Add import at top of TrackerRoute.kt:

```kotlin
import com.adsamcik.tracker.app.onboarding.permission.ContextualPermissionRequest
import com.adsamcik.tracker.app.onboarding.permission.PermissionType
import com.adsamcik.tracker.app.onboarding.permission.PermissionDeniedSnackbar
```

### Testing:
1. Clear app data (fresh install simulation)
2. Complete streamlined onboarding (tap "Get Started")
3. Land in main app tracker screen
4. Tap "Start Tracking"
5. ✅ Verify permission dialog appears with rationale
6. Tap "Allow" → tracking starts
7. Deny permission → snackbar appears with "Open Settings" action

---

## TASK 2: Delete Legacy Onboarding Screen Files

**Goal:** Remove deprecated multi-step onboarding UI files

### Files to Delete (9 total):

```bash
# Execute from project root:
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/WhatToTrackScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/AutoTrackingSetupScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/LocationSetupScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/ActivitySetupScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/EnhancedFeaturesScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/BackgroundLocationScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/ValueDemoScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/PrivacyScreen.kt
rm app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/screens/PlaceholderScreens.kt
```

### Before Deleting:
1. ✅ Verify `OnboardingActivity.kt` no longer imports these screens
2. ✅ Verify `OnboardingScreen.kt` is not used (replaced by `StreamlinedOnboardingScreen.kt`)
3. ✅ Run build to check for compilation errors

### After Deleting:
1. Run: `./gradlew.bat :app:assembleDebug --no-daemon --console=plain`
2. Fix any missing import errors (should be none if previous step passed)
3. Commit with message: "Remove deprecated multi-step onboarding screens"

---

## TASK 3: End-to-End Testing Checklist

### Test Scenario 1: New User Fresh Install
**Setup:** Uninstall app or clear app data  
**Steps:**
1. Launch app
2. ✅ See streamlined onboarding (single screen, 3 benefits)
3. Tap "Get Started"
4. ✅ Land in main tracker screen (<30 seconds from app launch)
5. Tap "Start Tracking"
6. ✅ Permission dialog appears with clear rationale
7. Grant location permission
8. ✅ Tracking starts successfully

**Verify:**
- [ ] Time from app open to main screen: <30 seconds
- [ ] No configuration screens shown
- [ ] Smart defaults applied (check Settings):
  - [ ] Auto-tracking: ON
  - [ ] Location: Enabled
  - [ ] WiFi/Cell: Disabled
  - [ ] Notifications: Enabled

### Test Scenario 2: Existing User Upgrade
**Setup:** Install over existing app with onboarding already completed  
**Steps:**
1. Launch app
2. ✅ Skip onboarding entirely (go straight to main app)

**Verify:**
- [ ] No onboarding shown
- [ ] Existing preferences unchanged
- [ ] Tracking works normally

### Test Scenario 3: Permission Denial Handling
**Setup:** Fresh install  
**Steps:**
1. Complete onboarding
2. Tap "Start Tracking"
3. Deny location permission
4. ✅ Snackbar appears: "Location permission required to start tracking"
5. Tap "Open Settings" in snackbar
6. ✅ Navigates to app settings

**Verify:**
- [ ] No crash on permission denial
- [ ] Tracking button disabled or shows "Enable Location" text
- [ ] Settings action works correctly

### Test Scenario 4: Settings Accessibility
**Setup:** Complete streamlined onboarding  
**Steps:**
1. Navigate to Settings
2. ✅ All tracking options visible (WiFi, cell, intervals, etc.)
3. Change tracking mode to "High Precision"
4. ✅ Preferences persist and apply

**Verify:**
- [ ] Settings screen fully functional
- [ ] Smart defaults match expected values
- [ ] Can customize all options post-onboarding

### Test Scenario 5: Analytics Continuity
**Setup:** Analytics-enabled build  
**Steps:**
1. Complete onboarding
2. ✅ Check logs for onboarding completion event

**Verify:**
- [ ] Analytics event fires: `onboarding_completed`
- [ ] Step names preserved for continuity

---

## Common Issues & Solutions

### Issue: "Cannot resolve symbol ContextualPermissionRequest"
**Solution:** Add import in TrackerRoute.kt:
```kotlin
import com.adsamcik.tracker.app.onboarding.permission.ContextualPermissionRequest
```

### Issue: "accompanist-permissions not found"
**Solution:** Add to `libs.versions.toml`:
```toml
[versions]
accompanist = "0.34.0"

[libraries]
accompanist-permissions = { module = "com.google.accompanist:accompanist-permissions", version.ref = "accompanist" }
```
Then in `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.accompanist.permissions)
}
```

### Issue: Permission dialog not showing
**Solution:** Ensure `showLocationPermissionRequest` state is hoisted correctly and dialog is placed before Scaffold in TrackerRoute composable tree.

### Issue: Snackbar not appearing on permission denial
**Solution:** Add `SnackbarHost` to Scaffold in TrackerRoute:
```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    ...
) { ... }
```

---

## Final Checklist Before PR

- [ ] Task 1: Permission triggers integrated in TrackerRoute
- [ ] Task 2: Legacy onboarding screen files deleted
- [ ] Task 3: All test scenarios passed
- [ ] Build succeeds: `./gradlew.bat :app:assembleDebug`
- [ ] No lint errors (or justified suppressions)
- [ ] Documentation updated (README, CHANGELOG)
- [ ] Screenshots captured for PR (before/after onboarding flow)

---

## Files Modified Summary

**Created:**
- `StreamlinedOnboardingScreen.kt`
- `ContextualPermissionRequest.kt`

**Modified:**
- `OnboardingStep.kt`
- `OnboardingViewModel.kt`
- `OnboardingActivity.kt`
- `strings.xml`
- `TrackerRoute.kt` (pending)

**Deleted:**
- 9 legacy onboarding screen files (pending)

**Estimated Time Remaining:** 2-3 hours (permission integration + testing + cleanup)

---

**Last Updated:** 2025-10-11  
**Next Assignee:** [Your Name Here]  
**Reference:** STREAMLINED_ONBOARDING_IMPLEMENTATION_SUMMARY.md
