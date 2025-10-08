# Sessionless Tracking Migration - Implementation Status

**Assessment Date:** October 1, 2025  
**Current Database Version:** 12  
**Branch:** dev/v10

---

## Executive Summary

**Current State:** The codebase is **100% on the legacy session-driven architecture**. None of the sessionless tracking components have been implemented yet.

**Implementation Status:** 0% complete

---

## Detailed Analysis

### ❌ Not Started Components

#### 1. Database Schema (New Tables)

**Status:** None exist

Missing tables for the new architecture:
- `location_sample` - Raw location points with quality/motion metadata
- `activity_snapshot` - Activity changes over time
- `step_interval` - Step count deltas with sensor reset handling
- `cell_sample` - Cell tower observations with nullable coordinates
- `wifi_observation` - Wi-Fi scan observations over time
- `tracker_run` - Policy state tracking
- `session_segment` - Inferred/derived sessions
- `dwell_cluster` - Stationary location clusters
- `daily_rollup` - Denormalized daily statistics

**Current entities:**
- `DatabaseLocation` (legacy `location_data` table)
- `TrackerSession` (legacy `tracker_session` table)
- `DatabaseWifiData` (legacy `wifi_data` table)
- `DatabaseCellLocation` (legacy `cell_location` table)
- `DatabaseLocationWifiCount` (legacy `location_wifi_count` table)

#### 2. Migration (v12 → v13)

**Status:** Not started

Last migration: `MIGRATION_11_12` (composite index optimization only)

Required:
- Create all new tables
- Copy legacy data with transformations (lat/lon → E7 integers, provenance tagging)
- Drop legacy tables after validation
- Add schema version flag

#### 3. Tracking Policy Manager

**Status:** Not implemented

Current: `LocationPreTrackerComponent` always registered and requires location

Missing:
- `TrackingPolicyManager` class
- Policy state machine (PASSIVE_LOW, MOVEMENT_SUSPECTED, ACTIVE_ELEVATED, etc.)
- Movement detection heuristics (step rate, activity transitions)
- Dynamic component registration based on policy
- `tracker_run` persistence

#### 4. Raw Stream Writers

**Status:** Not implemented

Current: Post components write directly to session-coupled tables

Missing:
- `RawStreamWriterComponent` for location samples
- `StepIntervalWriter` for step deltas
- Activity snapshot writer
- Modified cell/wifi writers that accept null coordinates

Current behavior:
- `DatabaseCellComponent` requires location (line 44: "todo add tracking without location")
- `DatabaseWifiComponent` writes with upsert, no provenance tracking
- `DatabaseLocationComponent` has batching but no quality/motion state
- Steps stored only in session aggregate, not as intervals

#### 5. Workers (Background Processing)

**Status:** Not implemented

Missing workers:
- `SessionInferenceWorker` - Retrospective session detection
- `CellLocationEnrichmentWorker` - Back-fill cell coordinates
- `WifiLocationEnrichmentWorker` - Back-fill Wi-Fi coordinates
- `DailyRollupWorker` - Aggregate statistics
- `LocationAgingWorker` - Compress/quantize old samples

Existing workers (unrelated):
- `DataRetentionWorker` - Delete old data
- `DatabaseMaintenanceWorker` - Vacuum/optimize
- `ActivityRecognitionWorker` - Post-session activity classification
- Various game/points workers

#### 6. Repository Layer

**Status:** Not implemented

Current: Modules access DAOs directly

Missing:
- `RawSamplesRepository` interface + implementation
- `SegmentRepository` interface + implementation
- Coordinate conversion utilities (E7 ↔ Double)
- Provenance/confidence abstractions

#### 7. Downstream Consumer Updates

**Status:** Not started

Areas needing updates:

**Map Module:**
- `LocationPathLayer` reads from `locationDao().getAllBetweenOrdered()` (legacy)
- Heatmaps use `UnifiedGeoDao` but query legacy tables
- No provenance/confidence visualization

**Statistics Module:**
- `SummaryGenerator` queries legacy session aggregates
- No support for inferred segments or confidence

**Import/Export:**
- GPX/KML/JSON exporters iterate legacy tables
- Importers write to legacy tables

**Activity Module:**
- `ActivityRecognitionWorker` queries `locationDao().getAllBetween()` (legacy)

**Points & Game:**
- `ExplorerChallengeInstance` reads `locationDao()` (legacy)

**App Module:**
- UI reads session/location data via legacy DAOs

---

## Current Architecture Analysis

### What Works Well (To Preserve)

