# Stats Architecture — Processor Pipeline Design

> **Status:** Foundation implemented, integration pending  
> **Branch:** `feature/stats-architecture-rework`  
> **Last Updated:** February 2026

---

## Overview

The stats domain has been rearchitected from a hardcoded PostTrackerComponent pipeline + scattered
BroadcastReceivers into a **Processor Pipeline with Room as Integration Bus**. The new system uses:

- **Kotlin Multiplatform** (stats-api, stats-engine) for testable, platform-independent logic
- **Inline value classes** for type-safe domain primitives (no more raw Long/Double confusion)
- **SignalProcessor interface** with Hilt `@IntoSet` multibinding for discovery
- **ProcessorPipeline** with SupervisorJob isolation, mutex-protected delivery, tier filtering
- **Domain events** persisted in Room, replacing broadcast-based inter-module communication
- **Arrow typed errors** replacing exceptions across module boundaries
- **Molecule-style Presenters** replacing ViewModels for UI state

---

## Module Roles

```
┌─────────────────────────────────────────────────────────────────────┐
│                        stats-api (KMP)                               │
│  Contracts: SignalProcessor, Repositories, Value Classes,            │
│  DomainEvent, StatsError, Presenter, TrackingSignal                  │
│  Targets: Android + JVM                                              │
└─────────────────────────────────────────────────────────────────────┘
                                │
                ┌───────────────┼───────────────┐
                ▼                               ▼
┌───────────────────────┐         ┌───────────────────────┐
│   stats-engine (KMP)  │         │    stats-data (AAR)   │
│ Pure algorithms:      │         │ Repository impls:     │
│ - Aggregation         │         │ - Room DAO mapping    │
│ - Segment detection   │         │ - Hilt DI bindings    │
│ - Exploration cells   │         │ - Entity → Domain     │
│ - Achievement rules   │         │                       │
│ + SignalProcessor      │         │                       │
│   wrappers            │         │                       │
│ Targets: Android + JVM│         │                       │
└───────────────────────┘         └───────────────────────┘
                │                               │
                └───────────┬───────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     tracker (pipeline/)                               │
│  ProcessorPipeline — orchestrates all SignalProcessors               │
│  SignalAdapter — raw sensor data → TrackingSignal                    │
│  ProcessorPipelineModule — Hilt @IntoSet multibinding                │
└─────────────────────────────────────────────────────────────────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼                               ▼
┌───────────────────────┐         ┌───────────────────────┐
│  statistics (UI)      │         │    game (events)      │
│ Presenters:           │         │ GameDomainEvent-      │
│ - StatsPresenter      │         │   Consumer            │
│ - HistoryPresenter    │         │ Replaces broadcast    │
│ - TripDetailPresenter │         │   receivers           │
└───────────────────────┘         └───────────────────────┘
```

---

## Data Flow

### Tracking → Processing → Storage → UI

```
Sensors
  │
  ▼
SignalAdapter ─── converts raw data ──→ TrackingSignal (value classes)
  │
  ▼
ProcessorPipeline ─── delivers to all registered SignalProcessors
  │
  ├─→ AggregatorProcessor ──→ LiveStatsDao (distance, steps, duration)
  ├─→ SegmentDetectorProcessor ──→ Trip/DailySummaryEntity + DomainEvent
  ├─→ ExplorationProcessor ──→ ExplorationCellDao
  └─→ AchievementProcessor ──→ AchievementProgressEntity + DomainEvent
                                        │
                                        ▼
                                  Room Database  ◄── Integration Bus
                                        │
                          ┌─────────────┼─────────────┐
                          ▼             ▼             ▼
                    Repositories    DomainEvent    DomainEvent
                    (Flow)         Consumer       Consumer
                          │         (game)        (points)
                          ▼
                    Presenters → Compose UI
```

### Domain Events (replacing Broadcasts)

Old: `TrackerService → sendBroadcast(ACTION_SESSION_FINAL) → ChallengeSessionReceiver`  
New: `SegmentDetectorProcessor → DomainEventRepository.persist(TripCompleted) → GameDomainEventConsumer.processUnconsumed()`

Benefits: crash-safe, ordered, replayable, no Android manifest entries, testable.

---

## Key Types

### Value Classes (stats-api)

| Type | Wraps | Purpose |
|------|-------|---------|
| `SpeedMps` | `Float` | Speed in meters/second |
| `DistanceM` | `Double` | Distance in meters |
| `LatE7` / `LonE7` | `Int` | Coordinates as E7 integers |
| `CoordinateE7` | `LatE7 + LonE7` | Coordinate pair |
| `ActivityConfidence` | `Int` | 0–100 confidence score |
| `StepCount` | `Int` | Step count |
| `EpochMs` | `Long` | Unix timestamp milliseconds |
| `DurationMs` | `Long` | Duration in milliseconds |

### SignalProcessor Interface

```kotlin
interface SignalProcessor {
    val descriptor: ProcessorDescriptor  // id, tier, priority
    suspend fun onStart(context: ProcessorContext)
    fun onSignal(signal: TrackingSignal)  // FAST — no I/O
    suspend fun onFlush()                  // batch persist to Room
    suspend fun onStop()
    suspend fun checkpoint(): ByteArray
    suspend fun restore(data: ByteArray)
}
```

### TrackingSignal

```kotlin
sealed interface TrackingSignal {
    data class Location(val lat: LatE7, val lon: LonE7, val speed: SpeedMps?, ...)
    data class Step(val count: StepCount, val timestamp: EpochMs)
    data class Activity(val type: DetectedActivityType, val confidence: ActivityConfidence)
    data class Cell(val cellToken: String, val timestamp: EpochMs)
    data class Wifi(val bssidHash: String, val rssi: Int, val timestamp: EpochMs)
}
```

### DomainEvent Hierarchy

`SessionStarted`, `SessionEnded`, `TierChanged`, `TripStarted`, `TripCompleted`,
`CellDiscovered`, `AchievementUnlocked`, `AchievementProgress`, `DailySummaryUpdated`

### StatsError (Arrow)

`NotFound`, `DatabaseError`, `InvalidState`, `ProcessorFailed`, `Timeout`

---

## Error Handling

All cross-module operations return `Either<StatsError, T>` (Arrow). No exceptions cross module
boundaries. Presenters map `StatsError` to UI-appropriate sealed states (Loading, Content, Error).

---

## Testing Strategy

- **stats-api + stats-engine**: Pure JVM tests (no Android deps), Kotest assertions
- **stats-data**: Robolectric + Room in-memory DB
- **ProcessorPipeline**: Unit tests with fake processors
- **Presenters**: Turbine for Flow testing, MockK for repositories
- **Integration**: `TestAppGraphBuilder` for full pipeline tests

---

## Related

- `ARCHITECTURE_OVERVIEW.md` — Full app architecture
- `STATS_MIGRATION_TODO.md` — Remaining integration work
