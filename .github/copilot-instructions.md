# Tracker Android – LLM / Copilot Evergreen Instructions

This file defines permanent, context-rich guidance for any LLM or code assistant acting within this repository. It replaces ad‑hoc prompts and avoids roadmap or transient TODOs. Treat everything here as normative unless a future commit deliberately revises it.

---

## 1. Project Essence
Privacy‑first, fully local location & activity tracker with analytics, map visualization, and optional gamification. No backend, no remote sync, no hidden telemetry. All suggestions must preserve this local‑only contract.

---

## 2. Architectural Overview
- Multi‑module Gradle; feature and concern separation (tracking, map, statistics, gamification, preferences, utilities, logging, import/export).
- Foreground/background Android services for continuous tracking (`TrackerService`, activity recognition companion).
- Room database as single persistence layer; modular DAOs; explicit migrations required.
- Jetpack Compose only for UI (legacy views removed when touched). Navigation via a single graph of composable routes.
- Shared utility and base modules (`sbase`, `sutils`) supply cross‑cutting primitives (theming, formatting, math, time, style).

Core modules (indicative, not exhaustive):
- `app`: Application wiring, DI/bootstrap, navigation graph composition.
- `tracker`: Tracking engine (location, WiFi, cell, steps, activity) + auto-tracking logic + locks.
- `map`: Map & heatmap rendering, overlays, spatial transforms.
- `statistics`: Session analytics, aggregates, chart adapters.
- `game`: Challenges, goals, points domain logic.
- `impexp`: Import/export pipelines (GPX, KML, JSON, DB copy) with streaming writers.
- `spreferences`: Strongly-typed preference accessors & migrations.
- `logger`: Structured logging façade (privacy-aware redaction utilities).
- `points`: Points/achievement persistence (if separate from game logic specifics).
- `sutils` / `sbase`: UI utilities, base abstractions, math/time helpers, style support.

---

## 3. Core Engineering Principles
ALWAYS: preserve privacy, maintain modular boundaries, prefer clarity over cleverness, write testable units, minimize battery & I/O overhead.
NEVER: introduce network sync, inline dependency versions, block main thread with I/O, expose raw coordinates or identifiers in logs for release builds.

North Star Posture: Treat every edit as an opportunity to move the codebase toward the target architecture (pure Compose, Material 3 Expressive, Flow/DataStore, fully modular, privacy-hardened). Do not perpetuate legacy patterns—migrate them in-place or isolate and schedule removal.

Legacy Handling Rule: When encountering legacy Views, Fragments, XML themes, LiveData, SharedPreferences, ad-hoc threading, or non‑Expressive theming, the default action is to refactor toward the north star unless explicitly constrained by an open migration blocker.

---

## 4. UI & Compose Standards
- Only Jetpack Compose; zero XML layouts, zero new or retained Fragments, and no `AndroidView` interop. Existing XML/Fragment artifacts must be deleted when their functionality is reimplemented.
- Route-based organization: `FeatureRoute()` entry composables; internal sub‑composables stateless where possible.
- State hoisting: UI functions accept state + callbacks; logic inside ViewModel / controller layers.
- Material 3 Expressive palette: use dynamic color (Android 12+) else derive an expressive scheme (Material Color Utilities) from a single stable seed. No ad-hoc color constants.
- Large data lists: `LazyColumn` / `LazyVerticalGrid` with stable keys; never allocate entire large datasets in memory for display.
- Avoid unnecessary recomposition: isolate heavy sections, use `derivedStateOf`, keep parameters stable.
- Single navigation graph; nested graphs only with explicit justification.
- Adaptive layouts: use current Compose adaptive layout APIs (window size classes & posture) for foldables/tablets; multi‑pane list/detail where valuable; avoid hard-coded dp breakpoints.
- Predictive back navigation: ensure navigation graph & top-level surfaces support predictive back (proper transitions, cancellable gesture safety).
- Provide & maintain a Baseline Profile for critical cold-start + first interactive flows (map open, stats list) to improve startup & scroll performance.
 - Do not implement UI fallbacks for pre-Compose paradigms; minimum supported devices receive Compose UI directly.