1. **Producer independence:** `StepDataProducer`, `ActivityDataProducer`, `CellDataProducer`, `WifiDataProducer` already operate independently of location and populate temp data correctly.

2. **Component pipeline:** The pre→data→post component chain is well-structured and can accommodate new components without architectural changes.

3. **Batching foundation:** `DatabaseLocationComponent` already has batching scaffolding (though threshold is 1).

4. **Graceful degradation:** Producers check permissions and handle missing sensors cleanly.

### Blockers & Dependencies

1. **Location-required gating:** `LocationPreTrackerComponent` is always registered and blocks the entire cycle if location requirements aren't met. This prevents location-less collection.

2. **Session coupling:** All distance/step metrics accumulated in `SessionTrackerComponent` during collection. No way to defer aggregation.

3. **Cell persistence:** Explicitly skips if no location available (line 44-48 in `DatabaseCellComponent`).

4. **No provenance:** Current schema has no way to mark data quality, source, or enrichment status.

5. **No inference infrastructure:** No workers, no segment detection, no retrospective analysis.

---

## Implementation Roadmap

### Phase 0: Foundation (Estimated: 3-5 days)

**Priority: Critical path items**

1. **Schema design finalization**
   - Define exact column types for new entities
   - Design enum converters (SampleQuality, MotionState, Provenance, SegmentSource)
   - Plan index strategy

2. **Migration 12→13 implementation**
   - Create all new tables
   - Implement data copy with validation
   - Write migration tests with fixtures

3. **Basic entity & DAO creation**
   - `LocationSample` entity + DAO
   - `ActivitySnapshot` entity + DAO
   - `StepInterval` entity + DAO
   - `CellSample` entity + DAO (with nullable coords)
   - `WifiObservation` entity + DAO
   - `TrackerRun` entity + DAO
   - `SessionSegment` entity + DAO

**Deliverable:** AppDatabase v13 with new schema and passing migration tests

### Phase 1: Core Collection (Estimated: 4-6 days)

**Priority: Enable location-less collection**

1. **Policy manager skeleton**
   - `TrackingPolicyManager` with simple state machine
   - Default PASSIVE_LOW policy
   - Conditional `LocationPreTrackerComponent` registration in `TrackerService`

2. **Raw stream writers**
   - `RawLocationWriter` (replace `DatabaseLocationComponent` logic)
   - `StepIntervalWriter` (new)
   - `ActivitySnapshotWriter` (new)
   - Modify `DatabaseCellComponent` to allow null coordinates
   - Modify `DatabaseWifiComponent` to write observations without upsert

3. **Integration testing**
   - Service cycle without location (steps + activity + cell/wifi only)
   - Verify new tables populated
   - Ensure legacy tables still written (dual-write mode)

**Deliverable:** Tracking service writes to both old and new tables; can collect data without GPS

### Phase 2: Inference & Enrichment (Estimated: 5-7 days)

**Priority: Prove the concept with basic retrospective analysis**

1. **Session inference worker**
   - Simple heuristic: movement spans based on displacement + steps
   - Write to `session_segment` table
   - Compare outputs vs legacy sessions

2. **Enrichment workers**
   - `CellLocationEnrichmentWorker` - nearest location lookup
   - `WifiLocationEnrichmentWorker` - nearest location lookup
   - Provenance tagging

3. **Daily rollup worker**
   - Aggregate from raw tables + segments
   - Write to `daily_rollup`

4. **Worker scheduling**
   - WorkManager constraints (charging, unmetered)
   - Trigger on new data availability

**Deliverable:** Background workers populate derived tables; basic session detection functional

### Phase 3: Consumer Migration (Estimated: 6-8 days)

**Priority: Switch UI/export to new data sources**

1. **Repository layer**
   - `RawSamplesRepository` + `SegmentRepository` interfaces
   - Default implementations
   - Coordinate conversion utilities

2. **Map module updates**
   - `LocationPathLayer` reads `location_sample`
   - Heatmaps support provenance filtering
   - Visual indicators for low-confidence data

3. **Statistics module updates**
   - `SummaryGenerator` reads `daily_rollup` + `session_segment`
   - Chart support for confidence/provenance

4. **Import/export updates**
   - Exporters iterate new tables
   - Importers write new tables + schedule inference

5. **Other module updates**
   - Activity, points, game modules
   - App UI screens

**Deliverable:** All consumers read from new tables; legacy tables only written, not read

### Phase 4: Adaptive Policy & Optimization (Estimated: 4-6 days)

**Priority: Battery savings**

