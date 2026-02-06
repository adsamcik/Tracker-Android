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

## 20. Systematic Problem-Solving & Root Cause Analysis

**Philosophy:** Never rush to a fix. The LLM MUST slow down, gather evidence systematically, and identify the TRUE root cause before proposing solutions. Surface-level symptom patching leads to regression, technical debt, and frustrated users.

**CRITICAL: Complete Investigation Before Conclusions.** The natural tendency is to stop investigating once something "looks like the problem." This is WRONG. Many issues arise from:
- **Multiple contributing factors** where no single factor causes the issue alone, but together they create a "perfect storm"
- **Cascading failures** where fixing one symptom reveals (or ignores) deeper issues
- **Coincidental correlations** where the first thing found is related but not causal
- **Hidden dependencies** where the obvious problem masks the real constraint

**The LLM MUST complete the ENTIRE investigation scope before forming conclusions.** Finding something suspicious is NOT permission to stop looking. Document the finding, then CONTINUE investigating other areas. Only after examining ALL relevant aspects should conclusions be drawn.

### 20.1. Mandatory Todo-Driven Workflow

For ANY non-trivial task (debugging, feature implementation, refactoring, investigation), the LLM MUST use the `manage_todo_list` tool to:

1. **Plan before acting:** Write out the investigation/implementation plan as discrete, actionable todos before writing any code or making changes.
2. **Track progress visibly:** Mark todos in-progress (one at a time) and completed as work proceeds.
3. **Adapt the plan:** Update the todo list when new information emerges or scope changes.
4. **NEVER short-circuit:** Complete ALL planned investigation todos even if an early finding "looks like the answer."

**When to use todos (MANDATORY):**
- Any task with ≥3 logical steps
- Debugging any error (compile, runtime, test failure)
- Implementing features touching multiple files/modules
- Investigating unexpected behavior
- Refactoring or migration work
- Answering questions requiring research across multiple files

**Todo structure requirements:**
- Each todo has a clear, action-oriented title (3-7 words)
- Description includes specific files, methods, or concepts to examine
- Order reflects logical dependency (what must be understood before proceeding)
- Include explicit "Verify fix" or "Validate hypothesis" todos—never skip verification
- **Include a "Synthesize findings" todo AFTER all investigation todos** - this is where conclusions are formed, NOT during individual investigation steps

**Example todo list for a build error:**
```
1. [in-progress] Gather full error context - Read complete error message, identify ALL errors not just first
2. [not-started] Map error locations - List every file/line mentioned, note patterns
3. [not-started] Examine each error site - Read surrounding code for each location
4. [not-started] Check recent changes - Review what changed that could affect these areas
5. [not-started] Trace dependencies - Understand how affected components connect
6. [not-started] Synthesize findings - NOW form hypothesis based on ALL collected evidence
7. [not-started] Validate hypothesis - Check if hypothesis explains ALL symptoms (not just some)
8. [not-started] Implement minimal fix - Change only what's necessary
9. [not-started] Validate fix completely - Build AND verify ALL original errors resolved
10. [not-started] Check for regressions - Ensure fix doesn't break related functionality
```

### 20.2. The "Complete Picture First" Rule

**MANDATORY: Do not form conclusions until investigation is complete.**

**The Evidence Collection Phase:**
During investigation, the LLM collects observations WITHOUT drawing conclusions. Each finding is noted as a data point, not as "the answer." Use neutral language:
- ✅ "Observation: Variable X is not imported in file Y"
- ✅ "Observation: Class A was modified 3 days ago"
- ✅ "Observation: There are 5 errors, 3 in module X, 2 in module Y"
- ❌ "Found the problem: Variable X is missing" (premature conclusion)
- ❌ "This is the issue: Class A change broke it" (premature conclusion)

**The Synthesis Phase:**
Only AFTER collecting all observations does the LLM:
1. Review ALL findings together
2. Look for patterns across findings
3. Identify which findings might be connected
4. Consider if multiple factors contribute
5. Form a hypothesis that explains ALL observations (not just some)
6. Explicitly note any observations the hypothesis does NOT explain

**Multi-Factor Analysis Template:**
When synthesizing, explicitly answer:
- "How many distinct issues did I find?" (often more than one)
- "Could these issues be independent problems requiring separate fixes?"
- "Could these issues be symptoms of a single deeper cause?"
- "Is there a scenario where all these factors interact to cause the failure?"
- "What would happen if I only fixed one of these—would the problem fully resolve?"

### 20.3. Root Cause Analysis Framework

**The 5 Whys Rule:** Before proposing any fix, ask "Why?" at least 3-5 times to drill past symptoms to root cause.

**Example:**
- Error: "Unresolved reference: trackingState"
- Why? → The variable isn't in scope
- Why? → It was removed in a recent refactor
- Why? → The refactor moved state to a different class
- Why? → The migration was incomplete—usages weren't updated
- Root cause: Incomplete migration, not a missing variable

**Symptom vs. Cause Distinction:**
| Type | Example | Action |
|------|---------|--------|
| Symptom | "Unresolved reference" error | Investigate, don't patch |
| Proximate cause | Variable not imported | Ask why it's missing |
| Root cause | Incomplete refactor / wrong abstraction | Fix the underlying issue |

