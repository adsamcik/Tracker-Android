# StatisticDataManager Pipeline Retirement

**Date:** October 10, 2025  
**Status:** Retired (Compose Migration)

## Decision

The `StatisticDataManager` class and its associated producer/consumer pipeline have been formally retired as part of the Compose migration. The system is no longer referenced anywhere in the codebase.

## Background

The original statistics architecture used a complex graph-based system:
- **StatisticDataManager**: Orchestrated data generation via dependency graph
- **Producers**: Generated intermediate data (LocationDataProducer, OptimizedAltitudeProducer, etc.)
- **Consumers**: Consumed producer outputs to generate statistics (DistanceConsumer, ElevationChartConsumer, etc.)
- **Caching**: Stored computed stats in StatsDatabase to avoid recomputation

This system was designed for a legacy detail view that showed per-session detailed statistics including charts and maps.

## Current Architecture

After the Compose migration, the statistics system was simplified:

### Session List (StatsRoute)
- Displays paginated list of sessions via `StatsViewModel`
- Uses Room's built-in `PagingSource` for efficient lazy loading
- No per-session detail computation needed

### Summary Statistics (Dialog-based)
- Overall summary: All-time aggregates
- Weekly summary: Last 7 days aggregates
- Data source: `SummaryGenerator.buildSummary()` / `buildSevenDaySummary()`
- Accessed via: `SessionRepository.getSummaryStats()` / `getWeeklyStats()`

### Key Simplification
The new system **only computes aggregates**, not per-session detail statistics. Users see:
1. List of sessions (id, date, duration, steps)
2. Overall/weekly summary dialogs (total distance, time, steps, etc.)

No session detail view → No need for the producer/consumer graph.

## What Was Removed (Conceptually)

While the code still exists in the repository, it is **unreachable**:
- `StatisticDataManager` (no instantiation anywhere)
- All `StatDataProducer` implementations (12 classes)
- All `StatDataConsumer` implementations (12 classes)
- `StatsDatabase` caching layer (never accessed)
- `CacheStatData` entity

## Migration Path

If per-session detail statistics are needed in the future:
1. **Option A (Simple):** Extend `SummaryGenerator` to accept a single session ID and compute on-demand without caching
2. **Option B (Reuse):** Resurrect `StatisticDataManager` selectively for detail-only routes
3. **Option C (Modern):** Build a Flow-based incremental stats pipeline with in-memory caching

Recommendation: **Option A** for simplicity, unless detail views become performance-critical.

## Cleanup Recommendations

Consider removing in a future cleanup pass:
- `statistics/data/source/` directory (StatisticDataManager + producers/consumers)
- `statistics/database/` directory (StatsDatabase + CacheStatData)
- `statistics/detail/` legacy structures (if no detail view is planned)
- Associated test files for removed components

**Justification:** Reduces maintenance burden, avoids confusion, prevents accidental usage.

## References

- Original system: `statistics/data/source/StatisticDataManager.kt`
- New system: `statistics/repository/DefaultSessionRepository.kt` → `SummaryGenerator`
- UI entry: `statistics/fragment/StatsRoute.kt`
