# Tracker Android — Known Followups

Open issues discovered during the v10 QC pass and adjacent work that were intentionally not fixed in their originating commit. Each item explains why it's open, what's known, and what would let someone close it.

This file is hand-curated. Update it when items are closed, when new context is uncovered, or when a related fix lands that changes the picture.

---

## MAJOR — Session duration timer appears stalled at ~4 s on Tracking screen
**Source:** Resilience QC pass (`qc-screenshots/r-resilience/R-REPORT.md`).

**Observed:** Started an In-Motion mode tracking session on the Tracking screen while the device was stationary. The "Session duration" hero ticked to ~4 s and then stuck. Survived background/foreground but never advanced past 4 s during 5+ seconds of subsequent foreground time. After Stop the saved session correctly read "4 s / 0 m".

**Code review notes:**
- `rememberWallClockMillis(isTracking)` at `tracker/src/main/java/com/adsamcik/tracker/tracker/ui/compose/TrackerDashboard.kt:364` is the source of the wall-clock value. It uses `produceState(key1 = isTracking, key2 = lifecycle)` with a `repeatOnLifecycle(STARTED)` that ticks every 1 s while `isTracking` is true.
- `durationMillis = (sessionEnd - sessionData.start).coerceAtLeast(0L)` in `TrackerDashboardStatsCards.kt:92`. When `isTracking` is true, `sessionEnd = wallClockNowMillis`.
- Both pieces look correct in isolation. The stall implies one of:
  - `isTracking` going stale (false) while the user is still on Tracking,
  - `sessionData.start` mutating to follow `sessionEnd` (zero-duration sliding window),
  - lifecycle event sequencing on background→foreground briefly cancelling and not restarting the tick coroutine.

**To close:** reproduce live on emulator with logcat tags around `TrackerService` session start/end, `TrackerController.isServiceRunningFlow`, and the `rememberWallClockMillis` coroutine. Confirm `isTracking == true` throughout, observe `sessionData.start` over time, and check whether `produceState` re-keys unexpectedly.

---

## MINOR — Map scale a11y node missing in landscape
**Source:** Resilience QC pass.

**Observed:** Map renders correctly in landscape. The visual scale bar is present. But the "Map scale: 50 m" accessibility node that exists in portrait disappears from the a11y tree dump.

**Code review notes:** `ScaleBar` at `map/src/main/java/com/adsamcik/tracker/map/ui/MapScreen.kt:826` hides the entire composable when `spec.widthDp < 20f || spec.widthDp > 250f`, or when `metersPerDp <= 0.0`. Either branch removes both the visual and the a11y semantics. During rotation, `metersPerDp` may briefly be 0 or the computed `spec.widthDp` may fall outside the renderable range until MapLibre re-fits.

**To close:** reproduce, confirm whether the visual was actually still rendered when the a11y dump was taken (agent claimed yes), or whether the dump caught a moment with no scale bar visible. If they truly disagree, separate the visual size guard from the semantics so the a11y node persists even when the bar is too small to draw usefully.

---

## MINOR — V10 Compose-era strings untranslated in every locale bucket
**Source:** P4 locale sweep (`qc-screenshots/p4-locale/P4-REPORT.md`).

**Observed:** Repo ships `values-{af-rZA, ar-rSA, ca-rES, cs-rCZ, da-rDK, de-rDE, el-rGR, es-rES, fi-rFI, fr-rFR, hu-rHU, it-rIT, iw-rIL, ja-rJP, ko-rKR, nl-rNL, no-rNO, pl-rPL, pt-rBR, pt-rPT, ro-rRO, ru-rRU, sr-rSP, sv-rSE, tr-rTR, uk-rUA, vi-rVN, zh-rCN, zh-rTW}` — 29 buckets. But every v10 Compose-era onboarding/settings/dashboard string only exists in `values/strings.xml`. Confirmed-missing keys include `onboarding_get_started`, `onboarding_streamlined_title`, `onboarding_benefit_*`, `button_continue`, `setup_start_exploring`, `setup_perm_granted`, plus most dashboard and map UX strings.

**Impact:** Even with a working LocaleConfig (added in `b5b167624`), users on tr-TR / de-DE / etc. see the bulk of v10 UI in English fallback.

**To close:** decide which locales the maintainer wants to keep maintained (probably fewer than 29). For the kept ones, run a translation pass on all v10 keys. For the rest, delete the orphan buckets so they don't masquerade as supported.

---

## NIT — Compose CTAs report `clickable=false` in the a11y tree
**Source:** Resilience and P4 passes (pervasive across screens).

