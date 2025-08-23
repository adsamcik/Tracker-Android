Here’s a cleaned-up, architecture-only revision of your plan. I’ve stripped perf/parity/design talk and pushed a modern Compose/UDF shape, sealed overlay models, and tighter module boundaries.

---

# Map Compose Migration Tracking (Temporary)

Note: This is a temporary doc to guide the migration and will be deleted after completion.
Use websearch to research additional information about APIs, libraries and approaches as needed.

## Goals (architecture-only)

* Compose-first, **unidirectional data flow** (UDF).
* Single source of truth in a **Map ViewModel (“MapStore”)**.
* **Declarative overlays** (markers/circles/polylines/tiles) from state; `MapEffect` only for gaps.
* **Small managers** for sensors and layers; legacy controllers removed.
* Clear **module boundaries** and contracts.

## Current architecture (summary)

* Fragment-based UI (`FragmentMap`) hosting a `SupportMapFragment` via `MapOwner`.
* Controllers: `MapController`, `MapSensorController` (location/sensors/follow), `MapPositionController` (user markers/circle), `MapEventListener` (multiplex camera and click), `MapSheetController` (XML bottom sheet UI + keyboard/padding), `MapBottomSheetBehavior`.
* Layers: `DefaultLayerRegistry` provides `LayerDescriptor` → `LayerEntry` → `BaseMapLayer` implementations (heatmaps, wifi, cell, speed, polyline) managed by `LayerController`.
* Heatmap pipeline: `OptimizedTileProvider` / `HeatmapTileProviderBase` / `HeatmapEngine` / caches.
* Style: `ColorMap` sets Google Map style.
* Presentation: lightweight `MapViewModel` scaffold exists but not wired to UI.

## Target architecture (Compose-first, UDF)

* `MapScreen` composable hosting Material sheet + `GoogleMap` (Maps Compose).
* **Contracts**:

  * `MapState` – immutable UI model.
  * `MapEvent` – user/system intents.
  * `MapEffect` – one-offs (e.g., “center camera to bounds”).
  * `MapOverlayState` – sealed description of all overlays to render.
* **Store**: `MapViewModel` (“MapStore”) reduces `MapEvent` → new `MapState` + optional `MapEffect`.
* **Managers**:

  * `LocationAndSensorsManager` – exposes cold `Flow`s of location/bearing (no UI refs).
  * `LayerManager` – pure overlay factory/diff; no Android UI types in its API.
  * `CameraBehavior` – policies for follow/cancel derived from camera and location streams.
* **Compose-first overlays**: prefer `Marker`, `Circle`, `Polyline`, `Polygon`, `GroundOverlay`, `TileOverlay` with hoisted `TileOverlayState`. Use `MapEffect` only where the composables can’t express behavior (temporary interop).
* **Styling**: `MapProperties(mapStyleOptions = ...)`; `ColorMap` becomes a provider of `MapStyleOptions` or JSON.

### Core contracts (sketch)

```kotlin
// feature:map:ui
data class MapState(
  activeLayerIds: ImmutableSet<String>,
  camera: CameraModel,              // target, zoom, tilt, bearing (UI snapshot)
  isFollowing: Boolean,
  sheet: SheetStateModel,
  overlays: ImmutableList<MapOverlayState>,
  legend: ImmutableList<LegendItem>,
  search: SearchState,
)

sealed interface MapEvent {
  data object ToggleFollow : MapEvent
  data class SelectLayer(val id: String) : MapEvent
  data class CameraMoved(val position: CameraModel, val byGesture: Boolean) : MapEvent
  data class SearchSelected(val bounds: LatLngBounds) : MapEvent
  // …
}

sealed interface MapEffect {
  data class CenterCamera(val bounds: LatLngBounds) : MapEffect
  data object ShowFollowCanceled : MapEffect
  // …
}

// Declarative overlay model (no GoogleMap types leaking out)
sealed interface MapOverlayState {
  data class UserMarker(val latLng: LatLngModel) : MapOverlayState
  data class AccuracyCircle(val latLng: LatLngModel, val radiusM: Double) : MapOverlayState
  data class Polyline(val points: ImmutableList<LatLngModel>) : MapOverlayState
  data class TileLayer(val id: String, val providerKey: String) : MapOverlayState
  // …
}
```

> **Invariants**
>
> * UI renders *only* `MapState` and emits `MapEvent`.
> * `MapEffect` is for one-off actions; it never lives in `MapState`.
> * Overlay identity is keyed (e.g., `TileLayer.id`) so we can diff and preserve instances.

## Module layout

* `:feature:map` – UI/VM, reducers, screen navigation contracts (no engine details).
* `:map:engine` – `LayerManager`, heatmap/tile provider implementations, style provider.
* `:sensors` – `LocationAndSensorsManager` and sensor abstractions.
* `:style` – style JSON, `MapStyleOptions` adapters.
* `:model` – shared POJOs/`@Immutable` UI models (no Android types).

