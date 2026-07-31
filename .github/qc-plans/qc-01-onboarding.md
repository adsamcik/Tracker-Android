# QC-01: Onboarding & First Launch

## Objective
Validate the complete new-user experience from cold start through first dashboard view.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **APK:** `app\build\outputs\apk\debug\app-debug.apk`
- **Device:** `emulator-5554`

## Pre-conditions
- Clear app data before starting: `adb shell pm clear com.adsamcik.tracker.debug`

## Test Steps

### Step 1: Fresh Launch
- Launch app (`com.adsamcik.tracker.debug`)
- Verify Setup route loads (NOT Dashboard)
- Take screenshot for evidence

### Step 2: Welcome Screen (WelcomeStep)
- Verify: Title and body text visible
- Verify: "GET STARTED" or primary CTA button visible
- Verify: NO back button in top bar
- Verify: NO progress indicator bar
- Take screenshot

### Step 3: Navigate to Step 2
- Tap "GET STARTED" / "CONTINUE" / primary CTA
- Verify: Transition to WhatToCollectStep (data collection toggles)
- Verify: Progress bar now appears (step 1 of 2)
- Verify: Back button (arrow) now visible in top bar
- Take screenshot

### Step 4: Back Navigation
- Tap back button → verify return to Welcome screen
- Tap forward again to return to Step 2
- Take screenshot to confirm

### Step 5: Navigate to Step 3
- Tap "CONTINUE" / next CTA
- Verify: Transition to HowToTrackStep
- Verify: Progress bar shows step 2 of 2
- Verify: Back button still available
- Take screenshot

### Step 6: Complete Onboarding
- Tap "START EXPLORING" / final CTA
- Verify: Dashboard loads
- Verify: Empty state shown (EmptyStateCard or GettingStartedCard visible)
- Take screenshot of empty dashboard

### Step 7: Persistence Check
- Kill the app (terminate + relaunch)
- Verify: App goes straight to Dashboard (onboarding NOT shown again)
- Take screenshot as proof

## Verification Checklist
- [ ] All 3 onboarding screens render without clipping/overflow
- [ ] Progress indicator shows correct step count (hidden on step 1, shown on steps 2-3)
- [ ] Back navigation works on steps 2-3 but NOT on step 1
- [ ] Completion persists across app restart
- [ ] No crashes (check Tracebox Diagnostics for a new crash record after each transition)
- [ ] Touch targets ≥ 48dp on all buttons
- [ ] Text is readable, no truncation at default and large font sizes
