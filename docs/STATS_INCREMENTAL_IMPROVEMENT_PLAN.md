# Stats Architecture: Incremental Improvement Plan

> **Author**: Architecture review, June 2025
> **Status**: Proposal
> **Premise**: Evolution over rewrite — unless the conditions in §E are met

---

## Executive Summary

The stats subsystem is mid-migration. Three new modules (`stats-api`, `stats-engine`, `stats-data`)
were designed with excellent API contracts and solid test coverage, and the `tracker` module already
bridges to them via `SessionSegmentWriter`, `StreamingAggregatorWriter`, and `ExplorationWriter`.
But the consumer-facing `statistics` module knows nothing about any of this — it still computes
everything from raw Room queries on `AppDatabase`. Users see the old pipeline's output; the new
pipeline writes to tables (`live_stats`, `daily_summary`, `session_segments`, `inferred_trips`)
that no UI reads yet.

This is not a crisis. It's an incomplete migration. The new engine is deployed, accumulating data,
and tested. The old UI still works. We should finish the bridge, not start over.

---

## A. Phased Improvement Plan

### Phase 0 — Safety Net (1–2 days)

**Goal**: Fix things that can silently corrupt data *right now*.

| # | Task | Files | Risk |
|---|------|-------|------|
| 0.1 | **Add speed validation** — clamp negative speed to `null` at the tracker→engine boundary in `StreamingAggregatorWriter.buildSignal()` and `SessionSegmentWriter.buildSignal()` | 2 files, ~4 lines each | Minimal |
| 0.2 | **Create missing `consumer-rules.pro`** in `stats-api/`, `stats-engine/`, `stats-data/` (empty files are fine — declares intent; prevents release build breakage if minify is enabled) | 3 new empty files | Zero |
| 0.3 | **Unify speed thresholds** — Extract canonical constants to `stats-api` (e.g. `SpeedThresholds.kt`) with `MAX_WALK_MPS = 2.5`, `MAX_RUN_MPS = 6.0`. Have `ActivityTrackerComponent` import from there. Deprecate the tracker-local constants. | 3 files | Low — changes classification behavior for the 2.0–2.5 m/s band; needs regression test |

**Milestone**: No more silent data corruption paths. Speed thresholds consistent across all modules.

---

### Phase 1 — Deduplicate & Consolidate (2–3 days)

**Goal**: Single source of truth for shared concepts.

| # | Task | Files | Risk |
|---|------|-------|------|
| 1.1 | **Consolidate activity-type mapping** — Move `mapActivityTypeToInt()` to `stats-api` as `DetectedActivityType.Companion.fromPlayServicesCode(code: Int)` and the inverse `toPlayServicesCode()`. Delete the 3 copies in `TripEnricher`, `SessionSegmentWriter`, `StreamingAggregatorWriter` and the copy in `PolicyTierMapper`. | 5 files | Low — pure refactor, logic identical |
| 1.2 | **Make `stats-api` a pure Kotlin module** — Replace `android.library` plugin with `java-library` + `kotlin("jvm")`. Replace `kotlinx-coroutines-android` with `kotlinx-coroutines-core`. `stats-api` has zero Android API usage; this makes the "pure contracts" claim true and enables running tests without Robolectric. | 1 build file + test config | Medium — downstream modules need recompile; verify no transitive android dep |
| 1.3 | **Standardize location representation** — Define `E7` conversion utilities in `stats-api` (`toE7()`, `fromE7()`) alongside documentation explaining the format. This doesn't change what modules use internally yet, but provides the canonical conversion path. | 1 new file in stats-api | Low |

**Milestone**: No more duplicate mapping code. `stats-api` is genuinely pure Kotlin.

---

### Phase 2 — Wire the New Engine to the UI (5–8 days)

**Goal**: `statistics` module reads from `stats-engine` output tables instead of recomputing everything.

This is the critical phase that closes the "two disconnected engines" gap.

