# Achievement Engine — Architecture Design

> **Status:** Proposal  
> **Date:** 2025-07-17  
> **Scope:** End-to-end achievement evaluation, persistence, celebration, and UI integration

---

## 1. Existing Infrastructure Audit

### What already exists

| Component | Location | Status |
|-----------|----------|--------|
| `AchievementDefinition` | `stats-api` | ✅ Clean data class with id, category, metric, tiers |
| `AchievementTier` | `stats-api` | ✅ BRONZE→DIAMOND enum with pointBonus |
| `AchievementCategory` | `stats-api` | ✅ 6 categories: EXPLORATION, DISTANCE, STEPS, STREAKS, MODES, MILESTONES |
| `AchievementSnapshot` | `stats-api` | ✅ Runtime snapshot with progress fraction |
| `AchievementRepository` | `stats-api` (interface), `stats-data` (impl) | ✅ `observeAll()`, `observeRecent()` |
| `AchievementCatalog` | `stats-api` | ✅ 18 definitions with tier thresholds |
| `RuleEvaluator` | `stats-api` | ✅ Stateless rule evaluator, tier/target comparison |
| `AchievementProcessor` | `stats-engine` | ✅ SignalProcessor wrapper, emits DomainEvents |
| `AchievementProgressEntity` | `sbase` | ✅ Room entity with indices |
| `AchievementProgressDao` | `sbase` | ✅ CRUD + Flow observation |
| `DomainEvent.AchievementUnlocked` | `stats-api` | ✅ Emitted on tier unlock |
| `DomainEvent.AchievementProgress` | `stats-api` | ✅ Emitted on progress change |
| `GameDomainEventConsumer` | `game` | ✅ Consumes unlock/progress events, fires notifications |
| `AchievementCard` | `game/ui` | ✅ Compose card showing tier counts + next closest |
| `ExplorationViewModel.AchievementSummaryState` | `feature/game/viewmodel` | ✅ Aggregates entity-free achievement snapshots from `ExplorationProgressRepository` |

### What's missing or incomplete

| Gap | Impact | Priority |
|-----|--------|----------|
| **Metrics provider doesn't supply all catalog metrics** | `unique_areas`, `seasons_explored`, `transport_mode_count`, `walking_trips`, `cycling_trips`, `total_exports` are undefined at runtime | 🔴 Critical |
| **No `notifiedAt` field** on `AchievementProgressEntity` | Cannot track which unlocks the user has seen vs. new ones | 🟡 Medium |
| **No `category` column** on `AchievementProgressEntity` | Filtering by category requires joining with catalog in memory | 🟢 Low (catalog is in-memory) |
| **No dedicated AchievementWorker** | Background re-evaluation after app restart relies entirely on processor pipeline | 🟡 Medium |
| **Missing DAO aggregate queries** for several metrics | Blocks metric provider from computing values | 🔴 Critical |
| **No incremental update strategy** | Full-scan on every flush is wasteful | 🟡 Medium |
| **TrophyCaseScreen shows challenges, not achievements** | Achievement display uses only the small AchievementCard | 🟢 Low |

---

## 2. Architecture Decision: Where Should the Engine Live?

### Recommendation: **Keep in `stats-engine`, extend `game` for celebrations**

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────┐
│  stats-api   │◄────│ stats-engine  │────►│  stats-data  │────►│   sbase   │
│  (contracts) │     │  (evaluation)│     │  (repos)     │     │  (Room)  │
└──────────────┘     └──────┬───────┘     └──────────────┘     └──────────┘
                            │ DomainEvents
                            ▼
                     ┌──────────────┐
                     │    game       │
                     │  (consume,   │
                     │   celebrate, │
                     │   UI)        │
                     └──────────────┘
