# Map Compose Migration Tracking (Temporary)

Note: This is a temporary doc to guide the migration and will be deleted after completion.

## Goals

- Simplify the Map architecture, centralize state, and migrate UI to Jetpack Compose without losing features.
- Keep performance and behavior parity (heatmaps, polyline, follow-mode, search, date range, legend, styling, intros, permissions).
- Commit at the end of each phase with a clear message; small, reviewable PR slices.

## Current architecture (summary)

- Fragment-based UI (`FragmentMap`) hosting a `SupportMapFragment` via `MapOwner`.
- Controllers: `MapController`, `MapSensorController` (location/sensors/follow), `MapPositionController` (user markers/circle), `MapEventListener` (multiplex camera and click), `MapSheetController` (XML bottom sheet UI + keyboard/padding), `MapBottomSheetBehavior`.
- Layers: `DefaultLayerRegistry` provides `LayerDescriptor` → `LayerEntry` → `BaseMapLayer` implementations (heatmaps, wifi, cell, speed, polyline) managed by `LayerController`.
- Heatmap pipeline: `OptimizedTileProvider` / `HeatmapTileProviderBase` / `HeatmapEngine` / caches.
- Style: `ColorMap` sets Google Map style.
- Presentation: lightweight `MapViewModel` scaffold exists but not wired to UI.

## Target architecture (Compose-first)

- MapScreen Composable with `BottomSheetScaffold` and `GoogleMap` (Maps Compose) as main content.
- `MapEffect` to access underlying `GoogleMap` for styling and legacy overlays during transition.
- ViewModel-centric state (layer/date range/quality/follow/search/sheet/legend/tile progress).
- Managers: `LocationAndSensorsManager`, `LayerManager` (refactor of `LayerController`), `CameraBehavior`.
- Replace XML and custom behavior with Compose; replace imperative markers with Compose markers/circles where possible.
- Prefer Maps Compose overlay composables directly: `Marker`, `Circle`, `Polyline`, `Polygon`, `GroundOverlay`, and crucially `TileOverlay` with a hoisted `TileOverlayState` and our `TileProvider`. Reserve `MapEffect` for gaps only.

## Extra simplifications we can make

- Centralize to a single MapStore (ViewModel) as the source of truth. Drop ad-hoc controller state and keep all UI state in the VM.
- Remove `MapSheetController` immediately once the Compose sheet is added (Phase 1), not later. Also remove `MapBottomSheetBehavior` from the codebase in Phase 1.
- Dissolve `MapController` early (Phase 1/2). Map UI settings and defaults live in MapScreen and ViewModel; tile progress becomes a Flow in the VM.
- Use `MapProperties(mapStyleOptions = ...)` from Maps Compose for styling instead of imperative `ColorMap.addListener/removeListener`. Adjust `ColorMap` to provide style JSON or `MapStyleOptions`.
- Represent overlays declaratively with a sealed state (e.g., `MapOverlayState`) and Composables for `Marker`/`Circle`/`Polyline`/`TileOverlay`. Avoid `MapEffect` for overlays; keep it only for rare interop.
- Replace Sensor-based bearing with an optional mode: default to location bearing to simplify and reduce battery; keep device orientation as a user toggle.
- Use `callbackFlow` for location updates; avoid manual Looper calls and imperative flags.
- Use Compose `WindowInsets` for IME/system bars rather than padding hacks.
- Move permission UX to Compose; prefer `rememberLauncherForActivityResult` + `ActivityResultContracts.RequestMultiplePermissions` (platform-first). Optionally wrap with `accompanist-permissions` for ergonomic state (noting it’s experimental).
- Migrate legend and layer lists fully to Compose immediately; delete RecyclerView adapters.
- Introductions via Compose (or keep current `IntroductionManager` but trigger from Compose-only hooks) to simplify View wiring.

## Recommended APIs (2025-ready)

- Maps Compose: `GoogleMap`, `CameraPositionState` (read `isMoving` and `cameraMoveStartedReason`), `MapUiSettings`, `MapProperties(mapStyleOptions = ...)`, overlay composables (`Marker`, `Circle`, `Polyline`, `Polygon`, `GroundOverlay`, `TileOverlay` + `TileOverlayState`).
- Camera gesture handling: prefer `snapshotFlow { cameraPositionState.isMoving }` with debounce for follow-mode cancel; avoid heavy work in direct callbacks.
- Bottom sheets (Material3): use `ModalBottomSheet` for modal behavior; if a persistent sheet is required, use Material3 `BottomSheetScaffold` if on a version where it’s stable; otherwise emulate with `ModalBottomSheet` + anchored layouts.
- Permissions: platform `ActivityResultContracts.*` via `rememberLauncherForActivityResult`; optionally `accompanist-permissions` for simplified state, noting `@ExperimentalPermissionsApi`.
- Insets: `WindowInsets.systemBars` and `WindowInsets.ime`, set `contentWindowInsets = WindowInsets(0)` on Scaffolds and manage paddings explicitly where needed.

