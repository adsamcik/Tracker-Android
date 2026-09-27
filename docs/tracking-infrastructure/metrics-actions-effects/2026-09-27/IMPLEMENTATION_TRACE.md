# Metrics, actions and effects implementation trace

## Request and evidence boundary

Original user text:

> **Complete metrics, actions and effects.** Provide truthful summaries for all six sources, exact Steps totals, original Activity/Pressure bounds, supported actions and correction/deletion retraction.

All original task messages and approved decisions in `../../planning/2026-09-26-provider-freedom/TASK_CONTEXT.md` apply. Candidate: `codex/source-native-map-context-root`, based on `2c307247e6`. Main `dev/v10` remains `13f1a99871`.

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Only production/test-source authorship, static inspection/review and local checkpoints are allowed. No Gradle, compilation, executed tests, lint, Detekt, Room schema generation/drift, `git diff --check`, emulator/device/UI evaluator, battery, CI, release or rollout validation. No convergence freeze, provider/writer activation, new dev/v10 integration or push. Protected paths, original refs and excluded drafts remain preserved. Authored tests and independent static acceptance do not prove runtime behavior.

**The whole request remains incomplete.** This checkpoint repairs metric rejection and native recording-header mappings; it does not finish the shared summaries, selected actions, portable original intervals or non-Steps effects.

## Current authored repairs

1. `stats/data/.../repository/DefaultWindowedMetricsProvider.kt` rejects unknown metric identifiers through the existing typed `StatsError.ValidationError` path for cumulative, interval and rolling queries. It no longer fabricates confirmed zero. Reversed intervals and negative rolling duration are rejected before DAO reads. Existing Steps aliases still reject unqualified legacy queries. No changed metric identifiers, units or supported aggregation semantics.
2. `stats/api/.../repository/WindowedMetricsProvider.kt` documents rejection instead of the old unknown-metric zero. Six all-time-only metric branches still return cumulative values for period requests; this is a known unresolved gap, not accepted period evidence.
3. `stats/data/.../repository/ActivityHistoryComposer.kt` maps an independently source-qualified native logical entry to its stored original start and cutoff. Surviving physical segments and later drain completion cannot shorten/extend a valid closed header. STOPPING uses its cutoff; active/open entries remain MATERIALIZING/partial and keep existing observed-extent end, not an exact closed recording duration. Invalid native lifecycle start/cutoff fails through the existing provenance-failure contract. This does not solve a separately qualified uncertain interval display.
4. `PressureHistoryBatchReader.kt`, `PressureHistorySelector.kt`, `PressureHistoryReadModel.kt` and `PressureHistoryPublicMapper.kt` carry validated original closed Pressure headers in the existing bounded transactional snapshot. Bulk logical-ID queries are chunked; no per-row lookup or new table. Descriptive headers never qualify observations. Exact logical identity and valid terminal metadata are required. Independently authenticated windows, original per-window wall/elapsed bounds/zones, direct hPa summary, source qualification and recency remain unchanged.
5. `logical_tracking_session` already belongs to `TRACKING_HISTORY_INVALIDATION_TABLES`; both native mappings use the existing reader invalidations. Public header changes do not alter Activity/Pressure portable comparison/checksum identity, which is read independently.

Pressure missing/open/inconsistent headers still use the old physical extent in the current mandatory start/end public contract. Do not call that original time. `PortablePressureRoomReader` also still composes its export header from runs. Full Activity/Pressure portable header handling and collision/version compatibility need separate implementation; the wire format is not silently changed in this checkpoint.

## Six-source consumer trace

| Source | Existing source-qualified evidence | Remaining summary/action/effect gap |
| --- | --- | --- |
| Location | Native/legacy geographic readers and retained context seams exist; enrichment does not request Location. | Complete retained ownership/quality proof and historical permission/collection-off policy are still pending. `DefaultSessionStatsRepository` and achievement/window providers still read raw geographic/session projections. Shared range summary must respect exact ownership, gaps, coverage and origin. |
| Steps | `StepsNumericSummaryRepository`, retained metrics and Room readers return exact checked `Long` totals only for proven count-domain scope. Partial, baseline, stale and unverifiable evidence are nonnumeric; positive cross-midnight intervals are not prorated. | Keep this numeric authority in every summary/export consumer. Native Steps has no producer-issued shared history action selector. Imported Steps carries exact members but detail/action dispatch is unwired. Goal-progress Int capping is not an exact weekly total. |
| Wi-Fi | Native history exposes identity-free observed results/counts, units and qualification; opaque source-local selectors exist. | Shared summaries must use observed units and separately label legacy distinct BSSID counts. Source detail has read models but universal unavailable-action UI. Complete actual export/deletion dispatch with reauthentication. |
| Cell | Native history exposes identity-free observed results/counts, units and qualification; opaque source-local selectors exist. | Same separation from legacy distinct tower IDs and actual selected actions as Wi-Fi. No inferred geography or new reward from radio observations. |
| Activity | Captured source reader authenticates original windows, fragments, confidence/coverage and revision lineage. Imported mapper already preserves original portable entry bounds after filtering survivors. New native header repair is authored. | CONTROL and organization labels remain excluded. Full shared summaries, uncertain/open interval contract, fresh selected detail/action admission and portable header handling remain open. |
| Pressure | Source reader authenticates original windows, hPa quality/coverage and revision lineage. Imported mapper preserves original portable entry bounds. Closed native header repair is authored. | Preserve direct hPa values; no elevation/weather inference. Missing/open interval contract, shared summary/detail and exact logical/native/imported action selectors/dispatch remain open. Current portable native header remains run-derived. |