---

## 5. State & Concurrency
- Kotlin Flow only for reactive streams (north star). Replace LiveData when touched; do not add new LiveData wrappers.
- `StateFlow` for current state snapshots; `SharedFlow`/channels for one-off UI events.
- Structured concurrency only (`viewModelScope`, injected scopes). No `GlobalScope`.
- CPU-heavy polyline/heatmap or aggregation -> `Dispatchers.Default`; DB/file I/O -> `Dispatchers.IO`.
- Batch high-frequency sensor/location writes (size or time threshold) before DB persistence.
- Prefer immutable snapshot models to UI; restrict mutation to repositories/state holders.
 - When replacing legacy LiveData, consolidate transformation logic into a single Flow pipeline; remove intermediate observers.

---

## 6. Database & Data Access
- Room entities with indices on time, sessionId, lat, lon, foreign keys; annotate purpose of each index.
- Use explicit SQL in `@Query` for hot paths; document rationale above method.
- Avoid returning unbounded result sets—use paging/window queries.
- New tables: include `createdAt` (epoch millis) and optionally `lastModifiedAt`; enforce referential integrity with `ON DELETE CASCADE` where meaningful.
- Every schema change => migration + migration test verifying legacy data readability.
 - DataStore (proto strongly preferred) is the only accepted key-value mechanism. Migrate SharedPreferences entries opportunistically; do not add new keys there.

---

## 7. Performance & Memory
- Minimize allocation churn in tracking loops and rendering; reuse buffers or lightweight pooled objects judiciously.
- Down‑sample points for heatmaps / density layers based on zoom (adaptive LOD strategy).
- Cache frequently recomputed aggregates (distance, elevation deltas) keyed by session + version stamp.
- No blocking calls on the main thread; any exception demands documented justification.
- Maintain Baseline Profiles (macrobenchmark generated) and update when new critical composables/routes are introduced.
- Use Macrobenchmark & trace tooling to guard against >10% regressions in startup, scroll, or animation jank.
- Profile recomposition counts on key routes (tracking dashboard, map, stats) after significant UI refactors.
 - Baseline Profiles must be kept current; add coverage for newly introduced critical composables before merging feature branches.

---

## 8. Privacy & Security
- Local‑only: no remote endpoints, analytics, or silent transmissions.
- Explicit user action required for all exports; never schedule automatic background exports.
- Redact fine-grained coordinates in release logs; allow verbose detail only behind a debug flag.
- If encryption is introduced: encapsulate via `SecureStorage` + (preferred) SQLCipher; do not scatter cryptographic primitives.
- Re-check permissions gracefully after OS auto-reset (long idle periods) without interruptive dialogs.
- When precise location is revoked, downgrade to coarse gracefully and communicate reduced accuracy subtly.
 - Apply least-precision-first principle: request precise location only at the moment needed for high-accuracy tasks (e.g., detailed route capture) else operate in coarse mode.

---

## 9. Permissions & Onboarding
- Request permissions contextually, sequentially, and accompanied by rationale + optional “Skip”.
- Denial: degrade gracefully (disable feature toggle) and surface subtle, non-blocking reminders.
- No aggressive re‑prompt loops; respect user intent.
- Handle partial media permissions (READ_MEDIA_* variants) only if/when media features added; never request broad storage for narrow needs.
- Foreground service usage: declare only necessary types; prefer user‑initiated data transfer jobs or WorkManager for deferred/background tasks (Android 14+ guidance).
- Avoid USE_FULL_SCREEN_INTENT unless functionality aligns with permitted alarm/call categories; otherwise provide standard notification UX.
 - Migration note: remove any legacy broad storage or background location prompts replaced by scoped, contextual flows.

---

## 10. Theming & Styling
- Single `AppTheme` provider at the application composition root.
- Consolidate color/time-of-day logic inside theme layer; avoid scattering sunrise/sunset computations.
- Prefer dynamic color or deterministic seeded palette; avoid ad-hoc color constants.
- Encapsulate any expressive or advanced tonal palette generation centrally; do not compute palettes per screen.
 - All theme access via a single `AppTheme` composition local set; direct color constants or Material defaults without mediation are disallowed.

