# Achievement Engine Design — Architecture Critique

> **Reviewer:** Performance & Architecture (Opus pass)
> **Date:** 2025-07-18
> **Scope:** Issues the GPT battery/performance review MISSED
> **Input:** `ACHIEVEMENT_ENGINE_DESIGN.md`, actual source code, Room schema v23

---

## 🔴 CRITICAL Issues

### C1. `upsert()` with REPLACE + autoGenerate PK causes row-ID churn and silent data loss

**Location:** `AchievementProgressDao.upsert()` + `AchievementProgressEntity`

**Problem:** The DAO uses `@Insert(onConflict = OnConflictStrategy.REPLACE)`, but the entity has `@PrimaryKey(autoGenerate = true)` on `id` with a separate `UNIQUE INDEX` on `achievement_id`. When a conflict fires on the `achievement_id` unique index, SQLite's `REPLACE` **deletes the old row entirely** then **inserts a new row with a new auto-incremented `id`**.

Consequences:
1. **Primary key changes on every update** — any future FK references to `achievement_progress.id` would break silently.
2. **Room's `InvalidationTracker` sees a DELETE + INSERT**, causing `getAllFlow()` to emit a full-list diff on every single progress update. For `TrophyCaseScreen` observing 18 achievements, this means 18 unnecessary recompositions per evaluation cycle.
3. **The `unlocked_at` timestamp is preserved only if the caller remembers to copy it** — if a caller constructs a new entity with default `unlockedAt = null`, the unlock timestamp is silently erased.

**Fix:** Replace with `@Upsert` (Room 2.5+) or use a raw `@Query` with `INSERT OR REPLACE` that explicitly preserves the `id`:
```kotlin
@Query("""
    INSERT INTO achievement_progress (achievement_id, current_value, target_value, tier, unlocked_at, updated_at)
    VALUES (:achievementId, :currentValue, :targetValue, :tier, :unlockedAt, :updatedAt)
    ON CONFLICT(achievement_id) DO UPDATE SET
        current_value = :currentValue,
        target_value = :targetValue,
        tier = :tier,
        unlocked_at = COALESCE(:unlockedAt, unlocked_at),
        updated_at = :updatedAt
""")
suspend fun upsert(achievementId: String, currentValue: Long, targetValue: Long, tier: Int, unlockedAt: Long?, updatedAt: Long)
```
The `COALESCE` on `unlocked_at` prevents accidental erasure of unlock timestamps.

---

### C2. Double-unlock: real-time processor and WorkManager worker fire the same achievement event

**Location:** `AchievementProcessor.onStop()` → `onFlush()` + proposed `AchievementEvaluationWorker`

**Problem:** When a session ends:
1. `ProcessorPipeline.stop()` calls `AchievementProcessor.onStop()` → `onFlush()` → emits `DomainEvent.AchievementUnlocked` with the final metric values.
2. These events are persisted to `domain_event` table and consumed by `GameDomainEventConsumer` → fires system notification.
3. The `SessionEnded` event **also** triggers `AchievementEvaluationWorker` (WorkManager).
4. The worker loads `previousProgress` from Room — but **the processor never wrote to Room**. The processor only tracks progress in its in-memory `previousProgress` map.
5. The worker sees the OLD Room state, collects the SAME fresh metrics, detects the SAME tier transition, and emits a **second** `AchievementUnlocked` event.

**Result:** User gets duplicate notifications for every achievement unlocked during tracking. XP points are awarded twice if the consumer awards on each event.

**Fix:** Either:
- **(A)** Have `AchievementProcessor.onFlush()` persist progress to Room before emitting events (adds I/O to hot path), OR
- **(B)** Make the WorkManager worker the **sole** emitter of unlock events; have the processor only track incremental metrics without emitting unlock events, OR
- **(C)** Add an idempotency guard: the worker checks `unlocked_at IS NOT NULL AND tier >= newTier` before emitting.

Option (C) is simplest and safest:
```kotlin
// In AchievementEvaluationWorker:
if (dbProgress.tier >= newTierOrdinal && dbProgress.unlockedAt != null) {
    // Already unlocked at this tier or higher — skip
    continue
}
```

---

### C3. `collect()` is a no-op optimization — executes ALL 14 DAO queries regardless

**Location:** `DefaultAchievementMetricsProvider.collect(metrics)` (design doc §3.2, line 169–172)

**Problem:** The "selective" collection method is:
```kotlin
override suspend fun collect(metrics: Set<String>): Map<String, Long> {
    return collectAll().filterKeys { it in metrics }
}
```
This calls `collectAll()` which executes **all 14 DAO queries** (7 of which are O(n) scans), then discards the ones not in `metrics`. The `IncrementalMetricsCache` dirty-tracking is built on the assumption that `collect()` is selective — but it's not.