**Multi-Factor Problem Recognition:**
Some problems have no single root cause. Recognize these patterns:
| Pattern | Description | Example |
|---------|-------------|---------|
| Perfect Storm | Multiple factors that are fine individually but fail together | Race condition only triggers when network is slow AND battery is low AND user navigates quickly |
| Layered Failures | Each layer compensated for issues until one too many | Missing null check + unexpected API response + aggressive timeout = crash |
| Coincidental Timing | Unrelated issues that appear together | Refactor broke module A; separately, dependency update broke module B; both fail simultaneously |
| Hidden Coupling | Components assumed independent are actually connected | Changing sort order breaks pagination because both relied on implicit ordering assumption |

**Investigation Before Action:**
1. **Collect ALL error messages** - Don't stop at the first error; related errors often reveal patterns
2. **Identify the error location vs. the cause location** - Where it manifests ≠ where it originates
3. **Check recent changes** - Use `get_changed_files`, grep for recent modifications in relevant areas
4. **Form multiple hypotheses** - Never lock onto the first explanation
5. **Rank hypotheses by likelihood** - Test most likely first, but keep alternatives ready
6. **Prove causation, not correlation** - The fix should logically explain why the problem occurred
7. **Consider multi-factor scenarios** - Ask: "Could this require fixing multiple things?"

### 20.4. Debugging Methodology

**For Compile Errors:**
1. Read the FULL error output, not just the first line
2. Identify the PRIMARY error (often first in a cascade)
3. Trace imports, dependencies, and type hierarchies
4. Check if the error is in generated code (Dagger/Hilt, Room, etc.) pointing to source issues
5. Verify the fix resolves ALL related errors, not just the surface one

**For Runtime Errors:**
1. Reproduce the exact conditions (inputs, state, timing)
2. Trace the call stack from crash point backward
3. Identify what state was unexpected and why
4. Check for race conditions, lifecycle issues, or null propagation
5. Use `adb logcat` to read device logs when debugging on connected devices (filter by app package or tag for relevance)

**For Test Failures:**
1. Read the assertion message AND the test setup
2. Understand what the test expected vs. what it got
3. Determine if the test is correct or if behavior intentionally changed
4. Check for flakiness indicators (timing, ordering, shared state)

**For "It used to work" problems:**
1. Identify WHEN it stopped working (bisection)
2. List changes between working and broken states
3. Isolate the specific change that introduced the break
4. Understand WHY that change broke it (not just THAT it broke)

### 20.5. Investigation Workflow

**Step 1: Observation (NEVER SKIP)**
- Collect complete context: error messages, stack traces, relevant code
- Use `read_file` liberally to understand surrounding context
- Use `grep_search` to find related usages and patterns
- Use `semantic_search` when unsure where to look

**Step 2: Hypothesis Formation**
- State the hypothesis explicitly: "I believe X is happening because Y"
- Identify what evidence would CONFIRM the hypothesis
- Identify what evidence would REFUTE it
- Consider at least 2 alternative hypotheses

**Step 3: Hypothesis Testing**
- Gather evidence systematically (not just confirming evidence)
- If evidence contradicts hypothesis, REVISE IT—don't ignore contradictions
- Use targeted reads/searches to prove or disprove

**Step 4: Solution Design**
- Fix should address ROOT CAUSE, not symptoms
- Consider side effects: what else does this change affect?
- Prefer minimal, surgical changes over broad rewrites
- Plan verification steps before implementing

**Step 5: Implementation**
- Make changes incrementally
- Verify each change step (build, test, behavior check)
- If unexpected issues arise, RETURN TO STEP 1—new observation needed

**Step 6: Verification**
- Confirm the original problem is resolved
- Check for regressions in related functionality
- Validate that the fix makes logical sense (not just "it compiles now")

### 20.6. Anti-Patterns in Problem-Solving (REJECT)

- **Shotgun debugging:** Making random changes hoping something works
- **Copy-paste fixes:** Applying fixes from similar-looking errors without understanding
- **Symptom suppression:** Adding null checks, try-catch, or @Suppress without understanding why
- **Tunnel vision:** Fixating on one hypothesis despite contradicting evidence
- **Incomplete investigation:** Stopping at "it compiles" without understanding why it failed
- **Skipping verification:** Assuming the fix works without testing
- **Ignoring cascading errors:** Fixing one error without checking if others are related
- **Premature optimization:** Refactoring while debugging (separate concerns)
- **Premature conclusion:** Declaring "found the problem" after finding ONE suspicious thing without completing investigation
- **Confirmation bias:** Only looking for evidence that supports the first hypothesis
- **Single-cause assumption:** Assuming every problem has exactly one cause (many have multiple contributing factors)
- **Satisfaction trap:** Feeling done when something "looks right" instead of verifying comprehensively

### 20.7. When Stuck: Escalation Protocol

If after 2-3 investigation cycles the root cause remains unclear:
1. **Summarize findings** - What has been ruled out? What remains uncertain?
2. **State blockers explicitly** - What information would unblock progress?
3. **Propose exploratory actions** - Add logging, create minimal repro, isolate components
4. **Ask clarifying questions** - Request user input on context, history, or constraints

---