`SessionStatsSnapshot`/`DefaultSessionStatsRepository`/`SessionStatsUiFormatter` currently expose unconditional legacy duration, distance, physical trip count, Location rows and legacy radio identity counts. They do not provide qualified six-source scope/coverage/origin cards. Session duration, recording coverage and detected movement are different metrics. Organization counts/labels/overlaps are not capture counts or reward inputs. Do not extend the old numeric snapshot with fabricated Activity/Pressure zeros.

## Actions and retraction trace

- `SourceAwareHistoryPageEntry.actionTarget` deliberately marks native Steps and Pressure non-actionable. Their exact source-only Android deletion services do not establish an opaque logical/mixed selection token. Guessing a Trip/segment ID from a logical history key is not an implementation.
- `SourceHistoryDetailPresenter`/`SourceHistoryDetailRoute` exclude Steps and Pressure and show universal unavailable actions. `TripDetailSourcePresentation` disables non-Location actions. Existing source readers and lower-level native/imported exports/deletions are groundwork, not usable shared UI actions.
- Activity/Wi-Fi/Cell have producer selectors or declared-origin adapters, but complete action handling must freshly requalify epoch, evidence revision, full source membership and preview scope; independently authorized siblings survive. Activity's current local deletion lookup still uses a bounded historical scan, covered by the pending exact ownership lookup decision.
- Exact deletion must retain durable Removing / Removed / Retry outcome. No fake rollback, clipping an immutable aggregate, cascading from a guessed physical ID, or enabling GPX for non-geographic data.
- Steps deletion/correction already drives count-domain retractions and historical goal repair. `StepsGoalRewardProjector` applies monotonically revisioned points/XP and exact acknowledgement; zero receipts prevent stale work restoring erased/corrected rewards. `StepsGoalHistoricalReconciler` reuses persisted exact calendars and source digest/revision. Preserve these paths and prior confirmed legacy receipts.
- `PointsDomainEventConsumer` only schedules `PointsWorker` for physical `SessionEnded`. Worker scores raw physical distance/duration and deduplicates by time/source. `PlayerProgressionRepository.insertSessionXp` reads raw physical distance/duration and keys by segment ID. These session-derived paths do not prove logical source-qualified eligibility or correction/deletion retraction. Existing Steps GOAL receipts cannot be presented as having repaired SESSION receipts.
- General metric dirty tables still primarily describe legacy projections. Source correction/deletion must invalidate affected summaries/effects and cached payload by source scope/revision. No new Activity/Pressure/radio reward is approved or implemented.

## Authored checks, not executed

- `DefaultWindowedMetricsProviderTest`: unknown ID under all three window kinds, reversed interval, negative rolling duration, exact typed errors and no DAO invocation; existing qualified Steps rejection and supported queries retained.
- `ActivityHistoryComposerTest`: original closed start/cutoff vs surviving extent/drain; selected and recent entry mapping; negative start/reversed/missing cutoff; open active original start with MATERIALIZING/SESSION_ACTIVE; STOPPING original cutoff; windows/fragments/active-time unchanged.
- `PressureHistorySelectorTest`: actual Room bulk-header read after logical session update; original closed start/cutoff without changed windows/summary/qualification/recency; absent, unrelated, negative, reversed, missing, open and unsettled header maps do not invent original closed bounds or clear independent valid windows.
- Existing Steps accumulator/import/goal tests cover checked totals, overflow, positive straddles, stored-day authority, correction/deletion and stale projections. They were traced, not rerun; no fresh execution claim.

## Required pending decisions

Supplied AGENTS requires confirmation before changing design/data/UI/API contracts. Tool questions remain unanswered; elapsed time is not approval:

1. For longest trip, activity variety, seasons, streaks and export count: reject unsupported period queries with typed unavailable while retaining explicit All time values, or expand the result API to carry the actual period. Current number-only all-time fallback is still incorrect for a period-labelled consumer.
2. For missing/open/clock-inconsistent original Activity/Pressure recording bounds: explicitly disclose recording-time unavailability while retaining independently qualified observations/original windows, or exclude those records from summaries. Recommended separate interval state requires a small public API/UI change.
3. Earlier unified-history decisions: preserve historically authorized Location after collection-off/permission loss, and use a rebuildable exact Activity/Pressure local ownership index. Both remain pending and constrain complete history/metric/action assembly.

## Remaining implementation order

1. Close pending contracts; preserve original/corrected/wire identities and legacy confirmed receipts.
2. Finish qualified retained source range readers and shared summary result carrying actual scope, coverage, origin and failure per source; isolate unavailable siblings.
3. Wire Statistics/Dashboard/History/detail/widget/export consumers to those same meanings, exact Steps totals and original intervals. Author source-only, mixed, legacy/imported, missing/zero/partial and boundary tests.
4. Complete native Steps/Pressure producer-owned logical/mixed selectors; fresh action preflight, supported selected-format export and durable deletion UI for all sources. No provider demand from these readers.
5. Complete prospective source-qualified logical capture effects and exact correction/deletion invalidation/retraction, including stale delivery/process interruption; preserve historical confirmed receipts and organization no-effect.
6. Independent whole-scope source recheck, then the already-required single final convergence freeze and deferred validation batch when every production/test slice exists. This checkpoint does not authorize that gate.
