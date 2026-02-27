# Database Consolidation Analysis

**Date**: 2025-07-15
**Branch**: `refactor/modernisation`
**Todo**: `5b-database-consolidation`

---

## Current State

The project has 7 Room databases. This analysis focuses on whether `StatsDatabase` and `PointsDatabase` should merge into `AppDatabase`.

| Database | Module | Version | Entities | Migrations | Purpose |
|----------|--------|---------|----------|------------|---------|
| **AppDatabase** | sbase | v11 | 32 | 9 migrations | Main data store |
| **StatsDatabase** | statistics | v1 | 1 (`CacheStatData`) | None | Stats cache |
| **PointsDatabase** | points | v1 | 1 (`PointsAwarded`) | None | Points ledger |
| **LogDatabase** | logger | v2 | 2 | Destructive | Debug logs |
| ChallengeDatabase | game | — | — | — | Challenges |
| PreferenceDatabase | sbase | — | — | — | Preferences |
| DebugDatabase | sbase | — | — | — | Debug data |

---

## StatsDatabase Analysis

### Entity: `CacheStatData`
- Composite PK: `(session_id, provider_id)`
- Single `value: String` column storing serialized stat results
- `session_id` is a **logical foreign key** to `TrackerSession.id` in AppDatabase (no `@ForeignKey` annotation)

### DAO: `StatsCacheDao`
- `upsert(obj)` / `upsert(list)` — insert-or-update pattern
- `getAllForSession(sessionId)` — fetch all cached stats for a session

### Usage: **Zero consumers**
- In the `refactor/modernisation` branch, `StatsDatabase` is **defined but never called**.
- No file imports or invokes `StatsDatabase.database()`.
- The new sessionless architecture appears to have superseded it with `DailySummaryEntity` and `LiveStatsEntity` in AppDatabase.

### Verdict: **REMOVE, do not merge**
`StatsDatabase` is dead code on this branch. The new stats pipeline (DailySummary, LiveStats, DomainEvents) already lives in AppDatabase. Merging `CacheStatData` would add a legacy entity to AppDatabase for no benefit. The correct action is to **delete `StatsDatabase`, `CacheStatData`, and `StatsCacheDao`** as part of legacy cleanup.

---

## PointsDatabase Analysis

### Entity: `PointsAwarded`
- Auto-generated `id: Int` PK
- `time: Long` — timestamp of award
- `value: Points` (wrapper around `Double`)
- `source: AwardSource` — enum-like: SESSION, CHALLENGE, GOAL
- Custom `PointsDataConverters` TypeConverter

### DAO: `PointsAwardedDao`
- `countBetween(from, to): Int` — sum points in time range
- `countBetweenFlow(from, to): Flow<Int>` — reactive version
- Inherits `BaseDao` (insert, update, delete)

### Usage: 3 consumers
1. **`DefaultGameRepository`** (`game` module) — lazy DAO access for point queries
2. **`GoalTracker`** (`game` module) — reads points for goal evaluation
3. **`PointsWorker`** (`points` module) — writes awarded points

### Cross-references: **None**
- `PointsAwarded` has no FK to any AppDatabase entity.
- Correlation is purely temporal (`time` field), not relational.
- Points are awarded independently and queried by time range only.

---

## Consolidation Trade-off Matrix

| Factor | Merge into AppDatabase | Keep Separate |
|--------|----------------------|---------------|
| **Connection overhead** | ✅ One fewer SQLite connection | ❌ Extra open handle |
| **Cross-DB transactions** | ✅ Atomic with session data | ❌ No atomicity across DBs |
| **Module isolation** | ❌ `points` module needs `sbase` dependency | ✅ Self-contained module |
| **Independent versioning** | ❌ AppDatabase migration complexity +1 | ✅ Can version independently |
| **Destructive migration** | ❌ Cannot wipe points without wiping all data | ✅ Can reset independently |
| **Schema complexity** | ❌ AppDatabase already has 32 entities | ✅ Keeps main DB focused |
| **Build coupling** | ❌ Entity changes trigger `sbase` recompilation | ✅ Isolated compilation |

---

## Recommendation: **KEEP SEPARATE** (with cleanup)

### Rationale

1. **StatsDatabase → DELETE** (not merge)
   - Zero consumers on the modernisation branch. Dead code.
   - Functionality replaced by `DailySummaryEntity`, `LiveStatsEntity`, and the domain events pipeline already in AppDatabase.
   - Action: Remove `StatsDatabase.kt`, `CacheStatData.kt`, `StatsCacheDao.kt`.

2. **PointsDatabase → KEEP SEPARATE**
   - No foreign key relationships with AppDatabase — merging gains no transactional benefit.
   - AppDatabase is already large (32 entities, 9 migrations). Adding points increases migration burden for zero relational gain.
   - The `points` module is architecturally independent. Merging would create an unnecessary dependency from `points` → `sbase` for the entity definition, or require moving `PointsAwarded` into `sbase` (violating module boundaries).
   - Points data is append-only and query-by-time-range — no joins with location/session tables needed.
   - One extra SQLite connection is negligible overhead on modern Android (WAL mode, shared cache).

3. **LogDatabase → KEEP SEPARATE** (not in scope, but confirmed)
   - Destructive migration is a feature, not a limitation. Must remain isolated.

### Future Considerations
- If `PointsAwarded` ever needs FK relationships to sessions/trips in AppDatabase (e.g., `sessionId` column), re-evaluate merging at that point.
- Consider whether `ChallengeDatabase` (also in `game` module) should consolidate with `PointsDatabase` into a single "game database" — both are small, same module, and could share a connection.
