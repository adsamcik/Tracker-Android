# Windowed discovery streak source slice

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** No compilation, tests, lint,
schema checks, device or CI gates were executed. No Git mutation or integration.

Base: `9d48e176b050bdc88b8c2ff68fcd6c11b950f49f`.
Exact seven-path selection, actual/snapshot hashes and patch hash are recorded in
`PERIOD_STREAK_FILES.json`; four tracked deltas are in `PERIOD_STREAK_TRACKED.patch`.
The three new source files and all selected versions are preserved under
`period-streak-selection/after`. These seven paths were clean or absent at task
start; no other mixed source draft is selected by this packet.

## Approved scope and implementation

The user approved counting discovery day/week streaks only inside the selected
period, with all-time retaining its existing complete streak. The implementation
uses existing first-discovery evidence, rather than seasonal visit/revisit evidence:

- `ExplorationCellDao.discoveryTimestampsBetween` reads distinct UTC timestamps at
  level 14 in the exact closed `TimeWindow` interval. Its existing
  `(level, first_discovered_at)` index supports the metadata-only read; no raw
  Location payload or new storage is queried.
- `DefaultWindowedMetricsProvider` routes Interval and Rolling discovery streaks
  to this read. Neither path reads the lifetime streak as a substitute.
- ZoneId and locale are captured once before suspension. Local-day membership
  follows `ExplorationStreakTracker`; locale week starts follow the existing
  `stepsCalendarAuthority` convention. A partial boundary day/week can qualify
  only through a discovery whose timestamp is inside the requested interval.
- `longestDiscoveryPeriodStreak` deduplicates local periods, sorts late-arriving
  evidence, and finds the longest consecutive run. It handles week-year changes
  without comparing year-relative week numbers or assuming 24-hour local days.
- The read has a 65,536-distinct-timestamp ceiling plus one lookahead row. A
  lookahead rejects the number using existing `StatsError.ValidationError`.
  Failed storage returns `DatabaseError`; cancellation is rethrown. Conversion
  uses the existing injected `DispatchersProvider.default`, with cancellation
  checks before and after the bounded calculation. No provider is activated.
- All-time behavior is unchanged. No seasonal or logical-distance behavior,
  public API shape, schema, dependency, or screen is changed.

Existing composition: `StatsDataModule.bindWindowedMetricsProvider` constructs
the injectable provider; `app/.../di/InfrastructureModule.kt:119` already supplies
`DispatchersProvider`. The existing direct unit-test constructor and new Room
fixture supply the existing test dispatcher abstraction.

## Authored source cases, not executed results

27 new cases and one adjusted existing all-time case:

- Three actual ExplorationCellDao cases: exact closed bounds/source level and
  equal timestamps; stable chronological finite reads; revisits are not new
  discoveries and retention/full-clear remove discovery evidence.
- Four actual Room DAO-to-windowed-provider cases: month boundary versus lifetime
  best; partial-day/exact-boundary instants; weekly evidence excluding old revisits
  and different levels; late insertion and retention/deletion changing the result.
- Ten pure calendar cases: empty/multiple/late discoveries, separated runs,
  short/long DST days, month/year/epoch boundaries, UTC versus local days,
  locale week starts, week-year continuity and a missing week.
- Ten provider cases: bounded daily/weekly/Rolling routing, true empty evidence,
  exact ceiling and lookahead exhaustion, storage failure/cancellation, and
  zone/locale changes inside the DAO call cannot change the captured calendar.
- The former interval-to-lifetime fallback test now explicitly exercises
  Cumulative, preserving the existing 17-day all-time result.

## Required remaining work outside this slice

This backend calculation does not establish complete exploration collection or
correction coverage. The existing producer suppresses same-process revisits;
background/imported Location discovery fanout, source correction/deletion rebuild,
retention treatment, and all-time weekly writer remain separate unresolved chain
items in `PERIOD_EXPLORATION_SOURCE_TRACE.md`. No new durable visit lookup has been
approved by this packet. Seasonal visits/revisits and complete logical-distance
period readers are separate work. Existing product callers/screens still need
their own period wiring review; this slice makes no screen-completion claim.

The finite timestamp limit can reject a particularly dense or broad discovery
window. It never reports a truncated streak as complete. Query and calculation
performance, Hilt/Room generation, test outcomes and on-device behavior are
unvalidated and belong to the later convergence gates.
