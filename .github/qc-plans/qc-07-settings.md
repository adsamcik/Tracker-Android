# QC-07: Settings & Configuration

## Objective
Validate all settings screens, navigation hierarchy, toggles, and destructive actions.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- App installed, on any main tab

## Test Steps

### Step 1: Open Settings
- Tap Settings icon (gear/cog in top right corner)
- Verify SettingsRoute loads with root menu
- Take screenshot of root settings

### Step 2: Root Settings Structure
- Verify visible sections:
  - Tracking
  - Data
  - Map
  - Game
  - About section (at bottom: version, privacy policy, feedback, licenses)
- Take screenshot showing all root items (may need to scroll)

### Step 3: Tracking Settings
- Tap "Tracking" row
- Verify TrackingSettingsScreen loads
- Verify visible controls:
  - Tracking preset selector (Balanced / High Accuracy / Power Save)
  - Sensor toggles (location, activity, steps, WiFi, cell)
  - Accuracy/distance/interval settings
- Verify TopAppBar title shows "Tracking" (or equivalent)
- Take screenshot
- Tap back → verify return to root settings

### Step 4: Data Settings
- Tap "Data" row
- Verify DataSettingsScreen loads
- Verify visible sections:
  - Data management options (backup toggles, watermarks)
  - "Danger Zone" section clearly marked
  - Destructive actions: "Clear preferences", "Delete all data"
- **DO NOT tap destructive actions**
- Verify destructive buttons have warning styling (different color/emphasis)
- Take screenshot
- Tap back → verify return to root

### Step 5: Map Settings
- Tap "Map" row
- Verify MapSettingsScreen loads
- Verify visible controls:
  - Basemap selector (built-in vs custom .pmtiles)
  - Heatmap resolution
  - Visit threshold
- Take screenshot
- Tap back → verify return to root

### Step 6: Game Settings
- Tap "Game" row
- Verify GameSettingsScreen loads
- Verify visible controls:
  - Step goal settings (daily/weekly)
  - Notification toggles
- Take screenshot
- Tap back → verify return to root

### Step 7: Units Section
- In root settings, find units section
- Verify length system options available (Metric, Imperial, etc.)
- Verify speed format options (km/h, min/km, m/s)
- Take screenshot

### Step 8: About Section
- Scroll to bottom of root settings
- Verify visible:
  - App version number
  - "Privacy policy" link
  - "Send feedback" / GitHub link
  - "Open source licenses" link
- Take screenshot

### Step 9: Privacy Policy
- Tap "Privacy policy"
- Verify it opens (may open browser or in-app viewer)
- Take screenshot
- Navigate back

### Step 10: Open Source Licenses
- Tap "Open source licenses"
- Verify licenses screen loads with library list
- Take screenshot
- Navigate back

### Step 11: Toggle Interaction
- Go to Tracking settings
- Toggle one sensor switch (e.g., WiFi collection)
- Verify toggle visually changes state (on ↔ off)
- Toggle it back to original state
- Take screenshot of toggle states

### Step 12: Settings Back Navigation Stack
- Navigate: Root → Tracking → Back → Data → Back → Map → Back
- Verify each back press returns to root settings (not jumping to main app)
- Verify TopAppBar title updates correctly at each level
- Take screenshot at root after full navigation

### Step 13: Settings → App Navigation
- From root Settings, press Back
- Verify return to the tab that opened Settings (originating tab)
- Take screenshot

## Verification Checklist
- [ ] All settings sections navigable (Tracking, Data, Map, Game)
- [ ] Toggles change state visually on tap
- [ ] Tracking presets selectable (Balanced, High Accuracy, Power Save)
- [ ] Destructive actions in clearly marked "Danger Zone"
- [ ] Back navigation works at every level
- [ ] Screen titles update correctly per section
- [ ] About section shows version, privacy policy, licenses
- [ ] Open source licenses screen loads
- [ ] Units section shows length and speed format options
- [ ] No crashes on rapid navigation between settings
- [ ] Settings returns to correct originating tab on final back
