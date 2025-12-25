# Coarse Location Support Migration - Status Analysis

**Date:** October 24, 2025  
**Objective:** Evaluate completeness of migration to support working without precise location permission  
**Result:** ❌ **INCOMPLETE** - Critical gaps identified across multiple layers

---

## Executive Summary

The application is **NOT ready** to operate gracefully when users grant only coarse (approximate) location permission instead of fine (precise) location. While both permissions are declared in the manifest and requested during onboarding, the runtime checks, tracking components, and UI messaging all assume precise location is available.

**Critical Impact:** Users who grant only approximate location (Android 12+ system prompt) will experience:
- Tracking appears disabled (fails permission checks)
- No explanation of reduced functionality
- No option to upgrade to precise location later
- Inconsistent behavior between WiFi module (works) and location tracking (fails)

---

## Compliance with Copilot Instructions

The `.github/copilot-instructions.md` explicitly mandates:

> **Section 8: Privacy & Security**
> - When precise location is revoked, downgrade to coarse gracefully and communicate reduced accuracy subtly.
> - Apply least-precision-first principle: request precise location only at the moment needed for high-accuracy tasks (e.g., detailed route capture) else operate in coarse mode.

**Current Status:** ❌ Not implemented

---

## Detailed Gap Analysis

### 1. ❌ **Permission Check Extensions (Critical)**

**File:** `sbase/src/main/java/com/adsamcik/tracker/shared/base/extension/ContextExtensions.kt:167`

**Current Implementation:**
```kotlin
inline val Context.hasLocationPermission: Boolean
    get() = hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
```

**Problem:**  
- Only checks for fine location
- Returns `false` when user grants coarse but denies fine
- Used throughout the codebase (20+ call sites)

**Required Fix:**
```kotlin
inline val Context.hasLocationPermission: Boolean
    get() = hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

inline val Context.hasPreciseLocationPermission: Boolean
    get() = hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)

inline val Context.hasCoarseLocationPermission: Boolean
    get() = hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
```

**Impact:** HIGH - Affects all location-based features  
**Effort:** LOW - Single file, well-tested extension property

---

### 2. ❌ **Collection Trigger Components (Critical)**

**Files:**
- `tracker/src/main/java/com/adsamcik/tracker/tracker/component/trigger/FusedLocationCollectionTrigger.kt:25`
- `tracker/src/main/java/com/adsamcik/tracker/tracker/component/trigger/AndroidLocationCollectionTrigger.kt:25`

**Current Implementation:**
```kotlin
override val requiredPermissions: Collection<String>
    get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
```

**Problem:**  
- Declares only fine location as required
- Tracker service checks `hasSelfPermissions(timerComponent.requiredPermissions).all { it }` (TrackerService.kt:351)
- Tracking won't start if user grants only coarse location

**Required Fix:**
```kotlin
override val requiredPermissions: Collection<String>
    get() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

// AND update permission validation logic to accept partial grants:
override fun hasRequiredPermissions(context: Context): Boolean {
    // Accept if EITHER fine OR coarse is granted
    return context.hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
           context.hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
}
```

**Impact:** HIGH - Prevents tracking with coarse-only permission  
**Effort:** MEDIUM - Requires updating permission validation logic in `CollectionTriggerComponent.kt`

---

### 3. ❌ **Location Request Priority (Medium)**

**File:** `tracker/src/main/java/com/adsamcik/tracker/tracker/component/trigger/FusedLocationCollectionTrigger.kt:68`

**Current Implementation:**
```kotlin
val request = LocationRequest.Builder(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)  // Always high accuracy
    .setMinUpdateDistanceMeters(minDistanceInMeters.toFloat())
    .setMinUpdateIntervalMillis(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
    .build()
```

**Problem:**  
- Hardcoded `PRIORITY_HIGH_ACCURACY` which requires GPS/precise location
- No adaptive behavior based on granted permissions
- Wastes battery if user grants coarse location (network-based) but system still tries GPS

**Required Fix:**
```kotlin
// Determine priority based on granted permissions
val priority = when {
    context.hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> 
        Priority.PRIORITY_HIGH_ACCURACY
    context.hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> 
        Priority.PRIORITY_BALANCED_POWER_ACCURACY  // Network-based
    else -> throw IllegalStateException("No location permission granted")
}

val request = LocationRequest.Builder(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
    .setPriority(priority)
    .setMinUpdateDistanceMeters(minDistanceInMeters.toFloat())
    .setMinUpdateIntervalMillis(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
    .build()
```

**Impact:** MEDIUM - Battery waste, suboptimal behavior  
**Effort:** LOW - Single location in trigger setup

---

### 4. ❌ **User-Facing Precision Mode Selection (High)**