## Migration plan (phased)

### Phase 0 – Prep and dependencies

- [ ] Add Maps Compose dependency (align with Play Services Maps).
- [ ] Ensure Material3 Compose + existing theme (`TrackerTheme`) ready.
- [ ] Keep legacy Map stack intact; no behavior change.

Commit checkpoint

- Message: `docs(map): add Compose migration tracking plan (temporary)`

### Phase 1 – Compose bottom sheet UI, remove legacy sheet immediately

- [ ] Add a `ComposeView` overlay in `FragmentMap` hosting a Compose bottom sheet (layer selector + legend + search + buttons).
- [ ] Wire layer selection to a thin `LayerManager` bridge (reusing `LayerController` internals) and show legend via Compose.
- [ ] Replace tile progress TextView with a Compose label fed from a VM `StateFlow`.
- [ ] Control map padding from sheet offset; for legacy map, call `map.setPadding(...)` via a small bridge.
- [ ] Delete `MapSheetController`, `layout_map_bottom_sheet*`, and `MapBottomSheetBehavior` now; remove their usages.

Commit checkpoint

- Message: `feat(map): Phase 1 – Compose bottom sheet replaces legacy sheet (remove XML/behavior)`

### Phase 2 – State centralization + dissolve MapController

- [ ] Expand `MapViewModel` to own: selected layer id, date range, quality, search query/results, sheet state, legend, tile progress.
- [ ] Compose sheet reads/writes VM; layer updates go through `LayerManager` (bridge) which applies overlays.
- [ ] Move map UI settings (toolbar/compass/myLocationButton off) into Compose map props; delete `MapController`.

Commit checkpoint

- Message: `refactor(map): Phase 2 – centralize state in ViewModel and remove MapController`

### Phase 3 – Replace SupportMapFragment with Maps Compose (+ style via MapProperties)

- [ ] Remove `MapOwner`/`SupportMapFragment`; render with `GoogleMap` composable and `cameraPositionState`.
- [ ] Apply style via `MapProperties(mapStyleOptions)` using data from `ColorMap` (refactored to expose `MapStyleOptions` or JSON).
- [ ] Render Tile overlays via `TileOverlay` composable with a hoisted `TileOverlayState` using our existing `TileProvider` implementation.
- [ ] Follow-mode cancel on gesture via camera state.
- [ ] Trigger introductions on map loaded via Compose callback.

Commit checkpoint

- Message: `feat(map): Phase 3 – switch to Maps Compose and MapProperties styling`

### Phase 4 – Controller refactors → managers + declarative overlays

- [ ] Replace `MapEventListener` with Compose-driven camera/gesture observation.
- [ ] Move `MapSensorController` responsibilities into `LocationAndSensorsManager` (flows via `callbackFlow`), make device-orientation-follow optional.
- [ ] Replace `MapPositionController` with Compose `Marker`/`Circle` for user, accuracy, direction, and activity.
- [ ] Define a `MapOverlayState` sealed class and render overlays declaratively, including `TileOverlay` with `TileOverlayState`.

Commit checkpoint

- Message: `refactor(map): Phase 4 – manager-based sensors and declarative overlays`

### Phase 5 – Permissions, polish, and cleanup

- [ ] Replace fragment-based permission prompts with Compose permission flow; remove `CorePermissionFragment` usage from Map screen.
- [ ] Wire low-memory trim via VM/managers (tile cache/bitmap pool).
- [ ] Add tests: VM state, layer toggle, basic sheet behavior; smoke test heatmap overlay.
- [ ] Remove any remaining legacy classes/resources and this temporary tracking file.

Commit checkpoint

- Message: `chore(map): Phase 5 – permissions in Compose and final cleanup`

## Risks and mitigations

- Gesture parity for follow-mode: rely on `cameraPositionState` and explicit gesture hooks; unit test.
- Tile overlay lifecycle: tie overlay install/clear to Compose lifecycle; centralize in `LayerManager`.
- Permissions/keyboard: Compose permission flow and IME handling; keep interop where needed.
- Performance: maintain budgets in heatmap providers; avoid excessive recompositions around Map.

## Success criteria

- Feature parity for: layers/legend, heatmaps/polyline, follow-mode, search, date range, style, intros, permissions, tile progress.
- No regressions in performance or battery; overlays clear correctly; smooth sheet/gesture UX.
- Codebase simplification: remove legacy fragment map XML and controllers; UI fully Compose; state in ViewModel.