> **Rule**: `:feature:map` depends only on `:model`, uses `:map:engine` via interfaces. No UI module depends on Play Services directly except where required by Maps Compose.

## Architectural principles

* **Single source of truth**: All UI-visible map state lives in `MapState`. No controller holds parallel state.
* **Declarative overlays**: Overlays are rendered from `MapOverlayState`. Installation/removal is a **diff** driven by keys.
* **Side-effects isolated**: Compose effects (`LaunchedEffect`, `DisposableEffect`) are confined to the map composition layer and react to `MapEffect`/state changes only.
* **Stable models**: Use `kotlinx.collections.immutable` and mark UI models `@Immutable`. No framework types in state.
* **Interop minimized**: `MapEffect` used only for legacy gaps; delete as soon as a declarative path exists.
* **Camera policy separated**: Decisions about follow/cancel/thresholds live in `CameraBehavior` (pure, testable), not in Composables.

## Migration plan (phased)

### Phase 0 – Prep and dependencies

* [x] Add Maps Compose; align with Play Services Maps.
* [x] Ensure Material3 Compose theme is ready.
* [x] Keep legacy Map stack intact.

**Commit**: `docs(map): add Compose migration tracking plan (temporary)`

---

### Phase 1 – Establish UDF contracts + Compose sheet (remove legacy sheet)

* [x] Introduce `MapState`/`MapEvent`/`MapEffect`/`MapOverlayState` and `MapViewModel` reducer.
* [x] Add a `ComposeView` overlay in `FragmentMap` hosting a Compose bottom sheet wired to the VM.
* [x] Bridge layer toggles/search to existing `LayerController` through a **temporary** `LayerManager` interface.
* [x] Remove `MapSheetController` and `MapBottomSheetBehavior` and their XML.

**Commit**: `feat(map): Phase 1 – UDF contracts + Compose sheet replaces legacy sheet`

---

### Phase 2 – State centralization, dissolve MapController

* [ ] Move selected layer/date/quality/search/sheet/legend/tile progress into `MapState`.
* [ ] Move map UI settings into Compose (`MapUiSettings`, `MapProperties`).
* [ ] Delete `MapController`; any defaults live in VM reducers/managers.

**Commit**: `refactor(map): Phase 2 – centralize state in MapViewModel; remove MapController`

---

### Phase 3 – Maps Compose swap + declarative style/overlays

* [ ] Replace `SupportMapFragment` with `GoogleMap` + `cameraPositionState`.
* [ ] Apply style via `MapProperties(mapStyleOptions)`; refactor `ColorMap` into a style provider.
* [ ] Render overlays declaratively:

  * `Marker`/`Circle`/`Polyline` directly from `MapOverlayState`.
  * `TileOverlay` using a hoisted `TileOverlayState` and our `TileProvider`.
* [ ] Introductions and one-off actions emitted as `MapEffect`s.

**Commit**: `feat(map): Phase 3 – Maps Compose, declarative style & overlays`

---

### Phase 4 – Controllers → managers; pure flows

* [ ] Replace `MapEventListener` with Compose camera/gesture observation; emit `MapEvent.CameraMoved`.
* [ ] Collapse `MapSensorController` into `LocationAndSensorsManager` (cold `Flow`s; no UI).
* [ ] Replace `MapPositionController` with declarative user marker/circle in `MapOverlayState`.
* [ ] Finalize `LayerManager` API: diff `activeLayerIds` → overlay set; no direct `GoogleMap` leakage.

**Commit**: `refactor(map): Phase 4 – manager-based sensors and overlay diffing`

---

### Phase 5 – Cleanup & boundaries

* [ ] Remove residual legacy classes/resources (MapOwner, listeners, unused XML).
* [ ] Lock module boundaries: `:feature:map` depends on interfaces only; `:map:engine` provides implementations.
* [ ] Remove this tracking file.

**Commit**: `chore(map): Phase 5 – finalize Compose architecture and remove legacy`

## Architectural risks & mitigations

* **State ownership drift**: UI or managers storing their own “truth”.
  *Mitigation*: forbid non-VM mutable state; review rule: “only VM mutates `MapState`”.
* **Overlay identity churn**: overlays recreated on trivial changes.
  *Mitigation*: key every overlay; diff by keys; hoist `TileOverlayState`.
* **Effect duplication**: repeating camera jumps on recomposition.
  *Mitigation*: one-off `MapEffect`s via `SharedFlow`; UI consumes with `collectLatest`.

## Success criteria (architecture)

* All map UI renders from `MapState`; **zero** legacy controllers in runtime path.
* Overlays are declared via `MapOverlayState`; lifecycle managed by diff + `DisposableEffect`.
* Style applied via `MapProperties`; no imperative `GoogleMap` styling calls.
* Clear module split; `:feature:map` has no compile-time dependency on Play Services types outside Maps Compose UI layer.
