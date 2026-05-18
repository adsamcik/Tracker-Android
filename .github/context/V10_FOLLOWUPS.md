# dev/v10 — Remaining Follow-Ups

Status snapshot at HEAD `b3882a3f4` (Merge docs/v10-drift-fixes, on top of
`93ff4d4f1` Merge fix/v10-runtime-crashes). Use this file alongside
[`V10_HIGHLIGHTS.md`](V10_HIGHLIGHTS.md) when landing on the branch.

Companion: [`V10_RC_MERGE_FOLLOWUPS.md`](V10_RC_MERGE_FOLLOWUPS.md) covers the
**RC-merge integration decisions** (which side won for tracking toggles,
onboarding, map controls, dashboard, impexp, TrackingOrchestrator,
PrivacyPolicyDialog) and release-gate validation status. This file
focuses on **what's still broken** in the merged tree.

This file is **hand-curated**. The underlying findings come from the
internal `.github/v10-validation/` reports (gitignored — local-only;
121 reviewer-approved task reports across 17 area folders), the
`SUMMARY.md` cross-area synthesis there, and observations from the
two integration sessions that produced commits `93ff4d4f1` and
`b3882a3f4`.

> **Verify before acting.** The validation reports were generated against
> an earlier snapshot of dev/v10 (~22 days before the runtime-crash
> merge). The parallel `feature/v10-rc-fixes` and `feature/qc-finalization`
> merges have changed substantial code since. Every claim below names a
> specific file:symbol — grep it and confirm the issue still reproduces
> on the current tip before opening a PR.

---

## 1. What this session already closed

### 1.1 Predicted runtime crashes — done

Merged via `93ff4d4f1`:

- **`:tracker` missing `androidx.hilt.compiler`** — Added
  `ksp(libs.androidx.hilt.compiler)` to `tracker/build.gradle.kts` so
  `DisableTillRechargeWorker`'s `@HiltWorker` injection generates
  correctly.
- **`WifiPermissionHintNotifier` intent target** — Already fixed by
  the parallel agent during the `feature/v10-rc-fixes` Phase B merge;
  now points to `MainActivityCompose` instead of the removed
  `OnboardingActivity`.
- **3 stale `<activity>` declarations** — Removed `LogViewerActivity`
  (in `activity/AndroidManifest.xml`), `StatsDetailActivity` and
  `WifiBrowseActivity` (in `statistics/AndroidManifest.xml`). The
  corresponding `MissingClass` entries were stripped from both
  modules' `lint-baseline.xml` too.
- **Schema 18.json / Migration_17_18 lift_type mismatch** — Moved the
  `lift_type` column add from `Migration_17_18` to `Migration_18_19`
  (alongside the existing `raw_gps_alt_m` add), so each migration
  matches its target schema. `migrate17To18` androidTest was also
  updated to stop seeding `lift_type` at v18.

### 1.2 Doc drift — done

Merged via `b3882a3f4`. See that commit for the full diff. Headline
corrections to module count (17→18), Room version (v17/27→v26/26),
AGP/Gradle/JDK versions, Geocoder removal, runBlocking remaining
count, Compose UI test count, map module description.

### 1.3 Pre-existing stale tests cleaned up incidentally

Six test repairs were necessary to get the unit suite green for the
crash-fix verification gate. They were not part of the original
crash list but were red on `dev/v10` after the parallel merges:

- `sutils/PrimaryActionButtonTest` — assertions now match natural-case
  rendering after `f76fb747c` dropped `.uppercase()`.
- `tracker/TrackingOrchestratorIntegrationTest` — dropped the removed
  `scope` arg from `onCycleUpdate`; added to
  `ArchitecturalFitnessTest`'s legacy-import allowlist.
- `game/HeroLevelCardTest` — action label asserted as `"Open Dashboard
  to start"` instead of the removed `"Start Tracking"`.
- `app/TrackingSettingsViewModelTest` — dropped the removed
  `TrackingTogglesDataStore` constructor arg.
