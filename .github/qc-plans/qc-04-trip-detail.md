# QC-04: Trip Detail Screen

## Objective
Validate trip detail metrics, charts, empty value handling, and actions menu.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- At least 1 tracking session with GPS data exists
- Navigate: Statistics tab → tap a session row

## Test Steps

### Step 1: Open Trip Detail
- From Statistics tab, tap a session row
- Verify TripDetailRoute loads
- Take screenshot of top section

### Step 2: Header Section
- Verify visible: trip date, time, activity type icon
- Verify visible: duration and distance in header
- Take screenshot

### Step 3: Metric Cards — Scroll Through All
Scroll through and verify each metric card renders:

**Distance metrics:**
- Total distance
- Distance on foot (if applicable)
- Distance in vehicle (if applicable)

**Time metrics:**
- Duration

**Step metrics:**
- Step count, OR "Step sensor unavailable" message

**Elevation metrics:**
- Min/max elevation
- Elevation ascended / descended
- If no altitude data: verify shows "—" with softer styling

**Speed metrics:**
- Max speed
- Average speed
- If speed is zero: verify shows "—" (NOT "0 km/h")

**Collection metrics:**
- Location count
- WiFi count
- Cell count

Take screenshots while scrolling through metrics.

### Step 4: Empty Value Styling
- For any metric displaying "—" or "N/A":
  - Verify: Uses lighter font weight (Normal, not Bold)
  - Verify: Uses `onSurfaceVariant` color (muted, not primary/onSurface)
  - Compare visually with metrics that have real values
- Take comparison screenshot

### Step 5: Zero Speed Guard
- Find speed metrics (Max speed, Avg speed)
- If the session had zero movement, verify they display "—"
- They must NOT display "0 km/h" or "0.0 km/h"
- Take screenshot

### Step 6: Elevation Chart
- Scroll to ElevationProfileChart
- If altitude data exists: verify chart renders with profile line
- If no altitude data: verify graceful empty state (no crash, appropriate message)
- Take screenshot

### Step 7: Speed Sparkline
- Look for SpeedSparklineChart
- If speed data exists: verify sparkline renders
- Take screenshot

### Step 8: Dropdown Menu
- Tap MoreVert (⋮) icon in top bar
- Verify dropdown menu appears with options:
  - "Edit session" (or similar)
  - "Change activity"
  - "Delete session"
- Take screenshot of menu
- Dismiss menu (tap outside)

### Step 9: View on Map
- Tap the route/map icon button
- Verify navigation to map view showing the trip path
- Take screenshot
- Navigate back to trip detail

### Step 10: Delete Confirmation
- Tap ⋮ → "Delete session"
- Verify: Confirmation dialog appears (not immediate deletion)
- Tap "Cancel" to abort
- Verify: Session still exists, trip detail still shown
- Take screenshot of confirmation dialog

### Step 11: Full Scroll Test
- Scroll from top to bottom of trip detail
- Verify smooth scrolling, no jank, no blank sections
- Take screenshot at bottom

## Verification Checklist
- [ ] All metric cards render without text overflow or clipping
- [ ] Empty metrics ("—", "N/A") use softer visual styling (lighter weight + muted color)
- [ ] Zero speed displays "—" not "0 km/h"
- [ ] Elevation chart renders or shows graceful empty state
- [ ] Speed sparkline renders when data available
- [ ] Dropdown menu shows all 3 options
- [ ] View on map navigates correctly
- [ ] Delete has confirmation dialog (not immediate)
- [ ] Screen scrolls smoothly through all content
- [ ] No crashes on any interaction