**Impact:** For the power user scenario (1000+ daily_summary rows, 50K exploration cells), marking one metric dirty still triggers 14 queries including `SUM(total_distance_m)` over 1000 rows and `COUNT DISTINCT` over 50K cells. The dirty-tracking cache provides zero benefit.

**Fix:** Implement `collect()` as a proper switch:
```kotlin
override suspend fun collect(metrics: Set<String>): Map<String, Long> = buildMap {
    if ("cells_discovered" in metrics) put("cells_discovered", explorationCellDao.countAtLevel(DEFAULT_LEVEL).toLong())
    if ("total_distance_km" in metrics) put("total_distance_km", (dailySummaryDao.sumTotalDistance() / 1000.0).toLong())
    // ... etc for each metric
}
```

---

### C4. `AchievementProcessor` state is not thread-safe against concurrent flush/stop

**Location:** `AchievementProcessor.previousProgress` + `pendingEvents` (mutable collections)

**Problem:** In `ProcessorPipeline`, flushes run **outside the mutex** (line 170–182: "Slow path: flush outside mutex so signal delivery isn't blocked by I/O"). If `stop()` is called while an `onSignal()`-triggered flush is in progress:
- `stop()` acquires the mutex, sets `isRunning = false`, then calls `processor.onStop()` — which delegates to `onFlush()`.
- The in-flight `onFlush()` from the signal path is still running concurrently.
- Both read and mutate `previousProgress` (a `HashMap`) and `pendingEvents` (an `ArrayList`) simultaneously → **data race, possible ConcurrentModificationException or lost events**.

**Fix:** Add a `Mutex` inside `AchievementProcessor` to serialize `onFlush()` calls:
```kotlin
private val flushMutex = kotlinx.coroutines.sync.Mutex()

override suspend fun onFlush(): List<DomainEvent> = flushMutex.withLock {
    // ... existing logic
}
```

---

### C5. `session_segment.primary_activity` has no index — `countByPrimaryMode()` does full table scan

**Location:** Room schema v23, `session_segment` table indices

**Problem:** The proposed queries `countDistinctActivities()` and `countByPrimaryMode(modeId)` filter on `primary_activity`. Current indices are:
- `idx_session_segment_time_range` → `(start_time_ms, end_time_ms)`
- `idx_session_segment_source` → `(source)`

**No index on `primary_activity`.** For a power user with 3 years of data, `session_segment` could have 50K+ rows. `COUNT(*) WHERE primary_activity = X` would scan the entire table.

**Fix:** Add a migration with:
```sql
CREATE INDEX IF NOT EXISTS idx_session_segment_primary_activity ON session_segment (primary_activity);
```

---

## 🟡 MODERATE Issues

### M1. `observeRecent()` returns the OLDEST items, not the most recent

**Location:** `DefaultAchievementRepository.observeRecent()` (line 28–32)

**Problem:**
```kotlin
override fun observeRecent(): Flow<List<AchievementProgressData>> {
    return observeAll().map { all ->
        all.filter { it.isUnlocked }.takeLast(10)
    }
}
```
`observeAll()` delegates to `getAllFlow()` which returns `ORDER BY updated_at DESC` — newest first. `.takeLast(10)` takes the LAST 10 items in the list, i.e., the **oldest updated** items. The dashboard "recent unlocks" widget would show the user's earliest achievements, not their latest.

**Fix:** Change to `.take(10)` or better, sort by `unlocked_at DESC` in the query.

---

### M2. `CelebrationBus` `DROP_OLDEST` silently loses unlocks during GPX import

**Location:** `AchievementCelebrationBus` (design doc §3.4, line 222)

**Problem:** `extraBufferCapacity = 10` with `BufferOverflow.DROP_OLDEST`. During the GPX import edge case, all 18 achievements could unlock near-simultaneously. If the UI collector lags even briefly, 8+ celebrations are silently dropped. The user never sees the in-app animation for those achievements.

System notifications still fire (via `GameDomainEventConsumer`), but the in-app celebration experience is degraded.

**Fix:** Use `BufferOverflow.SUSPEND` and process celebrations from a queue, or increase buffer to 64 (matching max possible unlock events: 18 achievements × 4 tiers = 72, though practically capped at 18 since they evaluate from current state).

---

### M3. `IncrementalMetricsCache` has a TOCTOU race on dirty flags

**Location:** `IncrementalMetricsCache.getMetrics()` (design doc §6.3, line 436–438)

**Problem:**
```kotlin
val dirty = dirtyMetrics.toSet()
dirtyMetrics.clear()                    // ← window opens here
val fresh = fullProvider.collect(dirty)  // ← slow I/O
cache.putAll(fresh)                     // ← window closes here
```
If `markDirty("total_steps")` is called by a DomainEvent handler between `clear()` and `putAll()`, the flag is lost. The next `getMetrics(dirtyOnly = true)` returns stale cached `total_steps`. The user's step achievement progress appears frozen until the next full evaluation.