## 21. Copilot / LLM Response Contract
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
14. **ALWAYS use `manage_todo_list` for multi-step tasks** - Plan visibly, track progress, adapt as needed.
15. **NEVER propose fixes without first understanding root cause** - Investigate before acting, document reasoning.
16. **Verify before declaring done** - Build, test, and validate that the fix addresses the actual problem.
17. **NEVER filter or truncate terminal command output** - Do not use `Select-Object`, `head`, `tail`, `grep`, or similar filtering mechanisms on command output. Instead, redirect full output to a temporary file (e.g., `command > temp_output.txt`), read and analyze the complete file, then delete it when done. This ensures no critical information is lost to truncation.
18. **Prioritize long-term health over quick fixes** - Every implementation and fix should improve or maintain the codebase's long-term maintainability, readability, and architectural integrity. Avoid band-aid solutions that create technical debt; prefer solutions that address root causes, follow established patterns, and leave the code better than found. When trade-offs exist between speed and sustainability, favor sustainability unless explicitly time-constrained by the user.
19. **ALWAYS activate specialized skills (Section 32) for matching tasks** - Read the skill's SKILL.md file before performing task types that match skill triggers. For ALL coding tasks, activate the **swe** skill. For code reviews, activate **code-review**. For test generation, activate **test-gen**. For security-sensitive work, activate **security-audit**. Skills contain domain-specific methodologies that dramatically improve output quality.

---

## 22. Prompt Patterns (Inline Comments Examples)
Use focused, outcome-oriented inline comments to guide generation:
// Copilot: Add batched DAO insert (50 items flush) emitting Flow progress.
// Copilot: Sealed TrackingState (Idle, Preparing, Active(sessionId), Error(msg)).
// Copilot: Room migration v12→v13 add index on time; include migration test.
// Copilot: Streaming GPX exporter (writer-based) with memory O(1) growth.

---

## 23. Example Implementation Micro-Contracts
When adding a function/class, define its contract succinctly (inputs, outputs, error modes) before code. Example:
// Contract:
// Input: List<LocationSample> (unordered)
// Output: AggregatedDistance (meters, elevationGain)
// Errors: Returns Result.failure if list < 2 or time not monotonic.

---

## 24. Logging Conventions
- Use structured logging helpers; avoid ad-hoc string concatenation for hot paths.
- Tag sensitive fields with redaction utilities; never log user-identifiable WiFi SSIDs in release variant.

---

## 25. Extensibility Guidelines
- New tracking component template: interface (enable/disable, isActive, Flow<Event>), lifecycle bound to service scope, explicit resource cleanup.
- New export format: streaming writer + deterministic ordering + version header.

---

## 26. Security Considerations Quick List
- No dynamic code loading / reflection-based plugin discovery.
- Validate all external file inputs (size bounds, structural sanity, encoding) before processing.
- Prefer immutable data classes for domain events to avoid shared mutable state races.

---

## 27. Performance Profiling Hooks (Optional)
- Provide opt-in lightweight counters (sessions started, points awarded) behind debug flag only.
- Use sampling or aggregation to avoid high-frequency logging overhead.

---

## 28. Commit Message Conventions
- Use concise, imperative-mood subject lines only (e.g., "Fix heatmap tile caching", "Add battery optimization toggle").
- No extended description body unless absolutely necessary for complex architectural decisions.
- Keep commit titles under 72 characters; focus on what changed and why in minimal words.
- Examples: "Migrate TrackerViewModel to Flow", "Remove legacy XML layouts from map module", "Fix NPE in session aggregation".

---

## 29. Product Philosophy & Decision Framework
Inspired by opinionated simplicity and end-to-end integration principles, tailored for privacy-first local tracking.

### Core Tenets
- **Opinionated simplicity:** One clear, optimized default over many toggles. Remove non-essential choices; defer advanced controls.
- **Consistency:** Reuse established patterns; keep interactions predictable across tracking, map, stats, and gamification.
- **Privacy as the default feature:** On-device processing is mandatory, not optional. Minimize data collection; require explicit, comprehensible permissions only when necessary.
- **Reliability over novelty:** Fewer paths, highly polished. Gradual feature evolution; no experimental flags exposed to users.
- **Progressive disclosure:** Hide complexity by default; surface expert options contextually (e.g., advanced tracking settings behind explicit "Advanced" section, not scattered).
- **Battery & performance as quality gates:** Treat startup time, responsiveness, and power efficiency as first-class constraints—optimize basics before adding features.

### Behavioral Rules for Suggestions
1. **Recommend one best path first** with a one-sentence rationale; list alternatives only if constraints or edge cases demand it.
2. **Hide complexity by default:** Surface expert/debug options via explicit user intent (e.g., "Developer options" toggle, advanced mode) or contextually when capability is detected (e.g., external sensors available).
3. **Favor on-device:** All storage, computation, and analytics must remain local. If proposing any future cloud-adjacent feature (e.g., backup), state what data, why, retention policy, and opt-in flow in plain language—and justify necessity.
4. **Design for continuity:** Ensure tracking sessions, preferences, and gamification state persist seamlessly across app restarts, device reboots, and configuration changes without user intervention.
5. **Optimize the basics:** Prioritize startup time, map responsiveness, battery efficiency, and accessibility (TalkBack, contrast, touch-target sizes ≥48dp) over secondary features.
6. **Use plain language:** Frame UX copy around human tasks ("Start tracking", "View routes") instead of system internals ("Initialize TrackerService", "Query LocationDao").

