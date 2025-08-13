# Map Layers Rework — Tracking Plan

This tracking document breaks the redesign into concrete, code-aware tasks with checkboxes. It’s organized into phases to keep the app working while we migrate gradually. Larger items are split into small, actionable tasks tied to specific files/classes.

Legend

- [ ] = Todo
- [x] = Done

Process

- After completing (and ticking) each checkbox or small sub-step, create a focused commit describing exactly what changed (e.g., "map: fix MapEventListener OnMapClick wiring" or "map: add lifecycle observer for MapSensorController"). Keep commits small and logically isolated to ease review and potential rollback.

---

## Phase 1 — Critical fixes and stabilizers

Stabilize current map to reduce risk before introducing v2.

 
### 1.1 MapEventListener click handler bug

Files: `map/src/main/java/com/adsamcik/tracker/map/MapEventListener.kt`

- [x] Fix incorrect plus/minus assign conditions for OnMapClickListener.
- [x] Add unit test verifying add/remove wiring and isolation of other listeners.

Acceptance

- [x] Adding/removing last OnMapClickListener properly sets/unsets GoogleMap.onMapClickListener.
- [x] No unrelated listeners are affected by click listener removal.

 
### 1.2 Rotation/bearing updates resume and lifecycle hygiene

Files: `map/.../MapSensorController.kt`, `map/.../fragment/FragmentMap.kt`, `map/.../MapOwner.kt`

- [x] Add lifecycle observer (onStart/onStop) for MapSensorController.
- [x] Register/unregister sensors + location in onStart/onStop.
- [x] Connect observer from FragmentMap when map is ready.

#### 1.2.a Consolidate lifecycle authority (follow-up)

Currently both `MapOwner` enable/disable listeners and the lifecycle observer could trigger enable/disable. Consolidated to lifecycle observer only.

- [x] Choose lifecycle observer as single authority.
- [x] Remove MapOwner enable/disable wiring for sensors.
- [x] Add idempotence guard + debug log.

Acceptance

- [x] Only one code path invokes MapSensorController enable/disable (verified via log).
- [x] No duplicate sensor or location requests after rapid pause/resume cycles.
- [x] Following mode resumes after app resume and cancels on user camera move (test + manual QA).

 
Additional Verification

- [x] No retained SensorManager callbacks after onStop (LeakCanary clean).
- [x] Following/bearing resume reliably with permission granted.

 
### 1.3 Centralize zoom constants

Files: `map/.../MapController.kt`, `map/.../heatmap/HeatmapTileProvider.kt`

- [x] Create `map/src/main/java/com/adsamcik/tracker/map/MapConstants.kt` with `const val MAX_ZOOM = 17f`.
- [x] Replace `MapController.MAX_ZOOM` usages with `MapConstants.MAX_ZOOM` in:
  - [x] `MapController` (setMaxZoomPreference)
  - [x] `HeatmapTileProvider` (MAX_HEAT_ZOOM)
- [x] Remove `MAX_ZOOM` from `MapController.Companion` when references are updated.
  - [x] Also updated additional usages in heatmap tile creators (not originally listed) to ensure compilation.

 
Acceptance

- [x] Build compiles and runtime behavior unchanged.

---

## Phase 2 — Core architecture scaffolding (v2; no behavior change yet)

Introduce state/registry alongside existing implementation.

 
### 2.1 Map state and ViewModel

Files (new): `map/src/main/java/com/adsamcik/tracker/map/v2/presentation/MapViewModel.kt`

- [x] Create `MapViewModel` with `StateFlow` holding immutable `MapState`:
  - [x] selectedLayerId, dateRange, quality, followMode, userLocation, searchQuery, searchResults, isLoading, error, bottomSheetState, layerParameters, tileGenerationProgress.
- [x] Add debounced refresh utility inside ViewModel for parameter changes.

 
Acceptance

- [x] ViewModel compiles; can be instantiated in tests.

 
### 2.2 Layer descriptors and registry (manual, no DI yet)