- `tracker/TrackerDashboardControlsTest` + `tracker/TrackerDashboardTest`
  — FAB content descriptions now match the actual `description_tracking_*`
  string values (`"Start tracking button"`, `"Stop tracking button"`,
  em-dash for the permission-required case).
- `app/SetupUiStateTest` + `app/SetupViewModelTest` — replaced removed
  `AutoTrackingMode` enum refs with raw Int constants; updated two
  default-value assertions stale after `b4b92d2d4` flipped privacy
  defaults (Disabled→OnFoot, BATTERY_SAVER→DEFAULT).

---

## 2. Highest-leverage remaining items

Ordered by impact. Each item has a **single source of truth** path
that the next agent should grep/inspect first to confirm the issue
still reproduces. Many will benefit from a per-issue worktree.

### 2.1 Settings stores — finish the consolidation

`feature/v10-rc-fixes` declared `TrackingParamsRepository` canonical
and removed `TrackingTogglesDataStore` from its only caller
(`TrackingSettingsViewModel`), but the implementation class still
ships at:

```
tracker/src/main/java/com/adsamcik/tracker/tracker/data/store/TrackingTogglesDataStore.kt
```

with **zero production callers** as of `b3882a3f4`. Decide:

- Delete it (and its proto definition, if any), or
- Re-wire it where the validation flagged data being silently dropped
  (see `feature/v10-rc-fixes` merge commit body — "Tracking toggles:
  adopt RC's single-source model"; the loser was `b4b92d2d4`'s
  dual-write idea).

Related still-active legacy reads (intentional, for migration compat):
- `spreferences/.../store/LegacyPreferenceStore.kt`
- `spreferences/.../flow/PreferenceFlows.kt`

These should stay readable for the migration window; only adds new
writes through them should be rejected.

### 2.2 Silent data corruption — top items still open

All cited line numbers were extracted from the original validation;
re-verify against current tip before fixing. Listed with the most
direct file:symbol to inspect.

- **`SignalDispatchStage` silently drops fields.**
  `tracker/src/main/java/.../tracker/pipeline/stages/SignalDispatchStage.kt`
  — the validation flagged that `distanceDelta`, `verticalAccuracy`,
  `speedAccuracy`, `provider`, `policyName` are not propagated into
  the persisted signal. Check the current envelope against
  `TrackingSignal`/`TrackingCycle` fields.

- **`ZipArchiveExtractor` doubly broken.**
  `impexp/src/main/java/.../impexp/importer/archive/ZipArchiveExtractor.kt`
  — validation reported a reversed `require(file.isDirectory)` and a
  `sequence { … }` yielded from inside a closed `use { }` block,
  making archive imports a no-op. `feature/v10-rc-fixes` commit
  `b7059d567 fix(impexp): lazily open zip entry streams` touched this
  file — verify whether the original issues are still present.

- **`ImportWorker` rollback semantics.**
  `impexp/src/main/java/.../impexp/importer/worker/ImportWorker.kt`
  — archive-wide `withTransaction` rolls back earlier successes when
  a later item fails; `getOrThrow()` aborts on first throw, so the
  `failedCount` branch is unreachable.

- **`SessionSegmentDao.getAllBetween` midnight off-by-one.**
  `sbase/src/main/java/.../shared/base/database/dao/SessionSegmentDao.kt`
  + `dashboard/src/main/java/.../dashboard/ui/DashboardViewModel.kt:210`
  — segments straddling midnight are excluded from both days in
  non-UTC zones.

- **`RetentionConfigStore.toProto()` resets a legacy migration flag.**
  `spreferences/src/main/java/.../shared/preferences/retention/RetentionConfigStore.kt`
  — validation reported `update { }` clears
  `dataSettingsLegacyMigrated` each time. Check the `toProto()` /
  Builder usage on the current implementation.

- **`LegacyPreferenceStore.stringOrIntFlow()` ClassCastException.**
  `spreferences/src/main/java/.../shared/preferences/store/LegacyPreferenceStore.kt`
  — throws on legacy String-backed numerics (StepGoal,
  BackgroundTrackingApi).