```

**Rationale:**
- `stats-api` owns the declarative catalog and shared `RuleEvaluator`; `stats-engine` owns live `AchievementProcessor` orchestration.
- The evaluation logic is pure computation (metric value → tier comparison) — it belongs in the shared rule layer.
- `game` already owns celebration (notifications, XP awards) via `GameDomainEventConsumer`.
- Creating a new module would add build complexity for no separation benefit.

**Module responsibilities:**

| Module | Responsibility |
|--------|---------------|
| `stats-api` | Contracts/catalog: `AchievementDefinition`, `AchievementTier`, `AchievementSnapshot`, `AchievementCatalog`, `RuleEvaluator`, `DomainEvent.*` |
| `stats-engine` | Live evaluation orchestration: `AchievementProcessor` |
| `stats-data` | Persistence: `DefaultAchievementRepository`, **new `DefaultAchievementMetricsProvider`** |
| `sbase` | Schema: `AchievementProgressEntity`, `AchievementProgressDao` |
| `game` | Consumption: `GameDomainEventConsumer`, XP awards, notifications, UI |

---

## 3. Evaluation Pipeline Design

### 3.1 What Triggers Evaluation?

**Dual-trigger approach:**

```
Trigger 1: Real-time (during tracking)
  TrackerService → ProcessorPipeline → AchievementProcessor.onFlush()
  Frequency: Every 60s (flushIntervalMs = 60_000)
  Scope: Session-scoped metrics only (distance, steps, duration)

Trigger 2: Background (post-session)
  SessionEnded DomainEvent → AchievementEvaluationWorker (WorkManager)
  Frequency: Once per session end
  Scope: ALL metrics (full re-evaluation with DB queries)
  Constraints: requiresBatteryNotLow = true
```

**Why both?**
- Real-time gives instant feedback for distance/step achievements during tracking.
- Background handles metrics that require DB aggregation (streaks, exploration counts, mode variety).
- WorkManager guarantees evaluation even if the app is killed mid-session.

### 3.2 Metrics Collection Architecture

**New interface:** `AchievementMetricsProvider`

```kotlin
// stats-api/src/commonMain/kotlin/.../AchievementMetricsProvider.kt
interface AchievementMetricsProvider {
    /**
     * Collect current values for all achievement metrics.
     * Keys match AchievementDefinition.metric values.
     * Implementations should be O(1) where possible (pre-aggregated).
     */
    suspend fun collectAll(): Map<String, Long>

    /**
     * Collect values for a subset of metrics.
     * Use when only certain metrics could have changed.
     */
    suspend fun collect(metrics: Set<String>): Map<String, Long>
}
```

**Implementation:** `DefaultAchievementMetricsProvider` in `stats-data`:

```kotlin
// stats-data/src/main/java/.../DefaultAchievementMetricsProvider.kt
class DefaultAchievementMetricsProvider @Inject constructor(
    private val dailySummaryDao: DailySummaryDao,
    private val explorationCellDao: ExplorationCellDao,
    private val explorationStreakDao: ExplorationStreakDao,
    private val tripDao: TripDao,
    private val sessionSegmentDao: SessionSegmentDao,
    private val exportLogDao: ExportLogDao,
) : AchievementMetricsProvider {

    override suspend fun collectAll(): Map<String, Long> = buildMap {
        // EXPLORATION
        put("cells_discovered", explorationCellDao.countAtLevel(DEFAULT_LEVEL).toLong())
        put("unique_areas", explorationCellDao.countDistinctAreas(DEFAULT_LEVEL).toLong())
        put("seasons_explored", explorationCellDao.countDistinctSeasons(DEFAULT_LEVEL).toLong())

        // DISTANCE (from DailySummary — O(n) where n ≈ days)
        put("total_distance_km", (dailySummaryDao.sumTotalDistance() / 1000.0).toLong())
        put("longest_trip_km", (tripDao.getLongestTripDistanceM() / 1000.0).toLong())

        // STEPS
        put("total_steps", dailySummaryDao.sumTotalSteps().toLong())
        put("best_daily_steps", dailySummaryDao.getBestDailySteps().toLong())

        // STREAKS
        put("daily_streak", explorationStreakDao.getBestCount("daily").toLong())
        put("weekly_streak", explorationStreakDao.getBestCount("weekly").toLong())

        // MODES
        put("transport_mode_count", sessionSegmentDao.countDistinctActivities().toLong())
        put("walking_trips", sessionSegmentDao.countByPrimaryMode(WALKING_MODE_ID))
        put("cycling_trips", sessionSegmentDao.countByPrimaryMode(CYCLING_MODE_ID))

        // GENERAL
        put("total_trips", tripDao.countAllTrips().toLong())
        put("total_exports", exportLogDao.countTotal())
    }

    override suspend fun collect(metrics: Set<String>): Map<String, Long> {
        // Selective collection to avoid unnecessary queries
        return collectAll().filterKeys { it in metrics }
    }
}
```

### 3.3 Tier Transition Detection

The existing `RuleEvaluator.evaluate()` already handles this correctly:

```
Current approach (keep):
  1. Load previous progress from AchievementProgressDao-backed RuleInstance values
  2. Evaluate catalog-backed RuleInstance via RuleEvaluator.evaluate()
  3. Map TierUnlocked / ProgressUpdated to domain events with nextTierTarget
  4. Persist updated progress to AchievementProgressDao