### Output Requirements for Product Decisions
When proposing UI flows, settings, defaults, or copy:
- Provide a concise **recommendation** with **one default to ship** and brief justification.
- List **fallbacks** only if platform constraints or user edge cases apply (e.g., permission denied → graceful degradation).
- Include **acceptance criteria** centered on reliability, user comprehension, and privacy preservation.
- If proposing settings: supply **safe defaults** and **guardrails** (limits, confirmations for destructive actions, auto-revert for unsafe combinations).
- If introducing user-facing text: use task-oriented phrasing; avoid jargon, acronyms, or debug terminology.

### Decision Heuristics
**Do:**
- Recommend a single, opinionated default; justify in one sentence if non-obvious.
- Reuse established Compose components, navigation patterns, and theme tokens.
- Stage riskier or destructive actions (session deletion, data export, reset) with confirmation dialogs using clear, non-technical language.
- Provide inline contextual help (icon + tooltip) for advanced settings instead of lengthy preference descriptions.
- Default all optional features to OFF unless they enhance core tracking reliability or privacy (e.g., battery optimization prompt ON by default).

**Don't:**
- Add preference toggles without clear, measurable user value and evidence of demand.
- Expose internal state, debug counters, or raw technical details to end users (isolate behind developer mode if necessary).
- Trade reliability, privacy, or battery life for novelty or convenience.
- Propose multi-step wizards when a single smart default + optional refinement suffices.
- Introduce redundant confirmation prompts for reversible actions (e.g., pausing tracking); reserve confirmations for irreversible operations (e.g., "Delete all sessions").

### Conflict & Uncertainty Handling
- When uncertain, choose the **simpler, safer, reversible** option.
- Privacy and security requirements override convenience; if tension exists, favor the privacy-preserving path and propose UX mitigations to reduce friction.
- For ambiguous feature scope: propose the minimal viable implementation with a single extension point; avoid speculative scaffolding.

### Examples Applied to Tracker Android
| Scenario | Apple-Style Recommendation | Rationale |
|----------|---------------------------|-----------|
| User wants to configure tracking intervals | Default: Auto (adaptive based on activity + battery). Advanced: Manual interval picker behind "Advanced settings" accordion. | Most users benefit from intelligent defaults; power users can override without cluttering main UI. |
| Export format selection | Default: GPX (universal compatibility). Alternatives (KML, JSON) in dropdown, alphabetically sorted. | GPX is the de facto standard; no need to explain upfront; users familiar with alternatives will find them. |
| Permission request flow | Sequential, contextual: location → activity recognition → notifications. Each with plain-language rationale + "Skip" option. No re-prompts unless user re-enables feature. | Respects user autonomy; avoids aggressive loops; maintains trust. |
| Tracking accuracy toggle | Remove toggle. Use device location mode (high/balanced/low) + auto-adjust sampling based on motion state. Surface current accuracy in tracking UI (not settings). | Eliminates decision fatigue; leverages OS-level user choice; aligns with battery optimization goals. |
| Session deletion confirmation | Show dialog: "Delete [session name]? This cannot be undone." with "Cancel" (default focus) + "Delete" (destructive style). | Clear, task-oriented; prevents accidental data loss; follows platform conventions. |
| Map tile cache limit | Default: 200 MB with auto-eviction (LRU). Advanced: manual limit picker (50 MB – 1 GB) in "Storage" settings. | Safe for typical devices; power users with ample storage can expand; no need to expose to general audience. |
| Gamification opt-in | Default: ON for new installs with subtle first-run notice ("Tracking earns points—view achievements anytime"). Toggle in Settings > Gamification. | Enhances engagement without friction; easily discoverable for users who prefer pure tracking. |
| First-run onboarding | Single screen: app purpose + essential permissions with visual metaphors. "Get started" primary action. Optional "Learn more" link to detailed features. | Minimal time-to-value; no multi-step wizards; respects user's time. |

### Integration with Existing Guidelines
- This module augments **Section 3 (Core Engineering Principles)** and **Section 4 (UI & Compose Standards)** with product-level decision heuristics.
- When proposing code, apply technical standards from other sections; when proposing UX/features, apply this philosophy.
- In case of conflict between technical best practice and user-facing simplicity, escalate with both perspectives and recommend the balance (e.g., "Technically supports N formats, but ship with 1 default + 2 common alternatives to avoid choice paralysis").

---

## 30. Test Logging Infrastructure for Copilot Access

### Purpose
Enable Copilot to run tests and access full output in files for analysis, instead of relying on truncated terminal output.

### Running Tests with File Output

**Always capture test output to files for analysis.** Terminal output truncates; files persist and allow complete analysis.

#### Unit Tests (Single Module)
```powershell
# Run unit tests for a specific module with full output capture
./gradlew.bat :<module>:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log

# Examples:
./gradlew.bat :map:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
./gradlew.bat :tracker:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```

#### All Unit Tests
```powershell
./gradlew.bat testDebugUnitTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```

