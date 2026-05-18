# v10 RC Merge — Follow-ups

After integrating `feature/v10-rc-fixes` and `feature/qc-finalization` into local `dev/v10` (HEAD `883efc9cf` on 2026-05-17), the following items remain open.

## Validation not yet run

- **Release-gate suite** — the v10 RC validation command from the handover has not been re-run against the merged tree:
  ```powershell
  ./gradlew :app:lintRelease :app:checkReleaseLintReport :app:assembleRelease assembleRelease_nominify checkRoomSchemaDrift --no-daemon --console=plain
  ```
  Confirmed green so far: `:statistics:testDebugUnitTest` + `:app:assembleDebug` (1m 21s, 0 failures after the test fix in `883efc9cf`).

- **Full unit-test suite** not run. The original RC handover noted pre-existing baseline failures in `:map`, `:dashboard`, `:tracker`, `:app` before the RC work; the merged tree inherits that situation. Targeted module tests plus release gates are the canonical validation path, not the full suite.

## Not pushed

- `origin/dev/v10` is at `2609963b4` and significantly behind local `dev/v10` at `883efc9cf`. No push performed; left to operator judgement.

## Branches still around

These branches were left intact because the parallel agent appears to use them and they shouldn't be reaped without coordination:

- `feature/v10-rc-fixes` — last seen at `c7943e5b3` (merged via `3d025d196`).
- `feature/qc-finalization` — may have advanced again after the round-2 merge at `379123f71`. Worth a `git fetch` + `git log feature/qc-finalization` before declaring done; if new commits, repeat the round-N merge pattern.
- `merge/v10-rc-fixes` — at `702db60f7`. Not created during this session; origin unknown.

## Architectural decisions captured during the merge

Recorded here so future readers don't relitigate without context:

- **Tracking toggles** — RC's single-source `TrackingParamsRepository` won over dev/v10's dual-write to a separate `TrackingTogglesDataStore`. The latter was added in `b4b92d2d4` to plaster over the split-brain; RC's approach removes the second store entirely. Permission-gated `setWifi/CellEnabled` callbacks from RC are now canonical.
- **Onboarding scaffold** — RC's per-step `SetupStepScaffold` subsystem taken wholesale. dev/v10's `SetupScaffold` + route-level `contentPadding` refactor (`63b5e6067`) is superseded so RC's QC-validated a11y/error-state UX stays internally coherent. `MainActivityCompose` testTag root wrapper was preserved.
- **Map controls** — kept dev/v10's `QualityPickerPopover` + `activeQualityLabel` chip; layered RC's `selectedTripContext` date-range label + heatmap/cell-signal normalization on top.
- **Dashboard empty state** — combined dev/v10's wired hero/quick-start click handlers with RC's `testTag` + dynamic `bottomClearance` Spacer + hint-arrow icon.
- **impexp export dialog** — RC's data-driven format list (adds JSON + per-format sensitivity confirmation) won; descriptions are blended ("Full-precision route points for GPS devices, fitness apps, and universal compatibility" merges RC's privacy framing with dev/v10's user-friendly hooks).
- **TrackingOrchestrator** — kept dev/v10's `TrackingPipeline` cache + `createTrackingPipeline()` factory (perf win); added RC's testability injections (`dailySummaryFallbackEnqueuer`, `enableNotifications`, `private val trackingParamsRepository`) + `ShutdownResult` return type + smart fallback logic.
- **PrivacyPolicyDialog** — RC's `settings/components/PrivacyPolicyDialog.kt` kept; dev/v10's parallel duplicate at `settings/privacypolicy/PrivacyPolicyDialog.kt` was deleted (caused overload-ambiguity build break).

## Test contract drift

- `TripDetailPresenterViewModelTest.emits persisted route points for loaded trip` was rewritten to match the RC contract (`getOrderedChunkBetween` returning `List<LatLngModel>` instead of `getSamplesBetween` returning `List<LocationSample>`). The dev/v10 qc-finalization version of this test was incompatible with RC's paginated route-points loader. See commit `883efc9cf`.

## Cross-agent coordination

A parallel agent commits to `dev/v10` and `feature/qc-finalization` during the same wall-clock window as interactive Claude sessions. Before any further git writes on `dev/v10`:

- Re-check HEAD and reflog; state can change between diagnostic reads and writes.
- Watch for new commits with the multi-paragraph "N1/N2/S6-F2"-style messages from `adsamcik <adsamcik@users.noreply.github.com>` — those are the parallel agent.
- Don't delete `feature/*` branches without coordination.
- Stick to `--no-ff` merge commits per the project's worktree-merge-commit pattern.