```

**Enhancement needed:** The `AchievementProcessor` currently stores `previousProgress` in memory only. After app restart, it starts fresh. The background `AchievementEvaluationWorker` should load from Room instead:

```kotlin
// In AchievementEvaluationWorker:
val dbProgress = achievementProgressDao.getAll()
val previousProgress = dbProgress.associate { entity ->
    entity.achievementId to Pair(
        entity.currentValue,
        AchievementTier.entries.getOrNull(entity.tier)
    )
}
```

### 3.4 Celebration Emission

**Keep the existing pattern** — DomainEvents are the right abstraction:

```
AchievementProcessor emits:
  └─ DomainEvent.AchievementUnlocked(achievementId, tier)
  └─ DomainEvent.AchievementProgress(achievementId, currentValue, targetValue)

GameDomainEventConsumer handles:
  ├─ AchievementUnlocked → System notification + XP ledger entry
  └─ AchievementProgress → Log (notify only at ≥90% threshold)
```

**New addition: In-app celebration SharedFlow** for real-time UI feedback:

```kotlin
// game/src/main/java/.../event/AchievementCelebrationBus.kt
@Singleton
class AchievementCelebrationBus @Inject constructor() {
    private val _celebrations = MutableSharedFlow<AchievementCelebration>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val celebrations: SharedFlow<AchievementCelebration> = _celebrations.asSharedFlow()

    suspend fun emit(celebration: AchievementCelebration) {
        _celebrations.emit(celebration)
    }
}