**Expected (per copilot-instructions.md & CLEAN_SLATE_ONBOARDING_DESIGN.md):**
- Android 12+ users should see "Approximate vs Precise" choice during onboarding
- Settings should show current precision mode
- Inline upgrade prompt when user attempts high-precision task with coarse permission
- Subtle banner communicating reduced accuracy when coarse-only

**Current State:**  
- ❌ No precision mode selector in onboarding
- ❌ No indication of current precision level in UI
- ❌ No upgrade prompts
- ❌ No accuracy degradation messaging

**Files That Don't Exist Yet:**
- `app/src/main/java/com/adsamcik/tracker/app/permission/LocationPrecisionManager.kt` (needed)
- Precision mode UI in onboarding screens
- Precision upgrade dialog component
- Accuracy indicator in tracker dashboard

**Impact:** HIGH - Poor user experience, no compliance with Android 12+ UX patterns  
**Effort:** HIGH - Requires new UI flows, state management, permission request orchestration

---

### 5. ✅ **WiFi Data Producer (Compliant!)**

**File:** `tracker/src/main/java/com/adsamcik/tracker/tracker/component/producer/WifiDataProducer.kt:138`

**Implementation:**
```kotlin
private fun hasWifiScanPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        // Prior to API 33, Wi‑Fi scans/results are gated by location permission
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        fine || coarse  // ✅ Accepts either!
    }
}
```

**Status:** ✅ **CORRECT** - This is the ONLY component that properly handles coarse fallback  
**Impact:** This proves the pattern is understood and feasible  
**Action:** Replicate this pattern in all location checks

---

### 6. ⚠️ **Map Component (Partial)**

**File:** `map/src/main/java/com/adsamcik/tracker/map/presentation/sensors/LocationAndSensorsManager.kt:29`

**Implementation:**
```kotlin
fun locationUpdates(highAccuracy: Boolean = true): Flow<Triple<Double, Double, Double>> = callbackFlow {
    if (!context.hasLocationPermission) {
        // Don't crash the flow, just complete it gracefully
        close()
        return@callbackFlow
    }
    
    val priority = if (highAccuracy) 
        Priority.PRIORITY_HIGH_ACCURACY 
    else 
        Priority.PRIORITY_BALANCED_POWER_ACCURACY
    // ...
}
```

**Status:** ⚠️ **PARTIAL**
- ✅ Graceful degradation (closes flow instead of crashing)
- ✅ Priority parameterization exists
- ❌ Still uses `hasLocationPermission` (fine-only check)
- ❌ No automatic downgrade to balanced priority when only coarse granted

**Required Fix:** Use updated permission check + auto-adjust priority

---

### 7. ⚠️ **Onboarding Permission Manager (Inconsistent)**

**File:** `app/src/main/java/com/adsamcik/tracker/app/onboarding/permission/OnboardingPermissionManager.kt:134`

**Implementation:**
```kotlin
Permission.LOCATION_FOREGROUND -> {
    listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
}

// Later in isPermissionGranted:
override fun isPermissionGranted(permission: Permission): Boolean {
    val manifestPermissions = getManifestPermissions(permission)
    return manifestPermissions.all { manifestPermission ->  // ❌ Requires ALL
        ContextCompat.checkSelfPermission(activity, manifestPermission) == PackageManager.PERMISSION_GRANTED
    }
}
```

**Problem:**  
- Requests both fine + coarse (good!)
- But checks require `.all { }` instead of `.any { }` for location (bad!)
- Special case exists for NEARBY_WIFI_DEVICES (uses `.any { }`) but not for LOCATION_FOREGROUND

**Required Fix:**
```kotlin
override fun isPermissionGranted(permission: Permission): Boolean {
    val manifestPermissions = getManifestPermissions(permission)
    return if (permission == Permission.LOCATION_FOREGROUND ||
               permission == Permission.NEARBY_WIFI_DEVICES && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    ) {
        // Accept if either coarse or fine location is granted
        manifestPermissions.any { manifestPermission ->
            ContextCompat.checkSelfPermission(activity, manifestPermission) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        manifestPermissions.all { manifestPermission ->
            ContextCompat.checkSelfPermission(activity, manifestPermission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
```

**Impact:** MEDIUM - Onboarding may incorrectly show location as "not granted"  
**Effort:** LOW - Single function update

---

### 8. ❌ **Manifest (GPS Requirement Conflict)**

**File:** `app/src/main/AndroidManifest.xml:5`

**Current:**
```xml
<uses-feature
    android:name="android.hardware.location.gps"
    android:required="true" />
```

**Problem:**  
- Declares GPS as **required** hardware
- Conflicts with goal of supporting coarse (network-based) location
- Users with GPS-disabled devices (rare but possible) cannot install app
- Contradicts least-precision-first principle