- **Duplicate ski persistence path.**
  `stats-engine/src/main/java/.../stats/engine/ski/SkiRunExtractor.kt`
  → `SkiSegmentWriter` writes ski_run_segments; the activity worker
  in `activity/src/main/java/.../activity/ActivityRecognitionWorker.kt`
  also writes them with a disjoint id space. Naive dedup loses one
  side.

- **Paginated DAOs drained into unbounded `samples.addAll(chunk)`.**
  Four sites in stats/impexp identified by validation; rather than
  enumerate them stale, grep `addAll(chunk)` / `addAll(it)` patterns
  near `getPaged*` calls.

- **`GeoQuery.limit` defaults to `null`.**
  `map/src/main/java/.../map/data/GeoModels.kt` and
  `map/src/main/java/.../map/data/GeoRepository.kt`
  — `cameraToBounds()` returns null at zoom < 3.0, then a null limit
  full-scans `location_sample`.

- **GPX/JSON imports accept NaN/Infinity coordinates.**
  `impexp/src/main/java/.../impexp/importer/file/*Import.kt` — exports
  guard against `!isFinite()` (validation confirmed); imports do not,
  so "Null Island" data can be persisted.

- **`logger/.../LogDatabase.kt:31` calls `fallbackToDestructiveMigration()`
  in the `main` source set.** This violates the north star
  (never-destructive in production). Move it behind a `debug`-only
  builder or remove.

- **FK gaps.** Validation flagged missing foreign keys on
  `route_cache.session_id/segment_id`, `ski_run_segment.session_id`,
  `inferred_trip.segment_id`. Verify entities in `sbase/.../data/`
  and add `@ForeignKey` declarations where appropriate (will need a
  new migration).

- **`Reporter` is not non-throwing.** `logger/src/main/java/.../logger/Reporter.kt`
  and `logging-api/src/main/java/.../logging/api/ReporterFacade.kt`
  have no `try/catch`. The `V10_HIGHLIGHTS.md` claim was downgraded
  during the doc-drift sweep; if a non-throwing wrapper is actually
  wanted, add one and route all `ErrorReporter` calls through it.

- **~28 production files bypass `PiiRedactor` via raw `android.util.Log.*`.**
  Validation listed the file set; sweep all `android.util.Log` imports
  in production code and route through the structured logger. Also
  broaden `PiiRedactor` itself — it currently only handles ≥5-decimal
  coordinates and misses BSSID/SSID/IMEI/IP/email/4-decimal coordinate
  forms.

- **Theme regression.** Validation flagged that `AppTheme` regressed
  to a bare `MaterialTheme` (no `MaterialExpressiveTheme`). The
  copilot-instructions claim Material 3 Expressive throughout; if the
  regression is still present, restore it.

### 2.3 Other gaps

- **Achievement nullable-tier="null" bug locked by test.** Validation
  cited `DefaultAchievementRepositoryTest.kt:81-88` — fixing the
  achievement-unlock logic also requires updating that test.
- **`MapLibreInitializer` swallows errors silently** → perma-spinner
  UX. SAF-import discards errors. Need explicit error surfacing.
- **`BasemapManager` race condition.** Instantiated three times
  independently; no `@Singleton`, no Mutex.
- **`MissingPermission` lint suppressions.** Five in `game/lint-baseline.xml`,
  two in `impexp/lint-baseline.xml`. Replace with explicit permission
  checks where the call is reachable; remove the baseline entries.
- **`DataRetentionWorker.doWork()` test is `@Ignore`d.** Only schedule/
  cancel paths are covered. The body of `doWork()` is uncovered.
- **Inferred-trip persistence schema-only.** No producer writes the
  inferred_trip table; the entity ships but is never populated.
- **`smap/` directory residue.** Not a module in `settings.gradle.kts`
  but the directory holds `build.gradle.kts` and `.gitignore`. Delete
  for cleanliness.

### 2.4 Production `runBlocking` sites

V10_HIGHLIGHTS now lists them explicitly; if the goal is true zero,
target them in this order:

1. `impexp/.../exporter/PagedLocationSequence.kt`
2. `logger/.../CrashHandler.kt`
3. `logger/.../DebugCrashLogExporter.kt`
4. `spreferences/.../settings/TrackerSettingsAccess.kt`
5. `spreferences/.../store/LegacyPreferenceStore.kt`

CrashHandler is unavoidable (process is about to die); the others
have non-blocking alternatives.

---

## 3. Process notes for the next agent

### 3.1 Parallel-agent activity on `dev/v10`

There is an ongoing pattern of another agent (typically working out
of `Tracker-Android-qc-finalization` / `Tracker-Android-v10-rc-fixes`
worktrees) cherry-picking and merging onto `dev/v10` autonomously.
Before any git write:

1. Run `git --no-pager log -1 dev/v10` and remember the SHA.
2. Do all work in a dedicated worktree off that SHA.
3. Re-check `dev/v10` immediately before the final `git merge --no-ff`.
4. If it moved, `git rebase dev/v10` the feature branch first
   (rebase, not merge — keeps the final merge commit clean).

The two integration sessions that produced `93ff4d4f1` and
`b3882a3f4` both had to rebase mid-flight when the parallel agent
landed new commits.

### 3.2 Stale-test whack-a-mole risk

After the `feature/v10-rc-fixes` Phase B merge, the unit-test suite
appeared green only because warm build caches hid pre-existing test
staleness. Every time the cache invalidated (different gradle inputs,
new dep, etc.) a new cluster of stale tests surfaced. The
runtime-crash workstream needed **eight** test passes before reaching
green; six of those were chasing stale tests, not real regressions.

To pre-empt this for the next workstream:

- Run `./gradlew.bat clean testDebugUnitTest --no-daemon --console=plain`
  on `dev/v10` once at session start, with **no local changes**.
  Whatever it surfaces is the current pre-existing baseline; fix it
  before doing anything else.
- Common staleness patterns to look for: removed enums whose value
  references survive (e.g., `AutoTrackingMode`), removed/renamed
  constructor params, content-description string renames, default-value
  flips on UiState.

### 3.3 Emulator validation sessions

The original validation produced **178 emulator validation sessions**
(~52 P0, ~80 P1, ~46 P2) under each area's
`.github/v10-validation/by-area/<area>/EMULATOR_SESSIONS.md`. These
are *not* in the repo (gitignored). They drive the runtime confirmation
for the items in §2. The recommended execution order from the original
`SUMMARY.md` was:

1. `hilt-di-modernization` — confirms the Hilt KSP fix
   (`lock-till-recharge-worker` session).
2. `ski-and-altitude-detection` — confirms the `Migration_17_18` /
   `Migration_18_19` lift_type rewiring.
3. `compose-ui-migration` — confirms the manifest-activity cleanup.
4. `datastore-migration` — confirms the wifi-permission-hint intent.

The user has indicated only one agent at a time can drive the
emulator; a separate agent should be spun up for that.

### 3.4 Local-only validation artifacts

`.github/v10-validation/` is in `.gitignore`. Treat the per-area
reports as authoritative for *historical* claims, but always verify
against current source before acting — the snapshot is now several
weeks stale and substantial code has landed since.

### 3.5 Workflow rule

All changes must be done in a dedicated git worktree and integrated
back into `dev/v10` via `git merge --no-ff` (local merge commit, do
not push unless asked). The two merges already on the branch
(`93ff4d4f1`, `b3882a3f4`) demonstrate the expected pattern.

---

## 4. Suggested ordering for the next session

If picking up the next workstream:

1. **(2 min)** `git log -1 dev/v10` to capture current SHA.
2. **(5 min)** Read this file end-to-end; pick one §2 item.
3. **(5 min)** Cut worktree, copy `local.properties`.
4. **(10 min)** Read referenced files, grep for the symptom, decide
   whether the issue is still present and what the minimal fix is.
5. **Implement, test, merge back.** Per §3.5.

The doc-drift workstream (this commit) closes out the read-only
half of the validation. The next sessions should focus on the
silent-data-corruption items in §2.2 — those are the highest-risk
items still outstanding.
