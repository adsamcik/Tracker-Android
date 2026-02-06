# Tracker Android – LLM Instructions

## 1. Project Essence
Privacy-first, fully local location & activity tracker for Android. No backend, no remote sync, no telemetry. **All data stays on-device. Never introduce network calls, analytics, or remote endpoints.**

---

## 2. Architecture
Multi-module Gradle project (14 modules). See `docs/ARCHITECTURE_OVERVIEW.md` for full component maps.

| Module | Purpose |
|--------|---------|
| `app` | Entry point, DI (`AppGraph`), navigation, settings, onboarding |
| `tracker` | Core tracking: `TrackerService`, component pipeline, producers |
| `map` | Map visualization, heatmaps, layers |
| `statistics` | Session list, detail views, analytics |
| `game` | Challenges, goals, gamification |
| `activity` | Activity recognition (Google Play Services) |
| `impexp` | Import/export (GPX, KML, JSON, SQLite), streaming writers |
| `sbase` | Room database, entities, DAOs |
| `sutils` | Shared utilities, formatters, `AppTheme` |
| `smap` | Shared map utilities |
| `spreferences` | Typed preferences |
| `logger` | Structured logging with privacy-aware redaction |
| `points` | Points calculation |
| `testing-common` | Test utilities, fakes |

Data flow: `Sensors -> Producers -> TempData -> Post-Components -> Room Database`

---

## 3. North Star & Hard Rules

**Target architecture:** Pure Compose, Material 3 Expressive, Flow/DataStore, fully modular, privacy-hardened. Every edit should move toward this target. Do not perpetuate legacy patterns.

**ALWAYS:** Preserve privacy. Maintain modular boundaries. Write testable code. Minimize battery & I/O overhead.

**NEVER:** Introduce network sync. Inline dependency versions. Block main thread with I/O. Expose coordinates or identifiers in release logs. Use `GlobalScope`. Throw exceptions across module boundaries.

**Legacy handling:** When encountering Views, Fragments, XML themes, LiveData, SharedPreferences, or ad-hoc threading, refactor toward the north star unless explicitly constrained.

---

## 4. UI & Compose
- **Compose-only.** Zero XML layouts, zero Fragments, no `AndroidView` interop. Delete legacy artifacts when reimplemented.
- Route-based organization: `FeatureRoute()` entry composables; stateless sub-composables where possible.
- State hoisting: UI functions accept state + callbacks; logic in ViewModel/controller layers.
- Material 3 Expressive: dynamic color (Android 12+) else expressive scheme from stable seed. No ad-hoc color constants.
- Single `AppTheme` provider at composition root (see `sutils/.../AppTheme.kt`). All theme access via `AppTheme` composition local.
- `LazyColumn`/`LazyVerticalGrid` with stable keys for lists. No unbounded in-memory datasets.
- Single navigation graph. Adaptive layouts via window size classes. Predictive back support.
- Maintain Baseline Profiles for critical flows (startup, map, stats).
- Touch targets >= 48dp. WCAG AA contrast (4.5:1 normal, 3:1 large). Support 200% font scaling.

---

## 5. State & Concurrency
- **Flow-only.** No new LiveData. Replace LiveData when touching existing code.
- `StateFlow` for state snapshots; `SharedFlow`/channels for one-off events.
- Structured concurrency only (`viewModelScope`, injected scopes).
- CPU-heavy work -> `Dispatchers.Default`; DB/file I/O -> `Dispatchers.IO`. Inject `DispatchersProvider` instead of referencing dispatchers directly.
- Batch high-frequency sensor writes before DB persistence.
- Immutable snapshot models for UI; mutation restricted to repositories/state holders.

---

## 6. Data Layer
- Room as single persistence layer. Entities indexed on time, sessionId, lat, lon.
- Explicit SQL in `@Query` for hot paths with rationale comment above method.
- Paging/window queries only; no unbounded result sets.
- New tables must include `createdAt` (epoch millis). Enforce referential integrity with `ON DELETE CASCADE`.
- **Every schema change requires an explicit migration + migration test.**
- **DataStore (proto preferred) for all new key-value storage.** No new SharedPreferences keys.

---

## 7. Privacy & Security
- Local-only. No remote endpoints, analytics, or silent transmissions.
- Exports require explicit user action; never auto-export in background.
- Redact coordinates, WiFi SSIDs, and device identifiers in release logs. Verbose detail only behind debug flag.
- Least-precision-first: request precise location only for high-accuracy tasks; otherwise coarse mode.
- Permission requests: contextual, sequential, with rationale + "Skip". No aggressive re-prompt loops.
- Graceful degradation on denial (disable feature, subtle non-blocking reminder).
- No dynamic code loading or reflection-based plugin discovery.
- Validate all external file inputs (size, structure, encoding) before processing.

---

## 8. Performance
- Minimize allocation churn in tracking loops and rendering.
- Down-sample heatmap/density points based on zoom level.
- Cache frequently recomputed aggregates keyed by session + version stamp.
- Maintain Baseline Profiles; update when adding critical composables.
- Treat >10% regression in startup, scroll, or animation jank as release blocker.
- Offline tile caches: explicit size quota + LRU eviction.

---

