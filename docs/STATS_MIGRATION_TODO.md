# Stats Architecture Migration — Remaining Work

> **Branch:** `feature/stats-architecture-rework`  
> **Foundation:** Phases 0–5 complete (stats-api, stats-engine, stats-data, tracker pipeline, presenters, game events)  
> **Goal:** Replace all legacy PostTrackerComponents, broadcast receivers, and direct DAO injection with the new Processor Pipeline architecture.

---

## Legend

- ☐ Not started
- 🔧 In progress
- ✅ Done

---

## 1. TrackerService Integration

Wire the ProcessorPipeline into TrackerService to run alongside (and eventually replace) the
existing PostTrackerComponent list.

### 1.1 SignalAdapter hookup
- ✅ Inject `ProcessorPipeline` into `TrackerService` via Hilt
- ✅ Call `SignalAdapter.fromTrackerData()` in `TrackerService.onDataUpdate()` to produce `TrackingSignal`
- ✅ Feed signals to `pipeline.deliver(signal)` after existing post-component processing
- ✅ Call `pipeline.start()` in `TrackerService.onCreate()` / `pipeline.stop()` in `onDestroy()`
- ✅ Call `pipeline.flush()` at end of each tracking cycle

### 1.2 Dual-run validation
- ☐ Run both old PostTrackerComponents AND new ProcessorPipeline side-by-side
- ☐ Compare outputs: verify new pipeline produces equivalent Room rows
- ☐ Add logging/metrics to detect divergences
- ☐ Validate with real tracking sessions (walk, drive, bike, transit)

### 1.3 Remove PostTrackerComponents (after validation)
- ✅ `StreamingAggregatorWriter` → replaced by `AggregatorProcessor`
- ✅ `SessionSegmentWriter` → replaced by `SegmentDetectorProcessor`
- ✅ `ExplorationWriter` → replaced by `ExplorationProcessor`
- ✅ `StepIntervalWriter` → subsumed by pipeline signal handling
- ✅ `ActivitySnapshotWriter` → subsumed by pipeline signal handling
- ✅ Remove hardcoded component lists from `TrackerService.initializeComponents()`
- ✅ Remove hardcoded component lists from `TrackerService.onTierEscalation()`
- ✅ Remove `TrackerComponentManager` component registration (if fully replaced)

