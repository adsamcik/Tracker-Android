# QC-05: Map Screen

## Objective
Validate map rendering, layer switching, bottom sheet controls, and location features.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- App has at least 1 session with GPS data
- Location permission granted

## Test Steps

### Step 1: Navigate to Map
- Tap "Map" bottom nav tab (test tag: "nav_map")
- Verify MapScreen loads
- Wait for map tiles to render (may take 2-5 seconds)
- Take screenshot

### Step 2: Map Tile Rendering
- Verify map tiles load (NOT blank white, black, or gray screen)
- Verify some geographic features visible (roads, water, land)
- If tiles fail to load, check logcat for network/tile errors
- Take screenshot

### Step 3: Current Location Button
- Set mock GPS location: lat=48.8566, lon=2.3522 (Paris)
- Tap the current location FAB (LocationOn icon)
- Verify map centers/zooms to the mock GPS location
- Take screenshot showing centered location

### Step 4: Bottom Sheet — Expand
- Find the drag handle at bottom of screen
- Swipe up on drag handle
- Verify bottom sheet expands revealing layer controls
- Take screenshot of expanded bottom sheet

### Step 5: Layer Switching — Location Heatmap
- Tap "Location heatmap" layer option
- Verify heatmap overlay renders on map (colored density dots over tracked areas)
- If no data in view, pan to tracked area
- Take screenshot

### Step 6: Layer Switching — Speed Heatmap
- Tap "Speed heatmap" layer option
- Verify speed-coded overlay renders (if data available)
- Verify speed legend appears with labels:
  - "Very Slow", "Walking Pace", "Running Pace", "Fast Movement"
- Take screenshot showing legend

### Step 7: Layer Switching — Location Polyline
- Tap "Location polyline" layer option
- Verify connected route line renders on map
- Take screenshot

### Step 8: Layer Switching — No Overlay
- Tap "No overlay" option
- Verify clean basemap with no data overlay
- Take screenshot

### Step 9: Layer Switching — WiFi/Cell (Optional)
- Tap "Wi-Fi heatmap" → verify it loads (may be empty if no WiFi data)
- Tap "Cell heatmap" → verify it loads (may be empty if no cell data)
- Verify: empty layers don't crash, show clean map
- Take screenshot

### Step 10: Date Range Selector
- Open date range control (in bottom sheet or toolbar)
- Verify preset options: "All", "Last week", "Last month"
- Select "All"
- Verify map data updates to show all sessions
- Take screenshot

### Step 11: Collapse Bottom Sheet
- Swipe down on bottom sheet
- Verify sheet collapses to its peek state
- Verify map remains interactive (can pan/zoom)
- Take screenshot

### Step 12: Map Interaction
- Pan the map by swiping
- Pinch to zoom in/out (if supported)
- Verify map responds fluidly, no jank
- Take screenshot after interaction

### Step 13: Search (if available)
- Tap search button/icon
- Verify search input appears
- Type a location name (e.g., "Paris")
- Verify results appear (network-dependent)
- Take screenshot

## Verification Checklist
- [ ] Map tiles render successfully (not blank)
- [ ] All 6 layer options work without crash
- [ ] Location heatmap shows density visualization
- [ ] Speed heatmap shows color-coded overlay + legend
- [ ] Location polyline draws connected route
- [ ] "No overlay" shows clean basemap
- [ ] Empty layers (WiFi/Cell) don't crash
- [ ] Current location button centers map
- [ ] Bottom sheet expands/collapses smoothly
- [ ] Date range filtering works
- [ ] Map is interactive (pan, zoom)
- [ ] No ANR when switching layers
- [ ] No crashes throughout