data class AchievementCelebration(
    val achievementId: String,
    val tier: AchievementTier,
    val title: String,
    val pointsAwarded: Int,
)
```

This lets `GameScreen` show an animated toast/snackbar without polling Room.

---

## 4. Achievement Catalog — 18 Achievements Across 6 Categories

### 4.1 EXPLORATION (3 achievements)

| # | ID | Title | Metric | Source | Tiers (B/S/G/D) | Query |
|---|-----|-------|--------|--------|-----------------|-------|
| 1 | `explorer_cells` | Cell Pioneer | `cells_discovered` | `ExplorationCellDao.countAtLevel()` | 10 / 50 / 200 / 1000 | O(1) — COUNT on indexed table |
| 2 | `explorer_areas` | Area Mapper | `unique_areas` | `ExplorationCellDao.countDistinctAreas()` | 3 / 10 / 30 / 100 | O(n) — COUNT DISTINCT on area token |
| 3 | `seasonal_explorer` | Four Seasons | `seasons_explored` | `ExplorationCellDao.getDistinctSeasonBitmasks()` → bitwise OR → popcount | 1 / 2 / 3 / 4 | O(n) — scan bitmasks, but n ≤ cell count |

### 4.2 DISTANCE (2 achievements)

| # | ID | Title | Metric | Source | Tiers (B/S/G/D) | Query |
|---|-----|-------|--------|--------|-----------------|-------|
| 4 | `distance_total` | Road Warrior | `total_distance_km` | `DailySummaryDao.sumTotalDistance()` | 10 / 100 / 1,000 / 10,000 km | O(n) where n = daily_summary rows (~365/year) |
| 5 | `distance_single_trip` | Long Haul | `longest_trip_km` | `TripDao.getLongestTripDistanceM()` | 5 / 20 / 50 / 200 km | O(n) — MAX scan on trip table |

### 4.3 STEPS (2 achievements)

| # | ID | Title | Metric | Source | Tiers (B/S/G/D) | Query |
|---|-----|-------|--------|--------|-----------------|-------|
| 6 | `steps_total` | Step Counter | `total_steps` | `DailySummaryDao.sumTotalSteps()` | 10K / 100K / 1M / 10M | O(n) — SUM on daily_summary |
| 7 | `steps_daily_best` | Daily Champion | `best_daily_steps` | `DailySummaryDao.getBestDailySteps()` | 5K / 10K / 20K / 50K | O(n) — MAX on daily_summary |

### 4.4 STREAKS (2 achievements)

| # | ID | Title | Metric | Source | Tiers (B/S/G/D) | Query |
|---|-----|-------|--------|--------|-----------------|-------|
| 8 | `streak_daily` | Daily Devotion | `daily_streak` | `ExplorationStreakDao.getByType("daily")` | 3 / 7 / 30 / 100 days | O(1) — single row lookup |
| 9 | `streak_weekly` | Weekly Warrior | `weekly_streak` | `ExplorationStreakDao.getByType("weekly")` | 2 / 4 / 12 / 52 weeks | O(1) — single row lookup |

### 4.5 MODES (3 achievements)

| # | ID | Title | Metric | Source | Tiers (B/S/G/D) | Query |
|---|-----|-------|--------|--------|-----------------|-------|
| 10 | `mode_variety` | Mode Mixer | `transport_mode_count` | `SessionSegmentDao.countDistinctActivities()` | 2 / 3 / 4 / 5 | O(n) — COUNT DISTINCT |
| 11 | `mode_walking_trips` | Trailblazer | `walking_trips` | `SessionSegmentDao.countByPrimaryMode(WALKING)` | 5 / 20 / 100 / 500 | O(n) — COUNT with WHERE |
| 12 | `mode_cycling_trips` | Pedaleur | `cycling_trips` | `SessionSegmentDao.countByPrimaryMode(CYCLING)` | 5 / 20 / 100 / 500 | O(n) — COUNT with WHERE |

### 4.6 MILESTONES (6 achievements — single-tier)

| # | ID | Title | Metric | Tier | Query |
|---|-----|-------|--------|------|-------|
| 13 | `first_cell` | First Discovery | `cells_discovered` | BRONZE @ 1 | Reuses explorer_cells metric |
| 14 | `first_trip` | First Journey | `total_trips` | BRONZE @ 1 | Reuses total_trips metric |
| 15 | `first_streak` | Streak Starter | `daily_streak` | BRONZE @ 2 | Reuses daily_streak metric |
| 16 | `century_cells` | Century Club | `cells_discovered` | BRONZE @ 100 | Reuses explorer_cells metric |
| 17 | `first_export` | Data Keeper | `total_exports` | BRONZE @ 1 | `ExportLogDao.countTotal()` |
| 18 | `distance_first_km` | First Kilometer | `total_distance_km` | BRONZE @ 1 | Reuses total_distance_km metric |

### Metric Summary

| Metric Key | Unique Definitions | Query Complexity | Incremental? |
|-----------|-------------------|-----------------|-------------|
| `cells_discovered` | 3 (explorer_cells, first_cell, century_cells) | O(1) | ✅ Yes — increment on CellDiscovered event |
| `unique_areas` | 1 | O(n) | ⚠️ Semi — cache + invalidate on new area |
| `seasons_explored` | 1 | O(n) | ✅ Yes — maintain running bitmask |
| `total_distance_km` | 2 (distance_total, distance_first_km) | O(n) | ✅ Yes — add session delta |
| `longest_trip_km` | 1 | O(n) | ✅ Yes — compare with cached max |
| `total_steps` | 1 | O(n) | ✅ Yes — add session delta |
| `best_daily_steps` | 1 | O(n) | ✅ Yes — compare with cached max |
| `daily_streak` | 2 (streak_daily, first_streak) | O(1) | ✅ Yes — read from streak entity |
| `weekly_streak` | 1 | O(1) | ✅ Yes — read from streak entity |
| `transport_mode_count` | 1 | O(n) | ⚠️ Semi — cache + invalidate on new mode |
| `walking_trips` | 1 | O(n) | ✅ Yes — increment on walking trip end |
| `cycling_trips` | 1 | O(n) | ✅ Yes — increment on cycling trip end |
| `total_trips` | 2 (first_trip, distance_total context) | O(1) | ✅ Yes — increment on trip end |
| `total_exports` | 1 | O(1) | ✅ Yes — increment on export |

---

## 5. Schema Evaluation: `AchievementProgressEntity`

### Current schema

```kotlin
@Entity(tableName = "achievement_progress", indices = [...])
data class AchievementProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "achievement_id") val achievementId: String,
    @ColumnInfo(name = "current_value") val currentValue: Long = 0,
    @ColumnInfo(name = "target_value") val targetValue: Long,
    val tier: Int = 0,
    @ColumnInfo(name = "unlocked_at") val unlockedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0
)
```

### Recommended changes

#### 5.1 Add `notifiedAt` field — **YES, add**

```kotlin
@ColumnInfo(name = "notified_at") val notifiedAt: Long? = null
```

**Why:** The current system fires a notification in `GameDomainEventConsumer.onAchievementUnlocked()`, but has no way to know if the user has *seen* it. On app relaunch, the UI can't distinguish "new unlock to celebrate" from "old unlock already shown."

**Usage:**
- Set `notifiedAt = null` when a new tier is unlocked.
- Set `notifiedAt = System.currentTimeMillis()` after the notification is posted or the celebration animation plays.
- Query: `WHERE unlocked_at IS NOT NULL AND notified_at IS NULL` → "unseen unlocks."

#### 5.2 Add `category` column — **NO, skip**

The catalog is in-memory (18 items). Joining in Kotlin is trivial:
```kotlin
val byCategory = progressList.groupBy { entity ->
    AchievementCatalog.byId(entity.achievementId)?.category
}
```
Adding a denormalized column creates a sync risk if categories ever change.

#### 5.3 Current indices — **Sufficient**

```
Index(["achievement_id"], unique = true)  — primary lookup ✅
Index(["updated_at"])                     — recent-first queries ✅  
Index(["unlocked_at"])                    — unlocked filtering ✅
```

**One addition:** Composite index for the "unseen unlocks" query:

```kotlin
Index(value = ["unlocked_at", "notified_at"])
```

#### 5.4 Migration

```kotlin
// AppDatabaseMigrations.kt — Migration 17→18
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE achievement_progress ADD COLUMN notified_at INTEGER DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_achievement_progress_unlocked_at_notified_at ON achievement_progress (unlocked_at, notified_at)")
    }
}
```

---

## 6. Battery-Conscious Evaluation Strategy

### 6.1 Maximum evaluation frequency

| Context | Frequency | Justification |
|---------|-----------|---------------|
| During active tracking | Every 60s (existing `flushIntervalMs`) | Already battery-gated by tracking itself |
| Post-session background | Once per `SessionEnded` event | WorkManager with `requiresBatteryNotLow` |
| App cold start | Once, deferred 5s | Catch up on any missed events |
| Daily maintenance | Once per day via existing `NewDayGoalWorker` chain | Recalculate streaks and daily aggregates |

**Total worst-case:** ~60 evaluations/hour during tracking + 1 background. No evaluation when idle.

### 6.2 Incremental vs. full-scan metrics

```
INCREMENTAL (O(1) — update from event deltas):
├── cells_discovered  → +1 on CellDiscovered event
├── total_distance_km → +delta from SessionEnded.totalDistance
├── total_steps       → +delta from SessionEnded.totalSteps
├── total_trips       → +1 on TripCompleted event
├── total_exports     → +1 on export complete
├── daily_streak      → read from ExplorationStreakEntity
├── weekly_streak     → read from ExplorationStreakEntity
├── walking_trips     → +1 on TripCompleted where mode=WALKING
└── cycling_trips     → +1 on TripCompleted where mode=CYCLING