---

## 11. Import / Export
- Use streaming writers for large exports (GPX/KML/JSON) to avoid full in-memory materialization.
- Validate structure & fail fast with user-safe errors (no raw stack traces).
- Never auto-merge overlapping sessions without explicit user confirmation.
- Pre-validate file size & format signature before parsing large imports to prevent memory pressure.
 - Exports must be streaming and O(1) memory growth relative to dataset size.

---

## 12. Offline & Caching
- Any offline tile/data cache: explicit size quota + LRU eviction policy.
- Expose cache operations & progress as Flow; never block UI during enumeration or download.
- Support cancellation-aware incremental downloads so configuration changes (rotation/fold) do not restart from zero.
 - Provide deterministic cache keying strategy (versioned) so stale artifacts can be invalidated atomically on schema/format changes.

---

## 13. Optional Local Analytics (If Implemented)
- Aggregated counters only (feature toggles, session counts). No coordinates, timestamps, device fingerprints.
- Opt‑in toggle default OFF; immediate purge on opt‑out.
- Federated or on-device summarization allowed only if strictly local & explicitly opt-in (document scope & data fields).
 - Any analytics module must be hot-pluggable and removable without impacting core tracking.

---

## 14. Error Handling & Logging
- Wrap external or sensor interactions in sealed results (`Success`, `Recoverable`, `Error`).
- Do not silently swallow exceptions; log (debug) and propagate meaningful context without sensitive data.
- Provide user-facing fallbacks only where data integrity is maintained.
- Represent transient sensor degradation as structured state (e.g., `TrackingState.Degraded(reason)`) rather than repeated log spam.
 - Errors crossing module boundaries must surface as sealed results; never throw unchecked exceptions outward.
 - All public cross-module operation outcomes MUST be represented by a sealed type suffixed with `Result` (e.g., `ExportResult`, `TrackingStartResult`).
 - Forbid throwing across module boundaries (except truly unrecoverable programmer errors: IllegalState, AssertionError). Convert expected domain failures into result variants.

Domain Failure → User Surface (Guidance):
| Failure Category | Example Cause | User Surface | Notes |
|------------------|---------------|-------------|-------|
| PermissionDenied | Location permission revoked mid-session | Snackbar with action (Open Settings) | Auto-downgrade to coarse if possible |
| StorageFull | Export write failed (ENOSPC) | Dialog (non-blocking) + offer cleanup tips | Do not lose session data |
| GpxValidation | Malformed GPX import | Inline error in import screen | Provide sanitized message |
| EncryptionKeyMissing | (If implemented) key unavailable | Dialog requiring re-auth | Never log raw key refs |
| SensorDegraded | GPS accuracy poor | Subtle banner (degraded accuracy) | No toast spam |
| RateLimited | Batch insert backlog | Silent (metrics only) | Internal backpressure handling |

Logging Rules:
- Never include raw coordinates, WiFi SSIDs, or device identifiers in release logs.
- Use structured logging utility with redaction helpers for any potential PII.
- Tag each error with a stable short code (e.g., EXP-IO-01) when surfaced to aid support.

---

