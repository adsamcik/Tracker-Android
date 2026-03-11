# Android QC Report — Tracker App

**Run ID:** qc-run-001  
**Package:** com.adsamcik.tracker.debug  
**Device:** emulator-5554 (Android 16)  
**Depth:** standard  
**Screens:** 5 discovered, 5 tested  
**Orientation:** portrait + landscape  

---

## Verdict: BLOCKED

> **2 blocker, 1 critical, 8 major, 4 minor, 1 nit** — 16 confirmed + 1 suspected

The app cannot ship in its current state. Recurring ANR dialogs block basic usage,
and the map screen is non-functional.

---

## Blocker Issues

### 1. Recurring ANR on cold start and navigation (4+ times)
- **Source:** Tool-confirmed (MCP crash/ANR detection)
- **Screen:** Dashboard / app-wide
- **Details:** "Tracker isn't responding" ANR dialog appeared 4+ times during QC —
  on cold start and again when tapping the Statistics tab. The app entered an ANR loop
  that could only be broken by force-closing. After force-close and relaunch, the app
  recovered but the ANR risk persists on any navigation.
- **Hypothesis:** Heavy main-thread initialization (DB migration, Compose first-frame,
  sensor registration) blocks the UI thread beyond the 5s ANR threshold.

### 2. ANR on cold start — Compose UI fails to render until dismissed
- **Source:** Both GPT 5.4 + Opus 4.6 (confirmed)
- **Screen:** Dashboard
- **Details:** On first launch, the ANR appeared before any UI rendered. After dismissing
  with "Wait", the screen showed only a green gradient (no Compose content) for several
  seconds before the dashboard eventually appeared.

---

## Critical Issues

### 3. Map tiles fail to render — blank beige viewport
- **Source:** Both models (confirmed, conf: 0.94)
- **Screen:** Map
- **Details:** MapLibre map view displays a blank beige/olive fill with no streets,
  buildings, or landmarks at 50m zoom. The blue location dot and controls render,
  but the basemap is completely empty. The map is unusable for its core purpose.
- **Hypothesis:** Tile source unavailable at detailed zoom levels, or style layer
  configuration issue.

---

## Major Issues

| # | ID | Screen | Type | Title | Source |
|---|-----|--------|------|-------|--------|
| 4 | DASH-002 | Dashboard | data | Steps shows em dash despite having distance and duration | opus |
| 5 | DASH-004 | Dashboard | a11y | Cards and FAB lack content descriptions | opus |
| 6 | STAT-01 | Statistics | visual | No active-state indicator on tab chips | both |
| 7 | STAT-04 | Statistics | a11y | Tab chips lack accessibility selected state | opus |
| 8 | GAME-003 | Game | layout | Exploration card clipped by bottom nav bar | both |
| 9 | GAME-001 | Game | logic | Streak 0 despite tracking 23hr ago | opus |
| 10 | GAME-002 | Game | data | 0 points despite 2 cells discovered | opus |
| 11 | LAND-001 | Dashboard | layout | Layout broken in landscape — FAB overlaps | tool |

### Suspected (not yet confirmed)
| # | ID | Screen | Type | Title | Source |
|---|-----|--------|------|-------|--------|
| 12 | DASH-blank | Dashboard | perf | Blank green gradient before content renders | gpt |

---

## Minor Issues

| # | ID | Screen | Type | Title | Source |
|---|-----|--------|------|-------|--------|
| 13 | DASH-003 | Dashboard | logic | Behind label when no tracking occurred today | opus |
| 14 | STAT-03 | Statistics | layout | 60% empty space below single session entry | opus |
| 15 | MAP-002 | Map | layout | Cell heatmap chip text truncated | both |
| 16 | GAME-004 | Game | consistency | Two identical Start Tracking CTAs | both |

### Nit
| # | ID | Screen | Type | Title | Source |
|---|-----|--------|------|-------|--------|
| 17 | GAME-005 | Game | logic | Weekly goal 24000 != 7x daily 4000 | opus |

---

## Coverage Summary

| Screen | Status | Key Findings |
|--------|--------|-------------|
| Dashboard | Tested | ANR blocker, a11y gaps, landscape broken |
| Statistics | Tested | Tab chips lack selection state |
| Map | Tested | Tiles do not render (critical) |
| Game | Tested | Bottom nav clips content, streak/points logic |
| Settings | Tested | Clean — no issues found |

**Gaps:** Session detail view not tested. Tracking flow not executed (would trigger
permissions). Dates/Wi-Fi stats sub-tabs not explored. About section not scrolled to.

---

## Recommendations (Priority Order)

1. **Fix ANR:** Profile cold-start path. Move heavy init (DB, sensors, Compose prep)
   off main thread. Target < 2s to first frame.
2. **Fix map tiles:** Verify MapLibre tile source URL/API key configuration. Test at
   multiple zoom levels.
3. **Add content descriptions:** All interactive cards, FABs, and chips need
   meaningful contentDescription / Compose semantics.
4. **Fix bottom padding:** Game screen scrollable content needs bottom inset padding
   to clear the navigation bar.
5. **Fix landscape layout:** Add responsive layout adaptation for landscape orientation.

---

## Model Agreement Analysis

- **Both models agreed:** ANR (severity differed), map blank tiles, tab chip selection,
  game bottom-nav clip, chip truncation, duplicate CTAs
- **Opus-only catches (deeper reasoning):** Steps em-dash data gap, streak logic
  inconsistency, points-vs-cells contradiction, accessibility states
- **GPT-only catch:** Blank green gradient render (suspected)
- **No disagreements led to dismissals** — both models unique findings were valuable