CACHED-WITH-INVALIDATION (O(1) reads, O(n) on cache miss):
├── longest_trip_km   → cache MAX, only re-query if new trip > cache
├── best_daily_steps  → cache MAX, only re-query if today's steps > cache
├── seasons_explored  → maintain running bitmask, popcount
└── unique_areas      → cache count, increment on new area discovery

FULL-SCAN (O(n) — only in background worker, never real-time):
└── transport_mode_count → COUNT DISTINCT (rare, only changes on new mode)
```

### 6.3 Avoiding redundant evaluation

```kotlin
class IncrementalMetricsCache {
    private val cache = ConcurrentHashMap<String, Long>()
    private val dirtyMetrics = ConcurrentHashMap.newKeySet<String>()

    fun markDirty(metric: String) { dirtyMetrics.add(metric) }

    suspend fun getMetrics(
        fullProvider: AchievementMetricsProvider,
        dirtyOnly: Boolean = true,
    ): Map<String, Long> {
        return if (dirtyOnly && dirtyMetrics.isNotEmpty()) {
            val dirty = dirtyMetrics.toSet()
            dirtyMetrics.clear()
            val fresh = fullProvider.collect(dirty)
            cache.putAll(fresh)
            cache.toMap()
        } else if (!dirtyOnly) {
            val all = fullProvider.collectAll()
            cache.putAll(all)
            dirtyMetrics.clear()
            all
        } else {
            cache.toMap() // Nothing dirty, return cached
        }
    }
}
```

**Dirty-marking happens via DomainEvent observation:**
- `SessionEnded` → mark `total_distance_km`, `total_steps`, `total_trips`, mode metrics
- `CellDiscovered` → mark `cells_discovered`, `unique_areas`, `seasons_explored`
- `TripCompleted` → mark `longest_trip_km`, mode-specific metrics
- `DailySummaryUpdated` → mark `best_daily_steps`

---

## 7. Integration Points

### 7.1 GameScreen — Achievement Summary Card

**Already integrated.** `ExplorationViewModel` maps
`ExplorationProgressRepository.achievements` into `AchievementSummaryState`,
consumed by `AchievementCard`; the ViewModel does not read
`AchievementProgressDao` directly.

**Enhancement:** Add celebration overlay:

```kotlin
// game/src/main/java/.../viewmodel/ExplorationViewModel.kt
@Inject constructor(
    private val celebrationBus: AchievementCelebrationBus,
    ...
) {
    val celebrations: SharedFlow<AchievementCelebration> = celebrationBus.celebrations
}