| # | Task | Files | Risk |
|---|------|-------|------|
| 2.1 | **Implement `stats-data`** — This module was designed to be the persistence bridge. Populate it with: Room entities for `live_stats`, `daily_summary`, `inferred_trips`, `session_segments` (already in `AppDatabase`), plus repository interfaces exposing `Flow<>` queries. | ~8-12 new files | Medium — new code but API already designed |
| 2.2 | **Add `stats-data` dependency to `statistics`** — Replace the `statistics` module's raw `AppDatabase` queries with `stats-data` repository calls where the new tables have equivalent data. Start with the highest-value replacements: session distance, speed, steps, duration. | ~5-8 consumer files | High — this is the integration point; feature-flag the switchover |
| 2.3 | **Dual-read validation** — During transition, have `statistics` compute both old and new values, log discrepancies, report to `ReporterFacade`. This catches threshold disagreements and conversion bugs before users see different numbers. | Temporary instrumentation | Medium — must remove before release |
| 2.4 | **Deprecate `StatsDatabase`** — Once `statistics` reads from `stats-data` for all current stats, mark `StatsDatabase` (the 1-table Moshi cache) as deprecated. The Moshi serialization cache was needed because recomputing from raw data is expensive; now we have streaming aggregation. | statistics/database/ | Low — deprecation only, not deletion yet |

**Milestone**: Users see stats computed by `stats-engine` pipeline. One truth, one path.

---

### Phase 3 — Remove Dead Weight (1–2 days)

**Goal**: Reduce maintenance surface area.

| # | Task | Notes | Risk |
|---|------|-------|------|
| 3.1 | **Delete `stats-data` skeleton if replaced** — If Phase 2 fully populates `stats-data`, the original empty module is replaced. If we chose to put repositories elsewhere, delete the empty module from `settings.gradle.kts`. | Either populate or delete, don't leave empty | Zero |
| 3.2 | **Audit achievement code reachability** — The achievement types in `stats-api` + evaluator/catalog in `stats-engine` + DAO/entity in `sbase` + card in `game` form a complete vertical. Verify whether any code path actually triggers `AchievementEvaluator`. If no caller exists, mark with `@Deprecated("Planned feature — not yet wired")` but keep code; it's well-tested and designed. | ~6 files — annotation only | Zero |
| 3.3 | **Remove truly dead `StatsDatabase`** after Phase 2 validation period (at least one release cycle with dual-read validation clean). | statistics/database/ | Low — guarded by prior phase |

**Milestone**: No phantom modules. All live code is reachable.

---

### Phase 4 — Architectural Hardening (3–5 days)

**Goal**: Close remaining gaps flagged by the review.

| # | Task | Files | Risk |
|---|------|-------|------|
| 4.1 | **StreamingAggregator crash recovery** — On `onEnable`, check `live_stats` table for a row from the current day. If present and `lastUpdatedMs` is recent (< 5 min), seed the aggregator from it instead of starting fresh. This turns the 30-second flush into a 30-second recovery window. | `StreamingAggregatorWriter` | Medium — edge cases around day boundaries |
| 4.2 | **Extract interfaces for engine classes** — `TransportModeClassifier`, `StreamingAggregator`, `SessionSegmentDetector` are concrete. Extract interfaces in `stats-api` for the ones that cross module boundaries (classifier, segment detector). The aggregator, used only within `tracker`, can stay concrete. | 2 interfaces in stats-api, 2 impl annotations in stats-engine | Low |
| 4.3 | **Decouple `TripEnricher` from `SessionSegmentDetector`** — `TripEnricher.enrich()` calls `SessionSegmentDetector.approximateDistanceE7()`. Extract the distance function to a standalone utility in `stats-engine`. | 2 files | Low |
| 4.4 | **Coordinate representation convergence** — Migrate `statistics` module's ×10⁶ doubles to E7 integers or standard doubles. Don't change `tracker`'s raw doubles (they come from Android API). Document the canonical formats: raw doubles at boundary, E7 for storage/computation. | statistics data source files | Medium |

**Milestone**: Engine is recoverable, testable via DI, and structurally decoupled.

---

### Phase 5 — Legacy UI Migration (Ongoing, per-screen)

**Goal**: Migrate `statistics` screens to Compose, consuming `stats-data` repositories.

This is incremental by definition. Each screen is independent:

| Screen | Depends On | Effort |
|--------|-----------|--------|
| Session list | `stats-data` trip repository | 3–5 days |
| Trip detail | `stats-data` trip + segments | 3–5 days |
| Daily summary | `stats-data` daily summary | 2–3 days |
| Charts (speed/altitude/elevation) | `stats-data` + Compose Canvas | 5–8 days |

