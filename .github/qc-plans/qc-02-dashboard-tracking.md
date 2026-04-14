# QC-02: Dashboard & Tracking Lifecycle

## Objective
Validate dashboard cards, tracking start/stop, live UI updates, and session persistence.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- App installed, onboarding complete
- Navigate to Dashboard tab (Home)

## Test Steps

### Step 1: Dashboard Empty/Idle State
- Verify greeting text visible and matches time of day:
  - Morning (5-11): "Good morning! Ready to explore?"
  - Afternoon (12-16): "Good afternoon! Keep moving?"
  - Evening (17-20): "Good evening! Review your day?"
  - Night (21-4): "Night owl mode 🦉"
- Take screenshot of greeting area

### Step 2: TodayProgressCard
- Verify progress ring / "Start tracking" button visible
- Verify card shows today's stats (or zero state if fresh)
- Take screenshot

### Step 3: Start Tracking
- Tap "Start tracking" ring button or TrackingPill
- If permission dialog appears: Grant location permission
- Verify tracking starts — look for:
  - TrackingPill changes to show "Stop"
  - Dashboard switches to tracking mode content
  - "Awaiting GPS signal…" or live distance appears
- Take screenshot of tracking state

### Step 4: GPS Simulation
- Use `set_location` to simulate movement (e.g., Paris coords):
  - Point 1: lat=48.8566, lon=2.3522
  - Wait 5s
  - Point 2: lat=48.8576, lon=2.3532
  - Wait 5s
  - Point 3: lat=48.8586, lon=2.3542
- Wait 15-20s total for collection cycles
- Verify live metrics update (distance, activity type, collection count)
- Take screenshot showing live metrics

### Step 5: TrackingPill Scroll Behavior
- Scroll down past TodayProgressCard
- Verify floating TrackingPill appears at bottom
- Scroll back up
- Verify TrackingPill hides (TodayProgressCard visible again)
- Take screenshots of both states

### Step 6: Stop Tracking
- Tap "Stop" on TrackingPill or TodayProgressCard
- Verify tracking stops
- Verify session saved (dashboard updates)
- Take screenshot

### Step 7: Post-Session Dashboard
- Verify LastSessionCard appears showing duration/distance/steps from just-completed session
- Verify StreakBanner updates (if applicable)
- Take screenshot of LastSessionCard

### Step 8: Challenge Cards
- Scroll to ChallengeCards section
- Verify challenge cards render (if challenges exist)
- Verify card width is sufficient (no text truncation — should be ~200dp)
- Verify time remaining shows localized format (e.g., "20d", "3h", "<1h")
- Verify horizontal scroll works if >2 cards
- Take screenshot

### Step 9: Full Dashboard Scroll
- Scroll entire dashboard top to bottom
- Verify all cards render: Greeting → TodayProgress → Streak → Challenges → LastSession → RecentTrips → Exploration
- Take screenshot at bottom of scroll

## Verification Checklist
- [ ] Greeting message includes engagement phrase (not bare "Good afternoon")
- [ ] Tracking toggle works (start → stop → session appears)
- [ ] Live metrics update during tracking
- [ ] TrackingPill appears/disappears based on scroll position
- [ ] LastSessionCard populates after session ends
- [ ] Challenge cards don't truncate text (200dp width)
- [ ] Challenge time remaining is localized
- [ ] No ANR during tracking start/stop
- [ ] No crashes throughout flow
- [ ] All cards render without blank gaps on full scroll