**Observed:** MCP `tap_element` consistently warns "Element is not marked clickable" on `GET STARTED`, `CONTINUE`, nav tabs, settings rows, the action ring, and most other interactive Compose elements. Taps still register and the screen behaves correctly. The `clickable=true` semantic flag is just missing in `dumpsys` for these views.

**Impact:** TalkBack hint quality may suffer (the OS can't announce "double-tap to activate" reliably). Likely also confuses automated a11y scanners.

**Code review notes:** the project's `RadioCard` (`sutils/.../compose/RadioCard.kt`) uses `Modifier.selectable(role = Role.RadioButton)` correctly. Other interactive composables mostly rely on `Modifier.clickable { }` or `Card(onClick = ...)`, both of which should set the clickable semantic flag. Need to determine whether the a11y dump is reading the merged or unmerged semantics tree, or whether something in the project's theming wraps content in a way that strips the flag.

**To close:** pick a high-traffic CTA (e.g. the action ring or `GET STARTED`), inspect the merged + unmerged semantics tree via `composeTestRule.onRoot().printToLog`, identify why `clickable=true` doesn't propagate, fix at the primitive (PrimaryActionButton or whichever wrapper). Likely a single fix resolves it across the app.

---

## NIT — Settings list scroll position resets after rotation
**Source:** Resilience QC pass.

**Observed:** Rotating from portrait to landscape on Settings → root resets the scroll position to the top. Acceptable for a short list, mildly annoying once Settings grows.

**To close:** hoist the `RootSettingsScreen`'s `LazyListState` via `rememberSaveable(saver = LazyListState.Saver)` so scroll offset survives configuration change.

---

## MINOR — Dashboard scroll position not preserved across rotation (similar)
Lower confidence — not explicitly observed in the agent's report but worth checking once the Settings fix lands. `IdleContent` already accepts a `listState` parameter but the caller may not be hoisting it through saveable.

---

## MINOR — Basemap-import Snackbar visibility immediately after SAF picker dismisses
**Source:** S5-F9 PMTiles validation work (`qc-screenshots/s5-f9-pmtiles/S5-F9-REPORT.md`).

**Observed:** Data-layer validation + rollback is verified — a malformed file is rejected and `custom.pmtiles` is deleted (proven by unit test + filesystem check). The Compose `SnackbarHost` in `MapSettingsScreen` is wired and the `LaunchedEffect` collects `viewModel.basemapImportError` correctly. But the snackbar wasn't consistently captured in screenshots taken right after the SAF picker dismisses. Could be timing of screenshot vs snackbar display, or the snackbar being missed because the host attaches a frame later than the emission.

**To close:** either (a) move the snackbar host up to a top-level scaffold so it survives the SAF return more reliably, or (b) capture transient overlays with `uiautomator dump` during the snackbar window to confirm display. Not a correctness bug — the malicious file is still rejected.

---

## Pre-existing test infrastructure debt
These were `@Ignore`d or are known-flaky on the current dev/v10; not regressions caused by recent QC work.

- `DataRetentionWorkerTest."doWork returns success and does nothing when disabled"` — hangs under `StandardTestDispatcher` because `RetentionConfigStore.config` flow's `onStart` migration races against the test's `update()`. Recovery hint: replace the StandardTestDispatcher pattern with a fake DataStore.
- `LocationHeatmapLayerTest$LoadData."queries weighted data with hor_acc weight"` — assertion `expected:<5.0> but was:<0.9>`. Confirmed already failing on dev/v10 baseline.
- `TrackerDashboardControlsTest.trackingFAB_*` family — three tests fail asserting that ContentDescription `Start tracking` / `Stop tracking` / `Start tracking - permission required` are displayed. Confirmed already failing on dev/v10 baseline.

**To close:** these belong to a focused "test debt sprint" rather than feature work. They've been outstanding through multiple v10 RC iterations.

---

## Reports cross-reference
Detailed QC reports (per-pass) live under `qc-screenshots/` which is gitignored:
- `qc-screenshots/p4-locale/P4-REPORT.md`
- `qc-screenshots/p6-process-death/P6-REPORT.md`
- `qc-screenshots/s5-f9-pmtiles/S5-F9-REPORT.md`
- `qc-screenshots/r-resilience/R-REPORT.md`

These reports are local-only because the screenshots alongside them would bloat the repo. If a future session needs them, regenerate from the emulator using the same broadcast tooling described in `app/src/debug/README.md`.