## 15. Code Style & Documentation
- Expressive, intention-revealing names; comments reserved for rationale, invariants, edge decisions.
- Functions ideally < ~60 lines; extract helpers to clarify responsibilities.
- Sealed hierarchies for UI and process state machines (tracking, export, permissions flows).
- KDoc for non-trivial public APIs; omit boilerplate restatements.
- Document threading/dispatcher expectations in complex services, repositories, and processors.
- Annotate performance-critical algorithm choices (e.g., tolerance values for polyline simplification) with rationale comments.
 - Provide minimal contract headers (Inputs / Outputs / Failure modes) on complex public functions before implementation.
 - Avoid speculative scaffolding: do not create unused placeholder classes, empty modules, or future feature stubs without an issue reference.
 - Interface vs Implementation Naming: interfaces use domain name (`TrackerRepository`), default implementation prefixed with `Default` (e.g., `DefaultTrackerRepository`) or suffixed with `Impl` only if multiple strategies coexist. Prefer `Default*` for clarity.
 - Factories that yield scoped objects follow `*Factory` (e.g., `SessionScopeFactory`, `HeatmapTileFactory`). Runtime parameterized creation happens via explicit factory interfaces—avoid passing mutable setters post-construction.
 - Coroutines dispatchers provider must expose exact fields: `io`, `default`, `main`, `unconfined`. Type: `CoroutineDispatcher`. Provide test implementation overriding with `StandardTestDispatcher`.
 - Avoid string concatenation for user-visible messages; use string resources with placeholders. Compose format via `stringResource(id, arg1)`.
 - All UI must function with dynamic font scaling up to 200%; test semantics not pixel locations.
 - Minimum contrast ratio target: 4.5:1 for normal text, 3:1 for large text (WCAG AA). Adjust theme tokens if dynamic palette violates threshold.

---

## 16. Dependency & Module Hygiene
- All dependencies & plugin versions via version catalog (`gradle/libs.versions.toml`). No inline version literals.
- Reuse internal utilities before introducing new third-party libs; when adding a new lib, include brief rationale comment.
- Keep public API surfaces tight; mark internals with `internal`.
- Remove superseded or dead code when encountered—avoid accumulating TODO clutter.
- Prefer KSP over KAPT where library supports it (Room, JSON serialization) to reduce build time.
- Maintain Gradle configuration cache compatibility; avoid unsupported global state in plugin/application blocks.
- Adopt Kotlin K2 compiler configuration promptly; resolve warnings instead of suppressing broadly.
 - Disallow new KAPT usage; mandate KSP-supported alternatives or custom code generation.
 - All new third-party libraries must justify necessity versus existing internal utilities.

---

## 16A. Dependency Injection & Composition Root
North Star: Explicit, framework-light constructor injection with a single composition root. Avoid hidden global singletons or service locators.

Principles:
- Prefer pure Kotlin constructor injection; pass dependencies explicitly. Only introduce a DI framework (e.g., Hilt / Koin / Anvil) if boilerplate becomes a measurable drag; justify in PR if added.
- Single composition root lives in `app` module (e.g., `AppGraph` or `AppContainer`) building and wiring all module-level services.
- No static singletons (object) holding mutable state unless clearly immutable or value-only. Use lazy providers/factories for heavy resources.
- Separate interface (public API) from implementation (internal) across module boundaries to simplify test double injection.
- Service lifecycle scopes:
	- Application scope: long‑lived, stateless or internally synchronized services (repositories, formatters, time providers).
	- Foreground tracking scope: created when tracking starts; provides components needing live sensor streams; disposed when tracking stops.
	- ViewModel scope: UI logic only; never own lower-level resources (DB, sensors). Acquire via injected factory.

Construction Patterns:
- Provide stable time & dispatcher abstractions (`Clock`, `CoroutineDispatchers`) for deterministic tests.
- Prefer explicit factory functions for components requiring runtime parameters (sessionId, geo bounds) rather than injecting mutable setters.
- Inject DAOs or repository abstractions instead of the Room database itself when possible.

Testing Guidance:
- For tests, supply lightweight test graph (no reflection) that swaps concrete implementations with fakes/memory variants (e.g., in-memory Room DB, fake location source emitting controlled flows).
- Expose helper builders: `TestAppGraphBuilder` returning a configured graph for each test; avoid manual rewiring scattered across test classes.
- Provide interface-based boundaries for sensors (`LocationProvider`, `WifiScanner`, `ActivityEventsSource`) to allow deterministic replay of synthetic events.

Do / Avoid:
- DO: `class TrackerRepository(private val locationDao: LocationDao, private val clock: Clock)`.
- AVOID: static `TrackerRepository.instance` or fetching dependencies via a global registry.
- DO: pass `DispatchersProvider` instead of referencing `Dispatchers.IO` directly inside repositories.
- AVOID: injecting Android `Context` unless strictly required (I/O open, system service); extract the minimal capability (e.g., `FileResolver`, `NotificationSender`).