## 9. Error Handling
- Cross-module operations return sealed `Result` types (suffix with `Result`, e.g., `ExportResult`, `TrackingStartResult`).
- Never throw across module boundaries. Convert domain failures into result variants.
- Transient sensor degradation -> structured state (e.g., `TrackingState.Degraded(reason)`), not log spam.
- Tag errors with stable short codes (e.g., `EXP-IO-01`) when surfaced to users.
- User-facing surfaces: snackbar for recoverable, dialog for blocking, inline for validation. Never raw stack traces.

| Failure | User Surface |
|---------|-------------|
| Permission revoked | Snackbar + Open Settings action; auto-downgrade to coarse |
| Storage full | Non-blocking dialog + cleanup tips |
| Malformed import | Inline error with sanitized message |
| GPS degraded | Subtle accuracy banner |

---

## 10. Code Conventions
- **Naming:** Interfaces use domain name (`TrackerRepository`). Default impl prefixed `Default` (e.g., `DefaultTrackerRepository`). Factories use `*Factory` suffix.
- **DispatchersProvider** must expose: `io`, `default`, `main`, `unconfined` (type: `CoroutineDispatcher`). Test impl uses `StandardTestDispatcher`.
- Sealed hierarchies for state machines (tracking, export, permissions).
- String resources with placeholders for user-visible text; `stringResource(id, arg)` in Compose.
- Contract headers (Inputs/Outputs/Failure modes) on complex public functions.
- No speculative scaffolding: no unused placeholders, empty modules, or future stubs without an issue reference.
- Commit messages: imperative mood, under 72 chars, no body unless architecturally complex. Examples: "Fix heatmap tile caching", "Migrate TrackerViewModel to Flow".

---

## 11. Dependencies & DI
- **All versions via `gradle/libs.versions.toml`.** No inline version literals.
- **KSP only.** No new KAPT usage.
- Justify new third-party libraries vs. existing internal utilities.
- Mark internals with `internal`. Remove dead code when encountered.
- Gradle configuration cache compatible. K2 compiler warnings resolved, not suppressed.

**Dependency injection:**
- Constructor injection via `AppGraph` composition root in `app` module (see `app/.../AppGraph.kt`).
- No static singletons with mutable state. No service locator patterns.
- Separate interface (public) from implementation (`internal`) across module boundaries.
- Inject DAOs or repository abstractions, not Room database directly.
- Avoid injecting `Context`; extract minimal capability (`FileResolver`, `NotificationSender`).
- When adding a service: create interface + `Default*` impl + register in `AppGraph` + provide test fake.
- Propagate constructor changes through the graph; never add secondary setters.

**Lifecycle scopes:**
- Application scope: long-lived stateless services (repositories, formatters).
- Tracking scope: created on tracking start, disposed on stop (sensor streams).
- ViewModel scope: UI logic only; never own DB or sensor resources.

---

## 12. Testing

**Commands (always capture output to file):**
```
./gradlew.bat :<module>:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
./gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```

**Reports:** `<module>/build/reports/tests/testDebugUnitTest/index.html`, `<module>/build/test-results/testDebugUnitTest/*.xml`

**Requirements:**
- Each feature: 1 happy-path + >=1 edge case (empty, large, permission denied) + integration test if DB/service.
- Compose tests assert semantics (contentDescription, text), not structure.
- Tracking pipeline tests: start -> feed synthetic events -> stop -> assert counts.
- Migration test per schema change.
- Use `runTest`, `TestDispatcher`, `FixedClock`. No fixed delays.
- Provide test fakes alongside new external-facing services (sensors, time, export).
- Test graph via `TestAppGraphBuilder`; in-memory Room DB; fake location/wifi sources.

**Quality gates (every PR):** Build all modules, detekt + lint clean, tests green, no inline versions, no privacy regressions.

---

## 13. Product Decisions
- **One clear default** over many toggles. Progressive disclosure for advanced options.
- **Plain language** in UX copy: "Start tracking" not "Initialize TrackerService".
- Destructive actions (delete session, reset) require confirmation dialog. Reversible actions do not.
- Default optional features to OFF unless they enhance core tracking or privacy.
- When uncertain, choose the simpler, safer, reversible option.
- Privacy overrides convenience when in tension.

---

## 14. Anti-Patterns (Reject)
- XML layouts, Fragments, `AndroidView` interop
- `GlobalScope`, unmanaged coroutines
- New LiveData or SharedPreferences
- Unbounded DB queries feeding UI
- Hard-coded test delays
- Duplicate theming systems
- `mutableStateOf` holding large domain objects (slice state instead)
- Reflection-based dependency resolution
- Interop shims instead of target-state replacements
- Raw stack traces or PII in user-exported content or release logs

---

## 15. LLM Response Contract
1. Obey privacy, dependency, version catalog, and Compose constraints above.
2. Prefer incremental, minimal diffs. Do not refactor unrelated code.
3. When encountering legacy constructs, migrate toward north star rather than extending them.
4. Provide test fakes alongside new external-facing services.
5. Investigate root cause before proposing fixes. Verify fixes with build + tests.
6. Capture terminal output to files for analysis; do not filter or truncate command output.
7. Highlight privacy or performance implications when touching tracking, storage, or exports.
8. State assumptions briefly before proposing code when ambiguity exists.
9. For multi-step tasks, plan visibly before implementing.
