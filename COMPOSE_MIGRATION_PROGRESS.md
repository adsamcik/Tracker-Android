# Compose Migration Progress

This file tracks ongoing fragment/activity migrations to Jetpack Compose.

Last updated: 2025-08-31

## Scope

- Start with fragments. Activities are tracked separately in COMPOSE_MIGRATION_SCREENS.md.

## Fragments

- FragmentStats
  - Status: Phase 2 done (pure Compose list) + load states + actions wired
  - Approach: Fragment hosts Compose via ComposeView. Replaced RecyclerView with LazyColumn driven by Paging Compose; rendering covers ListHeader, SessionHeader, and Session rows using Material 3 Expressive components (ElevatedCards, icons). Added refresh/append loadState UIs and placeholders. Legacy Summary/Week dialogs ported to a Compose AlertDialog; Wi‑Fi action opens WifiBrowseActivity.
  - Next steps:
    - Localize any remaining hardcoded strings in non-base locales (added base strings in this change)
    - Add content descriptions and a11y review for new icons/chips
    - Add Compose UI tests for load states and navigation

- FragmentTracker
  - Status: Not started

- FragmentGame
  - Status: Not started

## Notes

- Statistics module was updated to enable Compose and add core Compose dependencies.
- No public APIs changed; FragmentStats still provides the same UI externally.
