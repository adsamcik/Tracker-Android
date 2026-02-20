# Domain-Driven Design Architecture for Tracker-Android

> **Status**: Architectural proposal — DDD-first redesign  
> **Author**: Architecture review  
> **Scope**: Complete domain model, bounded context map, module layout, aggregate definitions, event flow, and migration strategy

---

## Table of Contents

1. [Strategic Design: Bounded Contexts](#1-strategic-design-bounded-contexts)
2. [Context Map: Relationships](#2-context-map-relationships)
3. [Tactical Design: Aggregates & Entities](#3-tactical-design-aggregates--entities)
4. [Value Objects](#4-value-objects)
5. [Domain Events](#5-domain-events)
6. [Use Cases / Interactors](#6-use-cases--interactors)
7. [Anti-Corruption Layer](#7-anti-corruption-layer)
8. [Module Layout](#8-module-layout)
9. [Concrete Kotlin Code](#9-concrete-kotlin-code)
10. [Walkthrough: Adding "Weekly Streak Detection"](#10-walkthrough-adding-weekly-streak-detection)
11. [Migration from Current Architecture](#11-migration-from-current-architecture)

---

## 1. Strategic Design: Bounded Contexts

"Stats" is **not** a bounded context. It is a word that conflates five distinct domain concepts that have different invariants, lifecycles, and ubiquitous language. The true bounded contexts are:

### 1.1 Tracking Context (Real-time Collection)
**Ubiquitous Language**: session, collection cycle, sensor data, policy tier, tracking run  
**Responsibility**: Orchestrating the foreground service, managing the sensor pipeline, adaptive power policy, raw data persistence  
**Core Domain Problem**: "Collect location/activity/step data at the right frequency while minimizing battery impact"

This context owns the **write path**. It produces raw data. It does not interpret that data beyond what is needed for policy decisions (e.g., "is the user moving?" → escalate to ACTIVE tier).

### 1.2 Trips Context (Movement Interpretation)
**Ubiquitous Language**: trip, leg, segment, departure, arrival, transport mode, route  
**Responsibility**: Detecting trip boundaries, classifying transport modes, compressing routes, matching places  
**Core Domain Problem**: "Transform a continuous stream of location points into meaningful human journeys"

A Trip is not a Session. A single tracking session can contain multiple trips (walk → bus → walk). A trip can span tracking gaps (stop-and-resume). The Trips context interprets raw data from Tracking and produces structured movement records.

### 1.3 Exploration Context (Spatial Discovery)
**Ubiquitous Language**: cell, discovery, quality, visit, coverage, streak, season  
**Responsibility**: Tracking which geographic areas the user has visited, at what quality, discovery streaks  
**Core Domain Problem**: "Map the user's lifetime spatial footprint and reward consistent discovery"

Exploration has its own aggregate (ExplorationMap) with its own invariants (quality never downgrades, visit counts are monotonic, streaks reset on missed days). It is fundamentally different from trip detection — it cares about *where* you've been, not *how* you got there.

### 1.4 Gamification Context (Motivation & Rewards)
**Ubiquitous Language**: achievement, goal, challenge, streak, points, tier, progress, unlock  
**Responsibility**: Defining achievement catalogs, evaluating progress, managing challenges, awarding points  
**Core Domain Problem**: "Motivate continued tracking through meaningful goals and rewards"

Gamification consumes *domain events* from Trips and Exploration but never reaches into their aggregates. When a trip ends, Gamification receives a `TripCompleted` event and updates achievement progress. It has its own persistence (achievement progress, challenge state, point ledger).

### 1.5 Analytics Context (Retrospective Insight)
**Ubiquitous Language**: summary, trend, chart, comparison, record, stat, time range  
**Responsibility**: Computing aggregated views over historical data, daily/weekly/monthly summaries, personal records  
**Core Domain Problem**: "Present meaningful insights about the user's movement patterns over time"

Analytics is a **read model**. It does not produce new domain state — it projects existing state into views optimized for human consumption. DailySummary, WeeklySummary, PersonalRecord are all projections. They can be rebuilt from source data at any time.

### Why Not Fewer Contexts?

Merging Trips + Exploration seems tempting (both use location), but their invariants conflict:
- Trips care about **temporal continuity** (start → end, with legs)
- Exploration cares about **spatial coverage** (cells, regardless of when)
- A trip can cross 50 cells; a cell can be visited in 20 trips
- Exploration quality tracks *how* you visited (walked vs. drove through); trips track *where* you went

Merging Gamification + Analytics seems tempting (both are "downstream"), but:
- Gamification has **write-side effects** (unlock achievements, award points, progress challenges)
- Analytics is a **pure read model** (projections, no side effects)
- Gamification reacts to domain events; Analytics queries historical state

---

## 2. Context Map: Relationships

```
┌──────────────────────────────────────────────────────────────────┐
│                        CONTEXT MAP                               │
│                                                                  │
│  ┌─────────────┐         Domain Events          ┌─────────────┐ │
│  │  TRACKING   │──────────────────────────────>──│   TRIPS     │ │
│  │  (upstream) │   LocationRecorded              │ (downstream)│ │
│  │             │   ActivityDetected              │             │ │
│  │ Owns: raw   │   StepsAccumulated              │ Owns: trip  │ │
│  │ collection, │   TrackingSessionEnded           │ detection,  │ │
│  │ policy,     │                                 │ route, mode │ │
│  │ sensor data │                                 │ place match │ │
│  └──────┬──────┘                                 └──────┬──────┘ │
│         │                                               │        │
│         │ LocationRecorded                              │        │
│         ▼                                               │        │
│  ┌─────────────┐                                        │        │
│  │ EXPLORATION │                                        │        │
│  │ (downstream)│                                        │        │
│  │             │                                        │        │
│  │ Owns: cell  │   CellDiscovered                       │        │
│  │ discovery,  │   StreakUpdated                         │        │
│  │ coverage,   │───────────────┐                        │        │
│  │ streaks     │               │                        │        │
│  └─────────────┘               │   TripCompleted        │        │
│                                ▼   CellDiscovered       ▼        │
│                         ┌──────────────┐                         │
│                         │ GAMIFICATION │                         │
│                         │ (downstream) │                         │
│                         │              │                         │
│                         │ Owns: goals, │                         │
│                         │ challenges,  │                         │
│                         │ achievements,│                         │
│                         │ points       │                         │
│                         └──────┬───────┘                         │
│                                │                                 │
│                                │ (all contexts)                  │
│                                ▼                                 │
│                         ┌──────────────┐                         │
│                         │  ANALYTICS   │                         │
│                         │  (read model)│                         │
│                         │              │                         │
│                         │ Owns: daily/ │                         │
│                         │ weekly/month │                         │
│                         │ summaries,   │                         │
│                         │ records,     │                         │
│                         │ trends       │                         │
│                         └──────────────┘                         │
│                                                                  │
│  Relationship Types:                                             │
│  ──────> Customer/Supplier (upstream publishes, downstream subs) │
│  All communication via Domain Events through Room event table    │
└──────────────────────────────────────────────────────────────────┘
```

### Relationship Semantics

| Upstream | Downstream | Type | Mechanism |
|----------|-----------|------|-----------|
| Tracking | Trips | Customer/Supplier | Domain events via Room |
| Tracking | Exploration | Customer/Supplier | Domain events via Room |
| Trips | Gamification | Customer/Supplier | Domain events via Room |
| Exploration | Gamification | Customer/Supplier | Domain events via Room |
| Trips | Analytics | Published Language | Direct query of Trip read models |
| Exploration | Analytics | Published Language | Direct query of Exploration read models |
| Gamification | Analytics | Published Language | Direct query of Achievement read models |
| sbase (legacy) | All | Anti-Corruption Layer | ACL adapters wrap legacy DAOs |

---

## 3. Tactical Design: Aggregates & Entities

### 3.1 Tracking Context

```
Aggregate: TrackingRun (Aggregate Root)
├── Entity: TrackingRun
│   ├── id: TrackingRunId
│   ├── startTime: Instant
│   ├── endTime: Instant?
│   ├── policyTier: PolicyTier
│   ├── isUserInitiated: Boolean
│   └── status: RunStatus (ACTIVE, COMPLETED, CRASHED)
├── Value Object: CollectionCycle
│   ├── timestamp: Instant
│   ├── location: Coordinates?
│   ├── activity: DetectedActivity?
│   ├── stepDelta: StepCount
│   └── sensorReadings: SensorBundle
└── Value Object: PolicySnapshot
    ├── tier: PolicyTier
    ├── gpsInterval: Duration?
    └── reason: TransitionReason
```

**Invariants**:
- A TrackingRun has exactly one active PolicyTier at any time
- CollectionCycles are append-only within a run
- endTime is null iff status == ACTIVE

**Why TrackingRun is the aggregate root, not TrackerSession**: The legacy `TrackerSession` conflates "a period of tracking" with "accumulated stats about that period." A TrackingRun is purely about the act of collecting data. Statistics about the data are computed by downstream contexts.

### 3.2 Trips Context

```
Aggregate: Trip (Aggregate Root)
├── Entity: Trip
│   ├── id: TripId
│   ├── startTime: Instant
│   ├── endTime: Instant
│   ├── totalDistance: Distance
│   ├── totalSteps: StepCount
│   ├── primaryMode: TransportMode
│   ├── departurePlace: PlaceRef?
│   ├── arrivalPlace: PlaceRef?
│   ├── source: TripSource
│   └── status: TripStatus (DETECTED, CONFIRMED, ENRICHED)
├── Entity: TripLeg (local identity within Trip)
│   ├── sequenceIndex: Int
│   ├── startTime: Instant
│   ├── endTime: Instant
│   ├── distance: Distance
│   ├── mode: TransportMode
│   └── route: CompressedRoute?
└── Value Object: CompressedRoute
    ├── encodedPolyline: String
    ├── sampleCount: Int
    └── boundingBox: BoundingBox

Aggregate: FrequentPlace (separate aggregate)
├── Entity: FrequentPlace
│   ├── id: PlaceId
│   ├── center: Coordinates
│   ├── radius: Distance
│   ├── visitCount: Int
│   ├── firstVisit: Instant
│   ├── lastVisit: Instant
│   └── category: PlaceCategory?
```

**Invariants**:
- A Trip always has >= 1 TripLeg
- TripLegs are ordered by sequenceIndex with no gaps
- Trip.totalDistance == sum(legs.distance) (enforced on construction)
- Quality upgrades: status can only move forward (DETECTED → CONFIRMED → ENRICHED)
- FrequentPlace.visitCount is monotonically increasing

**Why Trip is an aggregate root**: A Trip encapsulates the complete journey with its legs. You never modify a TripLeg independently — you always go through the Trip to maintain the distance invariant. FrequentPlace is a separate aggregate because it has independent lifecycle (visited by many trips, persists indefinitely).

### 3.3 Exploration Context

```
Aggregate: ExplorationMap (Aggregate Root — singleton per user)
├── Entity: DiscoveredCell
│   ├── cellToken: CellToken (S2 cell identifier)
│   ├── level: Int
│   ├── quality: DiscoveryQuality
│   ├── firstDiscoveredAt: Instant
│   ├── lastVisitedAt: Instant
│   ├── visitCount: Int
│   └── seasonMask: SeasonMask
└── Value Object: CoverageStats
    ├── totalCells: Int
    ├── byQuality: Map<DiscoveryQuality, Int>
    └── byLevel: Map<Int, Int>

Aggregate: DiscoveryStreak (separate aggregate)
├── Entity: DiscoveryStreak
│   ├── type: StreakType (DAILY, WEEKLY, CHAIN)
│   ├── currentCount: Int
│   ├── bestCount: Int
│   └── lastIncrementDay: EpochDay
```

**Invariants**:
- DiscoveredCell.quality **never downgrades** (PASSED_THROUGH can become EXPLORED, never reverse)
- visitCount is monotonically increasing
- DiscoveryStreak.bestCount >= currentCount always
- DiscoveryStreak resets to 0 if lastIncrementDay gap > streak window

**Why ExplorationMap is a singleton aggregate**: The entire exploration map is one conceptual thing — "everywhere I've ever been." Individual cells are entities within it, but the aggregate controls quality-upgrade and deduplication invariants. In practice, we shard persistence by cell token for performance, but the domain model treats it as one aggregate.

### 3.4 Gamification Context

```
Aggregate: AchievementProgress (Aggregate Root per achievement)
├── Entity: AchievementProgress
│   ├── achievementId: AchievementId
│   ├── definition: AchievementDefinition (ref)
│   ├── currentValue: Long
│   ├── currentTier: AchievementTier?
│   └── unlockedAt: Instant?

Aggregate: Challenge (Aggregate Root)
├── Entity: Challenge
│   ├── id: ChallengeId
│   ├── type: ChallengeType
│   ├── target: Long
│   ├── progress: Long
│   ├── startTime: Instant
│   ├── endTime: Instant
│   ├── status: ChallengeStatus (ACTIVE, COMPLETED, EXPIRED)
│   └── processedSessions: Set<SessionId>

Aggregate: PointLedger (Aggregate Root — singleton)
├── Value Object: PointEntry
│   ├── amount: Int
│   ├── source: PointSource
│   └── earnedAt: Instant

Aggregate: Goal (Aggregate Root)
├── Entity: Goal
│   ├── type: GoalType (DAILY_STEPS, WEEKLY_STEPS)
│   ├── target: Int
│   ├── currentValue: Int
│   ├── periodStart: Instant
│   └── isCompleted: Boolean
```

**Invariants**:
- AchievementProgress.currentTier can only advance (BRONZE → SILVER → GOLD → DIAMOND)
- Challenge.processedSessions prevents double-counting
- Challenge.status transitions: ACTIVE → COMPLETED | EXPIRED (terminal)
- PointLedger is append-only; points cannot be revoked
- Goal resets at period boundary (daily goals reset at midnight)

### 3.5 Analytics Context (Read Models / Projections)

```
Read Model: DailySummary
├── date: LocalDate
├── totalDistance: Distance
├── totalSteps: StepCount
├── totalDuration: Duration
├── tripCount: Int
├── activeTrackingDuration: Duration
└── topActivity: TransportMode?

Read Model: WeeklySummary
├── weekStart: LocalDate
├── dailySummaries: List<DailySummary>
├── totalDistance: Distance
├── totalSteps: StepCount
├── averageDailyDistance: Distance
├── streakDays: Int
└── comparedToPreviousWeek: TrendDirection

Read Model: PersonalRecord
├── metric: RecordMetric
├── value: Double
├── achievedAt: Instant

Read Model: SessionStats (replaces StatisticDataManager output)
├── sessionId: Long
├── stats: List<StatItem>
└── computedAt: Instant
```

**These are not aggregates** — they are CQRS read models. They can be rebuilt from Trip, Exploration, and Tracking data at any time. They have no invariants beyond data consistency.

---

## 4. Value Objects

Value objects eliminate an entire class of bugs (unit mismatches, negative distances, invalid coordinates) and make the ubiquitous language explicit in code.

### Core Value Objects

```kotlin
// ─── Spatial ───
@JvmInline value class Latitude private constructor(val degrees: Double) {
    init { require(degrees in -90.0..90.0) }
    companion object { fun of(degrees: Double) = Latitude(degrees) }
}

@JvmInline value class Longitude private constructor(val degrees: Double) {
    init { require(degrees in -180.0..180.0) }
    companion object { fun of(degrees: Double) = Longitude(degrees) }
}

data class Coordinates(val lat: Latitude, val lon: Longitude) {
    val latE7: Int get() = (lat.degrees * 1e7).toInt()
    val lonE7: Int get() = (lon.degrees * 1e7).toInt()

    companion object {
        fun fromE7(latE7: Int, lonE7: Int) = Coordinates(
            Latitude.of(latE7 / 1e7),
            Longitude.of(lonE7 / 1e7)
        )
    }
}

@JvmInline value class CellToken(val hex: String) {
    init { require(hex.isNotBlank()) }
}

// ─── Measurement ───
@JvmInline value class Distance private constructor(val meters: Float) {
    init { require(meters >= 0f) }
    val kilometers: Float get() = meters / 1000f
    val miles: Float get() = meters / 1609.344f
    operator fun plus(other: Distance) = Distance(meters + other.meters)
    operator fun compareTo(other: Distance) = meters.compareTo(other.meters)
    companion object {
        val ZERO = Distance(0f)
        fun meters(m: Float) = Distance(m)
        fun kilometers(km: Float) = Distance(km * 1000f)
    }
}

@JvmInline value class Speed private constructor(val metersPerSecond: Float) {
    init { require(metersPerSecond >= 0f) }
    val kmh: Float get() = metersPerSecond * 3.6f
    val mph: Float get() = metersPerSecond * 2.237f
    companion object {
        val ZERO = Speed(0f)
        fun mps(v: Float) = Speed(v)
        fun kmh(v: Float) = Speed(v / 3.6f)
    }
}

@JvmInline value class StepCount(val count: Int) {
    init { require(count >= 0) }
    operator fun plus(other: StepCount) = StepCount(count + other.count)
    companion object { val ZERO = StepCount(0) }
}

@JvmInline value class EpochDay(val day: Long) {
    fun toLocalDate(): LocalDate = LocalDate.ofEpochDay(day)
    companion object {
        fun fromLocalDate(date: LocalDate) = EpochDay(date.toEpochDay())
        fun today(clock: Clock = Clock.systemDefaultZone()) =
            fromLocalDate(LocalDate.now(clock))
    }
}

// ─── Identity ───
@JvmInline value class TripId(val value: Long)
@JvmInline value class PlaceId(val value: Long)
@JvmInline value class TrackingRunId(val value: Long)
@JvmInline value class ChallengeId(val value: Long)
@JvmInline value class AchievementId(val value: String)
```

### Speed Thresholds as a Shared Value Object

This **eliminates the current speed threshold contradictions** between modules:

```kotlin
/**
 * Canonical speed classification thresholds.
 * Single source of truth — replaces scattered constants in
 * ActivityTrackerComponent, TransportModeClassifier, etc.
 */
object SpeedClassification {
    val WALK_MAX = Speed.mps(2.5f)
    val RUN_MIN = Speed.mps(2.0f)
    val RUN_MAX = Speed.mps(6.0f)
    val CYCLE_MIN = Speed.mps(3.0f)
    val CYCLE_MAX = Speed.mps(12.0f)
    val VEHICLE_MIN = Speed.mps(10.0f)
    val DEFINITELY_VEHICLE = Speed.mps(15.0f)
    val HIGH_SPEED_RAIL = Speed.mps(40.0f)

    fun classify(speed: Speed, activity: DetectedActivityType?): TransportMode =
        when {
            speed.metersPerSecond <= WALK_MAX.metersPerSecond -> TransportMode.WALK
            speed.metersPerSecond <= RUN_MAX.metersPerSecond ->
                if (activity == DetectedActivityType.RUNNING) TransportMode.RUN
                else TransportMode.WALK
            speed.metersPerSecond <= CYCLE_MAX.metersPerSecond ->
                if (activity == DetectedActivityType.ON_BICYCLE) TransportMode.CYCLE
                else TransportMode.DRIVE
            else -> TransportMode.DRIVE
        }
}
```

---

## 5. Domain Events

Domain events are the **integration backbone**. Each bounded context publishes events when significant state changes occur. Other contexts subscribe and react independently.

### 5.1 Event Taxonomy

```kotlin
/**
 * Base for all domain events. Sealed at the context level,
 * open across contexts for extensibility.
 */
interface DomainEvent {
    val eventId: UUID
    val occurredAt: Instant
    val contextName: String
}

// ─── Tracking Context Events ───
sealed interface TrackingEvent : DomainEvent {
    override val contextName: String get() = "tracking"
}

data class LocationRecorded(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val coordinates: Coordinates,
    val accuracy: Float,
    val speed: Speed?,
    val trackingRunId: TrackingRunId,
) : TrackingEvent

data class StepsAccumulated(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val stepDelta: StepCount,
    val trackingRunId: TrackingRunId,
) : TrackingEvent

data class ActivityDetected(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val activityType: DetectedActivityType,
    val confidence: Int,
) : TrackingEvent

data class TrackingRunStarted(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val runId: TrackingRunId,
    val isUserInitiated: Boolean,
    val initialTier: PolicyTier,
) : TrackingEvent

data class TrackingRunEnded(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val runId: TrackingRunId,
    val reason: EndReason, // USER_STOPPED, AUTO_STOPPED, CRASH_RECOVERED
) : TrackingEvent

// ─── Trips Context Events ───
sealed interface TripEvent : DomainEvent {
    override val contextName: String get() = "trips"
}

data class TripStarted(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val tripId: TripId,
    val triggerActivity: DetectedActivityType?,
) : TripEvent

data class TripCompleted(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val tripId: TripId,
    val distance: Distance,
    val steps: StepCount,
    val duration: Duration,
    val primaryMode: TransportMode,
    val legCount: Int,
    val departurePlace: PlaceId?,
    val arrivalPlace: PlaceId?,
) : TripEvent

data class TripEnriched(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val tripId: TripId,
    val departurePlace: PlaceId?,
    val arrivalPlace: PlaceId?,
) : TripEvent

data class TransportModeChanged(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val tripId: TripId,
    val fromMode: TransportMode,
    val toMode: TransportMode,
    val legIndex: Int,
) : TripEvent

// ─── Exploration Context Events ───
sealed interface ExplorationEvent : DomainEvent {
    override val contextName: String get() = "exploration"
}

data class CellDiscovered(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val cellToken: CellToken,
    val quality: DiscoveryQuality,
    val isFirstVisit: Boolean,
    val totalCellCount: Int,
) : ExplorationEvent

data class CellQualityUpgraded(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val cellToken: CellToken,
    val previousQuality: DiscoveryQuality,
    val newQuality: DiscoveryQuality,
) : ExplorationEvent

data class DiscoveryStreakUpdated(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val streakType: StreakType,
    val currentCount: Int,
    val isNewBest: Boolean,
) : ExplorationEvent

// ─── Gamification Context Events ───
sealed interface GamificationEvent : DomainEvent {
    override val contextName: String get() = "gamification"
}

data class AchievementUnlocked(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val achievementId: AchievementId,
    val tier: AchievementTier,
) : GamificationEvent

data class AchievementProgressed(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val achievementId: AchievementId,
    val previousValue: Long,
    val newValue: Long,
    val currentTier: AchievementTier?,
    val nextTier: AchievementTier?,
) : GamificationEvent

data class ChallengeCompleted(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val challengeId: ChallengeId,
    val pointsAwarded: Int,
) : GamificationEvent

data class GoalAchieved(
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant,
    val goalType: GoalType,
    val value: Int,
    val target: Int,
) : GamificationEvent
```

### 5.2 Event Bus: Room as the Integration Bus

We use Room — not an in-memory event bus — because:
1. **Crash recovery**: Events survive process death. If the app crashes between writing a trip and updating achievements, the achievement update resumes on restart.
2. **Ordering guarantees**: Auto-increment IDs provide total ordering.
3. **Replay capability**: Contexts can replay events they missed (e.g., after a version upgrade adds a new projection).
4. **Audit trail**: The event table is a permanent log of everything that happened.
5. **No new dependencies**: Already have Room; no need for EventBus/RxRelay/etc.

```kotlin
@Entity(tableName = "domain_event")
data class DomainEventRecord(
    @PrimaryKey(autoGenerate = true)
    val sequenceNumber: Long = 0,
    val eventId: String,         // UUID
    val contextName: String,     // "tracking", "trips", etc.
    val eventType: String,       // Kotlin class simple name
    val payload: String,         // JSON-serialized event
    val occurredAt: Long,        // epoch millis
    val publishedAt: Long,       // when written to table
)

@Entity(tableName = "event_consumer_offset")
data class EventConsumerOffset(
    @PrimaryKey
    val consumerId: String,      // "gamification", "analytics", etc.
    val lastProcessedSequence: Long,
    val updatedAt: Long,
)

@Dao
interface DomainEventDao {
    @Insert
    suspend fun publish(event: DomainEventRecord): Long

    @Query("""
        SELECT * FROM domain_event
        WHERE sequenceNumber > :afterSequence
        AND contextName IN (:contexts)
        ORDER BY sequenceNumber ASC
        LIMIT :limit
    """)
    suspend fun readEvents(
        afterSequence: Long,
        contexts: List<String>,
        limit: Int = 100,
    ): List<DomainEventRecord>

    @Query("""
        SELECT * FROM domain_event
        WHERE sequenceNumber > :afterSequence
        ORDER BY sequenceNumber ASC
        LIMIT :limit
    """)
    suspend fun readAllEvents(
        afterSequence: Long,
        limit: Int = 100,
    ): List<DomainEventRecord>

    @Upsert
    suspend fun updateOffset(offset: EventConsumerOffset)

    @Query("SELECT lastProcessedSequence FROM event_consumer_offset WHERE consumerId = :consumerId")
    suspend fun getOffset(consumerId: String): Long?
}
```

### 5.3 Event Dispatcher

```kotlin
/**
 * Publishes domain events to the Room event table and notifies
 * in-process listeners for real-time reactivity.
 */
class DomainEventPublisher @Inject constructor(
    private val eventDao: DomainEventDao,
    private val serializer: DomainEventSerializer,
    private val clock: Clock,
) {
    // In-process hot signal for real-time subscribers (e.g., live UI updates)
    private val _events = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DomainEvent> = _events.asSharedFlow()

    suspend fun publish(event: DomainEvent) {
        val record = DomainEventRecord(
            eventId = event.eventId.toString(),
            contextName = event.contextName,
            eventType = event::class.simpleName ?: "Unknown",
            payload = serializer.serialize(event),
            occurredAt = event.occurredAt.toEpochMilli(),
            publishedAt = clock.millis(),
        )
        eventDao.publish(record)
        _events.emit(event)
    }

    suspend fun publishAll(events: List<DomainEvent>) {
        events.forEach { publish(it) }
    }
}

/**
 * Consumes domain events with at-least-once delivery guarantee.
 * Each consumer tracks its own offset and can replay missed events.
 */
abstract class DomainEventConsumer(
    private val consumerId: String,
    private val sourceContexts: List<String>,
    private val eventDao: DomainEventDao,
    private val serializer: DomainEventSerializer,
) {
    suspend fun processNewEvents() {
        val lastOffset = eventDao.getOffset(consumerId) ?: 0L
        val events = eventDao.readEvents(lastOffset, sourceContexts)
        if (events.isEmpty()) return

        for (record in events) {
            val event = serializer.deserialize(record.payload, record.eventType)
            handle(event)
            eventDao.updateOffset(
                EventConsumerOffset(consumerId, record.sequenceNumber, System.currentTimeMillis())
            )
        }
    }

    abstract suspend fun handle(event: DomainEvent)
}
```

---

## 6. Use Cases / Interactors

Use cases sit between repositories and ViewModels. They encode **business rules that don't belong in any single aggregate** and orchestrate cross-aggregate operations.

### Why Use Cases?

Current problem: `StatsViewModel` injects `DailySummaryDao` and `TripDao` directly. This means:
- Business logic (how to compute a weekly summary) lives in the ViewModel
- The ViewModel is coupled to Room — untestable without Robolectric
- Two ViewModels needing the same logic must duplicate it

Use cases solve all three problems.

### Use Case Catalog

```kotlin
// ─── Trips Context ───
class GetTripsForDateRangeUseCase @Inject constructor(
    private val tripRepository: TripRepository,
) {
    suspend operator fun invoke(range: ClosedRange<LocalDate>): List<Trip> =
        tripRepository.getTripsInRange(range.start, range.endInclusive)
}

class GetTripDetailUseCase @Inject constructor(
    private val tripRepository: TripRepository,
    private val placeRepository: PlaceRepository,
    private val routeRepository: RouteRepository,
) {
    suspend operator fun invoke(tripId: TripId): TripDetail {
        val trip = tripRepository.getById(tripId) ?: throw TripNotFoundException(tripId)
        val legs = tripRepository.getLegsForTrip(tripId)
        val departure = trip.departurePlace?.let { placeRepository.getById(it) }
        val arrival = trip.arrivalPlace?.let { placeRepository.getById(it) }
        val route = routeRepository.getForTrip(tripId)
        return TripDetail(trip, legs, departure, arrival, route)
    }
}

// ─── Analytics Context ───
class GetWeeklySummaryUseCase @Inject constructor(
    private val dailySummaryRepository: DailySummaryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(weekStart: LocalDate? = null): WeeklySummary {
        val start = weekStart ?: LocalDate.now(clock).with(DayOfWeek.MONDAY)
        val end = start.plusDays(6)
        val dailies = dailySummaryRepository.getBetween(start, end)
        val previousWeekDailies = dailySummaryRepository.getBetween(
            start.minusWeeks(1), end.minusWeeks(1)
        )
        return WeeklySummary.from(start, dailies, previousWeekDailies)
    }
}

class GetPersonalRecordsUseCase @Inject constructor(
    private val recordRepository: PersonalRecordRepository,
) {
    suspend operator fun invoke(): List<PersonalRecord> =
        recordRepository.getAll()
}

// ─── Exploration Context ───
class GetExplorationStatsUseCase @Inject constructor(
    private val explorationRepository: ExplorationRepository,
    private val streakRepository: StreakRepository,
) {
    suspend operator fun invoke(): ExplorationOverview {
        val coverage = explorationRepository.getCoverageStats()
        val streaks = streakRepository.getAll()
        val recentDiscoveries = explorationRepository.getRecentDiscoveries(limit = 20)
        return ExplorationOverview(coverage, streaks, recentDiscoveries)
    }
}

// ─── Gamification Context ───
class GetAchievementSnapshotsUseCase @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val catalog: AchievementCatalog,
    private val evaluator: AchievementEvaluator,
) {
    suspend operator fun invoke(): List<AchievementSnapshot> =
        catalog.all().map { definition ->
            val progress = achievementRepository.getProgress(AchievementId(definition.id))
            evaluator.evaluate(definition, progress?.currentValue ?: 0L)
        }
}

class ProcessTripForGamificationUseCase @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val challengeRepository: ChallengeRepository,
    private val goalRepository: GoalRepository,
    private val pointLedger: PointLedgerRepository,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: TripCompleted) {
        // Update distance achievements
        updateAchievement("distance_total", event.distance.meters.toLong())
        updateAchievement("distance_single_trip", event.distance.meters.toLong())

        // Update step achievements
        updateAchievement("steps_total", event.steps.count.toLong())

        // Update mode achievements
        updateAchievement("mode_${event.primaryMode.name.lowercase()}_trips", 1)

        // Process active challenges
        challengeRepository.getActive().forEach { challenge ->
            challenge.applyTrip(event)
            challengeRepository.save(challenge)
            if (challenge.isCompleted) {
                val points = challenge.calculatePoints()
                pointLedger.credit(points, PointSource.CHALLENGE, clock.instant())
                eventPublisher.publish(ChallengeCompleted(
                    occurredAt = clock.instant(),
                    challengeId = challenge.id,
                    pointsAwarded = points,
                ))
            }
        }

        // Update daily goals
        goalRepository.getDailyStepGoal()?.let { goal ->
            goal.addSteps(event.steps)
            goalRepository.save(goal)
            if (goal.isCompleted) {
                eventPublisher.publish(GoalAchieved(
                    occurredAt = clock.instant(),
                    goalType = GoalType.DAILY_STEPS,
                    value = goal.currentValue,
                    target = goal.target,
                ))
            }
        }
    }

    private suspend fun updateAchievement(metric: String, delta: Long) {
        val progress = achievementRepository.getOrCreate(AchievementId(metric))
        val oldTier = progress.currentTier
        progress.increment(delta)
        achievementRepository.save(progress)
        val newTier = progress.currentTier
        if (newTier != null && newTier != oldTier) {
            eventPublisher.publish(AchievementUnlocked(
                occurredAt = clock.instant(),
                achievementId = AchievementId(metric),
                tier = newTier,
            ))
        }
    }
}
```

---

## 7. Anti-Corruption Layer

The legacy `sbase` module contains `TrackerSession`, `SessionDataDao`, and 25+ entities that encode the old session-centric worldview. The new domain model must coexist without corruption.

### Strategy: Adapter Pattern at Repository Boundary

```kotlin
/**
 * Maps between legacy TrackerSession/SessionDataDao and the new
 * Tracking context's TrackingRun domain model.
 *
 * This adapter lives in stats-data (or a dedicated acl module) and is the
 * ONLY place where legacy types appear. Domain code never sees TrackerSession.
 */
class LegacySessionAdapter @Inject constructor(
    private val sessionDao: SessionDataDao,
    private val clock: Clock,
) : TrackingRunRepository {

    override suspend fun getById(id: TrackingRunId): TrackingRun? {
        val session = sessionDao.get(id.value) ?: return null
        return session.toDomain()
    }

    override suspend fun getActive(): TrackingRun? {
        // Legacy: active session has no end time
        return sessionDao.getAll()
            .firstOrNull { it.end == (-1L) }
            ?.toDomain()
    }

    override fun observeActive(): Flow<TrackingRun?> = flow {
        // Bridge legacy query to Flow
        while (currentCoroutineContext().isActive) {
            emit(getActive())
            delay(5_000) // Poll interval until legacy is replaced with Room Flow
        }
    }

    private fun TrackerSession.toDomain() = TrackingRun(
        id = TrackingRunId(id),
        startTime = Instant.ofEpochMilli(start),
        endTime = if (end == -1L) null else Instant.ofEpochMilli(end),
        policyTier = PolicyTier.ACTIVE, // Legacy didn't track this
        isUserInitiated = isUserInitiated,
        status = if (end == -1L) RunStatus.ACTIVE else RunStatus.COMPLETED,
    )
}

/**
 * Adapts legacy TripDao (which reads SessionSegment as Trip POJO)
 * to the new Trips context's TripRepository.
 */
class LegacyTripAdapter @Inject constructor(
    private val tripDao: TripDao,
    private val inferredTripDao: InferredTripDao,
    private val tripLegDao: TripLegDao,
) : TripRepository {

    override suspend fun getById(id: TripId): Trip? {
        // Prefer new InferredTrip if available, fall back to legacy Trip
        val inferred = inferredTripDao.getById(id.value)
        if (inferred != null) return inferred.toDomain()

        return tripDao.getById(id.value)?.toDomain()
    }

    override fun getAllPaged(): PagingSource<Int, Trip> {
        // Delegate to existing paged query, map results
        return tripDao.getAllPaged().map { it.toDomain() }
    }

    override suspend fun getTripsInRange(from: LocalDate, to: LocalDate): List<Trip> {
        val fromMs = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return tripDao.getBetween(fromMs, toMs).map { it.toDomain() }
    }

    // ─── Legacy Trip POJO → Domain Trip ───
    private fun com.adsamcik.tracker.shared.base.database.data.Trip.toDomain() = Trip(
        id = TripId(id),
        startTime = Instant.ofEpochMilli(startTimeMs),
        endTime = Instant.ofEpochMilli(endTimeMs),
        totalDistance = Distance.meters(distanceM),
        totalSteps = steps?.let { StepCount(it) } ?: StepCount.ZERO,
        primaryMode = primaryActivity.toTransportMode(),
        departurePlace = null, // Legacy doesn't have place matching
        arrivalPlace = null,
        source = source.toDomain(),
        status = TripStatus.CONFIRMED,
    )
}
```

### ACL Placement in Module Graph

```
┌─────────┐     ┌─────────────────┐     ┌─────────────┐
│  sbase  │ ──> │  stats-data     │ ──> │  stats-api   │
│ (legacy │     │  (ACL adapters  │     │  (domain     │
│  DAOs)  │     │   + new repos)  │     │   contracts) │
└─────────┘     └─────────────────┘     └─────────────┘
                        ↑                       ↑
                 ┌──────┘                       │
                 │                              │
          ┌──────────┐                   ┌──────────┐
          │statistics│                   │  game    │
          │ (UI)     │                   │  (UI)    │
          └──────────┘                   └──────────┘
```

The ACL adapters live in `stats-data`. UI modules (`statistics`, `game`) depend only on `stats-api` interfaces. They never see legacy types.

---

## 8. Module Layout

### Target Module Structure

```
stats-api/          ← Domain contracts (pure Kotlin, zero Android deps)
├── event/          ← Domain event interfaces & types
│   ├── DomainEvent.kt
│   ├── TrackingEvent.kt
│   ├── TripEvent.kt
│   ├── ExplorationEvent.kt
│   └── GamificationEvent.kt
├── model/          ← Aggregate interfaces, value objects
│   ├── vo/         ← Value objects (Distance, Speed, Coordinates, etc.)
│   ├── tracking/   ← TrackingRun, PolicyTier, CollectionCycle
│   ├── trip/       ← Trip, TripLeg, FrequentPlace, TransportMode
│   ├── exploration/← DiscoveredCell, DiscoveryStreak, CoverageStats
│   ├── gamification/← Achievement*, Challenge, Goal, PointEntry
│   └── analytics/  ← DailySummary, WeeklySummary, PersonalRecord
├── repository/     ← Repository interfaces (ports)
│   ├── TripRepository.kt
│   ├── PlaceRepository.kt
│   ├── ExplorationRepository.kt
│   ├── AchievementRepository.kt
│   ├── ChallengeRepository.kt
│   ├── GoalRepository.kt
│   ├── DailySummaryRepository.kt
│   ├── PersonalRecordRepository.kt
│   └── DomainEventRepository.kt
├── usecase/        ← Use case interfaces (optional; concrete in stats-engine)
├── classification/ ← SpeedClassification, ActivityClassification
├── policy/         ← PolicyEscalationEngine (existing)
├── segment/        ← SegmentEvent, SegmentSignal (existing)
└── achievement/    ← AchievementDefinition, AchievementTier (existing)

stats-engine/       ← Domain logic implementation (pure Kotlin + coroutines)
├── usecase/        ← Concrete use case implementations
│   ├── trip/       ← GetTripDetailUseCase, GetTripsForDateRangeUseCase
│   ├── exploration/← GetExplorationStatsUseCase
│   ├── gamification/← ProcessTripForGamificationUseCase, GetAchievementsUseCase
│   └── analytics/  ← GetWeeklySummaryUseCase, GetPersonalRecordsUseCase
├── aggregator/     ← StreamingAggregator (existing)
├── segment/        ← SessionSegmentDetector (existing)
├── exploration/    ← CellDiscoveryEngine (existing)
├── place/          ← TripEnricher, PlaceCluster (existing)
├── compression/    ← RouteCompressor (existing)
├── achievement/    ← AchievementCatalog, AchievementEvaluator (existing)
├── policy/         ← DefaultPolicyEscalationEngine (existing)
└── event/          ← DomainEventSerializer, consumer base classes

stats-data/         ← Repository implementations, ACL, Room integration
├── repository/     ← Default*Repository implementations
│   ├── DefaultTripRepository.kt
│   ├── DefaultPlaceRepository.kt
│   ├── DefaultExplorationRepository.kt
│   ├── DefaultAchievementRepository.kt
│   ├── DefaultChallengeRepository.kt
│   ├── DefaultDailySummaryRepository.kt
│   └── DefaultDomainEventRepository.kt
├── acl/            ← Anti-Corruption Layer adapters
│   ├── LegacySessionAdapter.kt
│   ├── LegacyTripAdapter.kt
│   └── LegacyLocationAdapter.kt
├── dao/            ← New DAOs for domain event table, consumer offsets
│   └── DomainEventDao.kt
├── entity/         ← Room entities for domain_event, event_consumer_offset
└── mapper/         ← Entity ↔ Domain model mappers

tracker/            ← Tracking context (owns foreground service)
├── service/        ← TrackerService, TrackerComponentManager (existing)
├── component/      ← Pipeline components (existing, refactored to use DI)
│   ├── producer/   ← Sensor data producers
│   ├── consumer/   ← Pre/Data/Post components
│   │   └── post/   ← Writers now publish domain events
│   └── trigger/    ← Collection triggers
├── event/          ← TrackingEventPublisher (wraps DomainEventPublisher)
└── policy/         ← TrackingPolicyManager (existing)

statistics/         ← Analytics context UI (Compose)
├── ui/             ← Screens, routes
├── viewmodel/      ← ViewModels inject use cases, NOT DAOs
└── repository/     ← (removed; use cases replace direct repo access)

game/               ← Gamification context UI + domain logic
├── ui/             ← Compose screens
├── viewmodel/      ← ViewModels inject use cases
├── event/          ← GamificationEventConsumer (reacts to Trip/Exploration events)
├── worker/         ← ChallengeWorker, NewDayGoalWorker (existing)
└── challenge/      ← Challenge domain logic (existing)
```

### Module Dependency Graph

```
                    stats-api (pure Kotlin)
                   /     |     \        \
                  /      |      \        \
          stats-engine   |   stats-data   \
          (pure + coroutines)  (Room, ACL)  \
               |         |    /    |         \
               |         |   /     |          \
            tracker   statistics  game      sbase (legacy)
            (Android)  (Compose)  (Compose)  (Room entities)
```

**Rules**:
- `stats-api` has ZERO Android dependencies
- `stats-engine` depends only on `stats-api` + Kotlin coroutines
- `stats-data` depends on `stats-api` + `stats-engine` + `sbase` (for ACL)
- UI modules (`statistics`, `game`) depend on `stats-api` + `stats-data` via Hilt
- `tracker` depends on `stats-api` + `stats-engine` (for real-time processors)

---

## 9. Concrete Kotlin Code

### 9.1 Repository Interfaces (stats-api)

```kotlin
// stats-api/repository/TripRepository.kt
interface TripRepository {
    suspend fun getById(id: TripId): Trip?
    suspend fun getTripsInRange(from: LocalDate, to: LocalDate): List<Trip>
    suspend fun getLegsForTrip(tripId: TripId): List<TripLeg>
    fun getAllPaged(): PagingSource<Int, Trip>
    suspend fun save(trip: Trip)
    fun observeTripsToday(clock: Clock): Flow<List<Trip>>
}

// stats-api/repository/ExplorationRepository.kt
interface ExplorationRepository {
    suspend fun getCoverageStats(): CoverageStats
    suspend fun getRecentDiscoveries(limit: Int): List<DiscoveredCell>
    suspend fun getCellsByQuality(quality: DiscoveryQuality): List<DiscoveredCell>
    suspend fun upsertCell(cell: DiscoveredCell)
    fun observeTotalCellCount(): Flow<Int>
}

// stats-api/repository/DailySummaryRepository.kt
interface DailySummaryRepository {
    suspend fun getForDate(date: LocalDate): DailySummary?
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailySummary>
    suspend fun upsert(summary: DailySummary)
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>>
}

// stats-api/repository/AchievementRepository.kt
interface AchievementRepository {
    suspend fun getProgress(id: AchievementId): AchievementProgress?
    suspend fun getOrCreate(id: AchievementId): AchievementProgress
    suspend fun save(progress: AchievementProgress)
    suspend fun getAll(): List<AchievementProgress>
    fun observeAll(): Flow<List<AchievementProgress>>
}
```

### 9.2 Hilt Wiring (app module)

```kotlin
// app/di/DomainModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds @Singleton
    abstract fun tripRepository(impl: DefaultTripRepository): TripRepository

    @Binds @Singleton
    abstract fun explorationRepository(impl: DefaultExplorationRepository): ExplorationRepository

    @Binds @Singleton
    abstract fun dailySummaryRepository(impl: DefaultDailySummaryRepository): DailySummaryRepository

    @Binds @Singleton
    abstract fun achievementRepository(impl: DefaultAchievementRepository): AchievementRepository

    @Binds @Singleton
    abstract fun challengeRepository(impl: DefaultChallengeRepository): ChallengeRepository

    @Binds @Singleton
    abstract fun goalRepository(impl: DefaultGoalRepository): GoalRepository

    @Binds @Singleton
    abstract fun personalRecordRepository(impl: DefaultPersonalRecordRepository): PersonalRecordRepository

    @Binds @Singleton
    abstract fun domainEventRepository(impl: DefaultDomainEventRepository): DomainEventRepository

    companion object {
        @Provides @Singleton
        fun domainEventPublisher(
            eventDao: DomainEventDao,
            serializer: DomainEventSerializer,
            clock: Clock,
        ): DomainEventPublisher = DomainEventPublisher(eventDao, serializer, clock)

        @Provides @Singleton
        fun domainEventSerializer(): DomainEventSerializer = MoshiDomainEventSerializer()
    }
}
```

### 9.3 Refactored ViewModel (statistics module)

```kotlin
// Before (current — anti-pattern):
// @HiltViewModel
// class StatsViewModel @Inject constructor(
//     private val dailySummaryDao: DailySummaryDao,  // ← DAO leak!
//     private val tripDao: TripDao,                  // ← DAO leak!
// ) : ViewModel()

// After (DDD):
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val getWeeklySummary: GetWeeklySummaryUseCase,
    private val getPersonalRecords: GetPersonalRecordsUseCase,
    private val tripRepository: TripRepository,
) : ViewModel() {

    val trips: Flow<PagingData<Trip>> = Pager(PagingConfig(pageSize = 20)) {
        tripRepository.getAllPaged()
    }.flow.cachedIn(viewModelScope)

    private val _weeklySummary = MutableStateFlow<UiState<WeeklySummary>>(UiState.Loading)
    val weeklySummary: StateFlow<UiState<WeeklySummary>> = _weeklySummary.asStateFlow()

    private val _records = MutableStateFlow<UiState<List<PersonalRecord>>>(UiState.Loading)
    val records: StateFlow<UiState<List<PersonalRecord>>> = _records.asStateFlow()

    init {
        viewModelScope.launch {
            _weeklySummary.value = try {
                UiState.Success(getWeeklySummary())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load weekly summary")
            }
        }
        viewModelScope.launch {
            _records.value = try {
                UiState.Success(getPersonalRecords())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load records")
            }
        }
    }
}
```

### 9.4 Event-Driven Writer (tracker module)

```kotlin
// Refactored SessionSegmentWriter to publish domain events
class SessionSegmentWriter @Inject constructor(
    private val detector: SessionSegmentDetector,
    private val escalationEngine: PolicyEscalationEngine,
    private val tripEnricher: TripEnricher,
    private val eventPublisher: DomainEventPublisher,
    private val segmentDao: SessionSegmentDao,
    private val inferredTripDao: InferredTripDao,
    private val clock: Clock,
) : PostTrackerComponent() {

    override suspend fun onNewData(data: CollectionTempData) {
        val signal = data.toSegmentSignal()
        val events = detector.processSignal(signal)

        for (event in events) {
            when (event) {
                is SegmentEvent.TripStarted -> {
                    escalationEngine.setMinimumTier(PolicyTier.ACTIVE)
                    eventPublisher.publish(
                        TripStarted(
                            occurredAt = Instant.ofEpochMilli(event.startTimeMs),
                            tripId = TripId(0), // Assigned on persistence
                            triggerActivity = event.triggerActivity,
                        )
                    )
                }

                is SegmentEvent.TripEnded -> {
                    escalationEngine.clearMinimumTier()

                    // Persist segment
                    val segment = event.toSessionSegment()
                    val segmentId = segmentDao.insert(segment)

                    // Enrich with places
                    val enriched = tripEnricher.enrich(event, segmentId)
                    val tripId = inferredTripDao.insert(enriched)

                    // Publish domain event
                    eventPublisher.publish(
                        TripCompleted(
                            occurredAt = clock.instant(),
                            tripId = TripId(tripId),
                            distance = Distance.meters(event.totalDistanceM),
                            steps = StepCount(event.totalSteps),
                            duration = Duration.ofMillis(event.endTimeMs - event.startTimeMs),
                            primaryMode = event.inferredTransportMode,
                            legCount = 1,
                            departurePlace = enriched.departurePlaceId?.let { PlaceId(it) },
                            arrivalPlace = enriched.arrivalPlaceId?.let { PlaceId(it) },
                        )
                    )
                }

                is SegmentEvent.TripUpdated -> { /* Update live stats */ }
                is SegmentEvent.DepartureCancelled -> {
                    escalationEngine.clearMinimumTier()
                }
            }
        }
    }
}
```

### 9.5 Gamification Event Consumer

```kotlin
/**
 * Reacts to Trip and Exploration events to update achievements,
 * challenges, goals, and points.
 *
 * Runs via WorkManager for crash recovery. On each run, processes
 * all events since last offset.
 */
@HiltWorker
class GamificationEventWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val eventDao: DomainEventDao,
    private val serializer: DomainEventSerializer,
    private val processTripUseCase: ProcessTripForGamificationUseCase,
    private val processExplorationUseCase: ProcessExplorationForGamificationUseCase,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val consumerId = "gamification"
        val lastOffset = eventDao.getOffset(consumerId) ?: 0L
        val events = eventDao.readEvents(
            afterSequence = lastOffset,
            contexts = listOf("trips", "exploration"),
        )

        if (events.isEmpty()) return Result.success()

        for (record in events) {
            val event = serializer.deserialize(record.payload, record.eventType)
            when (event) {
                is TripCompleted -> processTripUseCase(event)
                is CellDiscovered -> processExplorationUseCase(event)
                is DiscoveryStreakUpdated -> processExplorationUseCase.onStreak(event)
                else -> { /* Ignore unknown events */ }
            }
            eventDao.updateOffset(
                EventConsumerOffset(consumerId, record.sequenceNumber, System.currentTimeMillis())
            )
        }

        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "gamification_event_processor",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<GamificationEventWorker>().build()
            )
        }
    }
}
```

### 9.6 Component Registration via Hilt Multibinding

Replaces hardcoded component lists in `TrackerComponentManager`:

```kotlin
// stats-api/tracking/TrackerComponent.kt
interface TrackerComponent {
    val phase: ComponentPhase
    val requirements: Set<ComponentRequirement>
    suspend fun onEnable()
    suspend fun onDisable()
}

enum class ComponentPhase { PRE, DATA, SESSION, POST }

enum class ComponentRequirement {
    FINE_LOCATION, COARSE_LOCATION, ACTIVITY_RECOGNITION,
    STEP_COUNTER, WIFI, TELEPHONY, GPS_TIER,
}

// tracker/di/TrackerComponentModule.kt
@Module
@InstallIn(ServiceComponent::class)
abstract class TrackerComponentModule {

    @Binds @IntoSet
    abstract fun locationPreComponent(impl: PolicyAwareLocationPreTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun activityComponent(impl: ActivityTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun locationComponent(impl: LocationTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun cellComponent(impl: CellTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun wifiComponent(impl: WifiTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun sessionComponent(impl: SessionTrackerComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun segmentWriter(impl: SessionSegmentWriter): TrackerComponent

    @Binds @IntoSet
    abstract fun aggregatorWriter(impl: StreamingAggregatorWriter): TrackerComponent

    @Binds @IntoSet
    abstract fun explorationWriter(impl: ExplorationWriter): TrackerComponent

    @Binds @IntoSet
    abstract fun dbLocationComponent(impl: DatabaseLocationComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun dbCellComponent(impl: DatabaseCellComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun dbWifiComponent(impl: DatabaseWifiComponent): TrackerComponent

    @Binds @IntoSet
    abstract fun notificationComponent(impl: NotificationComponent): TrackerComponent
}

// Refactored TrackerComponentManager
class TrackerComponentManager @Inject constructor(
    private val allComponents: Set<@JvmSuppressWildcards TrackerComponent>,
) {
    private val activeComponents = mutableListOf<TrackerComponent>()

    fun enableForTier(tier: PolicyTier, grantedPermissions: Set<ComponentRequirement>) {
        val eligible = allComponents.filter { component ->
            component.requirements.all { it in grantedPermissions } &&
                (tier.isGpsEnabled || ComponentRequirement.GPS_TIER !in component.requirements)
        }

        val toDisable = activeComponents - eligible.toSet()
        val toEnable = eligible - activeComponents.toSet()

        toDisable.forEach { it.onDisable() }
        toEnable.forEach { it.onEnable() }

        activeComponents.clear()
        activeComponents.addAll(eligible)
    }

    suspend fun runCycle(data: CollectionTempData) {
        val byPhase = activeComponents.groupBy { it.phase }
        for (phase in ComponentPhase.entries) {
            byPhase[phase]?.forEach { it.onNewData(data) }
        }
    }
}
```

---

## 10. Walkthrough: Adding "Weekly Streak Detection"

This demonstrates how the DDD architecture makes new features trivial to add without touching existing code.

### Requirement
> Track consecutive weeks where the user tracked at least 3 days. Award an achievement at 4, 12, 26, and 52 week streaks.

### Step 1: Define the Achievement (stats-engine, AchievementCatalog)

```kotlin
// Add to existing AchievementCatalog.kt
val WEEKLY_STREAK = AchievementDefinition(
    id = "streak_weekly_tracking",
    category = AchievementCategory.STREAKS,
    titleRes = "achievement_weekly_streak_title",
    descriptionRes = "achievement_weekly_streak_desc",
    metric = "streak_weekly_tracking",
    tiers = mapOf(
        AchievementTier.BRONZE to 4L,    // 1 month
        AchievementTier.SILVER to 12L,   // 3 months
        AchievementTier.GOLD to 26L,     // 6 months
        AchievementTier.DIAMOND to 52L,  // 1 year
    )
)
```

No existing code changes — the catalog is additive.

### Step 2: Add Streak Entity (already exists!)

`ExplorationStreakEntity` already supports arbitrary streak types via the `type: String` primary key. We just use `type = "WEEKLY_TRACKING"`.

### Step 3: Create the Use Case (stats-engine)

```kotlin
// stats-engine/usecase/analytics/UpdateWeeklyTrackingStreakUseCase.kt
class UpdateWeeklyTrackingStreakUseCase @Inject constructor(
    private val dailySummaryRepository: DailySummaryRepository,
    private val streakRepository: StreakRepository,
    private val achievementRepository: AchievementRepository,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
) {
    suspend operator fun invoke() {
        val today = LocalDate.now(clock)
        val weekStart = today.with(DayOfWeek.MONDAY)

        // Only evaluate at end of week (Sunday) or when explicitly triggered
        val thisWeekDays = dailySummaryRepository.getBetween(weekStart, today)
        val activeDays = thisWeekDays.count { it.tripCount > 0 }

        if (activeDays < 3) return // Not yet qualified

        val streak = streakRepository.getByType(StreakType.WEEKLY_TRACKING)
            ?: DiscoveryStreak(StreakType.WEEKLY_TRACKING)

        val currentWeekNumber = today.get(WeekFields.ISO.weekOfWeekBasedYear()).toLong()
        val lastWeekNumber = streak.lastIncrementDay

        if (currentWeekNumber == lastWeekNumber) return // Already counted this week

        val isConsecutive = currentWeekNumber == lastWeekNumber + 1 || streak.currentCount == 0
        val newCount = if (isConsecutive) streak.currentCount + 1 else 1
        val newBest = maxOf(streak.bestCount, newCount)

        streakRepository.save(streak.copy(
            currentCount = newCount,
            bestCount = newBest,
            lastIncrementDay = EpochDay(currentWeekNumber),
        ))

        // Update achievement
        val progress = achievementRepository.getOrCreate(AchievementId("streak_weekly_tracking"))
        val oldTier = progress.currentTier
        progress.updateTo(newBest.toLong())
        achievementRepository.save(progress)

        if (progress.currentTier != null && progress.currentTier != oldTier) {
            eventPublisher.publish(AchievementUnlocked(
                occurredAt = clock.instant(),
                achievementId = AchievementId("streak_weekly_tracking"),
                tier = progress.currentTier!!,
            ))
        }

        eventPublisher.publish(DiscoveryStreakUpdated(
            occurredAt = clock.instant(),
            streakType = StreakType.WEEKLY_TRACKING,
            currentCount = newCount,
            isNewBest = newCount == newBest && newCount > 1,
        ))
    }
}
```

### Step 4: Wire the Trigger

The use case needs to run when a new `DailySummary` is materialized (which happens on tracking stop and daily). Two options:

**Option A**: React to a domain event in the GamificationEventWorker:

```kotlin
// In GamificationEventWorker.doWork(), add:
is DailySummaryMaterialized -> updateWeeklyStreakUseCase()
```

**Option B**: Schedule via existing `NewDayGoalWorker` (simpler):

```kotlin
// In NewDayGoalWorker, add:
updateWeeklyStreakUseCase()
```

### Step 5: Display in UI

The `GetAchievementSnapshotsUseCase` already returns all achievements. Since we added the definition to the catalog, it automatically appears in the game screen.

### What We Touched

| Layer | File | Change |
|-------|------|--------|
| Domain (stats-engine) | `AchievementCatalog.kt` | +1 definition (6 lines) |
| Use Case (stats-engine) | `UpdateWeeklyTrackingStreakUseCase.kt` | New file (~50 lines) |
| Event Consumer (game) | `GamificationEventWorker.kt` | +1 when branch (2 lines) |
| **Total** | **2 files modified, 1 new** | **~58 lines** |

No changes to: database schema, UI, existing aggregates, existing events, existing components, existing repositories. The feature composes entirely from existing building blocks.

---

## 11. Migration from Current Architecture

### Phase 0: Foundation (No Behavior Change)
1. Add `domain_event` and `event_consumer_offset` tables to Room (migration v18)
2. Move value objects to `stats-api/model/vo/`
3. Move `SpeedClassification` to `stats-api/classification/`
4. Add repository interfaces to `stats-api/repository/`
5. Add `DomainEventPublisher` and `DomainEventDao`

### Phase 1: Anti-Corruption Layer
1. Create `LegacyTripAdapter` in `stats-data/acl/`
2. Create `LegacySessionAdapter` in `stats-data/acl/`
3. Bind adapters via Hilt as repository implementations
4. Refactor `StatsViewModel` to inject use cases instead of DAOs
5. Verify existing UI behavior is identical

### Phase 2: Event Publishing
1. Modify `SessionSegmentWriter` to publish `TripCompleted` events
2. Modify `ExplorationWriter` to publish `CellDiscovered` events
3. Modify `StreamingAggregatorWriter` to publish `DailySummaryMaterialized`
4. Create `GamificationEventWorker` to replace `ChallengeSessionReceiver` + `GoalsSessionUpdateReceiver`
5. Dual-run: keep broadcast receivers active alongside event consumers; compare results

### Phase 3: Component DI
1. Create `TrackerComponentModule` with `@IntoSet` multibindings
2. Refactor `TrackerComponentManager` to accept `Set<TrackerComponent>`
3. Remove hardcoded component lists
4. Verify all components still activate at correct policy tiers

### Phase 4: Use Case Layer
1. Move business logic from ViewModels into use cases
2. Replace direct DAO injection in `StatsViewModel`, `HistoryViewModel`
3. Create use cases for trip detail, weekly summary, exploration stats
4. Remove `StatisticDataManager` producer/consumer DAG (replaced by use cases + repositories)

### Phase 5: Legacy Removal
1. Deprecate `TrackerSession.ACTION_SESSION_FINAL` broadcasts
2. Remove `ChallengeSessionReceiver`, `GoalsSessionUpdateReceiver`, `PointsSessionReceiver`
3. Remove legacy `TrackerSession` aggregate fields (distance, steps moved to Trip)
4. `TrackerSession` becomes a thin `TrackingRun` (start/end/policy only)
5. Remove `StatisticDataManager` and its producer/consumer classes

### Migration Safety Rails
- **Dual-read validation**: For Phase 1-2, both old and new code paths run. Compare outputs. Alert on >5% divergence.
- **Feature flags**: Each phase gated behind a runtime flag in DataStore. Rollback by flipping flag.
- **Schema backward compatibility**: New tables only; no column removals until Phase 5.
- **Event replay**: If a consumer is added in Phase 4, it replays all events from Phase 2 via offset=0.

---

## Summary: Why This Design?

| Current Problem | DDD Solution |
|----------------|-------------|
| "Stats" conflates 5 concerns | 5 bounded contexts with clear ownership |
| Hardcoded component lists | Hilt `@IntoSet` multibinding with requirement filtering |
| DAOs in ViewModels | Use cases as the interactor layer |
| Speed threshold contradictions | `SpeedClassification` value object, single source of truth |
| No crash recovery for game updates | Room event table with consumer offsets (at-least-once) |
| Broadcast receivers for cross-module comm | Domain events via Room + in-process SharedFlow |
| Raw primitives (Float for distance) | Type-safe value objects (`Distance`, `Speed`, `StepCount`) |
| No way to add features without touching core | Additive: new achievement = catalog entry + use case |
| Legacy TrackerSession pollution | Anti-Corruption Layer adapters in stats-data |
| No achievement system wired | Full event-driven pipeline from Tracking → Gamification |

The architecture is designed for a single question: **"When I add the next feature, how many existing files do I touch?"** The answer should be: **zero or one**.
