# Stats Rearchitecture: Comprehensive Architecture Plan

> **Status**: Design Complete, Pending Implementation
> **Last Updated**: February 2026
> **Scope**: Full rearchitecture of statistics, tracking policy, gamification, and data lifecycle
> **Git Strategy**: Feature branch hub (`stats-rearchitecture`) with worktrees per phase

---

## 1. Vision

Transform Tracker from a session-based "start/stop" tracker into a **continuous, always-aware life tracker** with:
- 4-tier adaptive tracking (OFF / AMBIENT / ACTIVE / PRECISION)
- Automatic trip inference from continuous data
- S2 cell-based exploration tracking with gamification
- Rich dashboard with timeline, trip, and calendar views
- Configurable data retention with route preservation
- Widgets and contextual notifications

**Privacy remains absolute**: all processing local, no network calls, no telemetry.

---

## 2. Core Architecture Decisions

### 2.1 Data Philosophy
- **Raw data is the immutable source of truth**
- **Materialized domain views** (not generic time buckets) are derived from raw data
- Views are purpose-built: trips, daily summaries, exploration cells, live stats
- Raw data can be purged after materialization; views survive independently

### 2.2 Tracking Policy: 4-Tier System

Keep existing `TrackingPolicy` enum (5 values). Add derived `PolicyTier`:

| PolicyTier | TrackingPolicy | GPS | Sensors | Battery |
|---|---|---|---|---|
| **OFF** | (no service) | No | None | 0% |
| **AMBIENT** | PASSIVE_LOW, MOVEMENT_SUSPECTED | No | Steps + activity | ~1.4%/day |
| **ACTIVE** | ACTIVE_MODERATE, ACTIVE_ELEVATED | Adaptive | All | ~5-15%/day |
| **PRECISION** | USER_INITIATED | Max rate | All | ~25-30%/day |

- **AMBIENT is the default** (after onboarding)
- Auto-escalation AMBIENT→ACTIVE via `MovementConfidenceAccumulator`
- Asymmetric de-escalation (4-8 min sustained stillness)
- Trip inference engine prevents premature de-escalation via `setMinimumPolicy()`
- Activity-aware GPS intervals within ACTIVE (walking=25s, vehicle=5s)

### 2.3 Module Structure

**New modules:**
| Module | Type | Purpose |
|---|---|---|
| `stats-engine` | Pure Kotlin | Trip inference, mode classification, aggregation, S2 cells |
| `stats-api` | Kotlin + kotlinx | Interfaces, data classes, result types |
| `stats-data` | Android/Room | Entities, DAOs, materialized view persistence |
| `widget` | Android/Glance | Home screen widgets |

**Existing modules evolved:**
- `tracker` → adds PolicyEscalationEngine, AMBIENT mode, component hot-swap
- `statistics` → becomes thin Compose UI consuming stats-api
- `game` → integrates S2 exploration, enhanced challenges, achievements
- `impexp` → evolved exporters, JSON format, retention integration

### 2.4 DI: Migrate to Hilt-Only

Three scopes:
- `@Singleton` (Application): repositories, database, dispatchers, clock
- `@TrackingScoped` (custom): sensor producers, StreamingAggregator, PolicyEscalationEngine
- `@ViewModelScoped`: UI state holders only

Migration is incremental: new modules use Hilt from day one; existing modules migrate gradually.

---

## 3. Subsystem Designs

### 3.1 Trip Inference Engine

**State Machine** (5 states):
```
STATIONARY → DEPARTING → IN_TRIP → STOP_PENDING → ARRIVED → STATIONARY
```

- DEPARTING debounces false starts from GPS drift (120s confirmation window)
- STOP_PENDING debounces brief stops (mode-dependent: walk=5min, drive=10min, transit=15min)
- GPS drift suppression: 25m radius + step counter cross-reference

**Transport Mode Classification** (rule-based, not ML):
- 4 signal dimensions: speed (0.35), steps (0.25), activity recognition (0.25), stop pattern (0.15)
- Modes: WALK, RUN, CYCLE, DRIVE, TRANSIT, HIGH_SPEED_RAIL, AIR
- Confidence scoring: >=0.60 high, 0.40-0.59 medium, <0.40 unknown

**Data Model**:
- `InferredTrip` — full journey A→B with departure/arrival places, total stats
- `TripLeg` — each transport mode segment within a trip
- `FrequentPlace` — DBSCAN-clustered trip endpoints (epsilon=80m, min_points=3)
- `TripUserOverride` — manual corrections stored separately from inference

