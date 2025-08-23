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

## Migration plan (phased)

### Phase 0 – Prep and dependencies

- [ ] Add Maps Compose dependency (align with Play Services Maps).
- [ ] Ensure Material3 Compose + existing theme (`TrackerTheme`) ready.
- [ ] Keep legacy Map stack intact; no behavior change.

Commit checkpoint

- Message: `docs(map): add Compose migration tracking plan (temporary)`

### Phase 1 – Compose bottom sheet UI, legacy map kept

- [ ] Add a `ComposeView` overlay in `FragmentMap` hosting a Compose bottom sheet (layer selector + legend + search + buttons).
- [ ] Bridge clicks to existing `LayerController` and legend to `MapLegendController` equivalent in Compose.
- [ ] Replace tile progress TextView with a Compose label fed from the same source (expose via VM/Flow).
- [ ] Control map padding from sheet offset (temporarily via existing map.setPadding or bridge function).
- [ ] Remove `MapBottomSheetBehavior` usage from runtime path; keep code until Phase 5 cleanup.

Commit checkpoint

- Message: `feat(map): Phase 1 – Compose bottom sheet scaffolding over legacy map`

### Phase 2 – State centralization

- [ ] Expand `MapViewModel` to own: selected layer id, date range, quality, search query/results, sheet state, legend, tile progress.
- [ ] Compose sheet reads/writes VM; layer selection updates go through a thin bridge that applies overlays.
- [ ] Remove `MapSheetController` from runtime path; keep until cleanup.

Commit checkpoint

- Message: `refactor(map): Phase 2 – centralize state in MapViewModel`

### Phase 3 – Replace SupportMapFragment with Maps Compose

- [ ] Remove `MapOwner`/`SupportMapFragment`; render with `GoogleMap` composable and `cameraPositionState`.
- [ ] Use `MapEffect` to apply `ColorMap` style and to attach legacy overlays via `LayerManager`.
- [ ] Follow-mode cancel on gesture via camera state or a small map callback.
- [ ] Trigger introductions on map loaded via `MapEffect`.

Commit checkpoint

- Message: `feat(map): Phase 3 – replace SupportMapFragment with Maps Compose`

### Phase 4 – Controller refactors → small managers

- [ ] Replace `MapEventListener` with Compose-driven camera/gesture observation.
- [ ] Move `MapSensorController` responsibilities into `LocationAndSensorsManager` (lifecycle-aware, flows).
- [ ] Replace `MapPositionController` markers/circle with Compose `Marker`/`Circle` where feasible (fallback to `MapEffect` as needed).
- [ ] Dissolve `MapController` (move quality/date defaults and low-memory into VM/managers).

Commit checkpoint

- Message: `refactor(map): Phase 4 – migrate controllers to managers and Compose overlays`

### Phase 5 – Cleanup and polish

- [ ] Remove legacy XML (`layout_map_bottom_sheet*`), `MapSheetController`, `MapEventListener`, `MapOwner`, `MapBottomSheetBehavior`, and unused resources.
- [ ] Wire low-memory trim via VM/managers (tile cache/bitmap pool).
- [ ] Add tests: VM state, layer toggle, basic sheet behavior; smoke test heatmap overlay.
- [ ] Remove this temporary tracking file.

Commit checkpoint

- Message: `chore(map): Phase 5 – cleanup legacy map XML/controllers and finalize Compose migration`

## Risks and mitigations

- Gesture parity for follow-mode: rely on `cameraPositionState` and explicit gesture hooks; unit test.
- Tile overlay lifecycle: tie overlay install/clear to Compose lifecycle; centralize in `LayerManager`.
- Permissions/keyboard: Compose permission flow and IME handling; keep interop where needed.
- Performance: maintain budgets in heatmap providers; avoid excessive recompositions around Map.

## Success criteria

- Feature parity for: layers/legend, heatmaps/polyline, follow-mode, search, date range, style, intros, permissions, tile progress.
- No regressions in performance or battery; overlays clear correctly; smooth sheet/gesture UX.
- Codebase simplification: remove legacy fragment map XML and controllers; UI fully Compose; state in ViewModel.
