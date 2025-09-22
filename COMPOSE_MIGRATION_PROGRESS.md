# Compose Migration Progress

This file tracks ongoing fragment/activity migrations to Jetpack Compose.

Last updated: 2025-09-21

## Scope

- Start with fragments. Activities are tracked separately in COMPOSE_MIGRATION_SCREENS.md.

## Routes (Compose-First Navigation)

Migrated from fragment-hosted ComposeViews to direct route composables inside a single NavHost (see `MainRoot`). Legacy fragment terminology below is retained only for historical comparison; active surfaces are now `*Route` + pure Compose screens.

- StatsRoute (replaces FragmentStats)
  - Current Status: Pure Compose implementation with Paging3 integration (sessions Pager), refresh/append load state mapping, inline append footer, header action buttons (summary/week/wifi placeholders). Session rows now include basic metadata (date, duration, steps). New unit test (`StatsScreenPlaceholderTest`) and instrumentation tests (`FragmentStatsUiTest`, `StatsScreenTest`, `StatsPagingIntegrationTest`) cover refresh states, append loading/error, header actions, placeholder mode, and paging integration.
  - Removed / Corrected Claims: Previous doc claimed dialog migrations and subtle motions not yet implemented in route version (summary/week dialogs still TODO; animations minimal). Updated to reflect actual feature set.
  - Next Steps:
    - Implement summary & week dialogs in pure Compose.
    - Inject repositories instead of direct DB access in `StatsViewModel` (constructor injection via app graph, remove `AndroidViewModel`).
    - Add intent navigation test for session detail once detail route is migrated from activity.
    - Accessibility pass (content descriptions for dynamic metadata, larger hit targets for header icons).

- TrackerRoute (ongoing migration of former TrackerFragment)
  - Status: Dashboard Compose surface present (per earlier plan) – doc sync pending separate PR. (No change in this update.)

- GameRoute (replaces FragmentGame)
  - Current Status: Now wired to real `GameViewModel` exposing points today (Room), steps goals (GoalTracker), active challenges (ChallengeManager). Existing instrumentation tests (`GameScreenTest`, `GameScreenReactiveTest`) validate rendering and reactive updates. Points, steps, challenges previously sample-only now live data driven.
  - Pending: DI refactor (constructor injection for DAOs/managers), add empty/progress states for challenges, detail actions.

## Removed / Outdated Assertions

- References to fragments hosting Compose have been superseded by direct route composables. No XML or RecyclerView remains for Stats/Game paths.
- Assertions about screenshot tests or dialog migrations were speculative; trimmed to concrete next steps.

## Testing Summary (Updated)

- Stats: 1 JVM unit test + multiple instrumentation tests covering refresh/append states, interactions, and paging integration.
- Game: Instrumentation tests for static render & reactive state changes.
- Additional tests will be added as dialogs & DI refactors land.

## Notes

- Statistics & Game modules now expose route composables only; fragment layer considered deprecated and removed.
- Direct database/service access in ViewModels scheduled for DI refactor (privacy & testability improvement).
- All new code adheres to Material 3 and avoids legacy view inflation.
