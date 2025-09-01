# Compose Migration Progress

This file tracks ongoing fragment/activity migrations to Jetpack Compose.

Last updated: 2025-08-31

## Scope

- Start with fragments. Activities are tracked separately in COMPOSE_MIGRATION_SCREENS.md.

## Fragments

- FragmentStats
  - Status: Phase 2 done (pure Compose list) + load states + actions wired + a11y + localized strings + subtle motions + UI tests added (refresh states, header actions, append placeholders, footer retry, session row click)
  - Approach: Fragment hosts Compose via ComposeView. Replaced RecyclerView with LazyColumn driven by Paging Compose; rendering covers ListHeader, SessionHeader, and Session rows using Material 3 Expressive components (ElevatedCards, icons). Added refresh/append loadState UIs and placeholders with Crossfade/AnimatedVisibility. Legacy Summary/Week dialogs ported to a Compose AlertDialog; Wi‑Fi action opens WifiBrowseActivity. Added test tags for deterministic selection.
  - Next steps (optional):
    - Add an Espresso Intents test to assert navigation to StatsDetailActivity with the correct session ID
    - Consider basic screenshot tests for visual regressions
    - Continue migrating remaining fragments

- FragmentTracker
  - Status: Phase 1 done (Compose host + dashboard). Phase 2 in progress (adaptive grid, lock badges, disabled-source states, haptics). Live preference observation wired. Minimal UI test scaffold added; androidTest assembles successfully. Legacy RecyclerView adapter and XML layouts removed.
  - Notes: TrackerFragment now hosts `TrackerDashboard` in Compose. Uses existing LiveData (service running, session/collection), lock state, and preferences. Adaptive layout with progressive disclosure implemented. Further tests and polish pending. Build green for :tracker:assembleDebug and :tracker:assembleDebugAndroidTest after cleanup.

- FragmentGame
  - Status: Phase 1 done (Compose host + expressive cards). Fragment now uses ComposeView and renders GameScreen in Material 3 with points, steps goals, and challenges list. LiveData bridged via observeAsState. Build green for :game:assembleDebug.
  - Approach: Enabled Compose in :game with version catalog deps. Added GameScreen (ElevatedCards, AssistChip, icons), wired points (Room LiveData), steps (GoalTracker LiveData), and challenges (ChallengeManager active list). Kept legacy Recycler code intact for now but unused.
  - Next steps (optional):
    - Add WindowInsets padding (status/navigation) and pull-to-refresh
    - Add progress indicators/empty states for challenges
    - Hook chip actions to detailed points/goals views
    - Compose UI tests (basic rendering + LiveData changes)

## Notes

- Statistics module was updated to enable Compose and add core Compose dependencies.
- No public APIs changed; FragmentStats still provides the same UI externally.
- Instrumented Compose UI tests pass (5/5) for the statistics module.
