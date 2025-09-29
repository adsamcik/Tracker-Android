# MainActivity Compose Migration – Plan & Tracking

This document tracks the end-to-end migration of `MainActivity` to Jetpack Compose, redesigning navigation with a Material 3 bottom bar and a prominent center Map pill, and decommissioning Dynamic Feature modules in favor of statically linked modules.

Last updated: 2025-09-28

## Goals

- Replace `MainActivity` UI with Compose while keeping `CoreUIActivity` (language/style infra).
- Bottom navigation: left=Statistics, center=Map (prominent pill), right=Game. Order preserved.
- Center Map “pill” is tap-first: tap toggles the Map overlay (collapsed ↔ expanded). We skip drag gestures for v1 to avoid system gesture conflicts; the pill and overlay animate in sync.
- Host existing modules’ UIs without regressions:
  - Tracker (base layer): reuse current `FragmentTracker` (already Compose-hosted `TrackerDashboard`).
  - Stats and Game: reuse current fragments (Compose-hosted) initially; later consider extracting composables.
  - Map overlay: reuse `FragmentMap` (legacy map + Compose `MapScreen` + `MapSheet`).
- Remove dynamic features: convert `:statistics`, `:game`, `:map` from dynamic features to static modules; remove install gating and reflective class loading.
- Maintain onboarding gate and back behavior parity.

## Design System Migration (Material 3 Expressive + Dynamic)

We retired the legacy StyleManager-driven dynamic styling in Compose entry points and now rely on a Material 3 theme with dynamic color support on Android 12+ plus an Expressive fallback everywhere else.

Key points:

- Dynamic color (Android 12+): `dynamicLightColorScheme` / `dynamicDarkColorScheme` continue to supply Monet palettes when available.
- Expressive fallback (pre-Android 12, or when dynamic disabled): `AppTheme` now computes a Material color scheme via Material Color Utilities `SchemeExpressive` from a stable seed.
- Single theme entry point: `AppTheme` (expressive-aware) replaces legacy wrappers. All Compose UIs consume `MaterialTheme` tokens only.
- Transitional bridge: minimize/remove `StyleManager` usage; keep only where needed until remaining View screens migrate.

### Phase G – Theming Migration to Material 3 Expressive

- [x] Evaluate need for additional dependencies (adopted `com.materialkolor:material-kolor` for stable Expressive palettes on non-dynamic devices).
- [x] Update `sutils/.../style/compose/AppTheme.kt` to generate Expressive palettes when dynamic color is unavailable (pre-Android 12 or manually disabled).
- [ ] Replace any lingering `TrackerTheme`/`DynamicTrackerTheme` usages in legacy modules with `AppTheme`.
- [ ] Remove `StyleManager` color listeners from Compose entry points (e.g., `ComposeDetailActivity`, fragments using Compose) and stop translating `StyleData` into M3 colors.
- [ ] Optional preference for seed color (or default to brand/app icon color); wire a lightweight storage in `Preferences`.
- [ ] Verify contrast and legibility (onPrimary/onSurface) across light/dark + expressive.
- [ ] Update screenshots/tests if applicable.

#### Acceptance Criteria (Theming)

- On Android 12+, app uses system dynamic color automatically (light/dark) when `dynamic=true`.
- On < Android 12 or when dynamic disabled, app uses an Expressive color scheme derived from a seed color.
- All Compose screens (Tracker, Stats, Game, Map overlays/sheets) use `MaterialTheme.colorScheme` consistently.
- No `StyleManager`-driven theme computations remain in Compose entry points.

#### Risks & Notes (Theming)

- System dynamic color style can differ from Expressive; we accept this (Expressive is used as fallback).*
- If we later want Expressive even on 12+ sourced from wallpaper seed, we can inspect Material Color Utilities APIs (or Compose adapters) to derive palettes from wallpaper metadata; v1 keeps system dynamic as-is.
- Remove legacy system bar color watchers after verifying edge-to-edge looks acceptable; otherwise add a small bridge that sets system bar colors from `MaterialTheme`.

## Non-Goals (for v1)

- No middle-pill drag gestures (may return in v2). This reduces gesture collisions with system navigation.
- No full rewrite of Stats/Game to pure composables (fragments remain; can be future work).
- No re-introduction of DraggableImageButton/DraggablePayload system.

## Architecture Overview