**Fix:** Use `getAndSet` pattern:
```kotlin
val dirty = ConcurrentHashMap.newKeySet<String>()
// swap atomically:
val snapshot = dirty.toSet().also { dirty.removeAll(it) }
// Now only remove what we're about to fetch, not what arrived during fetch
```
Or better: protect with a `Mutex` since the whole method is `suspend` anyway.

---

### M4. `exploration_cell` has no "area" column — `countDistinctAreas()` is undefined

**Location:** Design doc §4.1 references `ExplorationCellDao.countDistinctAreas(level)`, schema v23 `exploration_cell` table

**Problem:** The `exploration_cell` table has columns: `cell_token`, `level`, `quality`, `first_discovered_at`, `last_visited_at`, `visit_count`, `season_bitmask`, `center_lat_e7`, `center_lon_e7`, `created_at`. There is **no `area` column**. The design references `unique_areas` as a metric but never defines what an "area" is or how to derive it from the schema.

Possible implementations (cell_token prefix? geohash bucket? admin boundary?) have wildly different performance characteristics and require different indices.

**Fix:** Define "area" explicitly. If it's derived from `cell_token` prefix (e.g., S2 cell parent at a coarser level), document this and add:
```sql
-- Derived: area = substr(cell_token, 1, N) for parent-level grouping
SELECT COUNT(DISTINCT substr(cell_token, 1, :prefixLen)) FROM exploration_cell WHERE level = :level
```
Note this cannot use any existing index for the `DISTINCT` — it will scan all cells at the given level.

---

### M5. `ExportLogDao.countTotal()` doesn't exist and needs a status filter

**Location:** `ExportLogDao` (actual DAO has no `countTotal()`), design doc §4.6

**Problem:** Two issues:
1. `countTotal()` doesn't exist in the DAO — it's listed as Phase 1 work but easy to forget the status filter.
2. The `export_log` table has a `status` column (`TEXT NOT NULL`). A naive `SELECT COUNT(*) FROM export_log` would count failed exports toward the "Data Keeper" achievement, allowing a user to "earn" it by having a failed export attempt.

**Fix:** The query should be:
```kotlin
@Query("SELECT COUNT(*) FROM export_log WHERE status = 'SUCCESS'")
suspend fun countSuccessful(): Long
```

---

### M6. Notification ID hash collision risk

**Location:** `GameDomainEventConsumer.NotificationsIds` (line 197–203)

**Problem:** Notification IDs are computed as `"${event.achievementId}:${event.timestampMs.raw}:unlocked".hashCode()`. Java `String.hashCode()` returns `Int` (32-bit), so collisions are possible. More importantly, if the double-unlock bug (C2) fires two events for the same achievement with the same timestamp, the second notification silently replaces the first (same hash = same notification ID). This masks the duplication bug — it looks like one notification but two XP awards still fire.

**Fix:** Use a stable sequential ID or include a random component. But first, fix C2 so duplicates don't occur.

---

## 🟢 LOW Issues

### L1. `AchievementTier` stored as `Int` ordinal — fragile across enum reordering

**Location:** `AchievementProgressEntity.tier: Int`

**Problem:** If `AchievementTier` enum entries are reordered (e.g., a new tier inserted between GOLD and DIAMOND), all persisted `tier` values become wrong. The restore code `AchievementTier.entries.getOrNull(entity.tier)` would map to the wrong tier.

**Fix:** Store as `String` (the tier name) or add a `@TypeConverter` that maps by name, not ordinal.

---

### L2. `AchievementProgressEntity.currentValue` and `targetValue` are schema-enforced NOT NULL but entity defaults allow 0

**Location:** Schema v23 shows `current_value INTEGER NOT NULL`, entity defaults `currentValue: Long = 0`

**Problem:** For a new achievement that hasn't been evaluated yet, `currentValue = 0` and `targetValue` must be set by the caller. If a caller creates an entity without setting `targetValue`, it will be 0, making progress calculations (`current / target`) divide-by-zero or show 100% for a 0/0 case.

**Fix:** Validate `targetValue > 0` in the entity constructor or in the DAO's upsert query.

---

### L3. `DomainEvent.AchievementUnlocked.tier` is `String` — no deserialization guard

**Location:** `DomainEvent.AchievementUnlocked` (line 74–79 of DomainEvent.kt)

**Problem:** The `tier` field is `String` (set via `snap.currentTier!!.name`). The event is persisted as JSON in `domain_event.payload`. If the enum name changes between app versions, old events become unparseable during `getUnconsumed()`. Since the consumer just logs or notifies, this would cause a silent skip or crash depending on deserialization strategy.