// game/src/main/java/.../ui/compose/GameScreen.kt
// Collect celebrations and show animated snackbar
val celebration by viewModel.celebrations.collectAsStateWithLifecycle(null)
celebration?.let { AchievementUnlockOverlay(it) }
```

### 7.2 TrophyCaseRoute — Achievement Grid

**Current state:** Shows challenge history and personal records, but no achievement grid.

**Addition:** New section in `TrophyCaseScreen`:

```kotlin
// New composable in game/ui/compose/AchievementGrid.kt
@Composable
fun AchievementGrid(
    achievements: List<AchievementSnapshot>,
    onAchievementClick: (AchievementSnapshot) -> Unit,
)
```

**ViewModel change:**

```kotlin
// TrophyCaseViewModel.kt — add:
val achievements: StateFlow<List<AchievementSnapshot>?> =
    achievementRepository.observeAll()
        .map { progressList ->
            progressList.map { progress ->
                val def = AchievementCatalog.byId(progress.achievementId)
                    ?: return@map null
                evaluator.snapshot(def, progress.currentValue)
            }.filterNotNull()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
```

### 7.3 DashboardRoute — Progress Badges

```kotlin
// dashboard/src/main/java/.../ui/compose/cards/AchievementBadges.kt
@Composable
fun AchievementProgressBadges(
    recentUnlocks: List<AchievementSnapshot>,
    nextClosest: AchievementSnapshot?,
)
```

**Data source:** `AchievementRepository.observeRecent()` piped through a dashboard ViewModel.

### 7.4 Notification System

**Already wired.** `GameDomainEventConsumer.onAchievementUnlocked()` posts a notification with:
- SmallIcon: `ic_challenge_icon`
- Channel: `channel_challenges_id`
- Deep link: `navigate_to = "game"`
- Auto-cancel on tap

**Enhancement:** After posting notification, update `notifiedAt`:

```kotlin
private suspend fun onAchievementUnlocked(event: DomainEvent.AchievementUnlocked) {
    // ... existing notification code ...

    // Mark as notified
    val existing = achievementProgressDao.getById(event.achievementId)
    if (existing != null) {
        achievementProgressDao.upsert(
            existing.copy(notifiedAt = System.currentTimeMillis())
        )
    }
}
```

---

## 8. New/Modified Files — Implementation Plan

### Phase 1: Missing DAO Queries (sbase)

| File | Change | Queries |
|------|--------|---------|
| `DailySummaryDao.kt` | Add 2 queries | `sumTotalDistance()`, `sumTotalSteps()`, `getBestDailySteps()` |
| `TripDao.kt` | Add 1 query | `getLongestTripDistanceM()` |
| `SessionSegmentDao.kt` | Add 2 queries | `countDistinctActivities()`, `countByPrimaryMode(modeId)` |
| `ExplorationCellDao.kt` | Add 1 query | `countDistinctAreas(level)` |
| `ExportLogDao.kt` | Add 1 query | `countTotal()` |

### Phase 2: Schema Migration (sbase)

| File | Change |
|------|--------|
| `AchievementProgressEntity.kt` | Add `notifiedAt: Long?` field |
| `AchievementProgressDao.kt` | Add `getUnnotified()` query |
| `AppDatabaseMigrations.kt` | Add `MIGRATION_17_18` |
| `AppDatabase.kt` | Bump version to 18 |

### Phase 3: Metrics Provider (stats-api + stats-data)

| File | Type | Description |
|------|------|-------------|
| `stats-api/.../AchievementMetricsProvider.kt` | **New** interface | `collectAll()`, `collect(metrics)` |
| `stats-data/.../DefaultAchievementMetricsProvider.kt` | **New** class | Wires all DAO queries into metric map |
| `stats-data/.../di/StatsDataModule.kt` | **Edit** | Bind `AchievementMetricsProvider` |

### Phase 4: Background Worker (game)

| File | Type | Description |
|------|------|-------------|
| `game/.../worker/AchievementEvaluationWorker.kt` | **New** | WorkManager worker that collects all metrics, evaluates, persists, emits events |
| `game/.../event/GameDomainEventConsumer.kt` | **Edit** | Enqueue `AchievementEvaluationWorker` on `SessionEnded` |

### Phase 5: Incremental Cache (stats-engine)

| File | Type | Description |
|------|------|-------------|
| `stats-engine/.../achievement/IncrementalMetricsCache.kt` | **New** | Dirty-tracking cache for metric values |
| `stats-engine/.../processor/AchievementProcessor.kt` | **Edit** | Use cache in `onFlush()`, mark dirty from events |

### Phase 6: Celebration Bus (game)

| File | Type | Description |
|------|------|-------------|
| `game/.../event/AchievementCelebrationBus.kt` | **New** | SharedFlow-based in-app celebration emitter |
| `game/.../event/GameDomainEventConsumer.kt` | **Edit** | Emit to bus + set `notifiedAt` on unlock |
| `game/.../viewmodel/ExplorationViewModel.kt` | **Edit** | Collect celebrations for UI |

### Phase 7: UI Integration (game)

| File | Type | Description |
|------|------|-------------|
| `game/.../ui/compose/AchievementGrid.kt` | **New** | Grid of all achievements with progress |
| `game/.../ui/compose/AchievementUnlockOverlay.kt` | **New** | Animated celebration overlay |
| `game/.../ui/compose/TrophyCaseScreen.kt` | **Edit** | Add achievement grid section |
| `game/.../ui/compose/TrophyCaseViewModel.kt` | **Edit** | Add achievements StateFlow |

---

## 9. Dependency Graph for Implementation

```
Phase 1 (DAO queries)
  └──► Phase 2 (schema migration)
  └──► Phase 3 (metrics provider) ──► Phase 4 (background worker)
                                  ──► Phase 5 (incremental cache)
       Phase 6 (celebration bus)  ──► Phase 7 (UI integration)
```

Phases 1–3 are prerequisites. Phases 4–7 can be parallelized after Phase 3.

---

## 10. Testing Strategy

| Layer | Test Type | Framework | Key Cases |
|-------|-----------|-----------|-----------|
| `RuleEvaluator` | Unit | JUnit 5 + Kotest | All tier transitions, edge cases (0 value, maxed out, single-tier milestones) |
| `AchievementCatalog` | Unit | JUnit 5 | All tiers strictly increasing, all metrics have at least BRONZE, no duplicate IDs |
| `DefaultAchievementMetricsProvider` | Integration | Robolectric + Room in-memory | Each metric returns correct value from seeded DB |
| `IncrementalMetricsCache` | Unit | JUnit 5 + Turbine | Dirty-marking, cache hit/miss, concurrent access |
| `AchievementEvaluationWorker` | Integration | WorkManagerTestInitHelper | End-to-end: seed data → run worker → verify progress entities + events |
| `AchievementProgressDao` | Unit | Room in-memory | CRUD, `getUnnotified()`, `upsert` idempotency |
| Schema migration | Unit | MigrationTestHelper | `MIGRATION_17_18` applies cleanly |