#### Instrumented/Connected Tests (requires device/emulator)
```powershell
# Single module
./gradlew.bat :<module>:connectedDebugAndroidTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log

# Specific test class
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.tracker.app.StandardFlowsTest --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```

#### Build with Output Capture
```powershell
./gradlew.bat :app:assembleDebug --no-daemon --console=plain 2>&1 | Tee-Object -FilePath build-output.log
```

#### Clean Build with Test Run
```powershell
./gradlew.bat :map:clean :map:testDebugUnitTest --rerun-tasks --no-daemon --console=plain 2>&1 | Tee-Object -FilePath test-output.log
```

### Analyzing Test Results

Use these patterns to find failures in output files:

| Purpose | grep_search Query | includePattern |
|---------|-------------------|----------------|
| Find failures | `FAILED\|FAILURE\|BUILD FAILED` | `test-output.log` |
| Find exceptions | `Exception\|Error:\|Caused by:` | `test-output.log` |
| Find assertions | `expected\|actual\|AssertionError\|shouldBe` | `test-output.log` |
| Find stack traces | `at .+\\.kt:\|at .+\\.java:` | `test-output.log` |
| Find test names | `> Task :.+:test\|PASSED\|FAILED` | `test-output.log` |

**Full context analysis:**
```
read_file on test-output.log for complete output
```

### Output File Conventions

| File | Content | When to Use |
|------|---------|-------------|
| `test-output.log` | Full test console output including stack traces | Default for all test runs |
| `test-output-<module>.log` | Module-specific test output | When running multiple modules separately |
| `build-output.log` | Full build console output | When debugging build/compile errors |

### Gradle Test Report Locations (Structured HTML/XML)

Gradle generates detailed test reports automatically:
- **HTML Report:** `<module>/build/reports/tests/testDebugUnitTest/index.html`
- **XML Results:** `<module>/build/test-results/testDebugUnitTest/*.xml`
- **Instrumented HTML:** `<module>/build/reports/androidTests/connected/debug/index.html`

### Key Principles

1. **Always capture to file** - Terminal output truncates; files persist
2. **Use `--console=plain`** - Avoids ANSI codes that complicate parsing
3. **Use `--no-daemon`** - Ensures clean state and complete output
4. **Single file overwrite** - No rotation needed; overwrite each run
5. **Clean before rerun** - Use `:clean` task when investigating flaky behavior

### LLM Workflow for Test Debugging

1. Run tests with output capture to `test-output.log`
2. Use `read_file` to examine full output (prefer large ranges)
3. Use `grep_search` with specific patterns to locate failures
4. Follow the root cause analysis framework (Section 20) before proposing fixes
5. After fix, re-run tests with same capture pattern to verify

---

## 31. Subagent Usage for Research & Complex Tasks

**CRITICAL**: Use the `runSubagent` tool aggressively for research, investigation, complex implementations, and quality assurance. Subagents provide focused, in-depth analysis that leads to higher-quality solutions.

**Why this matters**: Research shows 72% of AI errors stem from ambiguous or incomplete context. Thorough upfront investigation via subagents dramatically reduces implementation mistakes and rework. Task decomposition into specialized agents improves success rates by 30% compared to single-agent approaches.

### 31.1. When to Use Subagents

**Research & Investigation:**
- API/Library research: Understanding external APIs, library behavior, platform specifics
- Codebase archaeology: Tracing how features work, finding patterns, understanding existing implementations
- Best practices: Researching current industry standards, design patterns, or framework idioms
- Uncertain solutions: When multiple approaches exist and you need deep analysis to choose

**Complex Implementations:**
- Multi-file changes: Features touching multiple modules requiring coordinated changes
- Architecture decisions: New features requiring structural decisions with trade-offs
- Refactoring: Large-scale code restructuring needing holistic understanding
- Cross-cutting concerns: Features affecting multiple layers (DB, API, UI, etc.)

**Validation & Quality Control:**
- Code review: Having a separate agent review implementation for bugs, security, edge cases
- Architecture review: Validating proposed designs against requirements and constraints
- Test coverage analysis: Identifying gaps in test coverage or missing edge cases
- Security audit: Reviewing code for vulnerabilities, injection risks, auth issues

**Debugging & Troubleshooting:**
- Root cause analysis: Investigating failing tests or bugs across multiple components
- Performance analysis: Understanding bottlenecks, memory issues, or slow code paths
- Integration issues: Debugging problems between multiple systems or modules

### 31.2. Orchestration Patterns

Choose the right pattern based on the task:

**Sequential Pattern (Pipeline):**
Use when tasks have linear dependencies, each step builds on the previous.
```
Research → Design → Implement → Review → Test
```

**Concurrent Pattern (Parallel):**
Use when multiple independent perspectives needed simultaneously.
```
         ┌─ Agent A (perspective 1) ─┐
Input ───┼─ Agent B (perspective 2) ─┼─→ Synthesis
         └─ Agent C (perspective 3) ─┘
```

**Maker-Checker Pattern (Validation Loop):**
Use when quality assurance, code review, or iterative refinement needed.
```
Maker ──→ Checker ──→ Feedback ──→ Maker (iterate until quality threshold)
```