**Processing**: Hybrid 3-layer
- Real-time (per sample, ~1ms): state machine transitions
- Near-RT (60s window, ~5ms): feature extraction, mode classification
- Batch (trip finalization, ~50-500ms): boundary refinement, place matching

### 3.2 Activity-Detected Mode Switching

**Escalation Engine**:
- `MovementConfidenceAccumulator` replaces single-event thresholds
- Three signal pathways: activity recognition (weight 20-30), step rate (5-25), significant motion (15)
- Decay: -2 points/second of silence
- Thresholds: 40→MOVEMENT_SUSPECTED, 80→ACTIVE, 120→ELEVATED
- Prevents oscillation: alternating STILL/WALKING nets ~0 accumulation

**Hysteresis**:
- Minimum dwell time per state (30s-120s)
- Re-escalation cooldown after de-escalation (60s-300s)
- Exponential backoff: 3+ false cycles/hour → threshold doubles

**TrackerService absorbs AMBIENT mode**:
- All tiers run in TrackerService with policy-aware component enabling
- AMBIENT: only StepDataProducer + ActivityDataProducer (no GPS)
- Components hot-swap on tier transition via `reconfigureComponents()`
- ActivityWatcherService narrows to OFF→AMBIENT bootstrap only

### 3.3 S2 Cell Exploration

- 4 resolution levels: L16 (~100m), L13 (~1km), L11 (~8km), L9 (~65km)
- Bloom filter (500K capacity, 1% FP, ~1.2MB): O(1) new-cell detection
- Discovery propagates upward: L16 → L13 → L11 → L9
- 5 quality tiers: PASSED_THROUGH → CYCLED_THROUGH → TRAVERSED_ON_FOOT → EXPLORED → THOROUGHLY_EXPLORED
- Quality only upgrades, never downgrades
- Seasonal rediscovery via 4-bit bitmask
- `ExplorationPostComponent` in tracker pipeline

### 3.4 Gamification

**Challenges**: Evolved from existing system with new types (CellDiscovery, AreaCompletion, ModeBased, ExplorationChain). Adaptive difficulty scaling based on 30-day rolling user performance. 4 slots: 1 daily + 1 weekly + 2 custom.

**Achievements**: 4 tiers (BRONZE→DIAMOND), ~30 initial achievements across categories (exploration, distance, steps, streaks, modes, milestones). Incremental evaluation on metric changes.

**Points**: Overhauled from on-foot-only to universal. Base 10 pts/new cell * quality * mode weight, streak bonuses, chain bonuses, distance points.

**Streaks**: Daily discovery, weekly explorer, exploration chains.

### 3.5 Dashboard & Visualization

**Policy-aware home screen**:
- OFF: motivational hero + recent summary
- AMBIENT: steps hero + activity timeline strip
- ACTIVE: live speed/distance/duration + mini-map + current activity
- PRECISION: same + accuracy indicator + altitude + bearing

**Three history views** (single HistoryRoute, segmented buttons):
- Timeline: chronological feed (trips, discoveries, milestones), paged
- Trips: filtered list with expandable cards, mode-colored segments
- Calendar: month grid with heatmap intensity, tap-to-detail

**Charts** (all Canvas-based, no third-party library):
SpeedChart, ActivityDonut, DistanceBarChart, StepChart, ExplorationProgress, ElevationProfile

**Widgets** (Glance API, new `widget/` module):
- Small (2x1): steps + distance
- Medium (4x2): summary + activity breakdown + goal ring
- Large (4x4): mini dashboard + 7-day chart + action button

**Notifications** (6 channels):
tracking_active, trip_summary, discovery, daily_summary, weekly_digest, achievements

### 3.6 Data Retention & Export

**Multi-stage lifecycle**:
```
HOT (full resolution) → WARM (route compressed, raw purged) → COLD (metadata only) → PURGED
```

**Retention tiers** (independently configurable, Proto DataStore):
- Raw data: 90 days default (configurable 7d-forever)
- Wi-Fi: 30 days default (separate control — dominates storage)
- Trips: 2 years default
- Exploration + summaries: forever (tiny footprint)

**Route preservation**: Douglas-Peucker simplification → Google Encoded Polyline in `route_cache` table. 100-200 points per trip, ~1KB. Created before raw data purge.

**Export evolution**:
- GPX/KML: enhanced with transport mode extensions
- JSON (new): full-fidelity backup/restore with schema versioning, streaming writer
- SQLite: direct copy (preserved)
- Auto-export before purge (WorkManager chain)

**Storage analytics**: Dashboard showing size breakdown, growth rate, days-until-full, per-tier retention controls with smart recommendations.

---

## 4. Database Schema Changes

