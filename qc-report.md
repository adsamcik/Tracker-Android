# Tracker Android — Comprehensive QC Report

**Date:** 2025-07-23
**Device:** emulator-5554 · Android 16 · sdk_gphone64_x86_64 · 1080×2400 · ~3x density
**App:** com.adsamcik.tracker.debug
**Methodology:** Dual-model parallel evaluation (GPT 5.4 xhigh + Opus 4.6 1M) per screen, reconciled with interactive testing
**Screens evaluated:** 7 primary + 12 sub-screens/dialogs
**Total findings:** 42

---

## Executive Summary

The Tracker app delivers a rich, privacy-first tracking experience with strong core functionality — tracking starts/stops cleanly, sessions persist correctly, and the Dashboard provides useful at-a-glance data. However, the QC evaluation uncovered **6 critical**, **9 major**, **23 minor**, and **4 nit-level** issues across all screens.

**Top concerns:**
1. **Broken open-source licenses** — legal/compliance risk (can't display license data)
2. **Map overlay chips inaccessible** — 4+ overlay options hidden, can't scroll to them
3. **Content clipped behind nav bar** — Game screen content cut off
4. **Map bottom sheet broken** — gestures blocked, can't expand
5. **Accessibility gaps** — undersized touch targets, hardcoded English a11y strings, doubled labels
6. **i18n regressions** — hardcoded English in switches, heatmap labels, pluralization bugs

The app is **not release-ready** due to the critical functional and legal issues. Core tracking works well, but supporting features (map overlays, licenses, activity management) have significant gaps.

---

## Severity Distribution

| Severity | Count | % |
|----------|------:|---:|
| 🔴 Critical | 6 | 14% |
| 🟠 Major | 9 | 21% |
| 🟡 Minor | 23 | 55% |
| ⚪ Nit | 4 | 10% |
| **Total** | **42** | **100%** |

## Findings by Screen

| Screen | Total | Critical | Major | Minor | Nit |
|--------|------:|---------:|------:|------:|----:|
| Settings | 11 | 2 | 3 | 5 | 1 |
| Game | 8 | 2 | 0 | 5 | 1 |
| Statistics | 8 | 0 | 2 | 6 | 0 |
| Map | 6 | 2 | 0 | 3 | 1 |
| Dashboard | 6 | 0 | 2 | 3 | 1 |
| Tracking | 2 | 0 | 1 | 1 | 0 |
| All screens | 1 | 0 | 1 | 0 | 0 |

## Findings by Type

| Category | Count |
|----------|------:|
| UX | 11 |
| Accessibility | 8 |
| Visual | 7 |
| i18n | 6 |
| Functional | 4 |
| Logic | 2 |
| Privacy | 1 |
| Resilience | 1 |
| Layout | 1 |
| Code quality | 1 |

---

## 🔴 Critical Findings (6)

### C1. Open source licenses screen BROKEN
**Screen:** Settings > About > Open source licenses
**Type:** Functional | **Source:** Interactive testing

Shows "Couldn't load bundled third-party license data." — a legal/compliance issue for any published app. Additionally, this screen uses the old View-based `OssLicensesActivity` with a blue Material 2 Toolbar, completely inconsistent with the Compose Material 3 Expressive design used everywhere else.

**Impact:** Cannot display required open-source attributions. Google Play policy violation risk.
**Fix:** Verify the licenses plugin generates the data file. Consider replacing with a Compose-native licenses screen.

---

### C2. Map overlay chip row DOES NOT SCROLL
**Screen:** Map > Bottom sheet > Overlay chips
**Type:** Functional | **Source:** Both models + interactive verification

The chip row shows approximately 2.5 of 7+ available overlays (Location heatmap, Cell heatmap, Wi-Fi heatmap, etc.). The row has no horizontal scroll behavior and no affordance indicating more items exist. At least 4 overlay options are completely inaccessible to users.

**Impact:** Core map feature — users cannot access the majority of available data overlays.
**Fix:** Make the chip row horizontally scrollable with a fade edge or scroll indicator.

---

### C3. Content clipped behind bottom navigation bar
**Screen:** Game (confirmed), possibly others
**Type:** Layout | **Source:** Both models agreed

Bottom content on the Game screen is cut off by the system/navigation bar. The content area does not account for bottom navigation bar insets, causing the last items (achievements, mini-games) to be partially or fully hidden.

**Impact:** Users cannot see or interact with bottom-of-screen content.
**Fix:** Apply proper `WindowInsets` padding to account for the bottom navigation bar height.

---

### C4. Map bottom sheet cannot expand — gestures blocked
**Screen:** Map
**Type:** Functional | **Source:** Opus + interactive verification

The bottom sheet on the Map screen cannot be swiped upward to expand because the MapLibre map view consumes all touch/gesture events. Even when swiping from the sheet handle area, the map pans instead of expanding the sheet.

**Impact:** Bottom sheet content beyond the peek height is completely inaccessible.
**Fix:** Implement proper nested scrolling/gesture interception so the bottom sheet handle area captures vertical swipes.

---

### C5. Cannot edit or delete existing activity types
**Screen:** Settings > Activities
**Type:** Functional | **Source:** Interactive testing

The Activities settings screen shows 8 activity types (Walking, Running, Bicycle, etc.). The FAB (+) button allows adding new types, but existing activity cards have no edit, delete, or long-press action. There is no way to modify or remove incorrectly added activity types.

**Impact:** Users who add wrong activity types have no way to fix them.
**Fix:** Add swipe-to-delete, long-press context menu, or card tap → edit dialog for existing activities.

---

### C6. Steps challenge shows 0/0 goal — meaningless progress
**Screen:** Game > Steps challenge tab
**Type:** Logic | **Source:** Interactive testing

The Steps challenge tab displays "0 steps of 0 goal" — the goal value is zero, making the progress bar and completion tracking completely meaningless. This is likely a division-by-zero scenario in the progress calculation. The associated leaderboard also shows all zeros.

**Impact:** Feature is non-functional. Potential crash if progress percentage is calculated.
**Fix:** Ensure a non-zero default goal is set. Show a "Set a goal" prompt if no goal exists.

---

## 🟠 Major Findings (9)

### M1. Top bar icon buttons undersized (all screens)
**Type:** Accessibility | **Source:** GPT

Top bar icon buttons (settings gear, customize) measure approximately 126×126px. At the device's ~3x density, this translates to ~42dp — below the WCAG 2.1 / Material Design minimum of 48dp touch target size.

**Fix:** Set minimum touch target size to 48.dp via `Modifier.minimumInteractiveComponentSize()`.

---

### M2. Challenge card 3 undersized tap target
**Screen:** Dashboard > Challenge carousel
**Type:** Accessibility | **Source:** GPT

The third challenge card in the carousel is only ~134px wide (~45dp), below the 48dp WCAG minimum. Combined with the lack of pagination indicators, users may not even know this card exists.

**Fix:** Ensure carousel items maintain minimum 48dp interactive width or use proper `SnapFlingBehavior`.

---

### M3. Filter chip text labels invisible
**Screen:** Statistics
**Type:** Visual | **Source:** Opus (code-level analysis)

Filter chips use `TextOverflow.Clip` which can hide text labels entirely. Users see the selected/unselected chip state but cannot read what the chip says, especially for longer labels.

**Fix:** Use `TextOverflow.Ellipsis` or ensure chip width accommodates full text.

---

### M4. FilterChip a11y labels doubled
**Screen:** Statistics
**Type:** Accessibility | **Source:** Opus

TalkBack announces filter chips as "Summary, Summary, selected" — the accessibility label duplicates the visible text. This creates a confusing experience for screen reader users who hear every chip name twice.

**Fix:** Set `contentDescription` explicitly or remove redundant semantics merging.

---

### M5. LastSessionCard negates positive trip IDs
**Screen:** Dashboard
**Type:** Logic | **Source:** Opus (code analysis)

`LastSessionCard.kt` line 104 negates positive trip IDs before passing them to the detail navigation handler. This could cause incorrect session lookups or crashes if the downstream handler doesn't expect negative IDs.

**Fix:** Verify the ID negation is intentional. If it's a legacy workaround, document or remove it.

---

### M6. Switch stateDescription hardcoded English
**Screen:** Settings (all toggle switches)
**Type:** i18n | **Source:** Opus (code analysis)

`SettingsComponents.kt` lines 119-121 hardcode "On"/"Off" strings for switch `stateDescription` instead of using string resources. This breaks internationalization for all 28 supported locales — non-English users hear English "On"/"Off" from TalkBack.

**Fix:** Use `stringResource(R.string.switch_on)` / `stringResource(R.string.switch_off)`.

---

### M7. Stop tracking button not marked clickable
**Screen:** Active tracking
**Type:** Accessibility | **Source:** Interactive testing

The stop button on the active tracking screen is not marked as clickable in the UI accessibility tree. While it responds to taps visually, assistive technologies cannot identify it as an interactive element.

**Fix:** Add `Modifier.clickable` or `Modifier.semantics { onClick(...) }` to the stop button.

---

### M8. Privacy Policy opens Chrome — requires network
**Screen:** Settings > About
**Type:** Privacy | **Source:** Interactive testing

For a privacy-first, fully local app, the privacy policy requires an internet connection and opens an external browser. This contradicts the app's core principle that everything stays on-device. Users in airplane mode or without connectivity cannot view the policy.

**Fix:** Bundle the privacy policy as a local asset and display it in an in-app Compose screen.

---

### M9. Licenses screen uses View-based toolbar
**Screen:** Settings > About > Open source licenses
**Type:** Visual | **Source:** Interactive testing

Even if license data loaded correctly, the `OssLicensesActivity` uses an old blue Material 2 Toolbar — a jarring visual break from the Material 3 Expressive Compose design used in every other screen.

**Fix:** Replace with a Compose-native licenses display (e.g., `aboutlibraries-compose`).

---

## 🟡 Minor Findings (23)

| # | Screen | Type | Finding |
|---|--------|------|---------|
| m1 | Map | Visual | "Cell heatmap" chip truncated to "Cell heatm..." |
| m2 | Statistics | i18n | Heatmap day labels ambiguous (T=Tue/Thu, S=Sat/Sun), hardcoded English |
| m3 | Statistics | UX | Session subtitle inconsistency — some show "Tap to open details", others show distance |
| m4 | Statistics | UX | Long-press context menu undiscoverable — 4 options (Trip Details, Route map, Export GPX, Delete) with zero visual hint |
| m5 | Statistics | UX | Distance shows false centimeter precision (53.96 m) — misleading from GPS |
| m6 | Game | UX | Leaderboard uses synthetic benchmarks disguised as competitive ranking |
| m7 | Game | i18n | "1 days" pluralization bug in Exploration card |
| m8 | Game | UX | Points system provides no context on how to earn points |
| m9 | Game | UX | Default weekly goal trivially low (0.1 km), not configurable from Game screen |
| m10 | Dashboard | a11y | Challenge card a11y labels omit visible time-remaining info |
| m11 | Game | UX | "0 of 0.1" progress text lacks units (km? miles?) |
| m12 | Map | a11y | My Location button uses `Role.Switch` but behaves as toggle button |
| m13 | Map | i18n | Hardcoded English accessibility string for map loading state |
| m14 | Dashboard | Resilience | TodaySummary silently swallows all exceptions (no logging) |
| m15 | Statistics | a11y | Sparse heatmap shows empty cells with no "no data" context |
| m16 | Dashboard | UX | Challenge carousel lacks pagination indicator (dots) |
| m17 | Settings | UX | "Speed per hour" — semantically redundant (speed already has /time) |
| m18 | Statistics | UX | Inconsistent "no data" representation: Steps="N/A", speeds="—" |
| m19 | Settings | Code | Dead Statistics settings redirect code in SettingsRoute.kt lines 94-96 |
| m20 | Settings | i18n | "Other" section header vs "Units & Display" string resource mismatch |
| m21 | Settings | Visual | "Delete" capitalized mid-sentence in delete confirmation dialog |
| m22 | Settings | UX | Game settings mentions Goals but has no navigation to Goals feature |
| m23 | Tracking | Visual | Landscape tracking: "Dist..." text truncated at left edge |

## ⚪ Nit Findings (4)

| # | Screen | Type | Finding |
|---|--------|------|---------|
| n1 | Dashboard | Visual | Challenge progress arc starts at 3 o'clock instead of conventional 12 |
| n2 | Map | Visual | Map title uses pill badge while other screens use large title text |
| n3 | Game | i18n | "Games" vs "Game" singular/plural inconsistency in navigation |
| n4 | Settings | a11y | SettingsItem contentDescription concatenation blocks TalkBack granular navigation |

---

## Interactive Testing Results

### ✅ Working Correctly
- **Tracking lifecycle**: Start → active tracking screen → stop → session saved with correct data
- **Session navigation**: Card tap opens Trip Details with duration, distance, route map, activity type
- **Trip detail overflow menu**: "Export as GPX" and "Delete" work from ⋮ menu
- **Map overlay switching**: Location heatmap toggle works, shows proper empty state card
- **My Location button**: Centers map on current GPS location
- **Customize Dashboard**: Bottom sheet with toggle switches and drag handles all functional
- **Data export dialog**: Well-designed with GPX (recommended), KML, Database options
- **Delete all data**: Proper red confirmation dialog with destructive action warning
- **Import data**: Option available and accessible
- **Map settings**: Basemap selection, ski lift data import, advanced sliders all work
- **Orientation change**: App survives portrait → landscape → portrait without crash
- **Streak card tap**: Navigates to Game tab (reasonable behavior)

### ⚠️ Partially Working
- **Session long-press**: Fully functional (4 options) but completely undiscoverable
- **Last Session card tap**: Navigates to Statistics tab, not directly to session detail (confusing indirection)
- **Map layer button**: Taps register but no visible effect (may need loaded tiles)
- **Activities FAB**: Add works, but no edit/delete for existing entries
- **Date picker (Statistics > Dates)**: Opens correctly, OK disabled until range selected (correct)
- **Wi-Fi stats tab**: Shows info dialog but doesn't switch tab content (displays over Summary)

### ❌ Not Working
- **Open source licenses**: Completely broken — cannot load data
- **Map chip row scroll**: Chips don't scroll — hidden overlays inaccessible
- **Map bottom sheet expand**: Cannot swipe to expand — gestures blocked by map
- **Steps challenge goal**: 0/0 makes feature non-functional

---

## Model Agreement Analysis

| Scenario | Count | Notes |
|----------|------:|-------|
| Both models found same issue | 10 | High confidence — auto-confirmed |
| Only Opus found (deeper analysis) | 15 | Code-level & a11y issues Opus excels at |
| Only GPT found (visual triage) | 5 | Touch target sizing, visual truncation |
| Interactive testing only | 12 | Required actual device interaction to discover |

Opus 4.6 1M consistently found deeper issues (code logic, i18n regressions, semantic accessibility) while GPT 5.4 was faster at visual sizing/truncation issues. Interactive testing was essential for discovering functional gaps (scroll behavior, gesture conflicts, broken screens).

---

## Recommendations (Priority Order)

### P0 — Fix Before Release
1. Fix open-source licenses data loading (legal requirement)
2. Make map overlay chip row scrollable
3. Apply `WindowInsets` to fix content clipping behind nav bar
4. Fix map bottom sheet gesture conflict
5. Set non-zero default for Steps challenge goal

### P1 — Fix Soon
6. Ensure all touch targets ≥ 48dp
7. Extract hardcoded English strings to resources (switches, a11y, heatmap)
8. Fix FilterChip doubled accessibility labels
9. Add edit/delete capability for activity types
10. Bundle privacy policy locally
11. Replace View-based licenses screen with Compose

### P2 — Quality Improvements
12. Add visual affordance for long-press context menus
13. Fix pluralization bugs ("1 days")
14. Add pagination indicators to carousels
15. Round GPS distances to sensible precision
16. Add units to progress text
17. Make landscape layouts responsive
18. Clean up dead Settings code

---

## Environment

| Property | Value |
|----------|-------|
| Device | emulator-5554 |
| Android | 16 (API 36) |
| Image | sdk_gphone64_x86_64 |
| Resolution | 1080×2400 |
| Density | ~3x (~440dpi) |
| App package | com.adsamcik.tracker.debug |
| Claim token | T3UDVLMM |
| Screens tested | Dashboard, Statistics, Map, Game, Settings, Tracking + 12 sub-screens |
| Evaluation method | Dual-model (GPT 5.4 + Opus 4.6 1M) + manual interactive |