**Hierarchical Pattern (Decomposition):**
Use when complex tasks need breakdown into subtasks with coordination.
```
Planner Agent
    ├─→ Worker Agent 1 (subtask 1)
    ├─→ Worker Agent 2 (subtask 2)
    └─→ Worker Agent 3 (subtask 3)
```

### 31.3. Subagent Strategy

**1. Decompose the Problem:**
Identify distinct focus areas—never bundle unrelated questions into one subagent.

**2. One Subagent Per Focus Point:**
Each subagent should have:
- Single, clear objective: One research question or one implementation task
- Specialized expertise: "You are an expert in [DOMAIN]"
- Defined deliverables: Exactly what to return

**3. Instruct for Depth:**
Include explicit instructions:
- "Do NOT provide a superficial answer"
- "Take time to gather complete context"
- "Trace through actual code paths, not just signatures"
- "If you cannot find definitive information, say so explicitly rather than guessing"

**4. Request Specific Output Format:**
Explicit output format increases accuracy by 40%:
- Define structure: "Return in this exact format..."
- Require confidence levels: "State High/Medium/Low confidence"
- Ask for reasoning: "Show your reasoning steps"

**5. Forbid Guessing:**
- "Do not guess—search and verify"
- "If unsure, say 'I could not find definitive information on X'"
- Reduces hallucination significantly

**6. Synthesize Results:**
After subagents complete:
- Combine findings into coherent solution
- Resolve any contradictions between subagent outputs
- Identify gaps needing additional investigation

### 31.4. Prompt Templates

**Research Subagent Template:**
```
You are an expert in [DOMAIN/TECHNOLOGY].

Research [SPECIFIC TOPIC] in depth.

Context: [Brief background on why this is needed and how it fits the larger goal]

Your task:
1. Fully understand [the problem/API/pattern] before answering—do NOT guess
2. Search the codebase/documentation thoroughly using available tools
3. Trace through the code to understand actual behavior, not just signatures
4. Explore edge cases, constraints, and failure modes
5. Provide concrete examples where applicable

Return (in this exact structure):
- **Summary**: [2-3 sentence overview]
- **Key Findings**: [Bulleted list of discoveries]
- **[Specific deliverable 1]**: [Details]
- **[Specific deliverable 2]**: [Details]
- **Caveats/Risks**: [Any limitations or concerns discovered]
- **Confidence Level**: [High/Medium/Low with brief justification]

Do NOT provide a superficial answer. Take the time to gather complete context.
If you cannot find definitive information, say so explicitly rather than guessing.
```

**Implementation Subagent Template:**
```
You are an expert [LANGUAGE/FRAMEWORK] developer.

Implement [SPECIFIC FEATURE/COMPONENT].

Context: [What this is for, how it fits the larger system, any constraints]

Requirements:
- [Requirement 1]
- [Requirement 2]
- [Acceptance criteria]

Constraints:
- Follow existing patterns in [relevant files/modules]
- [Specific technical constraints]

Your task:
1. First, read and understand the relevant existing code
2. Design the implementation approach before writing code
3. Implement following project conventions
4. Include error handling and edge cases
5. Add appropriate tests

Return:
- **Approach**: Brief explanation of implementation strategy
- **Files Changed**: List of files created/modified
- **Key Decisions**: Any design decisions made and rationale
- **Testing**: How to verify the implementation works
- **Risks/TODOs**: Any follow-up work needed

Ensure the implementation is complete and production-ready, not a skeleton or stub.
```

**Review Subagent Template:**
```
You are an expert code reviewer specializing in [DOMAIN/TECHNOLOGY].

Review [SPECIFIC CODE/IMPLEMENTATION] critically.

Context: [What this code is supposed to do, any requirements]

Your task:
1. Read through all the code carefully
2. Check for bugs, logic errors, and edge cases
3. Evaluate security implications
4. Assess performance characteristics
5. Verify adherence to project patterns and conventions
6. Check test coverage adequacy

Return (in this structure):
- **Overall Assessment**: [PASS/NEEDS WORK/MAJOR ISSUES] with brief summary
- **Critical Issues**: [Must-fix problems - bugs, security, data loss risks]
- **Improvements**: [Should-fix - performance, maintainability, edge cases]
- **Suggestions**: [Nice-to-have - style, minor optimizations]
- **Positive Notes**: [What's done well]
- **Confidence**: [How thoroughly you reviewed and any areas you're uncertain about]

Be critical and thorough. The goal is to catch issues before they reach production.
Do not rubber-stamp the review—actively look for problems.
```

**Debugging Subagent Template:**
```
You are an expert debugger specializing in [LANGUAGE/FRAMEWORK].

Investigate and fix [SPECIFIC BUG/ISSUE].

Context: 
- Symptom: [What's happening]
- Expected: [What should happen]
- Reproduction: [How to trigger the bug]

Your task:
1. Understand the expected behavior first
2. Trace through the code path that leads to the bug
3. Identify the root cause (not just the symptom)
4. Propose a fix that addresses the root cause
5. Consider if similar issues exist elsewhere

Return:
- **Root Cause**: What's actually causing the issue
- **Code Path**: How execution reaches the buggy state
- **Fix**: Proposed solution with code changes
- **Verification**: How to confirm the fix works
- **Related Issues**: Other places with similar patterns that might need attention
- **Prevention**: How to prevent similar bugs in the future
```

