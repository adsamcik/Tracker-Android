# Compose-native Main App Architecture (MainActivity → MainActivityCompose)

This proposes a Jetpack Compose–first architecture that replaces all UI previously hosted by MainActivity with a single-activity, multi-module Compose design.

## Goals

- Single-activity app with Navigation Compose; no new fragments.
- Bottom bar with 3 top-level destinations: Stats (left), Map (center, prominent), Game (right).
- Keep features in their modules; expose Composable entry points (small contracts).
- Material 3 dynamic/deterministic Expressive theme via `AppTheme`; edge-to-edge with insets (no StyleController).
- Preserve non-transitive R; fully qualify cross-module resources.

## High-level structure

- Launcher: `MainActivityCompose` only.
- Composition root (app module):
  - `MainRoot()` hosts:
    - `NavHost` with routes: `stats`, `map`, `game`.
    - `MainScaffold` bottom bar with emphasized center action for Map.
    - App-level SnackbarHost and transient dialogs.
- Feature routes (owned by modules):
  - tracker: `TrackerDashboardRoute(onOpenSettings: () -> Unit)`
  - map: `MapRoute(initialCamera: CameraConfig? = null)`
  - statistics: `StatsRoute()`
  - game: `GameRoute()`

Each Route:

- Owns its ViewModel lookup/factory.
- Exposes a small, stable Composable API from the module (no app types leaking in).
- Uses `collectAsStateWithLifecycle()`; emits UI; side effects via `LaunchedEffect`.

## Navigation

- Navigation Compose with three top-level destinations (no nested graphs initially).
- Deep links optional (e.g., openGame → navigate to `game`).
- Back behavior: if current is not `map`, go to `map`; else finish.

## Theming and system UI

- `AppTheme(darkTheme = isSystemInDarkTheme(), useDynamicColor = true)` at the root.
- Edge-to-edge via WindowInsets APIs and padding modifiers.
- StyleController/StyleManager deprecated and removed next release. MapScreen now follows `AppTheme` (dynamic/expressive) for the map style.

## State management

- UDF where already present (MapStore). Else: ViewModel exposing StateFlow for state + SharedFlow for one-shot effects.
- Composables read state and dispatch events; no business logic in Composables.

## Permissions and services

- Request runtime permissions via Compose-friendly helpers.
- Background services (TrackerService, ActivityWatcherService) unchanged.

## Module boundaries

- UI in feature modules; app module wires them via NavHost.
- Keep non-transitive R enabled; qualify R from other modules.

## Migration plan

1) Composition root
   - Add `MainRoot()` with `NavHost` + `MainScaffold` bottom bar.
   - Call `MainRoot()` from `MainActivityCompose` and remove manual bottom-row once stable.
2) Replace fragments
   - Inline tracker: use `TrackerDashboardRoute()` instead of `FragmentTracker`.
   - Map: retain current Compose overlay; remove `FragmentMap` wrapper when Maps Compose covers all.
   - Stats: expose `StatsRoute()` and stop using fragment shells.
3) Remove legacy layout
   - Stop inflating `activity_ui.xml` and delete draggable button resources after parity verified.
4) Remove style system
   - StyleController/StyleManager already marked @Deprecated; delete next release.
5) Back stack & deep links
   - Implement `openGame` as a deep link and `navController.navigate("game")`.
6) Testing
   - Compose UI tests per route; activity smoke test for bottom bar, back behavior, and deep link.

## Example (sketch only; not yet wired)

- `app/ui/navigation/Routes.kt`
  - sealed interface with Map/Stats/Game.
- `app/ui/MainRoot.kt`
  - Scaffold + BottomBar + NavHost calling `MapRoute()`, `StatsRoute()`, `GameRoute()`.

## Removal checklist

- [ ] Stop attaching `FragmentTracker`; render tracker in Compose.
- [x] Remove `activity_ui.xml` and draggable nav assets.
- [x] Remove StyleController/StyleManager usages from MapScreen; map follows AppTheme dark/light.
- [ ] Remove remaining usages across sutils/smap/statistics/activity; delete classes next release.

## CI additions

- Added dedicated map unit test jobs (Linux + Windows with flake guards). Windows disables Kotlin incremental and Gradle build cache for tests to avoid file-lock races.
- [x] Route `openGame` to NavController.
- [ ] Verify back parity via tests.

This design removes fragments from the main UI path, unifies navigation in Compose, and keeps modules independent by exposing small Composable contracts.