**Milestone**: `statistics` becomes a thin Compose UI layer, no business logic.

---

## B. Effort & Risk Summary

| Phase | Effort | Risk | Can Ship Independently? |
|-------|--------|------|------------------------|
| 0 — Safety Net | 1–2 days | Low | ✅ Yes |
| 1 — Deduplicate | 2–3 days | Low–Medium | ✅ Yes |
| 2 — Wire New Engine | 5–8 days | **High** | ✅ Yes (feature-flagged) |
| 3 — Remove Dead Weight | 1–2 days | Low | ✅ Yes (after Phase 2) |
| 4 — Hardening | 3–5 days | Medium | ✅ Yes |
| 5 — UI Migration | 13–21 days | Medium | ✅ Per-screen |

**Total estimated effort**: 25–41 developer-days across all phases.
**Critical path**: Phase 0 → Phase 1 → Phase 2. Phases 3–5 can partially parallelize.

---

## C. Preserve / Refactor / Delete

### Preserve (these are good — don't touch)

- **`stats-api` type system** — `SegmentSignal`, `SegmentEvent`, `AggregatorSnapshot`, `PolicyTier`,
  `TripState`, `TransportMode`, `DetectedActivityType`. Well-designed, well-documented, good KDoc.
  16 files of pure contracts.

- **`stats-engine` algorithms + tests** — `SessionSegmentDetector` (5-state machine),
  `TransportModeClassifier` (rule-based scoring), `StreamingAggregator` (streaming stats),
  `MovementConfidenceAccumulator` (hysteresis-based escalation), `CellDiscoveryEngine` (S2 cells).
  17 test files with good coverage.

- **`tracker` bridge components** — `SessionSegmentWriter`, `StreamingAggregatorWriter`,
  `ExplorationWriter`, `PolicyTierMapper`. These already feed data from the tracker pipeline
  into stats-engine. They work. They need minor fixes (speed validation, mapping consolidation)
  but the architecture is correct.

- **`statistics` producer/consumer pipeline architecture** — The dependency-graph execution model
  (`StatisticDataManager` with topological sort) is actually well-designed. What needs to change
  is the *data source*, not the *execution model*. Consumers should read from `stats-data`
  repositories instead of raw `AppDatabase` queries.

- **Achievement type definitions** — `AchievementCategory`, `AchievementTier`,
  `AchievementDefinition`, `AchievementSnapshot` in `stats-api` are clean, tested, and planned
  for the gamification feature. Keep them.

- **Zero circular dependencies** — This is hard to achieve in a 17-module project and easy to break.
  Guard it.

### Refactor

- **Activity type mapping** → consolidate to `DetectedActivityType` companion in `stats-api`
- **Speed thresholds** → consolidate to shared constants in `stats-api`
- **`stats-api` build** → pure Kotlin (`java-library`) instead of `android.library`
- **`statistics` data sources** → swap from raw `AppDatabase` to `stats-data` repositories
- **`StatsDatabase`** → deprecate, then remove after `stats-data` integration validated
- **`TripEnricher`↔`SessionSegmentDetector` coupling** → extract distance utility
- **`StreamingAggregator`** → add seed-from-DB recovery path
- **Location representations** → standardize conversions via shared E7 utilities

### Delete

- **`stats-data` empty module** — *Only if* we populate it in Phase 2. If we decide repositories
  go elsewhere, delete the skeleton. Don't leave ghost modules.
- **`StatsDatabase`** (1-table Moshi cache in `statistics/database/`) — After Phase 2 validation
  period confirms stats-data repositories produce identical results.
- **Duplicate `mapActivityTypeToInt` copies** — After Phase 1 consolidation.
- **Tracker-local speed threshold constants** — After Phase 0 moves them to `stats-api`.

---

## D. Honest Downsides of the Incremental Approach

### 1. Prolonged inconsistency window
During Phases 0–2, the system has *two active computation paths*. Users won't see wrong data
(the old path still works), but the new path writes to tables nobody reads. This wastes
battery and I/O until Phase 2 completes. It's been in this state for a while already, so
the cost is accepted but not free.

### 2. Dual-read validation is noisy
Phase 2.3's dual-read will surface discrepancies caused by the speed threshold contradictions,
different distance computation methods (Haversine vs. Vincenty vs. `Location.distanceBetween`),
and floating-point differences between E7 and ×10⁶ representations. Investigating and resolving
these takes time that wouldn't be needed in a clean rewrite.