**Required Fix:**
```xml
<uses-feature
    android:name="android.hardware.location.gps"
    android:required="false" />
```

**Impact:** LOW - Rare edge case, but philosophically incorrect  
**Effort:** TRIVIAL - Single manifest change

---

## Priority Breakdown

### Must Fix (Blocking Coarse Location Support)

| Priority | Component | File(s) | Effort | Impact |
|----------|-----------|---------|--------|--------|
| **P0** | Permission check extension | `ContextExtensions.kt` | LOW | HIGH |
| **P0** | Collection trigger permissions | `FusedLocationCollectionTrigger.kt`, `AndroidLocationCollectionTrigger.kt`, `CollectionTriggerComponent.kt` | MEDIUM | HIGH |
| **P0** | Onboarding permission validation | `OnboardingPermissionManager.kt` | LOW | MEDIUM |

### Should Fix (Optimal UX & Battery)

| Priority | Component | File(s) | Effort | Impact |
|----------|-----------|---------|--------|--------|
| **P1** | Adaptive location priority | `FusedLocationCollectionTrigger.kt` | LOW | MEDIUM |
| **P1** | Map location manager | `LocationAndSensorsManager.kt` | LOW | LOW |
| **P1** | GPS hardware requirement | `AndroidManifest.xml` | TRIVIAL | LOW |

### Nice to Have (Full UX Polish)

| Priority | Component | File(s) | Effort | Impact |
|----------|-----------|---------|--------|--------|
| **P2** | Precision mode selector UI | Onboarding screens + new components | HIGH | HIGH |
| **P2** | Accuracy degradation messaging | Tracker dashboard, settings | MEDIUM | MEDIUM |
| **P2** | Contextual upgrade prompts | Permission manager + dialogs | MEDIUM | MEDIUM |

---

## Recommended Implementation Phases

### Phase 1: Core Functionality (1-2 days)
**Goal:** Make tracking work with coarse-only permission

1. Update `hasLocationPermission` extension to accept coarse OR fine
2. Add `hasPreciseLocationPermission` and `hasCoarseLocationPermission` helpers
3. Fix collection trigger `requiredPermissions` to list both, update validation to accept partial
4. Fix onboarding permission validation (`.any` instead of `.all` for location)
5. Add unit tests for all permission check paths
6. Test on Android 12+ device with coarse-only grant

**Acceptance Criteria:**
- User grants coarse location → tracking starts successfully
- Location updates received (network-based, ~100-500m accuracy)
- No crashes or permission errors
- Existing fine-location users unaffected

---

### Phase 2: Adaptive Behavior (1 day)
**Goal:** Optimize battery and respect granted precision

1. Update `FusedLocationCollectionTrigger` to use adaptive priority
2. Update `LocationAndSensorsManager` to auto-adjust based on permissions
3. Add logging to track which precision mode is active (debug builds only)
4. Remove GPS hardware requirement from manifest

**Acceptance Criteria:**
- Coarse permission → uses `PRIORITY_BALANCED_POWER_ACCURACY`
- Fine permission → uses `PRIORITY_HIGH_ACCURACY`
- Battery tests show reduced drain with coarse-only
- No GPS attempts when only coarse granted

---

### Phase 3: User Experience (3-4 days)
**Goal:** Transparent precision management + upgrade paths

**Components to Build:**

1. **LocationPrecisionManager** (new service)
   - Detect current precision mode
   - Provide upgrade request flow
   - Track user preference (precise by default, coarse accepted)

2. **Precision Mode Selector** (onboarding)
   - Android 12+ only
   - "Approximate" vs "Precise" cards with clear benefits
   - Remember choice, allow change in settings

3. **Accuracy Indicator** (tracker dashboard)
   - Subtle chip: "Tracking (Approximate mode)" when coarse-only
   - Tap → explanation + upgrade option
   - Hide when precise granted

4. **Contextual Upgrade Prompts**
   - Export detailed route → "Precise location improves route accuracy. Upgrade?"
   - Heatmap → "Precise location shows finer detail. Upgrade?"
   - Settings: "Enable precise location" toggle with system prompt

5. **Strings & Copy**
   ```xml
   <string name="location_mode_precise">Precise location</string>
   <string name="location_mode_approximate">Approximate location</string>
   <string name="location_mode_indicator_approximate">Tracking (Approximate mode)</string>
   <string name="location_upgrade_rationale_export">Precise location improves route accuracy and detail. Upgrade to precise mode?</string>
   <string name="location_upgrade_rationale_heatmap">Precise location shows finer heatmap detail. Upgrade to precise mode?</string>
   ```