LLM Generation Rules (DI):
- When adding a new service, generate an interface + implementation + registration snippet in `AppGraph`.
- When modifying a constructor signature, propagate changes through the graph rather than adding secondary setters.
- Offer a test fake simultaneously for any non-trivial external interaction (sensors, time, randomness).
 - Enforce naming: interface `LocationProvider`, default impl `DefaultLocationProvider`; factories `LocationProviderFactory` where runtime parameters (sessionId, bounds) are required.
 - No service locator fallback patterns (no static registry lookups). If a dependency cannot be obtained via constructor chain, revise graph wiring.
 - Session-scoped objects created through a dedicated `SessionScopeFactory` (or similarly named) that returns a structured scope object managing lifecycle + cancellation.

Example Minimal Graph Sketch (illustrative only, not to replicate verbatim):
// In app module
class AppGraph(
	val database: AppDatabase,
	val dispatchers: DispatchersProvider,
	val clock: Clock,
	val locationRepo: LocationRepository,
	val trackingController: TrackingController,
	val exportManager: ExportManager,
)

fun buildAppGraph(context: Context): AppGraph { /* construct & wire */ }

Fakes Example Prompt:
// Copilot: Create FakeLocationProvider implementing LocationProvider; emits supplied list on start then completes.

Migration Directive: On encountering legacy static singletons, rewrite them as constructor-injected services registered in the graph; delete global access points.

Catalog usage example:
```toml
[versions]
compose = "2024.06.00"

[libraries]
compose-foundation-layout = { module = "androidx.compose.foundation:foundation-layout", version.ref = "compose" }
```
```kotlin
dependencies {
	implementation(libs.compose.foundation.layout)
}
```

---

## 17. Testing & Quality Strategy
- Each feature: 1 happy-path unit test + ≥1 edge case (empty / large / permission denied) + integration test if DB/service.
- Compose UI tests assert semantics (contentDescription, text) rather than structural implementation.
- Tracking pipeline tests: start → feed synthetic events → stop → assert session + sample counts.
- Mandatory migration test per DB schema change.
- Avoid fixed delays; use test dispatchers or injected clocks.
- Add Macrobenchmark tests (startup, map scroll, stats list scroll) & treat >10% regression as release blocker.
- Track Compose recomposition metrics for main surfaces after structural UI changes.
 - Baseline profile generation + macrobenchmark results must be part of CI gating for performance-sensitive modules.
 - Test Classification:
	 - Unit: pure Kotlin / small scope, <500ms, no Android framework.
	 - Integration: DB/file/Room/DataStore interactions; may use in-memory DB.
	 - Instrumentation: device/emulator; UI or sensor integration.
	 - Macrobenchmark: performance metrics (startup, scroll, transitions).
	 - Property-based (optional): generative tests for geometry/aggregation correctness.
 - Annotations / Tags (detekt/gradle filters): `@UnitTest`, `@IntegrationTest`, `@InstrumentationTest`, `@BenchmarkTest`, `@PropertyTest`, `@Slow` (execution >2s), `@Flaky` (temporary quarantine).
 - Flake Policy: CI auto-reruns a failed test up to 2 times. Persistent failure after retries marks test as `@Flaky` and opens an issue. Flaky tests must be resolved or removed before release freeze.
 - Benchmark Acceptance: PRs changing hot paths must include updated benchmark delta summary; reject if >10% regression unless explicitly waived with justification.

Quality gates (every PR): build success (all modules), detekt + lint clean (or justified suppression), tests green, no new inline versions, no privacy regressions.

---

## 18. Feature Flags
- Centralize flags in a single object/service with typed accessors + documented expiry or evaluation criteria.
- No scattering raw booleans or `BuildConfig` checks across modules.
- Storage schema impacting flags must specify migration & rollback behavior.
 - Feature flags are temporary; include an explicit expiration criterion or removal date.

---