- Activity: `MainActivity : CoreUIActivity`, calls `setContent { AppTheme { … } }` to host Compose.
- Layered layout inside Compose:
  1) Base content layer: Tracker (default “home”).
  2) Tab content layer: Stats OR Game (full-screen swap, independent of Map).
  3) Overlay layer: Map (full-bleed overlay with vertical animation between Collapsed and Expanded; optional intermediate Peek later).
  4) Bottom bar: Material 3 nav with center pill (prominent, elevated). Tap toggles the Map overlay.
- Fragment interop in Compose: one-time attachment of `FragmentContainerView` per screen via AndroidView; switch visibility/zIndex. Preserve fragment state.
- Insets: Use WindowInsets to compute `navigationBars`/`ime` bottom. Pass effective bottom padding to the Map overlay container so `MapSheet`→`MapScreen` stays padded (already wired via `onBottomPaddingChanged`).
- Back navigation: If Map is expanded → collapse; else if tab ≠ Tracker → switch to Tracker; else perform default back.

## Dynamic Features → Static Modules

Rationale: Dynamic delivery wasn’t used reliably and app size is modest. We will statically link modules.

- Convert plugins in `:statistics`, `:game`, `:map` from `com.android.dynamic-feature` to `com.android.library`.
- Remove `dynamicFeatures.add(…)` entries from `:app/build.gradle.kts`.
- Remove `implementation(project(":app"))` from feature modules (invalid for libraries). If shared app-only APIs are needed, move them to `:sbase`/`:sutils` (or create `:appcore` if necessary) and depend on that instead.
- Remove SplitInstall APIs and gating. Assume modules are always present.
- Update `Module` enum and `ModuleClassLoader` usage:
  - Prefer direct type references over reflective `ModuleClassLoader.loadClass`.
  - Deprecate/remove payload-style dynamic fragment creation in `MainActivity`.

## Detailed Phases & Tasks

### Phase A – Repo Prep & Static Modules

- [x] Switch `:statistics`, `:game`, `:map` plugins to `com.android.library`.
- [x] Remove `dynamicFeatures.add` from `:app`.
- [x] Remove `implementation(project(":app"))` from feature modules; compile-fix by moving shared code into `:sbase`/`:sutils` (or new `:appcore`).
- [x] Remove SplitInstallManager usage and module gating in app code (e.g., `MainActivity`, `Module` helper, installers/UI).
- [x] Replace reflective loading via `ModuleClassLoader` with direct references; mark helper as deprecated (cleanup follow-up).
- [x] Build passes for `:app`, `:statistics`, `:game`, `:map`.

### Phase B – Compose Shell for MainActivity

- [ ] Keep `CoreUIActivity` and StyleManager hooks (language, theme). Replace content view with Compose setContent.
- [ ] Implement Material 3 Scaffold with bottom bar. Items: Stats (left), Map pill (center, prominent), Game (right).
- [ ] State: `selectedTab` (Tracker default; Stats/Game when chosen), `isMapExpanded` (bool). Use `rememberSaveable` or Activity VM.
- [ ] Onboarding gate preserved (reuse existing `OnboardingActivity.isOnboardingCompleted` check in `onStart`).
- [ ] Handle `openGame` extra in `onNewIntent` → select Game.

### Phase C – Content Hosting

- [ ] Base layer: attach `FragmentTracker` once; show when `selectedTab == Tracker`.
- [ ] Stats: attach `FragmentStats` once; visible when `selectedTab == Stats`.
- [ ] Game: attach `FragmentGame` once; visible when `selectedTab == Game`.
- [ ] Map overlay: attach `FragmentMap` once in an always-on container; animate vertical offset based on `isMapExpanded`.
- [ ] Ensure overlay insets are propagated to Map so `MapSheet` can adjust padding; verify `MapScreen` `MapEffect` padding is applied.

### Phase D – Animations, A11y, Back

- [x] Center pill animation: scale/elevation/color synchronized to overlay expand/collapse; tap toggles.
- [x] Overlay animation: spring/tween from collapsed to expanded; optional Peek later. No drag gestures in v1.
- [x] A11y: add content descriptions (Statistics, Map, Game), pill labeled “Open map/Close map”.
- [x] Back handling: expanded → collapse overlay; non-Tracker tab → switch to Tracker; else default.

### Phase E – Cleanup Dynamic Artifacts