**Acceptance Criteria:**
- New users see precision choice on Android 12+
- Current mode visible in settings and tracker UI
- Upgrade prompts appear contextually (export, heatmap, etc.)
- User can downgrade back to coarse in settings
- No nagging or aggressive re-prompts
- Clear, jargon-free copy

---

## Test Plan

### Unit Tests
```kotlin
// ContextExtensionsTest.kt
@Test
fun `hasLocationPermission returns true when only coarse granted`() {
    // Mock context with coarse but not fine
    assertTrue(context.hasLocationPermission)
}

@Test
fun `hasPreciseLocationPermission returns false when only coarse granted`() {
    assertFalse(context.hasPreciseLocationPermission)
}

// FusedLocationCollectionTriggerTest.kt
@Test
fun `uses balanced priority when only coarse permission granted`() {
    // Verify LocationRequest uses PRIORITY_BALANCED_POWER_ACCURACY
}

@Test
fun `tracking starts successfully with coarse-only permission`() {
    // Verify onEnable succeeds and location updates received
}
```

### Integration Tests
```kotlin
// OnboardingPermissionFlowTest.kt
@Test
fun `granting coarse but denying fine shows location as granted`() {
    // Simulate Android 12+ precision dialog (coarse selected)
    // Verify onboarding considers location granted
}

// TrackerServiceTest.kt
@Test
fun `tracker service starts with coarse-only location`() {
    // Grant only coarse permission
    // Start tracking
    // Verify service running + location updates received
}
```

### Manual Testing Checklist
- [ ] Android 12+ device: Grant approximate location → tracking works
- [ ] Android 12+ device: Precision selector appears in onboarding
- [ ] Settings: Switch from precise to approximate → tracking continues with reduced accuracy
- [ ] Battery monitor: Coarse mode uses less power than precise mode
- [ ] Export: GPX file contains valid (but less precise) coordinates
- [ ] Map: Heatmap renders with coarse data (reduced detail, expected)
- [ ] Upgrade prompt: Tap "Precise location" in settings → system dialog appears
- [ ] Denial: User denies precise upgrade → app continues normally, no crash

---

## Risk Assessment

### Low Risk
- Extension property updates (well-isolated, easy to test)
- Manifest changes (declarative, no runtime impact)
- Adaptive priority (Google Play Services handles gracefully)

### Medium Risk
- Permission validation logic changes (affects core flow, needs thorough testing)
- Onboarding flow modifications (user-facing, requires UI testing)

### High Risk
- New precision mode UI (complex state management, user expectation alignment)
- Contextual upgrade prompts (timing, frequency, dismissal tracking)

**Mitigation:**
- Incremental rollout (phases 1-2 first, validate with beta testers)
- Feature flag for Phase 3 UX (enable after Phase 1-2 proven stable)
- A/B test precision selector (measure opt-in rates, battery impact)

---

## Alignment with Copilot Instructions

| Principle | Current Status | After Phase 1 | After Phase 3 |
|-----------|---------------|---------------|---------------|
| **Least-precision-first** | ❌ Always requests fine | ✅ Accepts coarse | ✅ Defaults to coarse, upgrades contextually |
| **Graceful degradation** | ❌ Fails silently | ✅ Works with reduced accuracy | ✅ + Clear messaging |
| **Privacy-first** | ⚠️ Requests fine upfront | ✅ Coarse by default | ✅ User controls precision |
| **Plain language** | N/A | N/A | ✅ "Approximate" vs "Precise" (no jargon) |
| **Progressive disclosure** | ❌ All-or-nothing | ✅ Functional baseline | ✅ Upgrade paths for advanced needs |

---

## Conclusion

**Migration Status:** ❌ **INCOMPLETE**

**Critical Blockers:** 3 components (permission checks, collection triggers, onboarding validation)

**Recommended Action:**
1. Implement **Phase 1** immediately (core functionality fix)
2. Deploy to beta testers, validate coarse-only tracking works
3. Plan **Phase 2** (adaptive behavior) for next sprint
4. Design **Phase 3** (UX polish) based on user feedback + Android 12+ adoption rates

**Timeline Estimate:**
- Phase 1: 1-2 days (critical fixes)
- Phase 2: 1 day (optimizations)
- Phase 3: 3-4 days (UX polish)
- **Total: ~1 week** for complete coarse location support

**Success Metrics:**
- % of users granting coarse vs fine location (Android 12+)
- Battery usage comparison (coarse vs fine mode)
- Tracking session success rate with coarse-only permission
- User upgrade-to-precise conversion rate (contextual prompts)

---

**Document Version:** 1.0  
**Last Updated:** October 24, 2025  
**Next Review:** After Phase 1 implementation