### 4.1 New Tables (Migration v13→v14)

| Table | Purpose | Rows/Day | Size/Year |
|---|---|---|---|
| `inferred_trip` | Trip metadata | 5-15 | ~5 KB |
| `trip_leg` | Trip segments by mode | 10-40 | ~10 KB |
| `frequent_place` | Auto-detected places | 0-2 | ~300 B |
| `trip_user_override` | Manual corrections | 0-1 | ~100 B |
| `exploration_cell` | S2 cell discoveries | ~200 | ~27 KB |
| `exploration_streak` | Streak tracking | ~3 (total) | ~200 B |
| `achievement_definition` | Achievement catalog | ~30 (static) | ~4 KB |
| `achievement_progress` | Per-user progress | ~30 | ~3 KB |
| `challenge_entry_v2` | Enhanced challenges | 1-4 | ~500 B |
| `personal_record` | Personal bests | ~18 | ~2 KB |
| `route_cache` | Compressed routes | 5-15 | ~18 MB |
| `export_log` | Export history | 0-1 | ~100 B |
| `storage_size_snapshot` | Growth tracking | 1 | ~50 B |
| `policy_daily_summary` | Battery analytics | 1 | ~50 B |
| `daily_summary` | Aggregated daily stats | 1 | ~200 B |
| `live_stats` | Single-row real-time | 1 (total) | ~200 B |

### 4.2 Modified Tables

- `tracker_run`: Add `conceptual_tier`, `transition_reason`, `detected_activity`, `accumulator_value`

### 4.3 New Enums (Room TypeConverters)

TransportMode, TripSource, ArrivalMethod, LegType, DeviceContext, PlaceCategory, OverrideType, DiscoveryQuality, StreakType, AchievementCategory, AchievementTier, AchievementMetric, ChallengeState, ChallengeCadence, PolicyTier

---

## 5. Implementation Phases

### Phase 0: Foundation
**Goal**: New modules, database migration, basic infrastructure

- Create `stats-engine`, `stats-api`, `stats-data`, `widget` modules
- Database migration v13→v14 (all new tables)
- `PolicyTier` enum and `PolicyState` data class
- `PolicyEscalationEngine` interface
- `MovementConfidenceAccumulator`
- `ActivityAwareIntervalMapper`
- S2 geometry library integration
- `ExplorationBloomFilter`
- Douglas-Peucker algorithm + polyline encoder
- Tests for all new pure-Kotlin code

### Phase 1: Policy Engine & AMBIENT Mode
**Goal**: 4-tier tracking operational

- Refactor `TrackingPolicyManager` → `DefaultPolicyEscalationEngine`
- Add confidence accumulator, asymmetric de-escalation, minimum policy locks
- TrackerService AMBIENT mode (step + activity only, no GPS)
- Policy-aware component enabling/disabling in TrackerComponentManager
- ActivityWatcherService evolution (OFF→AMBIENT bootstrap)
- `SignificantMotionProducer` (accelerometer backup)
- Update foreground notification for tier awareness
- PolicyTierBanner UI component on home screen
- Tests: policy transitions, hysteresis, battery budget validation

### Phase 2: Streaming Aggregator & Daily Summaries
**Goal**: Real-time stats and daily materialization

- `StreamingAggregator` (in-memory, flushed every 30s to `live_stats`)
- `DailySummary` entity and materialization worker
- `DailySummaryProvider` evolution (query materialized view, not raw SQL)
- Today's progress card on home screen
- Widget data source connected to daily summaries
- Tests: aggregation accuracy, midnight rollover, edge cases

### Phase 3: Trip Inference
**Goal**: Automatic trip detection from continuous data

- Trip detection state machine (STATIONARY→IN_TRIP lifecycle)
- Transport mode classifier (rule-based, 4-signal scoring)
- Two-pass boundary refinement (coarse 5-min windows → raw-sample precision)
- `FrequentPlace` DBSCAN clustering
- Trip merge/split logic
- `TripUserOverride` storage
- Trip inference ↔ policy engine integration (`setMinimumPolicy`)
- Re-processing worker (incremental, versioned)
- Legacy session migration (SessionSegment → InferredTrip with LEGACY_MIGRATION source)
- Tests: state machine transitions, mode classification accuracy, edge cases

### Phase 4: Exploration System
**Goal**: S2 cell tracking, gamification live

