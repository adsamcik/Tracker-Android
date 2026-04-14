# QC-08: Navigation, Accessibility & Edge Cases

## Objective
Validate tab navigation, back stack, adaptive layout, accessibility standards, and edge cases.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- App installed, onboarding complete

## Test Steps

### Part A: Tab Navigation

#### Step 1: Sequential Tab Switching
- Tap each bottom nav tab in sequence: Home → Statistics → Map → Game
- At each tab, verify:
  - Correct screen loads
  - Selected tab is visually highlighted
  - Other tabs are not highlighted
- Take screenshot at each tab

#### Step 2: Tab State Preservation
- Navigate to Dashboard, scroll down to see some cards
- Tap Statistics tab
- Tap Dashboard tab again
- Verify: Dashboard scroll position and state preserved (not reset to top)
- Take screenshot

#### Step 3: Rapid Tab Switching
- Rapidly tap between tabs 10 times (Dashboard → Stats → Map → Game → Dashboard → ...)
- Verify: No crashes, no ANR, no visual glitches
- Check crash logs after rapid switching
- Take final screenshot

#### Step 4: Double-Tap Tab
- Double-tap the current tab
- Verify: Does NOT duplicate screens or crash
- Verify: May scroll to top (acceptable behavior) or no-op
- Take screenshot

### Part B: Back Stack

#### Step 5: Deep Navigation — Statistics
- Dashboard → Statistics → tap a trip → Trip Detail
- Press Back → verify returns to Statistics list (NOT Dashboard)
- Press Back → verify returns to Dashboard
- Take screenshot after each back press

#### Step 6: Deep Navigation — Game
- Game tab → Trophy Case (if tappable)
- Press Back → verify returns to Game screen
- Take screenshot

#### Step 7: Deep Navigation — Settings
- Any tab → tap Settings icon → Tracking Settings
- Press Back → verify returns to Settings root
- Press Back → verify returns to originating tab
- Take screenshot

#### Step 8: Back from Root Tab
- At a root tab (Dashboard), press Back
- Verify: App minimizes (goes to home) or shows exit confirmation
- Verify: Does NOT crash
- Take screenshot (if app stays visible)

### Part C: Accessibility

#### Step 9: Touch Targets
- Take filtered screenshot with "touch-targets" filter on Dashboard
- Verify all interactive elements have ≥ 48dp touch target
- Flag any elements with red corner marks (below WCAG minimum)
- Take screenshot

#### Step 10: Touch Targets — Other Screens
- Take touch-target filtered screenshots on:
  - Statistics screen
  - Game screen
  - Settings root
- Flag any undersized targets
- Take screenshots

#### Step 11: Bottom Nav Semantics
- Use `list_elements` on bottom nav area
- Verify each nav item has:
  - Clickable = true
  - Appropriate text/label
  - Selected state for active tab
- Record element details

#### Step 12: Content Descriptions
- On Dashboard, use `list_elements` to check that:
  - Icons have content descriptions
  - Buttons have labels or content descriptions
  - Images have alt text
- Flag any elements missing accessibility labels

### Part D: Edge Cases

#### Step 13: Landscape Rotation
- Set orientation to landscape
- Verify layout adapts:
  - On tablets: side navigation rail replaces bottom bar
  - On phones: content adjusts to landscape width
- Verify no crashes, no content clipping
- Take screenshot in landscape
- Set orientation back to portrait

#### Step 14: Settings Origin Tracking
- From Statistics tab, open Settings
- Navigate to Data settings
- Press Back to Settings root
- Press Back → verify returns to STATISTICS tab (not Dashboard)
- Take screenshot

#### Step 15: Settings from Different Tabs
- From Map tab, open Settings
- Press Back → verify returns to MAP tab
- From Game tab, open Settings
- Press Back → verify returns to GAME tab
- Take screenshot each time

## Verification Checklist
- [ ] All 4 tabs navigate correctly and show correct content
- [ ] Tab state preserved when switching between tabs
- [ ] Back stack behaves correctly at every navigation depth
- [ ] Rapid tab switching causes no crashes or ANR
- [ ] Double-tap on tab doesn't create duplicate screens
- [ ] Touch targets ≥ 48dp on all interactive elements (all screens)
- [ ] Bottom nav items have proper selected/clickable semantics
- [ ] Icons and buttons have content descriptions
- [ ] Landscape rotation doesn't crash
- [ ] Layout adapts appropriately in landscape
- [ ] Settings returns to correct originating tab (Statistics, Map, Game, Dashboard)
- [ ] Back from root tab doesn't crash
