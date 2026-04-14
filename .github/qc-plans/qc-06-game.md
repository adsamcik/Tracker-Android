# QC-06: Game & Challenges

## Objective
Validate game screen, challenge cards, mini-games, XP display, and trophy case.

## App Details
- **Package:** `com.adsamcik.tracker.debug`
- **Device:** `emulator-5554`

## Pre-conditions
- App installed, at least 1 session completed (for potential challenge progress)
- Navigate to Game tab

## Test Steps

### Step 1: Navigate to Game
- Tap "Game" bottom nav tab (test tag: "nav_game")
- Verify GameRoute loads
- Take screenshot of initial view

### Step 2: Hero Level Card
- Verify hero card visible at top showing:
  - Level number (e.g., "Level 1")
  - XP progress bar
  - XP to next level text
- If new user (level 0): verify "Start tracking to level up!" hint text
- Take screenshot

### Step 3: Steps Goals Section
- Look for "Steps goals" section
- Verify daily and/or weekly step goal cards render
- Verify progress indicators shown (progress bar or ring)
- Take screenshot

### Step 4: Active Challenges Row
- Look for "Active Challenges" section
- Verify up to 3 challenge slots shown
- Empty/unfilled slots should show "?" placeholder
- Active challenges should show:
  - Challenge title
  - Progress bar/percentage
  - Time remaining
- Take screenshot

### Step 5: Challenge Time Format
- For active challenges showing time remaining:
  - Verify localized format: "20d" (days), "3h" (hours), "<1h" (less than hour)
  - Verify NOT raw numbers or unlocalized text
- Take screenshot zoomed on time text

### Step 6: Challenge Card Sizing
- Verify challenge cards are wide enough (~200dp) to prevent text truncation
- Verify difficulty label visible (Easy, Medium, Hard, Very Hard)
- Verify progress percentage visible
- No text should be clipped or cut off
- Take screenshot

### Step 7: Scroll to Mini-Games
- Scroll down to mini-games section
- Verify 3 mini-games listed:
  - "Outrun" — race against ghost pacer
  - "Territory" — claim grid cells
  - "Zen Walk" — maintain steady pace
- Take screenshot

### Step 8: Mini-Game Lock States
- For locked mini-games:
  - Verify "Unlocks at Level X" text shown
  - Verify locked visual state (dimmed/greyed)
- For unlocked mini-games:
  - Verify tappable and has active visual state
- Take screenshot

### Step 9: Trophy Case Navigation
- Look for "View Trophy Case" button or nav element
- Tap it
- Verify TrophyCaseRoute loads
- Verify medals/achievements display area visible
- Take screenshot of trophy case

### Step 10: Trophy Case Content
- Verify trophies/medals shown (if any earned)
- Verify visual distinction between earned and unearned trophies
- Scroll through trophy case content
- Take screenshot

### Step 11: Back from Trophy Case
- Press Back
- Verify return to Game screen
- Verify Game screen state preserved
- Take screenshot

### Step 12: Challenge Picker (if available)
- Look for "Start a challenge" button or challenge picker trigger
- If available, tap it
- Verify challenge picker shows challenge types and difficulty options
- Take screenshot
- Dismiss picker

### Step 13: Full Game Screen Scroll
- Scroll from top to bottom of Game screen
- Verify all sections render:
  - Hero card → Steps goals → Active challenges → Challenges → Mini-games
- Verify no blank gaps or missing sections
- Take screenshot at bottom

## Verification Checklist
- [ ] Hero card shows level and XP correctly
- [ ] Steps goals section renders with progress indicators
- [ ] Active challenges row shows up to 3 slots
- [ ] Challenge cards render at proper width without text clipping
- [ ] Time remaining is localized ("20d", "3h", "<1h")
- [ ] All 3 mini-games listed (Outrun, Territory, Zen Walk)
- [ ] Mini-game lock/unlock states display correctly
- [ ] Trophy case loads and displays content
- [ ] Challenge picker works (if available)
- [ ] All sections scroll smoothly with no gaps
- [ ] No crashes on any interaction