### 31.5. Specialist/Reviewer Pattern (Quality Loop)

For high-stakes work, use paired subagents:

1. **Specialist subagent**: Performs the research, design, or implementation
2. **Reviewer subagent**: Validates with fresh perspective, actively looks for issues

**Workflow:**
```
Task → Specialist (v1) → Reviewer → Issues Found? 
                                    ├─ Yes → Specialist (v2) → Reviewer → ...
                                    └─ No → Done
```

**Key principles:**
- Reviewer should use DIFFERENT prompt than specialist (fresh perspective)
- Reviewer should be explicitly instructed to be critical, not rubber-stamp
- Iterate until reviewer finds no critical issues
- This catches errors that single-pass work misses

### 31.6. Verify-Fix-Verify Loop (Implementation Quality)

When implementing features, use this cycle:

1. **Implement**: Make code changes
2. **Verify**: Run tests, linters, type checks
3. **Fix**: Address any failures with minimal targeted changes
4. **Repeat**: Until all checks pass

**Why this matters:**
- AI-generated code can be "plausibly wrong"—looks correct but has bugs
- Tests and linters provide objective feedback
- Tight loops catch issues immediately
- Prevents accumulation of technical debt

### 31.7. Anti-Patterns (REJECT)

- Ask one subagent to research everything at once (shallow, unreliable results)
- Skip subagents for "simple" research—depth matters more than speed
- Ignore subagent findings and proceed with assumptions
- Use subagents for trivial tasks that a quick grep or file read can answer
- Write vague prompts like "look into X"—be specific about what to find and return
- Forget to specify output format—unstructured responses are harder to synthesize
- Skip the review step for important implementations
- Trust AI output without verification (tests, linters, human review)

### 31.8. Benefits Summary

- **Depth over breadth**: Each subagent can thoroughly investigate its focus area
- **Parallel investigation**: Multiple research threads can run simultaneously
- **Reduced hallucination**: Explicit "don't guess" instructions and verification reduce fabricated information
- **Complete context**: Synthesis of multiple focused investigations yields better solutions
- **Validation loop**: Reviewer pattern catches errors before they become implementation bugs
- **Specialization**: Agents with focused expertise outperform generalists
- **Reduces errors**: Thorough research upfront prevents costly implementation mistakes
- **Quality assurance**: Verify-fix-verify loops ensure working, tested code

### 31.9. Web Search Integration

Subagents can use web search internally. When researching:
- External APIs, versions, or library behavior
- Platform-specific constraints or best practices
- Current industry standards or patterns
- Error messages or debugging approaches

Explicitly instruct: "Use web search to find current [PLATFORM/API] constraints rather than relying on potentially outdated training data."

### 31.10. Example: Complex Feature Implementation

**Task**: Implement encrypted backup export feature

**Phase 1: Research (Concurrent)**
Spawn 3 focused research subagents in parallel:
1. **Codebase Expert**: Research how encryption currently works in this codebase
2. **Android Platform Expert**: Research Android file export patterns and security best practices
3. **Format Analyst**: Research backup file format options (encrypted ZIP, custom format, protocol buffers)

**Phase 2: Design (Sequential)**
1. **Architecture Subagent**: Design the backup export feature given research findings
2. **Review Subagent**: Review proposed architecture for security flaws, performance issues

**Phase 3: Implementation (Sequential with Review)**
1. **Implementation Subagent**: Implement BackupExportManager following the design
2. **Review Subagent**: Review implementation for bugs, security issues, edge cases
3. **Test Subagent**: Write comprehensive tests for BackupExportManager

---

## 32. Specialized Skills Usage

**CRITICAL**: The LLM has access to specialized skills that dramatically improve output quality for specific task types. These skills contain domain-specific methodologies, adversarial techniques, and structured workflows. **Always activate the relevant skill by reading its instruction file before performing the task.**

### 32.1. Available Skills and Activation Triggers

| Skill | File Path | Activation Triggers |
|-------|-----------|---------------------|
| **swe** | `c:\Users\adam-\.copilot\skills\swe\SKILL.md` | Default for ALL coding tasks—writing, editing, debugging, refactoring, features. Prioritizes verification loops and test-driven development. |
| **code-review** | `c:\Users\adam-\.copilot\skills\code-review\SKILL.md` | "Review this code", "audit", "critique code quality", "review PR", "assess merge request" |
| **test-gen** | `c:\Users\adam-\.copilot\skills\test-gen\SKILL.md` | "Write tests", "generate tests", "improve coverage", "mutation testing", "set up testing" |
| **security-audit** | `c:\Users\adam-\.copilot\skills\security-audit\SKILL.md` | "Find security issues", "audit for vulnerabilities", "threat model", "OWASP check", "penetration test" |
| **deep-research** | `c:\Users\adam-\.copilot\skills\deep-research\SKILL.md` | "Research", "investigate", "compare options", "evaluate alternatives", "deep dive" |
| **brainstorming** | `c:\Users\adam-\.copilot\skills\brainstorming\SKILL.md` | "Brainstorm", "ideate", "generate ideas", "what are ways to", "how might we" |
| **idea-stress-test** | `c:\Users\adam-\.copilot\skills\idea-stress-test\SKILL.md` | "Is this a good idea", "critique my idea", "poke holes in", "validate this concept" |
| **problem-discovery** | `c:\Users\adam-\.copilot\skills\problem-discovery\SKILL.md` | "I don't know what I want", "not sure what the problem is", "help me figure out" |
| **creative-unblock** | `c:\Users\adam-\.copilot\skills\creative-unblock\SKILL.md` | "I'm stuck", "out of ideas", "creative block", "hitting a wall" |
| **context-init** | `c:\Users\adam-\.copilot\skills\context-init\SKILL.md` | "Initialize AI context", "generate CLAUDE.md", "set up repo for AI assistance" |
| **prompt-engineer** | `c:\Users\adam-\.copilot\skills\prompt-engineer\SKILL.md` | "Improve this prompt", "optimize instructions", "iteratively refine prompt" |