Files (new): `smap/.../shared/map/v2/layers/{LayerDescriptor.kt, LayerCapabilities.kt, LayerRecipe.kt, LayerParameter.kt}`
Files (new): `map/.../v2/layers/registry/{LayerRegistry.kt, DefaultLayerRegistry.kt}`

- [x] Define `LayerDescriptor`, `LayerCapabilities`, `LayerRecipe`, `LayerParameter`, `LayerFactory` in shared `smap` to be accessible by other modules.
- [x] Implement `LayerRegistry` interface and `DefaultLayerRegistry` with manual registration.
- [x] Register descriptors mirroring current layers from `MapSheetController` list:
  - [x] NoMap, Location Heatmap, Cell Heatmap, Wifi Heatmap, Wifi Count Heatmap, Location Polyline, Speed Heatmap.
  - [x] For now, factories can return wrapper adapters to existing `MapLayerLogic` implementations (v1) to keep parity.

 
Acceptance

- [x] `DefaultLayerRegistry.getAllLayers()` returns descriptors for all current layers with names/icons wired to existing resources.

 
### 2.3 MapHost and LayerController scaffolding

Files (new): `map/.../v2/ui/{MapHost.kt, LayerController.kt}`

- [x] `MapHost`: lifecycle-safe wrapper around `SupportMapFragment` using `MapOwner` patterns; exposes `mapReady` observable.
- [x] `LayerController`: minimal wrapper that enables/disables a selected layer (using descriptor factory) on the map.
- [x] Do not change UI yet; keep v1 `MapSheetController` active.

 
Acceptance

- [x] Able to initialize `MapHost` from `FragmentMap` without disturbing v1 flow. (Compilation-only scaffold; wiring deferred to 2.4.)

 
### 2.4 Wire ViewModel/MapHost lightly in FragmentMap (no behavior change)

Files: `map/.../fragment/FragmentMap.kt`

- [x] Instantiate `MapViewModel` and `MapHost` in `onViewCreated` (behind a dev flag or no-op collectors).
- [x] Observe ViewModel state but don’t drive UI yet.

 
Acceptance

- [x] App runs as before; no visual changes.

---

## Phase 3 — Data layer increments (Room flexible queries)

Add flexible query support without refactoring all DAOs.

 
### 3.1 Unified DAO with raw queries

Files (new): `shared.base.database/.../dao/UnifiedGeoDao.kt` (module where `AppDatabase` lives)

- [x] Add `@RawQuery(observedEntities=[LocationData::class]) fun queryLocations(q: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>`. (Implemented as `DatabaseLocation` observed; wifi/cell placeholders commented.)
- [x] Add similar for Wifi and Cell entities if available: `WifiData`, `CellLocation`.
- [x] Add weighted variants returning `GeoWeightedFeatureEntity` (lat, lon, time, weight) for location, wifi, cell.
- [x] Ensure `AppDatabase` exposes the new DAO.

 
### 3.2 GeoFeatureEntity and converters

Files (new): `shared.base.database/.../entity/GeoFeatureEntity.kt`

- [x] `GeoFeatureEntity(lat: Double, lon: Double, time: Long, properties: Map<String, Double>)` stored as JSON (map currently used for future queries; empty by default).
- [x] Introduce `@TypeConverter` to serialize/deserialize the map; verify no conflicting converters.

 
### 3.3 SafeQueryBuilder

Files (new): `map/.../v2/data/SafeQueryBuilder.kt`

- [x] Implement safe, parameterized SQL builder with allowed columns per data source; support bounds and optional time range.
- [x] Unit tests for invalid columns/predicates.

 
### 3.4 GeoRepository

Files (new): `map/.../v2/data/GeoRepositoryImpl.kt`

