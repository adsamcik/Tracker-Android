<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Tracker Android - LLM Instructions

<!-- context-init:managed -->

## 1. Project Essence
Privacy-first, fully local location & activity tracker for Android. No backend, no remote sync, no telemetry. **All data stays on-device. Never introduce network calls, analytics, or remote endpoints.**

---

## 2. Architecture
Multi-module Gradle project (28 modules) grouped by layer. See `docs/ARCHITECTURE_OVERVIEW.md`
for full component maps and `docs/MODULE_REARCHITECTURE_STATUS.md` for the modularization record.
Rule of thumb: depend on `*:api` contract modules, never on another feature's implementation.

| Module | Purpose |
|--------|---------|
| `:app` | Entry point, Hilt DI (`AppGraph`), root `NavHost`, settings, onboarding |
| `:core:base` | Room database, DAOs, Room `@Entity` types + entity↔model mappers (was `sbase`) |
| `:core:model` | Pure (Room-free) domain models (`Location`, `Trip`, `LocationSample`, `SkiRunSegment`, …) consumed by `:stats:api` |
| `:core:common` | Foundation leaf: concurrency (`DispatchersProvider`), time, constants, exceptions, DI/graph, io, logging, notification, service. No Room/DB. |
| `:core:ui` | Shared Compose UI, formatters, `AppTheme` (was `sutils`) |
| `:core:logging` / `:core:logging-api` | Logging impl / logger-facing contracts (`ReporterFacade`, `ErrorReporter`) (were `logger`/`logging-api`) |
| `:core:network` | Network helpers (was `network`) |
| `:core:testing` | Test fakes, utilities (was `testing-common`) |
| `:data:preferences` | Typed preferences, settings repos, retention (was `spreferences`) |
| `:stats:api` / `:stats:engine` / `:stats:data` | Stats contracts (depend on `:core:model`, NOT the DB) / algorithms / data layer (were `stats-*`) |
| `:domain:points` / `:domain:osm` | Points calculation / OpenStreetMap place lookup (were `points`/`osm`) |
| `:tracker:api` | Tracking contracts: `TrackerServiceController`, `LockManager`, `BackgroundTrackingApi`, session/insight types |
| `:tracker:engine` | Tracking impl: `TrackerService`, component pipeline, producers/consumers, policy, notification (no UI) |
| `:feature:tracker` | Tracking Compose UI (`TrackerRoute`) |
| `:sensor:activity-api` | Activity-recognition contracts (`ActivityRequestManager` interface, request/transition types) consumed by `:tracker:*` |
| `:sensor:activity` | Activity recognition impl via Google Play Services (was part of `activity`) |
| `:feature:activity` | Activity-type management Compose UI |
| `:feature:map` (+ `:feature:map:api`) | MapLibre visualization, heatmaps, layers + route contracts (was `map`) |
| `:feature:statistics` (+ `:feature:statistics:api`) | Session list, trip detail, analytics + route contracts (was `statistics`) |
| `:feature:dashboard` (+ `:feature:dashboard:api`) | Tracker dashboard, live stats, widgets + route contracts (was `dashboard`) |
| `:feature:game` (+ `:feature:game:api`) | Challenges, goals, gamification + route contracts (was `game`) |
| `:feature:import-export` | Import/export (GPX, KML, JSON, SQLite), streaming writers (was `impexp`) |

Data flow: `Sensors -> Producers -> TempData -> Pre/Data/Post-Components -> Room Database`

---

## 3. North Star & Hard Rules

**Target architecture:** Pure Compose, Material 3 Expressive, Flow/DataStore, fully modular, privacy-hardened. Every edit should move toward this target.

**ALWAYS:** Preserve privacy. Maintain modular boundaries. Write testable code. Minimize battery & I/O overhead.

**NEVER:** Introduce network sync. Inline dependency versions. Block main thread with I/O. Expose coordinates in release logs. Use `GlobalScope`. Throw exceptions across module boundaries.