### 32.2. Mandatory Skill Activation Rules

**Rule 1: Always Activate for Matching Tasks**
When a user request matches a skill's activation triggers, the LLM MUST:
1. Use `read_file` to load the skill's SKILL.md file
2. Follow the skill's methodology completely
3. Apply all adversarial techniques and quality checks defined in the skill

**Rule 2: Default to SWE Skill for Code**
For ANY coding task (writing, editing, fixing, debugging, refactoring, features), activate the **swe** skill FIRST. This ensures:
- Verification loops catch errors early
- Test-driven development is applied where appropriate
- Code quality gates are enforced
- Incremental, safe changes are made

**Rule 3: Combine Skills When Appropriate**
Some tasks benefit from multiple skills in sequence:
- Implementing a feature → **swe** (implementation) + **code-review** (self-review) + **test-gen** (tests)
- Security-sensitive code → **swe** (implementation) + **security-audit** (vulnerability check)
- Researching an approach → **deep-research** (investigation) + **swe** (implementation)
- Unclear requirements → **problem-discovery** (clarify) + **brainstorming** (options) + **swe** (implement)

**Rule 4: Quality-Critical Tasks Require Skill Usage**
For these task types, skill activation is MANDATORY (not optional):
- Code reviews and PR assessments → **code-review**
- Writing or improving tests → **test-gen**
- Security-related changes → **security-audit**
- Any task labeled "high quality" or "production-ready" → appropriate skill + **code-review**

### 32.3. Skill Usage Workflow

```
1. Identify Task Type
   └─→ Does it match a skill trigger? 
       ├─ Yes → Read skill file → Follow skill methodology
       └─ No → Apply general best practices

2. For Coding Tasks (ALWAYS)
   └─→ Read swe skill → Apply verification loops
       └─→ Additional skill if specialized (security, tests, etc.)

3. For Complex/Multi-Part Tasks
   └─→ Use Plan agent for decomposition
       └─→ Apply relevant skill to each part
           └─→ Use code-review skill for final validation
```

### 32.4. Skill-Enhanced Quality Patterns

**Pattern: Implementation with Self-Review**
```
1. Activate swe skill → Implement feature
2. Activate code-review skill → Review own implementation
3. Address findings → Iterate until clean
4. Activate test-gen skill → Add tests
5. Run tests → Verify passing
```

**Pattern: Security-First Development**
```
1. Activate security-audit skill → Threat model the feature
2. Activate swe skill → Implement with security constraints
3. Activate security-audit skill → Audit implementation
4. Fix vulnerabilities → Iterate until secure
```

**Pattern: Research-Driven Implementation**
```
1. Activate deep-research skill → Investigate options
2. Synthesize findings → Choose approach
3. Activate swe skill → Implement chosen approach
4. Activate code-review skill → Validate implementation
```

### 32.5. Anti-Patterns (REJECT)

- **Skipping skill activation**: Performing tasks without reading relevant skill files
- **Partial skill application**: Reading skill file but not following its full methodology
- **Single-skill tunnel vision**: Using only one skill when multiple apply
- **Skipping swe for code**: Writing code without verification loops and quality gates
- **Skipping code-review for PRs**: Reviewing code without structured review methodology
- **Ignoring security-audit for sensitive code**: Changing auth, permissions, or data handling without security review

### 32.6. Skill Activation Examples

**User asks**: "Fix this bug in the tracking service"
```
LLM Action:
1. read_file(swe skill) → Get methodology
2. Follow swe investigation workflow
3. Apply root cause analysis
4. Implement fix with verification
5. Run tests to confirm
```

**User asks**: "Review this PR for the map module"
```
LLM Action:
1. read_file(code-review skill) → Get methodology
2. Apply multi-dimensional review
3. Use adversarial techniques
4. Provide structured feedback
```

**User asks**: "Add tests for the export functionality"
```
LLM Action:
1. read_file(test-gen skill) → Get methodology
2. Analyze existing coverage
3. Identify edge cases
4. Generate comprehensive tests
5. Apply mutation testing if applicable
```

**User asks**: "I'm not sure what's wrong with the app, something feels off"
```
LLM Action:
1. read_file(problem-discovery skill) → Get methodology
2. Help user articulate the problem
3. Once clear, transition to appropriate skill (swe, debug, etc.)
```

---

End of evergreen instructions.
