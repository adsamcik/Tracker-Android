# Legacy Entity Deprecation Analysis

> Generated as part of todo `5a-legacy-entity-deprecation` on branch `refactor/modernisation`.

## Overview

AppDatabase contains 7 "legacy session-based" entities that coexist with 9 newer "sessionless architecture" entities. This document analyzes each legacy entity's deprecation readiness.

**Conclusion:** All 7 legacy entities still have active production write paths. None can safely receive `@Deprecated` annotations yet. Instead, `// TODO: Migrate` comments have been added to each entity documenting the migration path, active writers, and active readers.

---

## Entity Analysis

### 1. DatabaseLocation (`location_data`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `LocationSample` |
| **Status** | ❌ Still primary — no dual-write to LocationSample yet |
| **Active Writers** | `DatabaseLocationComponent` (tracker), `GpxImport`, `JsonImport` (impexp), `DummyDataSeeder` (debug) |
| **Active Readers** | `DefaultLayerRegistry` (map), `RawLocationDataProducer` / `SummaryGenerator` / `TripDetailPresenterViewModel` (statistics), `ImportExportComposeActivity` / `ExportPlanWorker` (impexp), `ExplorerChallengeProcessor` (game) |
| **Deletion** | `deleteAllCollectedData()` (AppDatabase), `DataRetentionWorker` (time-based pruning) |
| **Migration Path** | 1. Add dual-write in `DatabaseLocationComponent` to write `LocationSample` alongside `DatabaseLocation`. 2. Migrate all readers to use `LocationSampleDao`. 3. Disable legacy write. 4. Add `@Deprecated` annotation. |

### 2. TrackerSession (`tracker_session`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `TrackerRun` + `SessionSegment` |
| **Status** | ❌ Still primary — no dual-write to TrackerRun/SessionSegment yet |
| **Active Writers** | `SessionTrackerComponent` (tracker: insert + update), `GpxImport`, `JsonImport` (impexp), `DummyDataSeeder` (debug) |
| **Active Readers** | `DefaultStatsRepository` / `SummaryGenerator` (statistics), `JsonExporter` / `ImportExportComposeActivity` / `ExportPlanWorker` (impexp), `DefaultDailySummaryProvider` / `SessionTrackerComponent` (tracker), `ChallengeWorker` / `DailyStepGoal` / `WeeklyStepGoal` (game) |
| **Deletion** | `DataRetentionWorker` (time-based pruning); periodic empty-session deletion is retired because terminal source state does not prove presentation-writer quiescence |
| **FK Dependency** | Has `ForeignKey` to `SessionActivity.id` via `session_activity_id` column |
| **Migration Path** | 1. Implement `TrackerRun` writer in `SessionTrackerComponent`. 2. Add `SessionSegment` inference from location samples. 3. Migrate statistics/game/impexp readers. 4. Disable legacy write. 5. Add `@Deprecated`. |

### 3. DatabaseWifiData (`wifi_data`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `WifiObservation` |
| **Status** | 🔄 Dual-write ACTIVE — controlled by `enableDualWrite = true` in `DatabaseWifiComponent` |
| **Active Writers** | `DatabaseWifiComponent` (tracker: conditional on `enableDualWrite`) |
| **Active Readers** | `RawWifiLocationProducer` (statistics), `SummaryGenerator` (statistics: count queries) |
| **Migration Path** | 1. Migrate `RawWifiLocationProducer` and `SummaryGenerator` to use `WifiObservationDao`. 2. Set `enableDualWrite = false`. 3. Add `@Deprecated`. |

### 4. SessionActivity (`activity`)

| Aspect | Details |
|--------|---------|
| **Replacement** | **None — NOT a session-based entity** |
| **Status** | ✅ Active and correctly purposed — no deprecation needed |
| **Nature** | Reference-data entity storing activity type definitions (Walking, Running, Cycling, etc.) |
| **Active Writers** | `ActivityModuleInitializer` seeds native types; `RoomSessionActivityRepository` owns activity-management CRUD; GPX/KML importers create missing imported labels |
| **Active Readers** | `RoomSessionActivityRepository` maps rows to `SessionActivityItem` for feature UI; GPX/KML importers perform lookups; `RoomImportExportDataRepository` resolves localized share metadata. `SessionActivity.getAll()` remains an unused legacy helper, not a UI dependency |
| **Notes** | `ActivitySnapshot` does NOT replace this entity. `ActivitySnapshot` records raw activity transitions (timestamped events), while `SessionActivity` defines reusable activity type labels with icons. `TrackerSession` has a FK to this table. Despite the "Session" prefix, this is a standalone reference table. |