- [x] Implement `GeoRepository.query(query: GeoQuery): Flow<List<GeoFeature>>` using `UnifiedGeoDao` and `SafeQueryBuilder` (supports weighted queries).
- [x] Implement `queryWeighted(query, weightColumn)` convenience API returning `WeightedGeoFeature` list.
- [x] Add client-side grid aggregation (`queryWeightedAggregated`) with Sum/Avg/Max strategies.
- [ ] Implement `queryWeighted(...)` for tiled/weighted use-cases.

 
### 3.5 Optional: use repo in one tile creator under flag

Files: `map/.../heatmap/creators/LocationHeatmapTileCreator.kt`

- [x] Introduce dev flag to fetch via `GeoRepository` for a small, controlled scenario (Location heatmap); falls back to DAO when disabled.

 
Acceptance

- [ ] Unit tests green for builder and repository; v1 creators still work.

---

## Phase 4 — Performance guardrails

Make performance limits explicit and centralized.

 
### 4.1 PerformanceManager

Files (new): `map/.../v2/perf/PerformanceManager.kt`

- [ ] Provide budgets by quality (Low/Medium/High): maxPoints, maxPolylinePoints, maxCacheSize, tileRenderTimeout, decimationThreshold, batchSize.

 
### 4.2 BitmapPool

Files (new): `map/.../v2/graphics/BitmapPool.kt`

- [ ] Implement simple bounded pool with `acquire(width,height)` and `release(bitmap)`.

 
### 4.3 PolylineOptimizer

Files (new): `map/.../v2/graphics/PolylineOptimizer.kt`

- [ ] Implement Douglas-Peucker and even spacing; API `(points, tolerance, maxPoints) -> List<LatLng>`.

 
### 4.4 HeatmapTileProvider improvements (non-breaking)

Files: `map/.../heatmap/HeatmapTileProvider.kt`

- [ ] Add optional injection/setter for `BitmapPool`.
- [ ] Add in-memory LRU tile cache (keyed by x,y,zoom) with bounded size from `PerformanceManager`.
- [ ] Enforce per-tile render timeout to avoid long stalls; fail fast to `NO_TILE` on timeout.
- [ ] Review locking around tile generation; reduce contention to render-critical sections only.

 
Acceptance

- [ ] Manual smoke: panning/zooming heavy areas shows no visible jank; median tile render < 100ms on dev device.

---

## Phase 5 — Layer system v2 and first migrations

Introduce base classes and migrate layers incrementally.

 
### 5.1 Base layer abstractions

Files (new): `map/.../v2/layers/base/{BaseMapLayer.kt, HeatmapLayer.kt}`

- [ ] Implement template method: `enable()` calls `beforeEnable -> loadData -> processData -> render -> afterEnable`.
- [ ] Integrate `PerformanceManager` in processing.

 
### 5.2 Optimized tile provider
Files (new): `map/.../v2/tiles/OptimizedTileProvider.kt`

- [ ] Implement tile provider using `BitmapPool`, LRU cache, and safe rendering.

\n### 5.3 Migrate Location Heatmap first
Files: create new `LocationHeatmapLayer` in `map/.../v2/layers/impl/LocationHeatmapLayer.kt`

- [ ] Use `GeoRepository` for data load; grid aggregation for performance.
- [ ] Render via `OptimizedTileProvider`.
- [ ] Provide descriptor with parameters (date range, quality, etc.).

\n### 5.4 UI: descriptors-driven layer list (dual path)
Files: `map/.../MapSheetController.kt`

- [ ] Add new adapter (or branch) that renders from `LayerRegistry` descriptors.
- [ ] Keep existing hard-coded list for fallback; guard with dev toggle.
- [ ] When a descriptor is selected, use `LayerController` to enable v2 layer.

Acceptance

- [ ] Location Heatmap works through v2 flow without regressions; switching layers tears down overlays cleanly.

\n### 5.5 Migrate remaining layers
Files:

- `WifiHeatmapLogic` -> `WifiHeatmapLayer`
- `WifiCountHeatmapLogic` -> `WifiCountHeatmapLayer`
- `CellHeatmapLogic` -> `CellHeatmapLayer`
- `SpeedHeatmapLogic` -> `SpeedHeatmapLayer`
- `LocationPolylineLogic` -> `LocationPathLayer` (with decimation)

- [ ] Implement per-layer recipes and descriptors; port legends and colors from `MapLayerData`.
- [ ] Verify behavior parity on overlays and legends.

Acceptance

- [ ] All v1 `MapLayerLogic` features available in v2; v1 path can be removed after verification.

---

## Phase 6 — Testing and cleanup

\n### 6.1 Unit tests
Files: `map/src/test/...`

- [ ] `MapViewModelTest`: selection, param updates, debounced refresh.
- [ ] `SafeQueryBuilderTest`: allowed columns, parameterization, predicate safety.
- [ ] `PerformanceManagerTest`: budget selection.
- [ ] `PolylineOptimizerTest`: reductions to within budget and tolerance.

\n### 6.2 Tile harness and visual regression (optional)
Files (new): `map/.../v2/test/tiles/TileTestHarness.kt`

- [ ] Headless tile generation to bitmap; compare against golden with tolerance to catch regressions.

### 6.3 Integration tests

- [ ] Layer switch smoke test ensuring overlays mount/unmount and legends update.

### 6.4 Remove v1 code

- [ ] Remove `MapLayerLogic`-based layers and legacy heatmap creators/providers after v2 reaches parity.

Acceptance

- [ ] Tests green; no critical leaks; app uses v2 path by default.

---

## Codebase research notes (grounding)

- Fragment and controllers
  - `FragmentMap`: sets up `MapController`, `MapSensorController`, and `MapSheetController`; uses `MapOwner` lifecycle hooks.
  - `MapOwner`: abstracts map creation and enable/disable events.
  - `MapController`: manages active `MapLayerLogic`; `MAX_ZOOM = 17f` used and referenced by `HeatmapTileProvider`.
  - `MapSensorController`: handles sensors and location updates; currently no lifecycle observer.
  - `MapSheetController`: hard-codes layer list; controls legend and bottom sheet, search/geocoder.
- Layers v1
  - `MapLayerLogic` interface in `smap/.../shared/map/MapLayerLogic.kt` with `onEnable/onDisable/update`, `availableRange/dateRange/quality`, and `tileCountInGeneration` LiveData.
  - Heatmaps implemented via `HeatmapLayerLogic` base class and tile creators (`LocationHeatmapTileCreator`, `WifiHeatmapTileCreator`, `CellHeatmapTileCreator`, `SpeedHeatmapTileCreator`).
  - `LocationPolylineLogic` uses DAOs (`LocationDataDao`, `SessionDataDao`) to draw polylines.
- Heatmap provider
  - `HeatmapTileProvider` implements tile generation with internal concurrency and a heat-change mechanism; references `MapController.MAX_ZOOM` as `MAX_HEAT_ZOOM`.
- Known bug
  - `MapEventListener.minusAssign(OnMapClickListener)` incorrectly checks `onCameraMoveListeners` and unsets `OnCameraIdleListener` instead of `OnMapClickListener`.

---

## Open questions / assumptions

- DI: No Hilt/Dagger present. We’ll start with manual `DefaultLayerRegistry`; Hilt multibindings can be adopted later without blocking progress.
- DAOs: `LocationDataDao`, `SessionDataDao` referenced in map module; their definitions live in the shared database module (verify paths before implementing `UnifiedGeoDao`).
- Feature flags: Prefer simple build-time or runtime toggles to switch between v1 and v2 during migration.

---

## Success metrics checklist

- [ ] Extensibility: Add a new layer by registering a descriptor only (no core edits).
- [ ] Performance: Median tile render < 100ms under normal datasets; no visible jank when panning/zooming.
- [ ] Quality: ≥80% unit test coverage for new v2 code; 0 critical leaks (LeakCanary).
- [ ] Parity: v2 covers all existing layers and features, including legends.