- [x] Remove draggable button code, payload system bits, and exclusion rects no longer used.
- [x] Remove now-dead resources/layouts tied to old `activity_ui` and draggable nav.
- [x] Update `COMPOSE_MIGRATION_SCREENS.md` and `COMPOSE_MIGRATION_PROGRESS.md` with new status.

### Phase F – Tests & Quality Gates

- [x] Unit/UI tests for MainActivity Compose shell: `MainActivityComposeTest` (`tabSelection_updatesRoute`, `mapPill_collapsesOnBack`, deep link coverage) and `MainActivityBackBehaviorTest` executed on JVM.
- [x] Map unit tests: `./gradlew.bat :map:testDebugUnitTest --no-daemon --console=plain`.
- [x] Build: `./gradlew.bat :app:assembleDebug --no-daemon --console=plain` (covers `:statistics`, `:game`, `:map` via dependencies).
- [x] Lint/Detekt: `./gradlew.bat lint --no-daemon --console=plain` (warnings resolved by adding missing default strings).
- Follow-up: schedule instrumentation run `./gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain` on Pixel 6 (Android 14) to validate onboarding + map flows under expressive theme, including toggling dynamic color at runtime to confirm MaterialKolor fallback (pending device slot).

## Interaction Details

- Pill-only map interaction minimizes collision with Android gesture nav (no vertical drag near edges). When expanded, overlay provides a small tappable pill/handle to close; map gestures remain native via Google Maps Compose/interop (we already cancel follow on user gestures).
- Animation sync contract:
  - Pill: scale 1.0 → 1.06, elevation 6dp → 12dp, color shift to match `primaryContainer` while open.
  - Overlay: translateY from full height (collapsed) → 0 (expanded); 250–350ms tween, FastOutSlowIn; optional spring for snap.

## Acceptance Criteria

- Bottom nav shows three items, with center pill visually prominent.
- Tapping center pill expands/contracts the Map overlay; pill animates in sync with overlay.
- Stats and Game screens show correctly; Tracker is default home. Deep link `openGame` opens Game tab.
- Back collapses Map if open; otherwise returns to Tracker; from Tracker, default back works.
- No SplitInstall code remains; app builds without dynamic features configured.
- Map content respects bottom insets; `MapSheet` no longer overlaps nav bar or pill.

## Risks & Mitigations

- Converting dynamic features may expose module dependency issues (features depend on `:app`).
  - Mitigation: move shared APIs/resources to `:sbase`/`:sutils` (or a new `:appcore`). Add PRs per module if needed.
- Fragment state with Compose hosting: ensure we attach once and toggle visibility to avoid churn.
- System bars styling parity: initially accept Material defaults; optionally add a bridge to StyleManager for dynamic bar colors.

## Progress

Overall status: Phases A–F complete; theming migration (Phase G) underway

- Fragments Compose migrations: Tracker ✅, Stats ✅, Game ✅ (per `COMPOSE_MIGRATION_PROGRESS.md`).
- Decision log:
  - 2025-08-31: v1 removes middle-pill drag; tap-only with synchronized animation. ✅
  - 2025-08-31: Remove dynamic features; link modules statically. ✅ (decision)
  - 2025-09-28: Animated map overlay + accessibility + back handling shipped in Compose MainRoot. ✅
  - 2025-09-28: Removed legacy draggable payload resources and layouts; documentation synced. ✅

### Checklist snapshot

- [x] Phase A – Repo Prep & Static Modules
- [x] Phase B – Compose Shell for MainActivity
- [x] Phase C – Content Hosting
- [x] Phase D – Animations, A11y, Back
- [x] Phase E – Cleanup Dynamic Artifacts
- [x] Phase F – Tests & Quality Gates

## Next Steps (upcoming PRs)

1) Phase A PR: convert features to libraries, drop dynamicFeatures from `:app`, fix dependencies.
2) Phase B PR: introduce Compose Scaffold in `MainActivity` with bottom bar and state (no content swap yet).
3) Phase C PR: add fragment hosts for Tracker/Stats/Game/Map; wire tab selection and overlay toggle.
4) Phase D PR: animation polish + back handling + a11y.
5) Phase E/F PRs: cleanup dead code, tests, and quality gates.

---

When you make progress, mark the corresponding checkboxes and add notes under Progress.