### 5. NetworkOperator (`network_operator`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `CellSample` (stores `mcc`/`mnc` inline) |
| **Status** | 🔄 Dual-write ACTIVE — written by `DatabaseCellComponent` when `enableDualWrite = true` |
| **Active Writers** | `DatabaseCellComponent` (tracker: `cellOperatorDao.insert()`) |
| **Active Readers** | **None in production** — only written, never queried |
| **Deletion** | `deleteAllCollectedData()` calls `cellOperatorDao().deleteAll()` |
| **Migration Path** | 1. Set `enableDualWrite = false` in `DatabaseCellComponent`. 2. Add `@Deprecated`. This is the **closest to deprecation-ready** of all legacy entities — no readers exist. |
| **Note** | `NetworkOperator` is also used as a data class (not entity) in `CellScanData` and `CellInfo` for in-memory cell processing. The data class usage is separate from the Room entity role. |

### 6. DatabaseCellLocation (`cell_location`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `CellSample` |
| **Status** | 🔄 Dual-write ACTIVE — written by `DatabaseCellComponent` when `enableDualWrite = true` |
| **Active Writers** | `DatabaseCellComponent` (tracker: `cellLocationDao.insert()`) |
| **Active Readers** | `ExplorerChallengeProcessor` (game: `getAllInsideAndBetween()`) |
| **Migration Path** | 1. Migrate `ExplorerChallengeProcessor` to use `CellSampleDao`. 2. Set `enableDualWrite = false`. 3. Add `@Deprecated`. |

### 7. DatabaseLocationWifiCount (`location_wifi_count`)

| Aspect | Details |
|--------|---------|
| **Replacement** | `WifiObservation` (count derivable via `GROUP BY bssid` per location) |
| **Status** | ❌ Still primary — no dual-write, no direct replacement entity |
| **Active Writers** | `DatabaseWifiLocationCountComponent` (tracker) |
| **Active Readers** | `RawWifiLocationProducer` (statistics) |
| **Deletion** | `DataRetentionWorker` (time-based pruning) |
| **Migration Path** | 1. Create aggregation query in `WifiObservationDao` to derive wifi count per location. 2. Migrate `RawWifiLocationProducer` to use the new query. 3. Disable `DatabaseWifiLocationCountComponent`. 4. Add `@Deprecated`. |

---

## Deprecation Readiness Summary

| Entity | Dual-Write? | Active Writers | Active Readers | Deprecation Ready? |
|--------|:-----------:|:--------------:|:--------------:|:------------------:|
| DatabaseLocation | ❌ No | 3 (production) | 8+ call sites | ❌ Not yet |
| TrackerSession | ❌ No | 3 (production) | 12+ call sites | ❌ Not yet |
| DatabaseWifiData | ✅ Yes | 1 (conditional) | 2 call sites | 🔶 After reader migration |
| SessionActivity | N/A | 2 (production) | 2+ call sites | ✅ Not legacy — keep |
| NetworkOperator | ✅ Yes | 1 (conditional) | 0 call sites | 🟢 Closest to ready |
| DatabaseCellLocation | ✅ Yes | 1 (conditional) | 1 call site | 🔶 After reader migration |
| DatabaseLocationWifiCount | ❌ No | 1 (production) | 1 call site | ❌ Not yet |

### Recommended Migration Order

1. **NetworkOperator** — No readers, just disable dual-write and deprecate
2. **DatabaseCellLocation** — One reader to migrate (`ExplorerChallengeProcessor`)
3. **DatabaseWifiData** — Two readers to migrate (`RawWifiLocationProducer`, `SummaryGenerator`)
4. **DatabaseLocationWifiCount** — Needs aggregation query in `WifiObservationDao`
5. **DatabaseLocation** — Most readers, needs `LocationSample` dual-write first
6. **TrackerSession** — Most complex, needs `TrackerRun` + `SessionSegment` infrastructure

---

## Changes Made

- Added `// TODO: Migrate to [NewEntity]` comments to all 6 legacy entities documenting writers, readers, and migration path
- Added explanatory comment to `SessionActivity` clarifying it is NOT a legacy session entity
- No `@Deprecated` annotations added — all entities still have active production writers
- No tables dropped, no migrations created, no entities removed from AppDatabase