1. **Policy state machine expansion**
   - Movement detection (steps/activity)
   - Escalation/cooldown timers
   - Timer swapping (handler ↔ fused location)

2. **Aging & compression**
   - `LocationAgingWorker` with spatial quantization
   - Old data compaction into buckets

3. **Battery profiling**
   - Measure GNSS duty cycle
   - Validate state transitions

**Deliverable:** Adaptive collection with measurable battery savings

### Phase 5: Cleanup & Deprecation (Estimated: 2-3 days)

**Priority: Remove legacy code**

1. **Dual-write removal**
   - Stop writing to legacy tables
   - Keep DAOs for read-only access (one release grace period)

2. **Final migration (v13→v14)**
   - Drop legacy tables
   - Remove legacy entities/DAOs

3. **Documentation**
   - Update README
   - Add developer guide for new architecture

**Deliverable:** Legacy code removed; architecture fully migrated

---

## Estimated Total Effort

**Time:** 24-35 developer days (~5-7 weeks calendar time for single developer)

**Risk factors:**
- Migration complexity (data integrity)
- Inference algorithm tuning (false positives/negatives)
- Battery regression testing (requires real devices)
- Consumer update scope (many modules)

---

## Immediate Next Steps (To Start Implementation)

### Step 1: Create New Entities (Day 1)

Start with the core entities in `sbase/src/main/java/.../database/data/`:

1. **LocationSample.kt**
```kotlin
@Entity(
  tableName = "location_sample",
  indices = [
    Index("time_ms"),
    Index("lat_e7", "lon_e7"),
    Index(value = ["bucket_id"], unique = false)
  ]
)
data class LocationSample(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "time_ms") val timeMs: Long,
  val elapsedRealtimeNanos: Long,
  @ColumnInfo(name = "lat_e7") val latE7: Int?,
  @ColumnInfo(name = "lon_e7") val lonE7: Int?,
  @ColumnInfo(name = "alt_m") val altitudeM: Float?,
  @ColumnInfo(name = "h_acc_m") val hAccM: Float?,
  @ColumnInfo(name = "v_acc_m") val vAccM: Float?,
  @ColumnInfo(name = "speed_mps") val speedMps: Float?,
  @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float?,
  val provider: String,
  val quality: SampleQuality,
  val motionState: MotionState?,
  val policy: String?,
  val bucketId: Long?,
  val createdAt: Long
)

enum class SampleQuality { HIGH, MEDIUM, LOW, COARSE }
enum class MotionState { MOVING, STILL, UNKNOWN }
```

2. **StepInterval.kt**
3. **ActivitySnapshot.kt**
4. **CellSample.kt** (with nullable coordinates + provenance)
5. **WifiObservation.kt** (with provenance)
6. **TrackerRun.kt**
7. **SessionSegment.kt**

### Step 2: Create DAOs (Day 1-2)

Minimal CRUD operations for each new entity.

### Step 3: Write Migration 12→13 (Day 2-3)

In `AppDatabaseMigrations.kt`:
- CREATE TABLE statements for all new entities
- Copy data from legacy tables with transformations
- Validation queries (count checks)

### Step 4: Write Migration Tests (Day 3)

Use `MigrationTestHelper` with fixtures.

### Step 5: Update AppDatabase (Day 3)

Bump version to 13, add new entities to entity list, add new DAO getters.

---

## Key Decisions Needed Before Starting

1. **Coordinate storage format:** E7 integers (more compact) vs Double (simpler)
   - **Recommendation:** E7 integers for storage efficiency

2. **Legacy table retention:** Drop immediately in v13 or keep read-only for one release?
   - **Recommendation:** Keep read-only for one release (safety net)

3. **Default quantization:** What spatial resolution for aged data?
   - **Recommendation:** 30m after 7 days, 100m after 90 days

4. **Dual-write duration:** How long to write both old and new tables?
   - **Recommendation:** Until all consumers migrated + one release buffer

5. **Inference confidence threshold:** Minimum confidence to display inferred sessions?
   - **Recommendation:** Start with 50%, make user-configurable

---

## Conclusion

**The sessionless tracking architecture is entirely unimplemented.** The current codebase operates on the legacy session-driven model with all the limitations outlined in the original design discussion.

To proceed, start with **Phase 0** (schema + migration), which creates the foundation for all subsequent work. The good news is that the existing producer/consumer pipeline is well-structured and won't require major refactoring—we're adding new components rather than replacing the entire system.

**Recommended Starting Point:** Create the entity classes for the new tables and implement the database migration. This allows incremental testing and provides a clear foundation for subsequent phases.
