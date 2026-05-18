# dev/v10 — What Changed vs. main

High-level summary of the work landed on the `dev/v10` branch compared to `origin/main`.
At time of writing: **449 commits ahead, 0 behind**, ~262k insertions / ~39k deletions across ~2,058 files.

This file is **not auto-managed** — it is a hand-curated orientation note for LLMs and contributors
landing on the branch. Update it when major themes shift.

---

## 1. UI Modernization → Pure Compose + Material 3 Expressive
- Removed all XML layouts, Fragments, and the legacy View infrastructure (StyleController, ModuleActivity, draggable UI, RecyclerActivity, FragmentStats/Tracker/Game/Map/Activity/SessionActivity, preference fragments).
- Migrated every screen to Compose routes with type-safe Kotlin Serialization navigation.
- Replaced MaterialDialogs with Compose dialogs; LiveData → Flow throughout.
- Adopted `MaterialExpressiveTheme`, `MotionScheme` tokens, `TrackerShapes`, `TrackerTypography`, dynamic color (Android 12+) with MaterialKolor `Expressive` palette fallback, glass cards, `CircularWavyProgressIndicator`.
- **Ridgeline Design System** (30 design rounds) now drives spacing, typography, shape, motion, color, dialogs, empty states, snackbars, dark-mode/CVD treatments.

## 2. Map: Zero-Telemetry Rendering
- Replaced **Google Maps with MapLibre GL Native**; bundled offline z0–z6 basemap tiles.
- Added on-the-fly **vector tile generation** system, **zoom-adaptive LOD**, **viewport-bounded heatmap queries**, allocation perf passes.
- Geocoder removed entirely; place-name search now surfaces a format-hint snackbar instead of network/system geocoding.
- Heatmap data cache, async normalization, tile provider hardening.
- Replaced bottom sheet with a tiered chrome card; calmed location/speed heatmap saturation.

## 3. Tracker Pipeline Overhaul
- Extracted `TrackingOrchestrator` from the `TrackerService` god object.
- Replaced the untyped `TempData` map with a typed `TrackingCycle` envelope and `TrackingCycleBuilder`.
- **Composable pipeline** replacing the hardcoded stage sequence (`PreComponent` / `DataComponent` / `PostComponent` chain wired via configuration).
- Consolidated all Room persistence into a single `PersistenceProcessor` with shadow-validation parity tests.
- Producer failure isolation; `CancellationException` propagation; cell/wifi/pressure/policy fields added to `TrackingSignal`.
- Performance pass 1: producers off main, cell-info cache, batched location writes (size=10 / 5s, flush on shutdown), service-running state migrated to `StateFlow`.
- **Smart alpine ski detection** with barometer + 1D Kalman altitude fusion; gondola/lift type detection; ski session detail UI.
- **AMBIENT** tracking mode (steps + activity, no GPS).

## 4. Data & DI Hygiene
- **SharedPreferences → Proto DataStore** across settings, retention, tracking params, developer prefs, lock manager.
- **AppGraph service-locator → pure Hilt DI**: `@HiltViewModel` per screen, `@HiltWorker` for 8 workers, `DispatchersProvider` injected (no raw `Dispatchers.IO`).
- Removed mutable companion-object singletons (Preferences, ActivityWatcherService static bridge, etc.); ViewModels split per screen.
- Room schema bumped to **v26 / 26 entities** with **migration tests for every step**, missing indices added, `LIMIT` safety nets on unbounded queries, `BaseDao` CRUD methods made `suspend`.
- Hilt modules organized as `AppGraphModule` / `RepositoryModule` / `InfrastructureModule`; CompositionLocals replaced with Hilt ViewModel injection.

## 5. New Feature Surface
- **Dashboard module**: idle + tracking states, live stats, milestones, weekly chart, recent trips, motivational text, sensor details, glass cards, per-widget personality tones, drag-reorder, `EmptyStateStartHintCard`.
- **Glance app widgets** with hardened error handling.
- **Stats rearchitecture (phases 0–8)**: trip inference data layer + enrichment, `SessionSegmentDetector`, `StreamingAggregator` → `DailySummary` materialization, **S2 cell exploration** with discovery streaks, achievement engine, History UI with timeline + calendar + exploration/achievement views.
- **Data retention pipeline** with `PolicyTier`, `PolicyEscalationEngine`, configurable auto-cleanup & retention years.
- **Streaming GPX (jpx removed) + KML + JSON + SQLite** import/export; paged exports prevent OOM; coordinate validation prevents NaN/Infinity in output.
- Markdown-rendered **Privacy Policy**; contextual help tooltips; redesigned **Setup** flow replacing legacy Onboarding.
- **Smart goal notifications**, session insights cards, calendar, charts, LeakCanary, fitness tests.

## 6. Privacy & Safety Hardening
- Centralized **PII redaction** in Logger; `Reporter` / `ReporterFacade` route errors through the structured logger.
- Removed all remote URLs, Firebase scaffolding, analytics terminology.
- **Sealed `*Result` types** replace cross-module exceptions (no throwing across module boundaries).
- Most production `runBlocking` calls eliminated; remaining sites (`PagedLocationSequence`, `CrashHandler`, `DebugCrashLogExporter`, `TrackerSettingsAccess`, `LegacyPreferenceStore`) are explicit and isolated to lifecycle/IO entry points. `CopyOnWriteArrayList` / mutexes for thread safety; SQL injection fix in `SafeQueryBuilder`; KML coordinate validation.

## 7. Testing Explosion
- Hundreds of new unit tests across all modules. The `:app` module alone has **~25 Compose UI test files (~168 `@Test` methods)** exercising real composables; sbase, game, statistics, activity, tracker, stats-*, map, sutils, dashboard all gained substantial coverage.
- **Compose UI tests** verify real composables with real assertions.
- Room **migration tests** for every schema step, integration tests for streaming aggregator, ski detection, retention, repositories.
- Test fakes consolidated into `:testing-common` (`FakeLocationSource`, `FakeTrackerSettingsRepository`, `TestAppGraphBuilder`).
- Robolectric tests migrated from JUnit 4 to JUnit 5 extension where applicable.

## 8. Build & Tooling
- **AGP 9.1.0**, **Gradle 9.3.1**, **Kotlin 2.2.20**, **JDK 17** toolchain via Foojay auto-provisioning, **KSP-only** (no KAPT). compileSdk/targetSdk 36, minSdk 26.
- All versions live in `gradle/libs.versions.toml`; Jetifier disabled.
- Removed `macrobenchmark` module; lint baselines for all modules.
- `:smap` retired (a stub `smap/` directory remains with only `build.gradle.kts` + `.gitignore` and is not in `settings.gradle.kts`); `:logger` decoupled from `:sbase` via the `:logging-api` module; `:dashboard` extracted as its own module. Current module count: **18**.
- Photo geotagger and QC testing utilities under `tools/`; comprehensive architecture docs (`docs/ARCHITECTURE_OVERVIEW.md`, `docs/STATS_PIPELINE_ARCHITECTURE.md`).

---

## TL;DR

`dev/v10` rewrites the app on a **modern, modular, privacy-first foundation**
(Compose + M3 Expressive + Hilt + Flow + DataStore + Room migrations + MapLibre),
restructures the tracker and stats pipelines into typed/composable architectures,
and adds substantial new product surface (Dashboard, widgets, ski detection,
exploration system, achievements) — all backed by a much larger and more rigorous test suite.