### 3. The `statistics` module accumulates tech debt
Each incremental swap (from raw query → repository) leaves the module in a hybrid state.
Some consumers use the new path, some use the old. This is manageable with feature flags but
increases cognitive load for contributors during the transition.

### 4. Achievement system remains in limbo
The incremental plan preserves the achievement code (it's well-written) but doesn't wire it
to a UI. It stays "ready but unused" until the gamification feature is prioritized. A rewrite
could scope it out entirely and reintroduce it cleanly later.

### 5. Two databases persist longer
`StatsDatabase` (1-table cache) and the new `stats-data` tables in `AppDatabase` coexist
through Phases 0–3. This is confusing for new contributors. Deletion happens in Phase 3
but only after a validation period.

### 6. No "big bang" quality improvement
Users won't notice anything until Phase 2 completes and Phase 5 starts delivering new UI.
The intermediate phases are pure infrastructure. This is fine for code health but doesn't
generate visible progress for stakeholders.

---

## E. When to Recommend a Rewrite Instead

A rewrite of the stats subsystem (not the whole app) would be justified if **any two** of these
conditions become true:

### 1. The `statistics` module's consumer/producer pipeline can't adapt
If swapping data sources in Phase 2 reveals that the `StatisticDataManager` topological-sort
execution model is fundamentally incompatible with `Flow`-based repositories (e.g., it assumes
synchronous data availability, can't handle suspending producers, or the Moshi serialization
cache is load-bearing in ways we can't replace), then the pipeline needs replacement, not repair.

### 2. Schema migration becomes intractable
The `AppDatabase` is at version 17 with 27 entities. If adding the `stats-data` repository
layer requires schema changes that conflict with existing migrations, or if the `StatsDatabase`
cache contains user data we can't safely discard (it shouldn't, but verify), migration cost
may exceed rewrite cost.

### 3. The speed/distance discrepancy investigation reveals semantic incompatibility
If dual-read validation (Phase 2.3) shows that the old and new pipelines disagree by >10%
on core metrics (total distance, session duration, trip boundaries), the data models may be
semantically incompatible — not just using different thresholds but measuring fundamentally
different things. In that case, trying to make them agree is harder than picking one and
rebuilding the UI around it.

### 4. The `statistics` module exceeds 70+ files with increasing coupling
At ~70 source files, `statistics` is already the largest module. If refactoring reveals
hidden coupling between consumers, producers, and the `StatsDatabase` cache that prevents
individual component swapping, the module may need to be split or replaced rather than evolved.

### 5. The team decides to ship the new stats UI as a separate feature
If product strategy shifts to "new stats experience" as a user-visible feature (not just
infrastructure migration), a dedicated `stats-ui` module built from scratch on `stats-data`
repositories would be cleaner than migrating the existing `statistics` module screen by screen.
The old module could then be removed wholesale once the new one is complete.

---

## Summary: Why Evolution Wins (For Now)

| Factor | Evolution | Rewrite |
|--------|-----------|---------|
| **Data safety** | Existing data paths work; we add, not replace | Must re-validate all data paths |
| **Risk** | Phased, can stop/revert at any phase boundary | All-or-nothing for user-facing stats |
| **stats-engine investment** | Fully leveraged — it's already deployed and writing data | Same — both approaches use it |
| **stats-api investment** | Fully leveraged | Same |
| **statistics module** | ~30% of code needs to change (data sources) | ~100% rewrite |
| **User disruption** | Zero until Phase 5 (then incremental) | Potential data/UX discontinuity |
| **Timeline to value** | Phase 0 ships today, Phase 2 in ~2 weeks | 4–6 weeks minimum before anything ships |
| **Team knowledge** | Works with familiar code | Requires re-learning new patterns |

The new stats-engine and stats-api modules are *good*. They don't need a rewrite.
The tracker bridge is *working*. It needs minor fixes.
The statistics module needs its *data sources* swapped, not its architecture replaced.
The incremental plan does this safely, reversibly, and with continuous validation.

**Recommend: Proceed with incremental evolution, starting Phase 0 immediately.**
Re-evaluate at Phase 2.3 (dual-read validation results) for rewrite trigger conditions.