**Legacy handling:** When encountering Views, Fragments, XML themes, LiveData, SharedPreferences, or ad-hoc threading, refactor toward the north star.

---

## 4. UI & Compose
- **Compose-only.** Zero XML layouts, zero Fragments, no `AndroidView` interop.
- Route-based: `FeatureRoute()` entry composables; stateless sub-composables.
- `AppTheme` at all composition roots. Current dependencies do not expose public `MaterialExpressiveTheme` or public `MaterialTheme.motionScheme`; use `AppTheme`'s Material 3 root with Tracker/Ridgeline shapes, typography, and motion tokens.
- Dynamic color (Android 12+) else MaterialKolor `PaletteStyle.Expressive` seed. No ad-hoc colors.
- **Motion tokens for animations:** Use `RidgelineMotion`/`ridgeline*` springs for spatial movement and `tweenQuick`/`tweenStandard`/`tweenEmphasized` for effects until Material MotionScheme is public. Keep `infiniteRepeatable` custom.
- Touch targets >= 48dp. WCAG AA contrast. Support 200% font scaling.

---

## 5. State & Concurrency
- **Flow-only.** No new LiveData. `StateFlow` for state; `SharedFlow` for events.
- Structured concurrency only. Inject `DispatchersProvider` (not direct `Dispatchers.IO`).
- Batch high-frequency sensor writes before DB persistence.

---

## 6. Data Layer
- Room v26 (26 entities). Paging/window queries only. Explicit SQL for hot paths.
- **Every schema change requires migration + test.**
- **DataStore (proto) for new key-value storage.** No new SharedPreferences. Legacy SharedPreferences (`LegacyPreferenceStore`, `PreferenceFlows`) is still read for migration compatibility — do not add new writes through it.

---

## 7. Privacy & Security
- Local-only. No remote endpoints. Redact PII in release logs.
- Exports require explicit user action. Least-precision-first for location.
- Permission requests: contextual, sequential, with rationale + "Skip".

---

## 8. Error Handling
- Sealed `*Result` types for cross-module operations. Never throw across modules.
- Snackbar for recoverable, dialog for blocking, inline for validation.

---

## 9. Dependencies & DI
- **All versions via `gradle/libs.versions.toml`.** KSP only, no KAPT.
- Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`) + AppGraph composition root.
- Hilt modules: `AppGraphModule`, `RepositoryModule`, `InfrastructureModule`.
- Interface + `Default*` impl + Hilt binding + test fake for new services.

---

## 10. Testing
```
./gradlew.bat :<module>:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```
JUnit 5 (new), JUnit 4 (instrumented), MockK, Turbine, Kotest assertions, Robolectric.
Fakes: `FakeLocationSource`, `FakeTrackerSettingsRepository`. `TestAppGraphBuilder` for DI.

---

## 11. Anti-Patterns (Reject)
- XML layouts, Fragments, `AndroidView`
- `GlobalScope`, unmanaged coroutines, new LiveData/SharedPreferences
- Unbounded DB queries, hard-coded test delays, duplicate theming
- KAPT, inline versions, static mutable singletons, throwing across modules

---

## 12. LLM Contract
1. Obey privacy, version catalog, Compose constraints.
2. Minimal diffs. Migrate legacy on contact.
3. Provide test fakes with new services.
4. Investigate root cause before fixes. Verify with build + tests.
5. Capture terminal output to files for analysis.

<!-- context-init:user-content-below -->

## QC Testing

To run QC on the app, use the `/android-qc` skill or say "run QC on the app".
The skill is defined in `.github/skills/android-qc/SKILL.md`.

## Handling reported problems

When I report something as a problem, investigate the root cause first. If it turns out the
new/current solution is actually better than the old behavior I'm comparing against, **keep the new
solution** and explain why it's superior — do not revert just to match my initial report.