**Fix:** Use a stable string constant rather than the enum's `.name` property, or add a `@SerialName` annotation.

---

### L4. Checkpoint/restore doesn't save `pendingEvents`

**Location:** `AchievementProcessor.checkpoint()` / `restore()` (line 82–107)

**Problem:** `checkpoint()` serializes only `previousProgress`. If the pipeline checkpoints mid-session with pending achievement events (between signal delivery and flush), those events are lost on restore.

**Impact:** Low in practice — `pendingEvents` is only populated during `onFlush()` and cleared at the end, so the window is narrow. But if `onFlush()` throws after adding events but before clearing, the events accumulate and would be lost on checkpoint.

---

## Summary Matrix

| ID | Severity | Category | Issue | Effort |
|----|----------|----------|-------|--------|
| C1 | 🔴 Critical | Data integrity | REPLACE + autoGenerate PK causes row churn, silent data loss | S |
| C2 | 🔴 Critical | Correctness | Double-unlock from processor + worker dual emission | M |
| C3 | 🔴 Critical | Performance | `collect()` runs all 14 queries — dirty cache is useless | S |
| C4 | 🔴 Critical | Thread safety | Concurrent flush/stop race on mutable HashMap/ArrayList | S |
| C5 | 🔴 Critical | Performance | Missing index on `primary_activity` — full table scan | S |
| M1 | 🟡 Moderate | Correctness | `observeRecent()` returns oldest, not newest | XS |
| M2 | 🟡 Moderate | UX | CelebrationBus drops unlocks during mass evaluation | S |
| M3 | 🟡 Moderate | Concurrency | TOCTOU race in dirty-flag cache | S |
| M4 | 🟡 Moderate | Design gap | "area" concept undefined — no column, no index | M |
| M5 | 🟡 Moderate | Correctness | `countTotal()` missing + needs status filter | XS |
| M6 | 🟡 Moderate | Correctness | Notification ID collision masks double-unlock | XS |
| L1 | 🟢 Low | Durability | Tier stored as ordinal — fragile across enum changes | S |
| L2 | 🟢 Low | Validation | `targetValue = 0` allows divide-by-zero in progress | XS |
| L3 | 🟢 Low | Durability | Event tier as String with no deserialization guard | XS |
| L4 | 🟢 Low | Correctness | Checkpoint doesn't save pendingEvents | XS |

---

## Stress Test Results

### Power User (3 years, 500K samples, 1000 daily_summary, 50K exploration_cells)

| Metric Query | Table | Rows Scanned | Index Used? | Verdict |
|-------------|-------|-------------|-------------|---------|
| `sumTotalDistance()` | daily_summary | ~1095 | ❌ Full scan (no agg index) | ⚠️ Acceptable |
| `sumTotalSteps()` | daily_summary | ~1095 | ❌ Full scan | ⚠️ Acceptable |
| `getBestDailySteps()` | daily_summary | ~1095 | ❌ Full scan (MAX) | ⚠️ Acceptable |
| `countAtLevel()` | exploration_cell | uses index | ✅ `index_exploration_cell_level` | ✅ OK |
| `countDistinctAreas()` | exploration_cell | ~50K | ❌ No area column | 🔴 Undefined |
| `getDistinctSeasonBitmasks()` | exploration_cell | ~50K | ❌ Full scan (DISTINCT) | ⚠️ Slow |
| `getLongestTripDistanceM()` | session_segment | ~50K+ | ❌ No index on distance_m | ⚠️ Slow |
| `countDistinctActivities()` | session_segment | ~50K+ | ❌ No index on primary_activity | 🔴 Full scan |
| `countByPrimaryMode(X)` | session_segment | ~50K+ | ❌ No index on primary_activity | 🔴 Full scan |

**Total worst-case per evaluation: ~200K rows scanned.** Acceptable if run once post-session in background, but the `collect()` bug (C3) means this runs on EVERY dirty-metric check, not selectively.

### GPX Import (2 years of data, mass unlock)

1. Import creates 1000+ daily_summary rows + 30K exploration cells in a transaction.
2. `SessionEnded` fires → WorkManager enqueues `AchievementEvaluationWorker`.
3. Worker calls `collectAll()` → 14 queries, ~150K rows scanned. This is fine.
4. Evaluator detects up to 18 achievements × 4 tiers = potentially 72 tier changes.
5. **Each change emits a `DomainEvent`** → 72 events persisted to `domain_event`.
6. `GameDomainEventConsumer` processes all 72 → 72 system notifications fire simultaneously.
7. `CelebrationBus` buffer (10) overflows → 62 in-app celebrations lost (M2).
8. If the real-time processor also ran during import (C2), events double → 144 notifications.

**Recommendation:** Batch evaluations during import — evaluate once at the end, not per-segment.
