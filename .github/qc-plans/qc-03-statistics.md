# QC-03: Statistics & Session List

## Objective
Validate statistics overview, session list, filtering, and session actions.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- At least 1 tracking session exists in the database
- If no session exists, run QC-02 first to create one

## Test Steps

### Step 1: Navigate to Statistics
- Tap "Statistics" bottom nav tab (text: "Statistics", test tag: "nav_stats")
- Verify StatsRoute loads
- Take screenshot

### Step 2: Summary Stats Section
- Verify summary stats cards visible at top:
  - Distance (total)
  - Time spent tracking
  - Step count
  - Session count
- Verify values are non-zero if sessions exist
- Take screenshot of summary section

### Step 3: Weekly Chart
- Verify "This week" / "Last 7 days" bar chart renders
- Verify at least 1 bar visible for days with tracking data
- Take screenshot

### Step 4: Session List
- Scroll down to session list
- Verify each TripRow shows:
  - Activity icon (left side)
  - Date and time
  - Duration
  - Trailing chevron arrow icon (KeyboardArrowRight)
  - Distance/steps metrics (when data exists)
- Verify: NO "Tap to open details" placeholder text appears
- Take screenshot of session list

### Step 5: Session Row Details
- Examine a session row closely:
  - If distance > 0: verify distance metric shown
  - If steps > 0: verify steps metric shown
  - If both are 0/null: verify metric row is hidden (not showing zeros)
- Take zoomed screenshot of a single row

### Step 6: Tap Session → Trip Detail
- Tap a TripRow
- Verify navigation to TripDetailRoute
- Verify trip detail screen loads with metrics
- Take screenshot of trip detail

### Step 7: Back Navigation
- Press Back button
- Verify return to StatsRoute
- Verify list scroll position is preserved (same position as before)
- Take screenshot

### Step 8: Date Filter
- Tap "Dates" action button (if visible in top bar)
- Verify date picker / date range dialog appears
- Select a date range
- Verify session list filters accordingly
- Take screenshot of filtered results

### Step 9: Summary Dialog
- Tap "Summary" action (if visible)
- Verify summary stats dialog/sheet appears
- Take screenshot
- Dismiss dialog

### Step 10: Empty State
- If possible, filter to a date range with no sessions
- Verify empty state: "No tracking sessions yet" + subtitle "Start tracking to see your activity here"
- Take screenshot of empty state

### Step 11: Calendar Heatmap
- If data spans multiple days, check for CalendarHeatmap component
- Verify heatmap cells colored for days with activity
- Take screenshot

## Verification Checklist
- [ ] Stats summary cards render with correct formatting and non-zero values
- [ ] Weekly bar chart renders correctly
- [ ] Chevron arrow (→) appears on every session row
- [ ] No "Tap to open details" text visible anywhere
- [ ] Session rows show distance/steps when available, hide when zero
- [ ] Tapping session navigates to detail view
- [ ] Back navigation preserves scroll position
- [ ] Date filtering works and list updates
- [ ] Empty state shown when no sessions match filter
- [ ] Calendar heatmap renders (if multi-day data)
- [ ] No crashes on rapid scroll or filter changes