## 19. Anti‑Patterns (Reject / Refactor)
- New XML layouts or Fragments for production UI.
- GlobalScope or unmanaged coroutines.
- Blocking main thread I/O.
- Unbounded DB query results feeding UI directly.
- Hard-coded sleeps in tests.
- Excessive LiveData creation in new code (use Flow).
- Raw stack traces or sensitive data in user-exported content.
- Adding new SharedPreferences keys for structured settings instead of DataStore.
- Overusing `mutableStateOf` to hold large domain objects causing broad recompositions; slice state.
- Reflection-based dynamic dependency resolution when direct references suffice.
 - View / Fragment inflation or retention.
 - Introduction of new LiveData-based reactive chains.
 - Adding Compose interop wrappers (`AndroidView`) instead of migrating underlying feature.
 - Maintaining duplicate theming systems in parallel.

---

## 20. Copilot / LLM Response Contract
When generating suggestions:
1. Obey privacy, dependency, and Compose constraints above.
2. Use version catalog aliases for dependencies (no raw coordinates).
3. Provide concise rationale comments only where non-obvious.
4. Avoid speculative future feature stubs unless user explicitly requests ideation.
5. Highlight privacy or performance implications if code touches tracking, storage, or exports.
6. Prefer incremental, minimal diffs; do not refactor unrelated code opportunistically.
7. If ambiguity exists, state assumptions briefly before proposing code.
8. Integrate 2025 best practices automatically (adaptive layouts, predictive back, baseline profiles hooks) when relevant.
9. Prefer DataStore for new key-value persistence and outline migration when modifying existing SharedPreferences-backed features.
10. When encountering legacy constructs (XML layout, Fragment, LiveData, SharedPreferences), propose or apply a migration path instead of extending legacy code.
11. Do not generate interoperability shims; generate target-state replacements.
12. Prefer constructor injection and update the composition root when introducing new dependencies; never hide wiring inside static holders.
13. Provide test fakes/mocks/interfaces alongside new external-facing services (sensors, time, storage, export) in the same change.

---

## 21. Prompt Patterns (Inline Comments Examples)
Use focused, outcome-oriented inline comments to guide generation:
// Copilot: Add batched DAO insert (50 items flush) emitting Flow progress.
// Copilot: Sealed TrackingState (Idle, Preparing, Active(sessionId), Error(msg)).
// Copilot: Room migration v12→v13 add index on time; include migration test.
// Copilot: Streaming GPX exporter (writer-based) with memory O(1) growth.

---

## 22. Example Implementation Micro-Contracts
When adding a function/class, define its contract succinctly (inputs, outputs, error modes) before code. Example:
// Contract:
// Input: List<LocationSample> (unordered)
// Output: AggregatedDistance (meters, elevationGain)
// Errors: Returns Result.failure if list < 2 or time not monotonic.

---

## 23. Logging Conventions
- Use structured logging helpers; avoid ad-hoc string concatenation for hot paths.
- Tag sensitive fields with redaction utilities; never log user-identifiable WiFi SSIDs in release variant.

---

## 24. Extensibility Guidelines
- New tracking component template: interface (enable/disable, isActive, Flow<Event>), lifecycle bound to service scope, explicit resource cleanup.
- New export format: streaming writer + deterministic ordering + version header.

---

## 25. Security Considerations Quick List
- No dynamic code loading / reflection-based plugin discovery.
- Validate all external file inputs (size bounds, structural sanity, encoding) before processing.
- Prefer immutable data classes for domain events to avoid shared mutable state races.

---

## 26. Performance Profiling Hooks (Optional)
- Provide opt-in lightweight counters (sessions started, points awarded) behind debug flag only.
- Use sampling or aggregation to avoid high-frequency logging overhead.

---

## 27. Commit Message Conventions
- Use concise, imperative-mood subject lines only (e.g., "Fix heatmap tile caching", "Add battery optimization toggle").
- No extended description body unless absolutely necessary for complex architectural decisions.
- Keep commit titles under 72 characters; focus on what changed and why in minimal words.
- Examples: "Migrate TrackerViewModel to Flow", "Remove legacy XML layouts from map module", "Fix NPE in session aggregation".

---

End of evergreen instructions.