- `ExplorationPostComponent` in tracker pipeline
- Bloom filter initialization and persistence
- Cell discovery flow (L16 → parent propagation)
- Discovery quality computation and upgrades
- Streak tracking (daily, weekly)
- Chain mechanics (consecutive new cells)
- Achievement system (definitions, progress, evaluation)
- Enhanced challenge system (ChallengeEntryV2, adaptive difficulty)
- Points overhaul (universal scoring, exploration bonuses)
- Exploration map layer (`ExplorationHeatmapLayer` with tile rendering)
- Tests: bloom filter, S2 cell math, achievement evaluation, challenge scaling

### Phase 5: Dashboard & History
**Goal**: New UI surfaces

- Policy-aware dashboard (OFF/AMBIENT/ACTIVE/PRECISION content switching)
- HistoryRoute with segmented buttons (Timeline / Trips / Calendar)
- Timeline view (paged chronological feed)
- Trip view (filtered list, expandable cards, mode segments)
- Calendar view (month grid, heatmap intensity, day detail)
- Chart components (Speed, Activity, Distance, Steps, Exploration, Elevation)
- Widgets (Small, Medium, Large via Glance API)
- Notification channels and builders (6 channels)
- HistoryViewModel, TrackerDashboardViewModel
- Adaptive layouts (compact/medium/expanded)
- Accessibility (contentDescription, 48dp targets, contrast)
- Tests: Compose semantics tests, ViewModel state tests

### Phase 6: Retention, Export & Cleanup
**Goal**: Data lifecycle management, export evolution, legacy cleanup

- `RetentionConfig` Proto DataStore
- `RetentionPipelineWorker` (replaces DataRetentionWorker)
- Route compression integration (create route_cache before raw purge)
- `ExportDataSource` interface + `DatabaseExportDataSource`
- Evolved Exporter interface
- JSON exporter (streaming, schema-versioned)
- JSON importer with conflict resolution
- Enhanced GPX/KML exporters (transport mode extensions)
- Export-before-purge WorkManager chain
- Storage analytics (StorageAnalyzer + dashboard UI)
- `deleteAllCollectedData()` updated for all new tables
- Complete ExportPlanWorker execution
- DI migration completion (remaining AppGraph entries → Hilt)
- Remove deprecated code paths
- Tests: retention pipeline, export/import round-trip, storage analytics

---

## 6. Git Strategy

- **Hub branch**: `stats-rearchitecture` (branches from main)
- **Phase worktrees**: one worktree per phase, branched from hub
  - `stats-phase-0-foundation`
  - `stats-phase-1-policy-engine`
  - `stats-phase-2-aggregator`
  - `stats-phase-3-trip-inference`
  - `stats-phase-4-exploration`
  - `stats-phase-5-dashboard`
  - `stats-phase-6-retention`
- Phase branches merge to hub when complete
- Hub merges to main when all phases are stable
- Phases can overlap: Phase 0 must complete first, then 1-4 can partially parallelize

---

## 7. Risk Mitigation

| Risk | Mitigation |
|---|---|
| Database migration complexity (14 new tables) | Single migration v13→v14, comprehensive migration test |
| Battery regression from AMBIENT mode | Battery budget validated per-tier; exponential backoff for false escalations |
| Trip inference accuracy | Rule-based (debuggable), configurable thresholds, user overrides stored separately |
| S2 library availability | Pure Java/Kotlin port exists; fallback to manual lat/lng→cell computation |
| Storage growth for power users | Configurable retention, Wi-Fi separate control, storage analytics dashboard |
| DI migration breaking existing code | Incremental: parallel Hilt + AppGraph during transition |
| UI complexity (3 history views + tier dashboard) | Compose architecture with stateless components, preview-driven development |

---

## 8. Success Metrics

- AMBIENT mode battery: <2%/day confirmed via PolicyDailySummary
- Trip inference: >85% accuracy on mode classification (validated against user corrections)
- Escalation latency: <120s from motion start to GPS activation
- False escalation rate: <3 cycles/day for typical user
- Route compression: <5% visual deviation at map zoom levels users actually use
- Storage steady-state: <250MB at 90-day raw retention for power users
- Dashboard render: <16ms frame time on mid-range device (Pixel 6a equivalent)

---

## 9. References

| Document | Purpose |
|---|---|
| `docs/ARCHITECTURE_OVERVIEW.md` | Current architecture (pre-rearchitecture) |
| `.github/copilot-instructions.md` | Coding standards and conventions |
| `CLAUDE.md` | Build commands and project context |
| Trip inference agent output | Detailed state machine, data model, edge cases |
| Exploration agent output | S2 cells, gamification, achievement catalog |
| Dashboard agent output | Compose architecture, component decomposition, widgets |
| Retention agent output | Lifecycle stages, route compression, export formats |
| Policy engine agent output | Confidence accumulator, GPS scheduling, battery budget |