### 1.4 Database persistence writers
The following PostTrackerComponents write raw sensor data to Room and are NOT replaced by
SignalProcessors (they're data recorders, not stats processors). Decide per-component:
- ☐ `DatabaseLocationComponent` — keep as-is or convert to SignalProcessor
- ☐ `DatabaseCellComponent` — keep as-is or convert to SignalProcessor
- ☐ `DatabaseWifiComponent` — keep as-is or convert to SignalProcessor
- ☐ `DatabaseWifiLocationCountComponent` — keep as-is or convert to SignalProcessor
- ☐ `RawLocationWriter` — keep as-is or convert to SignalProcessor
- ☐ `NotificationComponent` — keep as-is (UI concern, not a processor)

---

## 2. Room Schema — Domain Event Table

The `DomainEventRepository` interface is defined but needs a backing Room entity and DAO.

- ✅ Create `DomainEventEntity` in `sbase` (id, type, payload JSON, processorId, timestampMs, consumedBy)
- ✅ Create `DomainEventDao` with insert, query-unconsumed, mark-consumed operations
- ✅ Create Room migration (current version → +1)
- ✅ Write migration test
- ✅ Implement `DefaultDomainEventRepository` in `stats-data`
- ✅ Register in `StatsDataModule` Hilt bindings

---

## 3. UI — Wire Presenters to Compose Screens

Presenters exist alongside old ViewModels. Switch the UI to use them.

### 3.1 Statistics screens
- ✅ Replace `StatsViewModel` usage in `StatsRoute` with `StatsPresenter`
- ✅ Replace `HistoryViewModel` usage in history screen with `HistoryPresenter`
- ✅ Replace `TripDetailViewModel` usage in trip detail with `TripDetailPresenter`
- ✅ Remove old `TripDetailViewModel` (StatsViewModel/HistoryViewModel kept as type hosts)

### 3.2 Presenter DI
- ✅ Add Hilt `@Provides` or `@Binds` for presenter injection
- ✅ Decide: `@HiltViewModel` wrapper vs `rememberPresenter()` composable helper
- ✅ Handle `SavedStateHandle` (trip ID) for `TripDetailPresenter`

---

## 4. Game — Complete Domain Event Consumer

The `GameDomainEventConsumer` skeleton exists. Wire it up and implement handlers.

### 4.1 Challenge integration
- ✅ Move `ChallengeWorker` trigger from `ChallengeSessionReceiver.onReceive()` into `GameDomainEventConsumer.onTripCompleted()`
- ✅ Remove `ChallengeSessionReceiver` and its manifest registration
- ✅ Verify challenge evaluation still works end-to-end

### 4.2 Goals integration
- ✅ Move goal update logic from `GoalsSessionUpdateReceiver` into `GameDomainEventConsumer.onDailySummaryUpdated()`
- ✅ Remove `GoalsSessionUpdateReceiver` and its registration in `GameModuleInitializer`
- ✅ Verify goal tracking still works

### 4.3 Consumer lifecycle
- ✅ Call `GameDomainEventConsumer.processUnconsumed()` at app startup
- ✅ Wire as periodic WorkManager job OR observe domain event Flow reactively
- ✅ Test crash recovery: kill app mid-processing, verify events are redelivered

---

## 5. Points — Domain Event Consumer

- ✅ Create `PointsDomainEventConsumer` in `points` module
- ✅ Move logic from `PointsSessionReceiver` (ACTION_SESSION_FINAL) to consume `TripCompleted` events
- ✅ Add `stats-api` dependency to `points/build.gradle.kts`
- ✅ Remove `PointsSessionReceiver` and manifest entry

---

## 6. Activity — Domain Event Consumer

- ✅ Create `ActivityDomainEventConsumer` in `activity` module
- ✅ Move logic from `ActivitySessionReceiver` (ACTION_SESSION_ENDED) to consume `SessionEnded` events
- ✅ Add `stats-api` dependency to `activity/build.gradle.kts`
- ✅ Remove `ActivitySessionReceiver` and manifest entry

---

## 7. App — Remove Legacy Broadcast Infrastructure

After all consumers are migrated:

- ✅ Remove `PrecisionUpgradeReceiver` (ACTION_SESSION_FINAL) — migrate to domain events
- ✅ Remove `ACTION_SESSION_FINAL` / `ACTION_SESSION_ENDED` broadcast sends from `TrackerService`
- ☐ Remove `ACTION_TRACKER_UPDATE` broadcasts if replaced by pipeline signals
- ✅ Clean up `AndroidManifest.xml` entries for removed receivers

---

## 8. Proto DataStore for Live Stats

Currently `DefaultLiveStatsRepository` uses Room. Migrate to Proto DataStore for lower-latency
live tracking stats (updated every tracking cycle).

- ☐ Define `live_stats.proto` schema
- ☐ Create `ProtoLiveStatsRepository` backed by DataStore
- ☐ Replace Room-based implementation in `StatsDataModule`
- ☐ Verify performance improvement with real tracking session

---

## 9. Tests

### 9.1 Unit tests
- ✅ `ProcessorPipeline` — signal delivery, tier filtering, flush, error isolation
- ✅ `SignalAdapter` — all signal type conversions
- ✅ Each `SignalProcessor` wrapper — onSignal/onFlush correctness
- ✅ Each Repository — entity ↔ domain mapping
- ✅ Each Presenter — state transitions, error handling
- ✅ `GameDomainEventConsumer` — event routing, offset tracking

### 9.2 Integration tests
- ☐ Full pipeline: mock sensors → ProcessorPipeline → Room → Repository → Presenter
- ☐ Domain event round-trip: processor emits → Room → consumer receives
- ☐ Crash recovery: checkpoint/restore for each processor

### 9.3 Migration tests
- ☐ Room migration test for domain_event table
- ☐ Dual-run comparison tests (old vs new pipeline output)

---

## 10. Cleanup (Final Phase)

After all of the above is verified:

- ✅ Delete `StatisticDataManager` (was planned for removal)
- ☐ Delete unused `PostTrackerComponent` interface if all components migrated
- ☐ Delete broadcast receiver base classes if no receivers remain
- ☐ Remove `stats-engine` legacy files that were superseded by processor wrappers
- ✅ Update `ARCHITECTURE_OVERVIEW.md` to reflect final state
- ☐ Archive or delete this file

---

## Priority Order

| Priority | Section | Rationale |
|----------|---------|-----------|
| **P0** | §2 Domain Event Table | Blocks all event consumers |
| **P0** | §1.1–1.2 TrackerService hookup | Core integration |
| **P1** | §4 Game event consumer | Validates domain event pattern |
| **P1** | §5 Points event consumer | Small, validates pattern |
| **P1** | §6 Activity event consumer | Small, validates pattern |
| **P1** | §3 Wire presenters | User-visible improvement |
| **P2** | §1.3 Remove PostTrackerComponents | After dual-run validation |
| **P2** | §7 Remove broadcast infra | After all consumers migrated |
| **P2** | §8 Proto DataStore | Performance optimization |
| **P3** | §9 Tests | Ongoing throughout |
| **P3** | §10 Cleanup | Final phase |
