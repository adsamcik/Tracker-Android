# Tracking Infrastructure Continuation Handover

Last updated: 2026-09-03

This handover is for the next device or Codex session continuing the tracking-infrastructure
program after the local and remote `dev/v10` histories were reconciled. It is an execution
checkpoint, not a replacement for the architecture or evidence ledgers.

## 2026-09-03 final pause checkpoint after bounded parallel corrections

This is the authoritative restart boundary for the next session. It supersedes every older
checkpoint below for current branch state, draft state, verification, blockers, and restart order.
Older sections remain historical evidence only.

### Accepted integration, protected root, and build state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `560163ecd73b37fdbdceafed3da979cbc2c4e1bb` (`docs(tracking): checkpoint settlement race
  diagnosis`), 88 commits ahead of `origin/dev/v10`. Resolve this documentation checkpoint's own
  commit with `git rev-parse HEAD`; no self-referential SHA is asserted here.
- The accepted implementation boundary remains Pressure selected-session deletion through
  `61608800e`, `9592c42d8`, and evidence commit `673c4186e`. Neither dirty Steps lane described
  below is accepted, committed, rebased, merged, or published. The clean live-consumer branch also
  contains no implementation commit.
- The detached root checkout remains at exact HEAD
  `ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly its six protected dirty paths and no
  additional path. Their SHA-256 values were rechecked and still exactly match the values in the
  following checkpoint sections. Never alter, stage, commit, clean, reset, or push those paths as
  part of this continuation.
- All three implementation specialists were stopped after completing only their bounded edits and
  then performed read-only checkpoint audits. No specialist ran Gradle, staged, or committed.
  `git -c core.fsmonitor=false diff --check` passes in the root, integration, awards, importer, and
  live-consumer worktrees. No `java` or `javaw` process remained at the checkpoint.
- The previously green 63-test settlement/recovery cohort and the 2,520 passing tests from the
  affected-module run predate the final parallel edits below. The latter run stopped at
  `stats:data` test compilation because `AchievementWorkerTest` lacked `MetricKeys`; that import is
  now present, but no current-byte rerun exists. Do not treat either earlier result as acceptance
  evidence for the final dirty awards bytes.
- Everything remains local-only. No push, release, tag, deployment, feature/source activation,
  remote flag, destructive or backward-incompatible migration, or external rollout occurred or is
  authorized here.

### Preserved lane 1: source-qualified awards, goals, and retained metrics

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`; branch
  `codex/ti-steps-qualified-awards`; exact base HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645`, 15 commits behind the pre-checkpoint parent and zero
  commits ahead.
- Exact draft state: 55 tracked modifications plus nine untracked files, 64 dirty paths total;
  tracked diffstat `+4780/-590`; nothing staged; `git -c core.fsmonitor=false diff --check` passes.
- The bounded settlement-race correction remains in the draft: Room installs an initial-emission
  observer as a registration barrier and revalidates the durable revision token on every emission,
  while preserving cancellation and structured observer ownership. Before the later parallel edits,
  the exact two-method regression passed `2/2` and the complete five-class recovery/settlement
  cohort passed `63/63`.
- The retained-metrics partition now clips fully pre-floor run discovery, ignores valid atomic facts
  ending before the retained floor, keeps crossing facts whole rather than prorating them, and
  excludes the civil day containing a mid-day floor. It is uncompiled and has no new focused tests.
  Before running it, resolve whether provably pre-floor orphan facts/completeness should be bounded
  out, test relaxed crossing-run authority adversarially, and cover active crossing runs whose
  discovery envelope may otherwise be too small.
- The decision-revision partition preserves general decision triggers, narrows segment UPDATE
  invalidation to structural fields, suppresses heartbeat amplification, and narrows projection-lane
  invalidation to Steps authority transitions. Its focused tests are authored but unrun. Before
  accepting it, resolve the stale-cache risk for INSERT/DELETE of segments whose two durable IDs are
  null, and audit cursor-only settlement before excluding `contiguous_admission_ordinal`.
- The Game/achievement partition adds versioned `QUALIFIED_STEPS_DAILY_GOAL_V1` ledger provenance,
  excludes generic legacy `GOAL` rows from qualified streak/week calculations, derives goal streaks
  and ISO perfect weeks from distinct qualified epoch days, blocks all four Steps metrics while
  materializing or storage-unavailable, confines settlement retries to terminal session events, and
  conflates settings/batch refresh requests. Its focused tests are authored but unrun. Before
  acceptance, add or justify a Room transaction integration test for XP provenance, review retained
  lower-bound streak/week semantics, and decide whether unexpected refresh exceptions should end the
  settings collector. The current `getRecent(Int.MAX_VALUE)` scans are truthful but remain a bounded
  performance debt rather than a reason for a speculative query framework.
- No current-byte compile, unit test, Detekt, lint, Hilt, Room-schema, staging, rebase, or fresh
  whole-diff review exists after these three partitions. Do not form commits merely to preserve the
  draft; first close the named correctness questions and add the missing retained-metrics Room cases.

### Preserved lane 2: portable Steps importer, composition, and no-resurrection

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`; branch
  `codex/ti-steps-portable-import`; exact base HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645`, 15 commits behind the pre-checkpoint parent and zero
  commits ahead.
- Exact repository state is 37 tracked modifications plus 15 untracked status entries, 52 dirty
  paths total; tracked diffstat `+2669/-164`; nothing staged; `git -c core.fsmonitor=false diff
  --check` passes. This current count replaces older handover claims of 16 untracked entries.
- The untracked `ImportedStepsHistoryIntegrationTest.kt` now includes two unrun regressions: an
  imported covered-zero entry remains discoverable and Steps-qualified without inventing a positive
  sample, and two replacement physical runs compose into one logical recent entry while preserving
  exact segment/run ownership and their `3 + 7` counts. Its fixture accepts a strictly ordered run
  list.
- No production importer change was made in this final checkpoint pass and no current-byte Gradle
  evidence exists. Keep this lane frozen until the overlapping awards v28/schema/composer draft is
  accepted and merged; then reconcile rather than selecting one schema snapshot wholesale.

### Preserved lane 3: truthful live Steps consumers

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-live-consumer-truth`; branch
  `codex/ti-steps-live-consumer-truth`; exact clean HEAD
  `560163ecd73b37fdbdceafed3da979cbc2c4e1bb`, with zero commits ahead of the pre-checkpoint
  `dev/v10`. No file was edited.
- The next bounded implementation should keep verified zero available to the Today Summary widget,
  but stop the Active Session widget, legacy tracker cards, and Last Session card from turning an
  unsupported non-null integer default or PackageManager hardware capability into captured zero.
  Positive legacy live values may remain an observed lower bound; zero requires explicit qualified
  coverage. Reuse the accepted typed Steps-only Dashboard presentation and do not widen
  `TrackerSessionSnapshot` or introduce another UI platform without demonstrated need.

### Exact restart order

1. Repeat the mandatory read-only checks, applicable `AGENTS.md` reads, and the complete ordered
   tracking-document read. Verify this checkpoint HEAD, all three lane states, the detached root HEAD
   and six protected hashes, and that no process owns the sole Gradle lease.
2. In the awards lane, perform source review and tests in dependency order: resolve the two revision
   trigger questions; add retained-floor/crossing/orphan Room cases and correct their implementation;
   then review the qualified goal/achievement lifecycle choices. Run the focused core, tracker,
   Game, and stats tests serially, followed by the exact five-class recovery/settlement cohort.
3. If focused behavior is green, run affected full module tests, Detekt, lint, Hilt compilation, and
   `checkRoomSchemaDrift`, then obtain one fresh whole-diff review. Split and commit only coherent
   exact-path chunks in dependency order. Rebase onto latest local `dev/v10`, repeat proportional
   current-byte gates, and merge locally only after GO.
4. The clean live-consumer lane can receive its narrow UI truth fix in parallel with awards source
   review, but all Gradle invocations remain globally serialized. Give it focused formatter/widget/
   Compose tests, static checks, a fresh bounded review, and the same rebase-before-local-merge rule.
5. Keep the importer frozen until awards releases the overlapping v28/schema/composer ownership.
   Reconcile it onto the accepted schema, run the imported-history regression plus the full named
   importer/deletion/repair/retention/codec cohort, then static/schema/review gates before coherent
   commits and local merge.
6. Run `ciUnitTest` at the combined Steps integration gate and `ciCheck --continue` only at
   integration readiness. Keep the physical manual Steps-only `TYPE_STEP_COUNTER`, walking,
   listener-removal, process/reboot, provider, battery, and UI-device evidence explicitly open.
7. After the Steps gates converge, continue source-local work in order: automatic Steps with
   explicit control separation; default-off ambient Steps only after a real provider/capability
   decision; Pressure history/product/retention/transfer; protected Location; then Activity,
   Wi-Fi, and Cell. Retain one integration owner for shared schema, history, retention, transfer,
   app DI/UI, rollout, and documentation collision zones.

## 2026-09-03 pause checkpoint after Steps settlement-race diagnosis

This is the authoritative restart boundary for the next session. It supersedes every older
checkpoint below for current branch state, draft state, verification, and restart order. Older
sections remain useful historical evidence only.

### Accepted integration and protected state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `37d230568fd681b55b40415472098210dfac9688` (`docs(tracking): checkpoint Steps parallel
  lanes`), 87 commits ahead of `origin/dev/v10`. Resolve this documentation checkpoint's own
  commit with `git rev-parse HEAD`; no self-referential SHA is asserted here.
- The accepted implementation boundary remains Pressure selected-session deletion through
  `61608800e`, `9592c42d8`, and evidence commit `673c4186e`. Neither Steps draft below is
  accepted, committed, rebased, merged, or published.
- The detached root checkout remains at exact HEAD
  `ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly its six protected dirty paths and no
  additional path. Their SHA-256 values were rechecked at this pause and still exactly match the
  values in the immediately following checkpoint section. Never alter, stage, commit, clean,
  reset, or push those paths as part of this continuation.
- The focused awards build completed and no `java` or `javaw` process remained afterward. All
  read-only diagnostic agents completed without editing. Recheck the single global Gradle lease
  before the next build.
- Everything remains local-only. No push, release, tag, deployment, source/feature activation,
  remote flag, destructive or backward-incompatible migration, or external rollout occurred or
  is authorized here.

### Preserved lane 1: source-qualified awards, goals, and retained metrics

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`; branch
  `codex/ti-steps-qualified-awards`; exact base HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645`, 14 commits behind the pre-checkpoint parent and
  15 behind the resulting documentation checkpoint, with zero commits ahead.
- Exact draft state: 55 tracked modifications plus nine untracked files, 64 dirty paths total;
  tracked diffstat `+4377/-592`; nothing staged; `git -c core.fsmonitor=false diff --check`
  passes.
- The full five-class cohort hang was reproduced with the formerly parked method alone, ruling
  out preceding-class leakage as the required cause. A JVM thread dump again showed the
  Robolectric SDK 34 main thread parked in the settlement wait while dispatcher workers were
  idle.
- Root cause is a lost-invalidation race: `awaitSourceEvidenceSettlement` accepted a durable
  revision token but ignored it and waited for a non-initial Room invalidation. Reconciliation
  could advance the revision before Room finished installing its transient observer. The bounded
  draft correction performs an initial observer emission, immediately revalidates the durable
  revision on every emission, and returns only after the token is `CHANGED` or storage is
  unavailable. It adds no permanent observer, provider, writer, or timeout to production.
- The correction touches the already-dirty
  `RoomStepsNumericSummaryRepository.kt`, `StepsNumericSummaryRepository.kt`, `GoalTracker.kt`,
  and `RoomStepsNumericSummaryRepositoryRoomTest.kt`. Test waits have a test-only 10-second
  timeout, and a deterministic regression settles the durable revision before starting the wait.
- Exact forced focused evidence is green: the two selected
  `RoomStepsNumericSummaryRepositoryRoomTest` methods completed `2/2`, with zero skipped,
  failures, or errors (`BUILD SUCCESSFUL in 5m 16s`, 234 tasks executed). The XML is
  `tracker/engine/build/test-results/testDebugUnitTest/TEST-com.adsamcik.tracker.tracker.source.summary.RoomStepsNumericSummaryRepositoryRoomTest.xml`.
- This is not acceptance of the broad lane. The exact full five-class cohort, app/Game/stats
  focused gates, Detekt, lint, Hilt, Room-schema check, current-byte review, staging, commit,
  rebase, and post-rebase verification remain open.

### Preserved lane 2: portable Steps importer, composition, and no-resurrection

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`; branch
  `codex/ti-steps-portable-import`; exact base HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645`, 14 commits behind the pre-checkpoint parent and
  15 behind the resulting documentation checkpoint, with zero commits ahead.
- Exact state remains 37 tracked modifications plus 16 untracked files, 53 dirty paths total;
  tracked diffstat `+2669/-164`; nothing staged; `git -c core.fsmonitor=false diff --check`
  passes.
- No current-byte Gradle evidence exists. In addition to the gates listed below, focused coverage
  is still needed for imported covered-zero history, replacement-run production composition,
  history observer invalidation, stale/malformed authority and pagination/overflow cases,
  selected-deletion preservation and rollback, exact outside-retention behavior, and the
  codec-to-Room production bridge. The changed 27-to-28 migration still requires a connected
  device test.

### Exact restart order

1. Repeat the mandatory read-only startup checks and full tracking-document read. Verify this
   checkpoint HEAD, both dirty-lane counts, root HEAD and six protected hashes, and that no other
   Gradle process owns the build lease.
2. Keep the importer frozen. In the awards worktree, rerun the complete exact five-class cohort:
   `PriorProcessPresentationReconcilerTest`, `PreviousExitRecoveryCoordinatorTest`,
   `PreviousExitSourceSessionFinalizerTest`, `ForceStopSourceSessionFinalizerTest`, and
   `RoomStepsNumericSummaryRepositoryRoomTest`, forced and serialized.
3. If that cohort is green, run the awards lane's affected app/Game/stats, Detekt, lint, Hilt, and
   Room-schema gates, then obtain a fresh whole-diff review. Only then form coherent exact-path
   commits, rebase onto current local `dev/v10`, repeat proportional gates, and merge locally.
4. After the awards lane releases the sole Gradle lease and converges, rebase/reconcile the
   overlapping importer draft, add its named missing regressions, and run its complete current-byte
   focused, production compilation, static, schema, and fresh-review gates before coherent commits
   and local merge.
5. Run `ciUnitTest` at the combined Steps integration gate and `ciCheck --continue` only at
   integration readiness. Keep the physical manual Steps-only `TYPE_STEP_COUNTER` and listener
   removal scenario explicitly open until a suitable device and walking operator exist.
6. Continue in bounded source-local waves: automatic Steps with explicit control separation;
   default-off ambient Steps only after a real provider/capability decision; Pressure
   history/product/retention/transfer; protected Location attribution/shadow work; then Activity,
   Wi-Fi, and Cell captured-product lanes. Shared schema, history, retention, transfer, app DI/UI,
   rollout, and documentation collision zones retain one integration owner.

## 2026-09-03 end-of-day two-lane source checkpoint

This is the authoritative restart boundary. It supersedes the earlier 2026-09-03 checkpoints for
accepted branch state, preserved draft state, current verification, review findings, and restart
order. Older sections remain historical evidence only.

### Accepted integration and protected state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `59fb05efeb847d0585b36fd2efbadb25db41ea10` (`docs(tracking): checkpoint parallel
  continuation`), 86 commits ahead of `origin/dev/v10`. Resolve this documentation checkpoint's
  own commit with `git rev-parse HEAD`; no self-referential SHA is asserted here.
- The accepted implementation boundary remains Pressure selected-session deletion through
  `61608800e`, `9592c42d8`, and its evidence commit `673c4186e`, plus the checkpoint documentation
  in `59fb05efe`. Neither draft below is accepted, committed, rebased, merged, or published.
- Both implementation lanes are preserved in their named worktrees at exact base HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645`. They are each 13 commits behind the pre-checkpoint
  parent named above and will be 14 commits behind the resulting documentation checkpoint; both
  remain zero commits ahead, unstaged, and intentionally dirty. Both pass
  `git -c core.fsmonitor=false diff --check`.
- All implementation and review agents completed and stopped. The hung awards Gradle invocation
  was interrupted with Ctrl+C after a thread dump established the exact wait site. A final
  `Get-Process -Name java,javaw` returned no process, so the shared Gradle lease is free; recheck
  it before the next build.
- Everything remains local-only. No push, release, tag, deployment, feature/source activation,
  remote flag, destructive or backward-incompatible migration, or external rollout occurred or
  is authorized here.

The detached root checkout remains at exact HEAD
`ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly the same six protected paths and no
additional changes. Their SHA-256 values were rechecked and remain:

- `DatabaseImport.kt`: `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`;
- `DatabaseImportCollisionTest.kt`:
  `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`;
- `DatabaseImportTest.kt`: `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`;
- `TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx`:
  `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`;
- `TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md`:
  `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`;
- `DashboardManualStartDecisionTest.kt`:
  `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6`.

Never alter, stage, commit, clean, reset, or push those six root-checkout paths as part of this
continuation.

### Preserved lane 1: source-qualified awards, goals, and retained metrics

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`; branch
  `codex/ti-steps-qualified-awards`.
- Exact state: 55 tracked modifications plus nine untracked files, 64 dirty paths total; tracked
  diffstat `+4344/-592`; nothing staged; `diff --check` passes.
- The draft now contains the review-driven corrections for source-qualified retained metrics,
  goals, achievements, process-exit presentation settlement, revision invalidation, Activity
  null-admission co-capture, migrated-v27 sentinel isolation, storage retry, subscribed Game
  invalidation, and active-authority validation. It remains unaccepted pending complete gates and
  a current-byte final review.
- The decision-revision singleton defect recorded in the prior checkpoint is corrected: database
  installation initializes the singleton once, trigger bodies are UPDATE-only, and corrupt or
  missing post-open singleton state fails closed. `StepsDecisionRevisionTriggersTest` passes
  `5/5` (`BUILD SUCCESSFUL in 1m 37s`, 79 tasks executed), including ordinary ABORT insert,
  rollback, repeated `daily_summary INSERT OR REPLACE`, missing-singleton, and corrupt-revision
  cases.
- The first corrected five-class tracker run completed 62 tests with 17 failures, all
  `SOURCE_EVIDENCE_UNAVAILABLE`. This exposed a test-fixture error: production database open now
  seeds epoch zero, so the fixture's insert-or-ignore did not change it to the requested epoch.
  The fixture now calls exact lifecycle update after `ensure()`.
- The two observer regressions then passed in isolation (`2/2`, `BUILD SUCCESSFUL in 6m 55s`, 234
  tasks executed) after adding the contract-required confirming read before awaiting settlement.
- The forced full five-class rerun did not complete. After approximately 8m 27s, a read-only
  `jcmd 41368 Thread.print -l` showed Robolectric's `SDK 34 Main Thread` parked in
  `RoomStepsNumericSummaryRepositoryRoomTest.startup settlement wakes terminal Steps without
  another observation or summary write` at line 245, inside `runBlocking`. Ctrl+C ended Gradle
  with exit 1. Because the same regression passes in the isolated pair, treat this as a test-order,
  leaked-observer, or shared-fixture/lifecycle defect; do not claim the five-class gate green.
- The next awards action is to reproduce the parked test in incrementally larger ordered
  selections, inspect observer/database teardown and settlement acknowledgement, and add a bounded
  timeout only as a diagnostic—not as a semantic substitute. Then rerun the exact five-class
  cohort before app, Game, stats, Detekt, lint, Hilt, schema, staging, or review gates.

The exact full cohort is:

1. `PriorProcessPresentationReconcilerTest`;
2. `PreviousExitRecoveryCoordinatorTest`;
3. `PreviousExitSourceSessionFinalizerTest`;
4. `ForceStopSourceSessionFinalizerTest`;
5. `RoomStepsNumericSummaryRepositoryRoomTest`.

Run with `--no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false'
'-Dorg.gradle.vfs.watch=false' --no-configuration-cache --rerun-tasks` and the repository-owned
credentials seam. Keep one global Gradle invocation at a time.

### Preserved lane 2: portable Steps importer, composition, and no-resurrection

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`; branch
  `codex/ti-steps-portable-import`.
- Exact state: 37 tracked modifications plus 16 untracked files, 53 dirty paths total; tracked
  diffstat `+2669/-164`; nothing staged; `diff --check` passes. The three protected root
  `DatabaseImport` paths are untouched in this worktree.
- The lane remains bounded to portable Steps format/codec, inert imported origin/run/manifest/fact
  authority, one-transaction Room import, imported product/history/numeric composition, exact
  selected deletion, correction-safe day repair, retention, and no-resurrection. It creates no
  provider demand, live session, policy, canonical writer, or capture authority.
- All four findings from the prior NO-GO review are source-corrected with focused regressions:
  `PORTABLE_IMPORT` has a truthful Trip Detail mapping and exhaustive enum coverage; imported
  deletion uses an exact 372-key-bounded summary lookup and full candidate-window conflict
  closure; both retention workers immediately transition crossing imported entries and publish
  one evidence revision even with a pre-synchronized floor plus pending WAL; and imported
  retention advances forward-only from `ACTIVE` through `RETENTION_CROSSED` to `RETAINED_OUT`.
- Additional calendar hardening preserves immutable persisted-authority windows, independently
  resolves candidate keys, rejects cross-key physical aliases, permits deterministic same-key
  aliases, rejects skipped Apia civil days with a typed zero-mutation result, and covers sparse and
  UTC/Honolulu spans plus post-lock concurrency. The final nested static review returned GO and
  found no remaining blocker in the frozen source.
- No Gradle command ran after these corrections because the awards cohort owned the sole build
  lease. Consequently the earlier `58/58` result is not evidence for these current bytes. Runtime,
  Room, production compilation, schema identity, Detekt, lint, Hilt, and final current-byte review
  all remain pending; do not stage, commit, rebase, or merge this lane before them.
- The next importer action, after the awards diagnostic releases the lease, is the complete
  current-byte focused importer/history/deletion/repair/retention cohort plus affected production
  compilation. Parse exact XML counts, then run proportional static and Room-schema gates and one
  fresh full-diff review. Rebase only after the awards lane is accepted because both touch v28 and
  shared Steps composition; reconcile the accepted Room exporter and prove imported-authority
  re-export plus native same-database dedup/global-authority race behavior before local merge.

### Exact restart order

1. Repeat the mandatory read-only startup checks and full tracking-document read. Verify this
   checkpoint HEAD, the two exact dirty-lane counts, root HEAD and all six protected hashes, and
   the absence of another Gradle owner.
2. Keep both lanes frozen. Diagnose and close the awards order-dependent hang first; do not weaken
   the production settlement contract or replace evidence with a timeout.
3. Serialize the importer current-byte focused gate after the awards invocation releases Gradle.
   Source is static-review GO but has no post-correction runtime evidence.
4. Complete each lane's focused, Detekt, lint, Hilt, Room-schema, and fresh-review gates; form exact
   dependency-ordered commits only from green coherent chunks. Rebase each completed branch onto
   latest local `dev/v10`, force proportional post-rebase gates, and merge locally only after GO.
5. Run `ciUnitTest` at the next combined integration gate and `ciCheck --continue` only at
   integration readiness. The physical manual Steps-only gate remains open until a real device
   exposes `TYPE_STEP_COUNTER` and an operator can walk it.
6. Continue the remaining source-local program only after these Steps lanes converge: automatic
   Steps with explicit control separation; default-off ambient Steps; Pressure history/product,
   retention, and transfer; protected Location; then Activity, Wi-Fi, and Cell as independent
   source-to-product verticals.

## 2026-09-03 Pressure accepted and parallel drafts stopped checkpoint

This is the authoritative restart boundary. It supersedes the earlier 2026-09-03 checkpoints for
accepted branch state, preserved draft state, review findings, verification, and restart order.
Older sections remain historical evidence only.

### Accepted integration state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `673c4186e04b81a55555be17e63aae222880d892` (`docs(tracking): record Pressure selected
  deletion`), 85 commits ahead of `origin/dev/v10`. Resolve the documentation checkpoint itself
  with `git rev-parse HEAD`; no self-referential commit SHA is asserted here.
- Pressure selected-session deletion is accepted locally in implementation commits `61608800e`
  and `9592c42d8`, with decision/status/verification evidence in `673c4186e`. The merged Pressure
  worktree was removed and its local feature branch deleted after verifying the merge.
- The accepted Pressure slice passed a corrected focused pre-rebase gate (`94/94`), a forced
  post-rebase focused gate (`94/94`), and the post-rebase Detekt, Android-test compilation,
  core/tracker lint, app Hilt compilation, and Room-schema gate. A fresh correction-chain review
  found the original per-manifest consent fan-out; the accepted correction uses one exact capped
  batch per scope pass, and the final review returned GO with no blocker, high, or medium finding.
- This is host, Robolectric, in-memory-Room, static, and schema evidence. It is not physical
  Pressure sensor/FIFO/cadence, device UI, process-death, reboot, FGS, battery/OEM, retention,
  portable transfer, activation, rollout, push, or release proof.
- No implementation draft described below was staged, committed, rebased, or merged at this stop
  boundary. Both pass `git -c core.fsmonitor=false diff --check`; dirty drafts are preserved work,
  not accepted implementation.
- All implementation/review agents are stopped. No `java` or `javaw` process was visible at the
  checkpoint, so the shared Gradle lease is free; tomorrow must still recheck it before building.
- Everything remains local-only. No push, release, tag, deployment, feature/source activation,
  remote flag, destructive migration, or external rollout occurred or is authorized here.

The detached root checkout remains at exact HEAD
`ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly the same six protected paths and no
additional changes. Their SHA-256 values were rechecked at this checkpoint and remain:

- `DatabaseImport.kt`: `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`;
- `DatabaseImportCollisionTest.kt`:
  `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`;
- `DatabaseImportTest.kt`: `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`;
- `TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx`:
  `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`;
- `TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md`:
  `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`;
- `DashboardManualStartDecisionTest.kt`:
  `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6`.

Never alter, stage, commit, clean, reset, or push those six root-checkout paths as part of this
continuation.

### Preserved implementation lanes

Both lanes below are intentionally dirty, unstaged, and uncommitted at exact base HEAD
`a347df9a25e2902b3d9951639f2303e9806f7645` (`docs(tracking): record Pressure fact lane`). Each is
12 commits behind local `dev/v10` and zero commits ahead because its implementation is still in the
working tree. Preserve the worktrees exactly until their named findings are corrected and gated.

#### 1. Source-qualified awards, goals, and retained metrics

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`; branch
  `codex/ti-steps-qualified-awards`.
- State: 55 tracked modifications plus nine untracked files, 64 dirty paths total; tracked diffstat
  `+4329/-590`; nothing staged; `diff --check` passes.
- The draft extends exact source-qualified Steps through retained metrics, achievements, goals,
  progression, and invalidation. It also adds terminal presentation reconciliation and bounded
  revision-trigger invalidation after a fresh review identified incomplete CAS coverage,
  process-death settlement, Activity co-capture admission, migrated-v27 sentinel scope,
  storage-unavailable retry, stale subscribed UI, and premature active discovery. Those semantic
  corrections remain unaccepted until the current database-trigger defect and all gates are green.
- Corrected trigger-construction coverage passes `StepsDecisionRevisionTriggersTest` (`2/2`,
  `BUILD SUCCESSFUL in 1m 44s`, 79 tasks executed). The next tracker gate ran 62 tests: the force-
  stop, previous-exit coordinator, and previous-exit finalizer classes passed `25/25`; the prior-
  process reconciler failed `1/1`, and the Room Steps summary class passed `6/36` with 30 failures.
  All 31 failures have the same root exception:
  `SQLiteConstraintException: UNIQUE constraint failed: source_evidence_state.id`.
- Exact diagnosis: each decision trigger currently executes `INSERT OR IGNORE` for the singleton
  evidence row inside an outer DAO statement. SQLite lets the outer statement's conflict policy
  override the trigger statement. An ordinary Room `@Insert` with ABORT therefore raises the
  singleton conflict, while `daily_summary INSERT OR REPLACE` can replace/reset the singleton and
  violate monotonicity.
- First correction tomorrow: ensure the singleton once during
  `StepsDecisionRevisionTriggers.install()`/database open, then make every trigger body UPDATE-only.
  Missing singleton state after open must fail closed. Strengthen the core regression with an
  ordinary ABORT `source_service_run` insert and a monotonic `daily_summary` REPLACE assertion.
- Then rerun the exact core trigger test and the five-class tracker gate before app, Game, stats,
  Detekt, lint, Hilt, schema, or review gates. Do not stage or commit this lane while that focused
  gate is red.

#### 2. Portable Steps importer, product composition, and no-resurrection

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`; branch
  `codex/ti-steps-portable-import`.
- State: 31 tracked modifications plus 16 untracked files, 47 dirty paths total; tracked diffstat
  `+2285/-152`; nothing staged; `diff --check` passes.
- The draft contains bounded portable-format/codec refinement, inert imported origin/run/manifest/
  fact authority, one-transaction Room import, imported history/numeric composition, selected
  deletion, day repair, retention, and no-resurrection behavior. It does not register provider
  demand or fabricate a native live session, writer, policy, manifest, or projection lane.
- Before the latest static corrections, the focused parity cohort passed `58/58` in
  `BUILD SUCCESSFUL in 6m 19s` (286 tasks). The regenerated v28 Room identity is
  `3aa9a02ebc2c87116e676438b9a4c431`, and the schema includes the exact composite imported
  manifest/run FK index. The current-byte 59-test cohort and affected production compilation have
  not run, so the earlier green result is not acceptance evidence for the present draft.
- Fresh full-diff review is NO-GO with three blockers and one medium finding:
  1. `SegmentSource.PORTABLE_IMPORT` has no exhaustive production mapping in
     `TripDetailPresenterViewModel`, and `SessionlessEnumsTest` still asserts the old enum set.
  2. Selected imported deletion repairs only each imported run's own zone. It must lock the bounded
     plausible-offset envelope, inspect persisted summaries under their stored zones, and repair
     the exact union of required days and overlapping persisted-zone rows. Add the Kiritimati-to-
     Honolulu import-then-delete/no-resurrection regression.
  3. Both retention workers can commit a newer `source_evidence_state.retainedFromMs` and return
     early for pending WAL before advancing imported entry state. Product history must compare the
     current authoritative floor directly, so stale `ACTIVE` can never remain numeric/exportable;
     cover both workers or one genuinely shared invariant plus production composition.
  4. `ImportedStepsAuthorityDao.advanceRetention` updates only `ACTIVE`, preventing a later
     `RETENTION_CROSSED` entry from advancing to `RETAINED_OUT`. Add the two-pass transition test
     and preserve monotonic floor semantics.
- The schema/migration/entity sub-review otherwise returned GO: FKs, indexes, additive v27-to-v28
  ordering, deletion order, bounded admission, query paging, and the generated Room hash are
  coherent. The atomic importer is also acceptable in isolation; the complete stack is not.
- After those findings pass focused compilation/tests, obtain a fresh full-diff review. Rebase only
  then onto current local `dev/v10`, reconcile the accepted Room exporter, and add imported-
  authority re-export plus native same-database dedup/global-authority preflight and race coverage.
  Do not invent a registry, generic materializer, or imported provider authority.

### Exact restart and convergence order

1. Repeat the mandatory read-only startup checks and full tracking-document read. Verify this
   checkpoint HEAD, both dirty lanes, the detached root HEAD, all six protected hashes, and the
   single Gradle lease before editing or building.
2. Correct awards and importer findings in parallel within their separate worktrees. Keep one
   owner per overlapping file and serialize all Gradle invocations globally.
3. For awards, repair the singleton-install/UPDATE-only trigger invariant first, rerun the two
   named gates, then complete app/Game/stats focused gates, Detekt, affected lint/Hilt/schema,
   current-byte review, exact coherent commits, rebase, forced post-rebase gates, and local
   fast-forward only after GO.
4. For importer, fix all four review findings and production compilation first; rerun the current
   59-test cohort and proportional static/schema gates, obtain GO, make dependency-ordered commits,
   rebase after awards because both touch v28/shared Steps composition, reconcile exporter/dedup,
   force post-rebase gates, and merge locally only when clean and accepted.
5. Run `ciUnitTest` at the next meaningful combined integration gate and `ciCheck --continue` only
   at integration readiness. Keep the physical manual Steps-only provider/listener gate open until
   a suitable real device supplies `TYPE_STEP_COUNTER` evidence.
6. Continue the remaining program in dependency order: automatic Steps with explicit control
   separation; default-off ambient Steps; Pressure history/product, retention, and portable
   transfer; protected Location; then Activity, Wi-Fi, and Cell as independent source-to-product
   verticals. Preserve canonical Location and all hard privacy, lifecycle, battery, and writer
   boundaries throughout.

## 2026-09-03 portable-export accepted restart checkpoint

This is the authoritative restart boundary. It supersedes the earlier 2026-09-03 end-of-day
checkpoint for branch tips, accepted implementation, dirty-lane state, review findings,
verification, and restart order. Older sections remain historical evidence only.

### Accepted integration state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `e8d514f3f2a94d3576e18db0ee9fdc68194b3c03` (`docs(tracking): record portable Steps Room
  export`), 80 commits ahead of `origin/dev/v10`. Resolve the documentation checkpoint itself with
  `git rev-parse HEAD`; no self-referential commit SHA is asserted here.
- The portable Steps Room export slice is accepted in commits `fd32fab2b`, `d9771257e`,
  `47f594f09`, and `6202d16a8`; decision/status/verification evidence is recorded by
  `e8d514f3f`. The merged exporter worktree and local branch were removed.
- Accepted export behavior is bounded to one exact candidate Steps logical entry and validates
  reciprocal run/segment binding, complete immutable authority, replacement grouping, retained
  floors, settlement, global correction lineage, per-run correction bounds, and full retained
  `LIVE_WAL` integrity before signing portable output. It emits nothing until the complete bounded
  snapshot validates. The v28 candidate lane remains unshipped and inactive.
- Post-rebase host evidence is 15/15 `StepFactRevisionDaoTest`, 1/1
  `StepFactRevisionIntegrityTest`, 17/17 `StepsSessionFactProjectionLaneTest`, and 34/34
  `RoomExportPortableStepsTest`: 67 tests, zero failures, errors, or skips. Root Detekt,
  `:stats:data:lintDebug`, `:app:hiltJavaCompileDebug`, and `checkRoomSchemaDrift` were green.
  This is host/static/schema evidence, not provider, process-death, reboot, FGS, battery, OEM, or
  device-UI proof.
- The exact device gate remains
  `app/src/androidTest/java/com/adsamcik/tracker/app/tracking/ManualStepsOnlyDeviceGateTest.kt`.
  `adb devices -l` found no attached device. Although the `Medium_Phone` AVD exists, that test
  requires an exposed physical `TYPE_STEP_COUNTER` and operator walking, so the manual Steps-only
  provider/listener gate remains genuinely device-blocked.
- All three implementation agents returned checkpoint results and stopped. The shared Gradle lease
  is released. Three Java processes remained visible, but Windows denied process-command-line
  inspection; tomorrow must still obtain the ordinary single-Gradle lease before any build.
- Everything remains local-only. No push, release, tag, deployment, feature/source activation,
  remote flag, destructive migration, or external rollout occurred or is authorized here.

The detached root checkout remains at exact HEAD
`ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly the same six protected paths and no
additional changes. Their SHA-256 values were rechecked at this checkpoint and remain:

- `DatabaseImport.kt`: `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`;
- `DatabaseImportCollisionTest.kt`:
  `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`;
- `DatabaseImportTest.kt`: `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`;
- `TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx`:
  `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`;
- `TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md`:
  `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`;
- `DashboardManualStartDecisionTest.kt`:
  `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6`.

Never alter, stage, commit, clean, reset, or push those six root-checkout paths as part of this
continuation.

### Preserved implementation lanes

All three lanes below are intentionally dirty, unstaged, uncommitted, and based at exact HEAD
`a347df9a25e2902b3d9951639f2303e9806f7645`. Each passes
`git -c core.fsmonitor=false diff --check`. Preserve them exactly; a dirty lane is not accepted
implementation.

#### 1. Typed selected-session Pressure deletion — one focused regression

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-pressure-deletion`; branch
  `codex/ti-pressure-deletion`.
- State: eight tracked modifications (`+432/-84`) and six untracked files. The draft now includes
  deterministic keyset batching beyond 2,048 facts, the matching v28 scope index and migration
  assertion, complete run/manifest/policy/consent/fact authority, source-local integrity, exact
  fence/delete/repair behavior, and focused tests. The generated Room v28 schema hash is
  `37eb710526dc9b550e9208d7713dd851`.
- Latest focused run compiled production and test sources. Core reported 8/8 green; tracker reported
  80/81 green. The sole failure is
  `materializing Steps survivor returns retryable before Pressure deletion authority`: expected
  `RetryableFailure(DAY_REPAIR_MATERIALIZING)` but received
  `UnsupportedScope(DAY_REPAIR_UNVERIFIABLE)` at
  `RoomPressureSelectedSessionDeletionServiceTest.kt:444`.
- Tomorrow first compare that fixture's lifecycle/authority with
  `StepsDailySummaryRepairComposer`. Correct only a demonstrated fixture or composer defect, rerun
  the isolated test, then rerun the full serialized focused selection. No coherent Pressure commit
  is ready while this regression is red.

#### 2. Source-qualified awards/goals/retained metrics — review-blocked coherent draft

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`; branch
  `codex/ti-steps-qualified-awards`.
- State: 27 tracked modifications (`+1731/-437`) and three untracked files. A just-started,
  incompatible two-file period-signature edit was rolled back before stopping, leaving the prior
  coherent draft intact.
- Implemented draft behavior includes qualified retained lifetime/best-day reads, missing versus
  verified-zero semantics, source-only run/fact day discovery with stale-`Ready` preflights,
  partial achievement availability, removal of raw streaming Steps from achievement metrics, and
  settings/calendar generation-CAS guards. Generic PERFECT_WEEKS and GOAL_STREAK output remains
  intentionally unavailable because legacy GOAL XP lacks correction-safe qualified provenance.
- Prior focused evidence (tracker 28/28 and game 21/21) predates the latest review corrections and
  is not current-byte acceptance evidence.
- Six review blockers remain, in dependency order: (1) locale/week-start `WeeklyStepGoal` v2 report
  identity with conservative legacy ISO handling and Sunday/year-boundary tests; (2) remove
  `collectLatest` cancellation loss and make completion/report side effects retry-safe; (3) carry
  one checked timestamp/day/week/zone/report authority through mutation and completion; (4) add a
  `service_run_id` plus operation Step-fact index to v28 with migration/query-plan evidence; (5)
  classify null completion from lifecycle as active materializing versus terminal/inconsistent
  unavailable; and (6) add explicit orphan-UPSERT and source-without-manifest regressions.

#### 3. Portable Steps importer storage seam — review NO-GO

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`; branch
  `codex/ti-steps-portable-import`.
- State: 11 tracked modifications (`+355/-94`) and four untracked files. No new Gradle run was
  started during this checkpoint.
- The draft contains the portable run checksum, v28 origin/replay table and DAO, inert import
  provenance, nullable portable fact shape, full-deletion clearing, migration/schema coverage, and
  focused tests. This remains storage scaffolding; an accepted transactional Room importer and
  read/re-export/deletion product integration do not yet exist.
- Latest review remains NO-GO. Required corrections are: (1) entry-wide globally distinct,
  monotonic manifest revisions across replacement runs; (2) one document-wide identity namespace
  across entry/run/fact kinds; (3) enforceable parent integrity plus transactional complete-
  hierarchy validation and rollback; (4) real complete-hierarchy retention, deletion/reopen, and
  populated v27-to-v28 migration tests; and (5) fixed-size lookup chunks, positive bounded child
  limits, cancellation checks, and ignored-insert conflict revalidation.
- Preserve the integrated full `LIVE_WAL` checksum semantics when this old-base branch is eventually
  rebased. Never modify the protected legacy `DatabaseImport` bridge or fabricate local provider
  authority for imported facts.

### Exact restart and convergence order

1. Repeat the mandated startup checks and full document read. Recheck integration, root, and all
   three lanes with `core.fsmonitor=false`; verify the six protected hashes and obtain the sole
   Gradle lease before building.
2. Resume all three lanes only within their listed review boundaries. Source editing may proceed in
   parallel, but Gradle remains globally serialized.
3. Close Pressure's one regression first, rerun its full focused and proportional
   static/Hilt/schema gates, obtain a fresh read-only review, make exact coherent commits, rebase
   onto current local `dev/v10`, force the relevant post-rebase gates, and merge locally with
   `--ff-only` only after GO.
4. Pressure and awards both modify `StepsDailySummaryRepairComposer`; all three lanes modify or may
   modify the unshipped v28 migration/schema. Therefore integrate serially. After Pressure, rebase
   awards, regenerate/recheck v28 evidence, close all six findings, review, gate, and only then
   merge. Repeat for portable import; do not integrate storage scaffolding without its actual
   bounded transactional importer and complete no-resurrection behavior.
5. Run `ciUnitTest` at the next meaningful combined integration gate and `ciCheck --continue` only
   at integration readiness. Keep device-only claims open until one representative suitable
   physical device supplies the required evidence.
6. Then continue the remaining source-local dependency order: complete Steps deletion/day repair,
   portable import/no-resurrection and numeric consumers, automatic Steps control separation, and
   default-off ambient Steps; finish Pressure history/product/retention/export; then protected
   Location, Activity, Wi-Fi, and Cell as independent thin verticals.

## 2026-09-03 end-of-day checkpoint

This is the authoritative local restart boundary. It supersedes the 2026-09-02 parallel-work
checkpoint for current branch tips, dirty state, verification, review findings, and restart order.
The older sections remain durable design and historical evidence.

### Accepted integration and stop state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Local integration branch: `dev/v10`. Its clean pre-checkpoint parent is exact HEAD
  `8c502b251889a605a1053461b7e51e856fe1593a` (`docs(tracking): checkpoint parallel
  continuation`), 74 commits ahead of `origin/dev/v10`. Resolve the documentation checkpoint
  itself with `git rev-parse HEAD`; no self-referential commit SHA is asserted here.
- No implementation branch described below was staged, committed, rebased, or merged during this
  stop operation. The last accepted product code remains the source-qualified Steps numeric
  presentation work and dormant append-only Pressure fact lane already recorded below.
- All implementation agents were stopped after returning their checkpoint/review results. No new
  Gradle run was started for the stop operation. A process snapshot showed no visible `java` or
  `javaw` process; process-command-line enumeration was denied, so tomorrow must still perform the
  ordinary single-Gradle-lease check before running a build.
- Every preserved implementation worktree and the integration worktree passed
  `git -c core.fsmonitor=false diff --check`. Plain Git may emit the known fsmonitor daemon error;
  keep the explicit `-c core.fsmonitor=false` override for authoritative checks.
- Everything remains local-only. No push, release, tag, deployment, source activation, remote flag,
  destructive migration, or external rollout occurred or is authorized by this checkpoint.

The detached root checkout remains at exact HEAD
`ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly the six protected paths and no additional
changes. Their SHA-256 values were rechecked on 2026-09-03 and remain byte-for-byte identical:

- `DatabaseImport.kt`: `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`;
- `DatabaseImportCollisionTest.kt`:
  `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`;
- `DatabaseImportTest.kt`: `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`;
- `TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx`:
  `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`;
- `TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md`:
  `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`;
- `DashboardManualStartDecisionTest.kt`:
  `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6`.

Never alter, stage, commit, clean, reset, or push those six root-checkout paths as part of this
continuation.

### Preserved implementation lanes

All four lanes are intentionally dirty, unstaged, and restartable. Do not discard, clean, reset,
overwrite, or treat their existence as accepted implementation.

#### 1. Portable Steps exporter correction — fresh review NO-GO

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-export`.
- Branch/HEAD: `codex/ti-steps-portable-export` at
  `34bc50522fb0c5321e832529dfbfd01b6d2e7a7a`.
- Branch relation at the stop boundary: 11 commits behind and 2 commits ahead of local `dev/v10`.
- Dirty paths are exactly `TrackingHistoryReadDao.kt`, `PortableStepsRoomReader.kt`, and
  `RoomExportPortableStepsTest.kt`.
- Current focused evidence is 30/30 `RoomExportPortableStepsTest` and 14/14
  `StepFactRevisionDaoTest`, followed by green root Detekt plus `:stats:data:lintDebug`, and green
  `:app:hiltJavaCompileDebug checkRoomSchemaDrift`. Those results are host/static/schema evidence,
  not device or round-trip proof.
- A fresh static adversarial review nevertheless returned NO-GO with two HIGH findings:
  1. Fact membership follows the globally latest UPSERT, but the export read scopes latest state and
     revision history inside selected runs. A fact first written in selected run R1 and later moved
     by correction to unselected run R2 can silently disappear from R1's export and evade the
     per-run correction bound.
  2. The exporter does not validate the retained `effect_checksum` over immutable fact semantics
     before calculating a new portable checksum. A raw mutation such as changed
     `wall_time_uncertainty_ms` with the old checksum could be laundered into a newly signed portable
     fact.
- Required tests before another review: selected R1 revision followed by globally latest unselected
  R2 correction (including a bounded-lineage/correction-cap variant), and raw-SQL mutation of a
  writer-checksummed field with unchanged `effect_checksum`. Both must emit nothing and fail closed.
- The reviewer otherwise accepted the source-local candidate traversal, reverse binding,
  executable-lane authority, strict retention edge, nullable/repeated completeness high-water,
  cancellation/pre-emission snapshot, absence of `sampleCount` inference, and absence of provider
  demand. Do not commit or merge until the two HIGH findings are corrected, tests pass, and a fresh
  reviewer returns GO.

#### 2. Portable Steps importer storage seam — uncompiled draft

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`.
- Branch/HEAD: `codex/ti-steps-portable-import` at
  `a347df9a25e2902b3d9951639f2303e9806f7645`, one documentation commit behind local `dev/v10`.
- Eight tracked modifications plus four untracked files add the v28 `portable_steps_origin`
  replay/no-resurrection map, DAO/migration coverage, run checksum, inert import writer identity,
  portable fact shape, and focused tests. `RoomImportPortableSteps` does not yet exist.
- The only attempted test command failed during Gradle task selection in 15 seconds because
  `:stats:api:androidHostTest` is not a task. No importer source compiled and no importer test ran.
  The fixed-vector run checksum expected by the new test was independently reproduced as
  `sha256:9bf73383055d5ef121498101721c812d6802c79da4cb24f0bdbcc369ad77e7f8`, but Kotlin has not
  verified it.
- Tomorrow first inspect `:stats:api:tasks --all` and select the actual Android-host test task.
  Then run the portable-format test plus `PortableStepsOriginDaoTest`,
  `PortableStepFactRevisionEntityTest`, and `LegacyV26ImportTest`; inspect any generated v28 schema
  diff before proceeding.
- Do not fabricate local provider policy, QoS, acquisition plan, boot/elapsed clock, or a product
  projection lane for imported facts. Do not touch the protected legacy `DatabaseImport` bridge.

#### 3. Source-qualified awards/goals/retained metrics — post-review draft

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`.
- Branch/HEAD: `codex/ti-steps-qualified-awards` at
  `a347df9a25e2902b3d9951639f2303e9806f7645`, one documentation commit behind local `dev/v10`.
- The tree has 24 tracked modifications and three untracked files, confined to `:core:base`,
  `:stats:api`, `:stats:data`, `:stats:engine`, `:tracker:engine`, and `:feature:game`. Exact paths are
  visible in `git status --short`; do not stage a subset before the semantic blockers below are
  resolved.
- Accepted draft semantics so far: retained lifetime/best-day reads use source-qualified facts with
  bounded 370-day authority batches and 256-row fact paging; verified zero remains distinct from
  unavailable; raw streaming Steps is removed from achievement metrics; `GoalTracker` accepts only
  typed `Ready`. A materializing Steps metric now carries safe non-Steps `availableMetrics` plus
  exact `blockedMetrics`, allowing unrelated achievements to persist while affected instances retry.
  PERFECT_WEEKS and GOAL_STREAK_DAYS were removed from provider output because legacy GOAL XP rows
  are not qualified, correction-safe Steps provenance; those rows are preserved, not relabelled.
- The earlier 825-test green focused gate covered the pre-review bytes only. Review corrections were
  made afterward, so current-byte evidence is only `git diff --check`; do not cite the historical
  gate as current verification.
- Four blocking findings remain, in restart order:
  1. Discover qualified run/fact days without a `daily_summary`; otherwise a newer source-only day
     can leave a stale retained `Ready` value.
  2. Fence suspended `GoalTracker` reads against exact settings and daily/weekly calendar authority.
  3. Unify locale-week reads with ISO persisted-report identity, including Sunday/year boundaries
     and compatible fail-closed legacy handling.
  4. Design durable correction/deletion-aware qualified goal-day, zone, and configuration provenance
     before re-enabling PERFECT_WEEKS or GOAL_STREAK_DAYS; never relabel generic GOAL XP rows.
- Cancellation propagation, current paging bounds, and missing-versus-verified-zero behavior passed
  static review. Session XP (`trip.steps ?: 0`), `PointsWorker` raw trip Steps, and the presently
  unused raw `DefaultWindowedMetricsProvider` remain explicit later audit items.

#### 4. Typed selected-session Pressure deletion — compile-blocked draft

- Worktree: `G:\Github\Tracker-Android\.worktrees\ti-pressure-deletion`.
- Branch/HEAD: `codex/ti-pressure-deletion` at
  `a347df9a25e2902b3d9951639f2303e9806f7645`, one documentation commit behind local `dev/v10`.
- Seven dirty paths hold the typed API, bounded Pressure fact-scope DAO, exact transactional
  delete/fence, source-aware day repair, Hilt binding, and focused service tests. The tests include
  isolated control-only and mixed-capture missing-segment fail-closed cases plus a SQLite mutation
  failure proving rollback and successful retry.
- Production code compiled and `PressureFactRevisionDaoTest` passed 5/5. Tracker test compilation
  stopped before execution on seven Kotlin warning-as-error intersection-type assertions in
  `RoomPressureSelectedSessionDeletionServiceTest.kt` at lines 1021, 1025, 1035, 1043, 1100, 1116,
  and 1126. No post-failure assertion edit was made.
- Tomorrow change only those calls to explicit `arrayOf<Any>(...)`, run `diff --check`, then under
  the shared Gradle lease run:

  ```powershell
  .\gradlew.bat :tracker:engine:testDebugUnitTest `
    --tests "*RoomPressureSelectedSessionDeletionServiceTest" `
    --no-parallel --max-workers=1 -Dorg.gradle.vfs.watch=false
  ```

- Preserve exact reverse binding, immutable manifest/policy/consent/fact authority, fence-before-
  delete, and transaction rollback. Never use `QUIESCED`, wall-time overlap, or a generic tombstone
  platform as ownership/deletion authority.

No Pressure history/product worktree exists. Do not create it until the portable-export DAO overlap
has been corrected, accepted, rebased, and merged.

### Exact restart sequence

1. Read applicable `AGENTS.md` and the tracking documents in their mandated order. Re-run
   `status --short --branch`, `rev-parse HEAD`, recent log, and `diff --check` in integration, root,
   and all four lanes; recheck the six protected hashes. Confirm no Gradle wrapper build is active.
2. Fix and re-review the two exporter HIGH findings first. After focused/static/schema gates, stage
   only its three reviewed paths, run cached diff-check, commit with the configured `adsamcik`
   identity, rebase onto latest local `dev/v10`, rerun forced relevant gates, and merge locally with
   `--ff-only`. Update the decision/status/verification ledgers in a separate accepted-doc commit.
3. In parallel, resume Pressure deletion with only the seven assertion type fixes and its focused
   gate; resume awards with source-only day discovery before the other three semantic blockers; and
   discover the importer's real host-test task before changing importer code. Serialize all Gradle
   invocations globally.
4. Do not commit/rebase/merge any lane merely to make it clean. Require focused behavior tests,
   proportional static/Hilt/schema evidence, fresh review at the storage/product boundary, exact
   staging, and `git diff --cached --check` for every coherent accepted chunk.
5. Only after the existing overlapping lanes converge may Pressure history/product start. Then
   continue Pressure, protected Location, Activity, Wi-Fi, and Cell as independent thin verticals;
   preserve the rollout and device-evidence boundaries below.

## 2026-09-02 local parallel-work checkpoint

This section was the authoritative restart boundary on 2026-09-02. It is retained as historical
evidence, but its statements about the then-current branch tip, verification, and next task are
superseded by the 2026-09-03 checkpoint above.

### Accepted integration state

- Integration worktree: `G:\Github\Tracker-Android\.worktrees\tracking-infra-integration`.
- Branch: local `dev/v10` at exact HEAD
  `a347df9a25e2902b3d9951639f2303e9806f7645` (`docs(tracking): record Pressure fact lane`).
- The integration worktree is clean. Local `dev/v10` is 73 commits ahead of `origin/dev/v10`.
  These continuation commits are local-only; no push, release, tag, deployment, source activation,
  or external rollout is claimed or authorized.
- Accepted since the prior handover are source-qualified Steps numeric presentation consumers
  (`39afb9fe4`, `3e727f255`, documentation `0d562cee2`) and the dormant append-only Pressure session
  fact lane (`32bfbdafe`, `42b3e1d7b`, `489068b34`, `db2a460a3`, documentation `a347df9a2`).
- No portable importer/exporter correction, qualified awards change, or Pressure deletion change
  described below has been staged, committed, rebased, or merged.

The detached root checkout remains intentionally dirty at `ffd5d372f` with exactly the six protected
paths named later in this handover. Their SHA-256 values were rechecked at this checkpoint and remain:

- `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`
  — `DatabaseImport.kt`;
- `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`
  — `DatabaseImportCollisionTest.kt`;
- `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`
  — `DatabaseImportTest.kt`;
- `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`
  — `TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx`;
- `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`
  — `TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md`;
- `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6`
  — `DashboardManualStartDecisionTest.kt`.

Never stage, alter, commit, or push those root-checkout paths during this continuation.

### Preserved worktrees at the stop boundary

All four implementation worktrees below are intentionally dirty and uncommitted. Every tree passed
`git diff --check`; none should be discarded, reset, cleaned, or overwritten. Re-run startup checks
and review the existing diff before editing.

1. Portable Steps export correction
   - Worktree `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-export`, branch
     `codex/ti-steps-portable-export`, HEAD `34bc50522fb0c5321e832529dfbfd01b6d2e7a7a`.
   - Three unstaged paths: `TrackingHistoryReadDao.kt`, `PortableStepsRoomReader.kt`, and
     `RoomExportPortableStepsTest.kt`.
   - The diff adds complete live-fact/manifest validation, executable product-lane authority,
     exact retention-boundary semantics, valid nullable/repeated completeness high-water handling,
     and source-filtered bounded candidate discovery. Adversarial tests cover 16,385 unrelated
     Activity runs, malformed unrelated counters/attribution, and an unbound materializing Steps run.
   - Last run compiled all 30 tests and passed 29. The failure exposed a reverse-binding result
     regression (`SOURCE_EVIDENCE_UNAVAILABLE` instead of
     `CAPTURE_ATTRIBUTION_UNVERIFIABLE`). The query was then corrected to discover both run-owned
     and segment-claimed evidence-qualified Steps relationships. That final three-file diff is
     check-clean but has not been rerun.

2. Portable Steps import storage seam
   - Worktree `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import`, branch
     `codex/ti-steps-portable-import`, HEAD `a347df9a25e2902b3d9951639f2303e9806f7645`.
   - Twelve unstaged/untracked paths add a v28 `portable_steps_origin` identity/replay map, DAO and
     migration coverage, exact physical-run checksums, an inert portable-import writer contract,
     portable fact shape, and focused entity/DAO tests.
   - This is storage scaffolding only. It has not compiled or run, no v28 schema JSON has been
     accepted, and `RoomImportPortableSteps` does not yet exist. It must not activate a provider or
     product projection lane, fabricate unavailable Steps as zero, or modify the protected legacy
     `DatabaseImport` bridge.

3. Source-qualified awards, goals, streaks, and retained metrics
   - Worktree `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards`, branch
     `codex/ti-steps-qualified-awards`, HEAD `a347df9a25e2902b3d9951639f2303e9806f7645`.
   - The dirty diff is confined to `:core:base`, `:stats:api`, `:stats:data`, `:stats:engine`,
     `:tracker:engine`, and `:feature:game`, with focused tests. It replaces raw daily-summary Steps
     authority in the direct retained-metric/goal/achievement paths with qualified typed values.
   - The focused host invocation passed: `BUILD SUCCESSFUL in 5m`, 408 actionable tasks (237
     executed, 171 from cache). XML totals were 320 passing `:stats:api` tests, 447 passing
     `:stats:engine` tests, 13 passing DailySummary DAO tests, 21 passing tracker summary tests,
     14 passing stats-data achievement tests, and 10 passing game goal tests; zero failures,
     errors, or skips. Static gates, full semantic review, commit, rebase, and post-rebase rerun
     remain outstanding.
   - Session-XP qualification remains a deliberately separate next slice; the unused raw
     `DefaultWindowedMetricsProvider` Steps path also remains an explicit audit item.

4. Typed selected-session Pressure deletion
   - Worktree `G:\Github\Tracker-Android\.worktrees\ti-pressure-deletion`, branch
     `codex/ti-pressure-deletion`, HEAD `a347df9a25e2902b3d9951639f2303e9806f7645`.
   - Seven dirty paths draft the typed API, bounded fact-scope DAO, transactional exact-run
     deletion/fencing, source-aware day repair, Hilt binding, and focused tests.
   - No Gradle command has run. Compilation, Detekt, semantic review, SQLite failure/retry coverage,
     an isolated control/mixed-source regression, and dedicated Hilt injection evidence remain.
     The implementation must continue to fail closed on exact run reverse binding and cross-scope
     revisions; it must never use `QUIESCED` or wall-time overlap as an ownership predicate.

No Pressure history/product worktree was created. Do not begin it until the portable-export DAO
correction is accepted and merged, because those paths overlap.

### Exact restart order

1. Confirm all paths, heads, statuses, `git diff --check` results, and protected hashes above. Check
   that no Gradle wrapper build is active before granting the single shared Gradle lease.
2. Finish the portable-export gate first: rerun its 30-case focused suite, then the 14-case
   `StepFactRevisionDaoTest`, root `detekt` plus `:stats:data:lintDebug`, and
   `:app:hiltJavaCompileDebug checkRoomSchemaDrift`. Resolve the source-local orphan-test semantics,
   obtain a fresh review GO, stage exact paths, run `git diff --cached --check`, commit, rebase onto
   latest local `dev/v10`, repeat forced relevant tests, and merge locally with `--ff-only`.
3. Validate the importer storage seam in coherent commits before writing the importer service:
   portable-format tests, new entity/DAO tests, legacy migration tests, Android-test compilation,
   Detekt/lint, Hilt compile, generated v28 schema review, schema drift, and a fresh storage review.
4. Resume the qualified-awards branch from the exact test result recorded above. Review direct
   numeric authority, cancellation and bounded reads before committing/rebasing/merging. Keep
   session XP separate unless a tiny existing typed contract proves it belongs in this slice.
5. Compile and review Pressure deletion before trusting its broad draft tests. Add the two missing
   focused failure cases, then run focused DAO/service/day-repair tests and proportional static,
   Hilt, schema, and integration gates before any commit or merge.
6. Only after those overlapping gates converge should a fresh Pressure history/product worktree be
   created from latest local `dev/v10`. Continue the source order and rollout constraints already
   recorded in the execution plan; do not start another horizontal framework.

Use the authenticated Gradle seam without printing the token: repository `.gradle` as
`GRADLE_USER_HOME`, `adsamcik` as `GITHUB_ACTOR`, and an in-memory `gh auth token` value as
`GITHUB_TOKEN`, cleared immediately after each invocation. Use `--no-daemon --no-parallel
--max-workers=1 -Pksp.incremental=false` for these serialized checkpoint gates. A passing host gate
is not device, provider, process-death, reboot, foreground-service, battery, OEM, or visual evidence.

Read these sources in order before changing code:

1. repository `AGENTS.md` files applicable to the files being changed;
2. `docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md`;
3. `docs/tracking-infrastructure/ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md`;
4. this handover;
5. `EXECUTION_PLAN.md`, `DECISIONS.md`, `IMPLEMENTATION_STATUS.md`,
   `VERIFICATION_MATRIX.md`, and `ROLLOUT_RUNBOOK.md`.

## Outcome

The two previously divergent histories have been semantically reconciled without discarding either
line of work. The remote release/quality/recovery work and the local tracking-infrastructure program
now share one ancestry. The final reconciliation and delivery facts are:

- first reconciliation merge: `1a112bbd55d36b6d7256713f61787de1fee72fe1`;
- final local-tracking merge: `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a`;
- authoritative full-repository result at that exact code commit: `BUILD SUCCESSFUL in 21m 46s`;
  1,991 actionable tasks, 224 executed and 1,767 up-to-date;
- remote delivery result: an ordinary push advanced `origin/dev/v10` from `4ab17fc9c` to handover
  preparation commit `cd9cecb7405ec672afe735a76f946af5fdc48d90`; post-push fetch showed local
  and remote divergence of `0 0`, and both former tips plus `9b8ab4b43` are ancestors. This
  evidence-only follow-up deliberately does not self-attest its future remote SHA; after fetching,
  resolve the delivered tip with `git rev-parse origin/dev/v10`. No force push or history rewrite
  was used for the verified delivery.

The implementation is still intentionally contained. This reconciliation does **not** mean the
tracking-infrastructure program, manual Steps, automatic Steps, ambient Steps, another source,
product history, UI, device rollout, or general availability is complete. It preserves the critical
path and adds a tested, explicit Steps writer-transition mechanism; it does not invoke the first
candidate activation in production.

The current local continuation production code tip is `9aeb8853a`, followed by test-contract
correction `d067704a9`, on `codex/ti410-deletion-rearm`. Its local
sequence is deletion/re-arm proof `3b2365547`, evidence handover `411bfe144`, the observable Steps
history facade `c5118e186`, its handover `d6e887d41`, the dormant source-deletion fence `03bbda2f1`,
its handover `bfe2e1f36`, unsafe empty-session cleanup containment `88309387d`, remaining zero-sample
product-read exclusion `24b9aeffb`, its handover `412882194`, the contained read-only Trip Detail
Steps consumer `f9b1c3c45`, its handover `dba200183`, exact service-run presentation settlement
`9aeb8853a`, and its full-suite test correction `d067704a9`, directly atop local and remote `dev/v10` at
`ffd5d372fafafceb7d9d595b95e47b89b949de83`. TI-B160 records the retained-AVD deletion proof;
TI-D108/TI-B162 record the one-selected-session read contract; TI-D109/TI-B163–TI-B165 record the
fresh R1 deletion review, scope withdrawal, dormant authority, and focused gates; TI-D110/TI-B166–
TI-B167 record the cleanup race, containment, and corrected product-read evidence; TI-D111 records
the source-local, product-first next-wave correction; TI-D112/TI-B168 record the contained Trip
Detail consumer and its adversarial boundary; TI-D113/TI-B169–TI-B170 record exact per-run segment
ownership, post-writer quiescence, the stale-test correction, and the passing full host aggregate.
These commits and this handover update are local-only:
they are not integrated into local `dev/v10` or pushed, and no new remote delivery is claimed.

## Exact presentation-settlement checkpoint

### Outcome

`9aeb8853a` binds each physical `source_service_run` to exactly one presentation segment and records
when the existing session/Ski presentation pipeline can no longer mutate it. One logical tracking
entry may therefore own multiple physical segments across replacement runs. Same-run recovery
resumes only the exact Room binding; a new run clears the descriptor mirror and creates a new
segment. Room is authoritative and the DataStore descriptor is an atomic exact-CAS recovery mirror.

Shutdown now carries an exact binding receipt across retries and acknowledges `QUIESCED` only after
the session final row, processing pipeline, Ski writer, ancillary components, and summary attempt
have stopped, and only after the bound run is terminal (`FINALIZED` or `FAILED`). Migrated v27 rows
are `LEGACY_UNVERIFIABLE`. Missing/mismatched ownership stays unacknowledged and is diagnosed without
tracked payload; it does not permanently keep already-stopped providers alive. No row is reclaimed.

`QUIESCED` is deliberately narrow: it means no future write from the existing presentation pipeline.
It does not mean the row is empty, retained, materialized, complete, queryable, or deletable.
Wi-Fi/Cell-only evidence is outside `SessionTrackerComponent`, so no cleanup predicate may infer
absence from that component's counters. The extra generic `NEEDS_SOURCE_EVIDENCE` state proposed by
one reviewer was deferred because no all-source evaluator or consumer exists; code comments, tests,
and the absence of deletion preserve the same fail-closed result without unused platform state.

### Evidence

- Code commit: `9aeb8853a feat(tracking): bind presentation lifecycle to service runs`.
- Focused lifecycle/component/store/orchestrator/crash suite: `BUILD SUCCESSFUL in 4m 20s`; 230
  actionable tasks.
- Focused core DAO and tracker API descriptor suites pass; Android migration-test Kotlin compiles.
- Root Detekt: `BUILD SUCCESSFUL in 13s`.
- `:tracker:engine:lintDebug :core:base:lintDebug :tracker:api:lintDebug --no-parallel`:
  `BUILD SUCCESSFUL in 2m 19s`; 383 tasks; no new issue.
- Post-commit `checkRoomSchemaDrift --no-parallel`: `BUILD SUCCESSFUL in 8s`; 46 tasks.
- The first repository-wide host aggregate exposed one obsolete test that still expected a
  zero-cycle row to be deleted. `d067704a9` corrects the assertion to retain the row until an
  all-source evaluator exists; focused `MultiSessionLifecycleTest` passes in `2m 7s` (234 tasks).
- Corrected `ciUnitTest --no-parallel`: `BUILD SUCCESSFUL in 7m 2s`; 990 tasks, 6 executed and 984
  up-to-date. Full `ciCheck` was not rerun at this checkpoint.
- No device is attached, so this revision's v27→v28 migration instrumentation test is compiled but
  not executed. This is not device, provider, process-death, reboot, or energy evidence.
- Three adversarial perspectives accepted the narrow Room authority and no-delete boundary. Their
  surviving product finding is that history still exposes physical rows until logical-entry
  grouping exists; the Steps selector also needs the new-v28 reverse-binding check.

### Required next boundary

First make new-v28 Steps history require `serviceRun.sessionSegmentId == segment.id`, while retaining
a typed blocked result for migrated unverifiable rows. Then add an exact capture-set/qualified-source
index that makes sole-source sessions discoverable without treating `sample_count` as source proof,
and group replacement-run segments by logical entry in the shared product composition. Only after
those read contracts are stable should the Steps-local attribution/fence and typed selected-deletion
command proceed. Never use `QUIESCED` as a deletion predicate, and do not add a universal tombstone,
generic materializer platform, or physical orphan cleanup to this critical path.

## Minimum remaining work for the actual product vision

The remaining program is six thin source-to-product verticals, not another horizontal framework
wave. The minimum honest completion path is:

1. Finish manual/session Steps end to end: require the exact new-v28 run/segment binding in history,
   make source-only sessions ordinarily discoverable, activate one canonical writer through the
   existing contained transition, expose truthful live/session/day state, and complete selected
   deletion, no-resurrection, retention, and portable export/import. Prove the exact `{Steps}`
   registration and product path before generalizing anything.
2. Deliver Pressure, protected Location, Activity, Wi-Fi, and Cell as separate thin verticals. Each
   needs fresh provider evidence, durable fact, exact purpose/epoch attribution, one canonical
   writer, and a production query/UI result while every other capture source is disabled. Location's
   existing canonical writer remains protected until its shadow/cutover decision.
3. Add only useful ambient products: default-off opted-in Steps; passive/opportunistic Location;
   callback/broadcast-driven Wi-Fi and Cell; no default continuous Pressure. When an authorized
   provider runs, fresh new-in-effect output persists. Stale cache replay and duplicate state have
   zero product value. Active radio attempts remain finite and direct-demand-only.
4. Add optional cross-source enrichment as a freshness- and consent-checked attribution/query join.
   For example, Wi-Fi may reference compatible Location already being collected, but enrichment
   never starts or retains an expensive provider and Wi-Fi must remain useful without Location.
5. Compose one production history contract that groups replacement physical segments into a logical
   entry and exposes day, session, and between-session facts with explicit disabled, unavailable,
   no-observation, materializing, partial, degraded, and failed states. Reuse existing Dashboard,
   History, Calendar, and detail surfaces first; build only UI those surfaces cannot express.
6. Finish the privacy/data lifecycle per source: consent epoch, approved retention, minimized export,
   selected and collected-data deletion, key rotation where identity is retained, correction/replay,
   and no resurrection. Final retention durations and user-facing privacy copy remain explicit
   product/privacy decisions.
7. Run proportional Android proof: all 12 manual/automatic only-source scenarios, dynamic policy and
   permission changes, process death/reboot, replay/correction/deletion/upgrade, and a small
   representative API/OEM/device battery-quality matrix. Acquisition tiers require measured quality
   per unit of battery; they do not require an enterprise-scale lab before internal proof.

Do not add a universal materializer language, generic tombstone platform, six speculative source
schemas at once, wake-reliable Wi-Fi/Cell promises without measurements, or a new UI platform before
two concrete source verticals demonstrate the shared need. Automatic and ambient modes remain
independent source gates rather than blockers for the first useful manual vertical.

## Dormant deletion-fence checkpoint

### Outcome

`03bbda2f1` adds the smallest correction-safe deletion authority that survived fresh R1 review: a
payload-free v28 source/purpose/logical-run fence, enforced by the dormant candidate Steps WAL lane
and the selected-session read facade. The fence is battery-neutral and does not register, start, or
change provider cadence. It has no production producer, and `DefaultTripRepository` remains on its
legacy segment-only delete path. Active legacy Steps generation 1 remains the sole production
writer.

The first draft attempted to connect permanent trip deletion through the presentation repository.
Three independent R1 adversaries returned `NO_GO`, so that production wiring and its
presentation-owned fact mutation were removed before commit. The resulting code is intentionally a
dormant primitive, not proof of product deletion, no-resurrection, `QUERYABLE`, or UI completion.

### Evidence and review disposition

- Data/replay/migration review found that legacy `StepInterval`, raw and typed import, retention,
  future writers, other sources, and persisted `daily_summary` could bypass the proposed scope.
- Android lifecycle/privacy review demonstrated a finalization-versus-asynchronous-writer-teardown
  race, unchecked active/`STOPPING` deletion failures, and no durable wake for a lane parked on a
  now-fenced poison ordinal.
- Product/scope review found that the existing “permanently deleted” copy would be false for a
  Steps-only or presentation-only retraction and that the mutation belonged in a data-plane command,
  not a presentation repository.
- All shared `BLOCKER`/`HIGH` findings are `MITIGATED` only by withdrawing production wiring or are
  still accepted phase blockers. None is dispositioned as a passed deletion gate.
- The exact code passes production Kotlin compile, isolated fence/DAO/lane/selector tests, Detekt,
  app Hilt compilation, Android-test Kotlin compilation, and the post-commit Room schema guard.
  Current v28 contains 69 entities. Connected migration execution was not rerun for this revision.

### Required next boundary

Production activation and permanent-deletion copy remain blocked, but contained source-adaptive
historical and live Steps states may be implemented and tested now against the read-only facade.
Before enabling a selected-session delete action, choose durable post-presentation acknowledgement
for source-neutral session/Ski writers, resolve exact new-v28 Steps ownership, and fence only legacy,
candidate, and portable-import Steps writes. The data-plane command must return typed `Deleted`,
accepted `NotFound`, `BlockedActive`, `LegacyUnverifiable`/`UnsupportedScope`, and retryable failure
outcomes; only the first two may dismiss product state. Distinct selected-delete, cutoff-retention,
portable-import, and read-only export services may share a narrow Steps fence/retraction evaluator,
but must not be routed through one row-oriented command. Define the current day-summary zone
authority before invalidation, use the existing post-commit conflated drain hint plus startup
recovery, and do not add polling, a permanent Room observer, or a generic dirty-day platform.

## Empty-session cleanup containment checkpoint

### Outcome

`88309387d` removes `SessionSegmentDao.deleteEmpty()` and makes the historical
`DatabaseMaintenanceWorker` an inert compatibility shell. The next successful UI maintenance
startup requests asynchronous cancellation; collected-data deletion awaits cancellation and never
restores the work. A persisted periodic request may still wake the inert shell until cancellation
completes, so database mutation is retired immediately but unconditional wake retirement is not
claimed.

`88309387d` adds positive-sample predicates to daily-summary/live recovery plus source and all-time
activity counts. `24b9aeffb` adds the same rule to app-age/hour/night/dawn achievements and
historical ActivityRecognition selection. Together, these named reads exclude every row without a
positive sample count, including the tested identity-stamped production crash fixture and matching
legacy/imported rows. Other `SessionSegmentDao` reads remain unchanged; this is not a claim that
every numeric DAO method was changed.

This is not completed cleanup. `sourceSession.stop()` persists terminal logical/run state before
cycle drain and `orchestrator.shutdown()`, while final session and Ski writers may still mutate the
presentation rows. Current terminal lifecycle state is therefore not proof that a zero row is
abandoned. The durable start descriptor also does not yet receive the segment ID allocated later by
`SessionTrackerComponent`, so a process death can leave a physical orphan indefinitely when
retention is disabled.

### Evidence and review disposition

- Original Room DAO verification at `88309387d` is green: `SessionSegmentDaoTest` `11/11`; `BUILD
  SUCCESSFUL in 1m 13s`, 79 tasks. The `24b9aeffb` correction rerun is `12/12`, zero
  failures/errors/skips; `BUILD SUCCESSFUL in 1m 5s`, 79 tasks.
- App verification is green: `DatabaseMaintenanceWorkerTest` `3/3`, deletion quiescer `3/3`, and
  all 37 nested architecture assertions; `BUILD SUCCESSFUL in 1m 9s`, 575 tasks.
- Root Detekt was green in `34s` for `88309387d`. The follow-up first exposed five missing public
  DAO contracts, which were documented; the exact committed correction then passed in `39s`, 5
  tasks. The app gate compiled production/test Kotlin, Hilt, and the simplified assisted worker
  constructor.
- The first combined attempt collided with an independently running Gradle test process and ended
  in test-result `EOFException`; it is discarded rather than represented as a test failure or pass.
- Two lifecycle/cleanup reviews falsified terminal-only SQL. A later evidence audit found that the
  first `11/11` proof left app-age/hour/night/dawn plus ActivityRecognition drift. `24b9aeffb`
  closes those reads. A final falsifier corrected two documentation claims: UI startup only requests
  cancellation, and the named positive-sample predicates apply to all zero-sample rows while other
  DAO reads remain unchanged. A separate scope adversary and its document falsifier found seven
  overbuilding/product-order issues; TI-D111 records their correction. No reviewer result is used as
  device or product-query proof.

### Required next boundary

Persist and validate exact `(logicalTrackingId, serviceRunId, sessionSegmentId)` ownership. Add a
durable presentation-quiesced acknowledgement only after cycle drain, final segment decision,
pipeline stop, Ski detector finish, Ski writer flush/join, and shutdown materialization. Relocate
graceful empty-row cleanup behind that acknowledgement. Previous-exit crash-orphan reclamation is a
separate later slice and may run only when recovery proves the exact run non-recoverable; it is not
on the manual Steps product critical path. A recoverable manual descriptor stays blocked. SQL
terminal checks may be defense in depth, never the race proof.

## Reconciliation topology

The histories diverged from the last common ancestor
`068ebe052a5c90ba8a2f011931acc3c16fa8ddc4`:

```text
                                  local tracking line
068ebe052 ─────────────────────── … ── 8d549c188 ── 9a65148a1
      └─────────────────────────── … ── 4ab17fc9c
                                  remote dev/v10

8d549c188 + 4ab17fc9c  ──>  1a112bbd5
1a112bbd5 + 9a65148a1  ──>  9b8ab4b43
```

Exact roles:

| Commit | Role |
| --- | --- |
| `068ebe052` | common baseline audited by the tracking program |
| `4ab17fc9c` | previously fetched `origin/dev/v10` tip; 90 commits beyond the common base |
| `8d549c188` | local tracking line immediately before its atomic Steps-transition commit; 80 commits beyond the common base |
| `9a65148a1` | local `feat(steps): coordinate atomic writer transitions`; the 81st local commit |
| `1a112bbd5` | first merge, with parents `8d549c188` and `4ab17fc9c` |
| `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a` | second merge, with parents `1a112bbd5` and `9a65148a1` |

Before reconciliation, local `dev/v10` was 81 commits ahead of and 90 commits behind
`origin/dev/v10`. The first merge was deliberately based on `8d549c188`; the separate second merge
kept the atomic Steps-transition slice reviewable rather than burying it in the 321-file remote/local
integration.

The next session must verify that both `4ab17fc9c` and `9a65148a1` are ancestors of the delivered
`origin/dev/v10`. Do not reconstruct or squash this topology unless there is an explicit reason and
authorization.

## What was reconciled

### Local tracking-infrastructure line retained

The local line supplies the tracking program's current architectural authority:

- Room-backed `SourcePolicy`, consent epochs, immutable/effective manifests, and the direct
  unreleased-v27-to-v28 migration;
- purpose-specific demands, physical provider generations, observed-time authorization revisions,
  and contained per-source rollout state;
- two-phase durable session intent, real start origins, exact service-run/provider claims,
  boot-aware leases, lifecycle acknowledgements, and no-ghost recovery;
- checksummed observation WAL admission, durable callback handoff, source-local failure containment,
  deletion/startup fences, and retained released-v27 recovery;
- the dormant Steps fact lane, stable receipt/fact identity, retention-safe redacted retractions,
  queued writer provenance, exact destination-owner fences, and immutable service-run history
  selection;
- the explicit Steps activation/rollback/full-deletion-rearm coordinator now brought in by the
  second merge.

### Remote `dev/v10` line retained

The remote line contributed substantial work outside and adjacent to the tracking program:

- repository-owned `ciCheck`, release-evidence, dependency-verification, lint, Detekt, and CI
  quality gates;
- Tracebox diagnostics/runtime measurement integration, privacy/support material, release
  validation, and ARM64 release packaging evidence;
- localization, onboarding/background-access, permission-capability, map/network readiness, and QC
  tooling improvements;
- tracking-adjacent recovery and correctness work, including raw Location observation repair, live
  statistics recovery, service-stop ordering, force/previous-exit handling, bounded runtime queues,
  framework Location fallback, and provider/capability tests.

### Semantic overlap decisions

The first merge used the local tracking line as the starting authority and then audited the remote
diff. The resulting tree:

- keeps the local policy, lifecycle, broker, WAL, and rollout contracts where the remote line still
  contained older duplicate foreground-service, event-outbox, or ingress approaches;
- keeps compatible remote recovery, permission, provider-fallback, live-statistics, release, and
  quality work;
- preserves active-session proto fields as `restart_boot_id = 12`, `restart_token = 13`, and
  `session_segment_id = 14`;
- retains the merged manifest's `NEARBY_WIFI_DEVICES` declaration without treating that declaration
  as evidence that Tracker's actual scan path no longer needs the separately modelled Location/Wi-Fi
  prerequisites;
- unions both parents' Detekt baselines rather than dropping either side: 5,144 remote IDs plus
  6,137 local-parent IDs, with 4,934 shared, produce 6,347 unique baseline IDs;
- refactors merge-only Detekt findings instead of suppressing them;
- preserves the established Location canonical writer. No second Location writer was activated.

The second merge had one textual conflict in `TrackerServiceSourceSession.kt`. The resolution keeps
the newer retry-preserving `SourceSessionStopOutcome` state machine and maps invalid suspend/stop
intent results to explicit failures without clearing active ownership. A focused test proves the
invalid result is visible, ownership remains recoverable, and a later valid stop succeeds.

## Current architectural checkpoint

Status below means what the repository proves at this handover. `Implemented foundation` is not
synonymous with `DONE`, `QUERYABLE`, or rollout-ready.

| Workstream | What is now present | What remains before its gate |
| --- | --- | --- |
| A. Policy and v28 | Room is the effective policy authority; consent epochs and immutable manifests exist; v28 is still the direct target because it never shipped; populated v27 migration previously passed 8/8 on `Medium_Phone(AVD) - 16`. | Production backup-wrapper proof, portable import/export containment, connected migrate-to-runtime ordering, retention decisions, and schema-freeze evidence. |
| B. Broker/acquisition | Direct capture/control/ambient demands, physical registration generations, observed-time authorization intervals, Activity's durable callback seam, source-specific runtime ownership, and contained source rollout exist. | The final app-scoped supervisor contract and all six source registration-set tests; five remaining source-native handoffs/identities; provider-specific reconfiguration and device power/quality evidence. |
| C. Lifecycle | Durable prepared intent precedes external service work; real origin/type candidates, exact run/action claims, cleanup retry, boot/epoch fencing, and recovery/finalizers are host-tested. | Connected process-death/reboot/FGS legality, permission and provider behavior across supported Android versions, plus device/OEM evidence. |
| D. Data/writers | Checksummed WAL, source-local containment, frozen-v27 drain, dormant Steps facts/receipts/cursor, retention, event-exact queued provenance, service-run selector, exact owner fence, explicit activation/rollback/deletion-rearm transactions, same-process production deletion-service/barrier/rearm proof, and a dormant exact-run deletion fence exist. | Exact segment ownership and durable presentation acknowledgement; new-v28 Steps attribution; source-local legacy/candidate/import fence checks; typed selected deletion with documented-zone repair and existing post-commit drain; distinct retention/import/export services; cold process/reboot/provider interleaves; and one real source vertical before generalizing. |
| E. History product | A Hilt-bound `TrackingHistoryRepository` observes one selected local session and preserves Steps availability, evidence, product state, coverage, stable causes, and fence invalidation without reading current global ownership. Trip Detail is its first contained read-only consumer; existing legacy readers remain available. | Define an exact historical capture-set and qualifying-Location contract before adapting the remaining detail layout; add the contained live consumer and run the exact Steps-only smoke. Propagate completeness through numeric consumers before activation. Add shared/batched composition only before list/day fan-out; no persisted universal `DayOverview` is required. |
| F. UI | Manual source selection/prerequisite logic is source-aware and no longer assumes Location for every source. Policy-error/status presentation exists. Selected Trip Detail now renders typed covered-zero, positive, partial, legacy, unavailable, materializing, deleted, and failure Steps states without blocking unrelated content; direct deletion is withheld. | Source-adaptive Location/map/export hiding based on qualified capture evidence, a truthful Steps-only live state, ordinary source-only discovery, localization, and typed blocked/unsupported delete feedback; no broad day-first journal is authorized. |
| G. Quality | Focused host, Room migration, architecture, lint, Detekt, release-evidence, and full repository gates have substantial evidence. Exact final code merge `9b8ab4b43` passed `ciCheck` in 21m 46s; code checkpoint `c5118e186` passes it in 8m 32s. TI-B160–TI-B162 cover retained-state Android and selected-session history. TI-B163 records the fresh R1 deletion `NO_GO`; TI-B164/TI-B165 record the narrowed fence gates; TI-B166/TI-B167 record cleanup containment, zero-sample read correction, and scope review; TI-B168 records the selected-detail consumer and its remaining list-discovery `HIGH`. | Current connected migration/device rerun, full `ciUnitTest`/`ciCheck` after the next production boundary, cold process/reboot proof, exact `{Steps}` registration/product proof, device/provider/power tests, and later source-specific dynamic/failure gates. |

The intended architecture is still the thin path:

```text
policy and consent authority
    -> direct purpose demand
    -> one physical runtime owner per source
    -> source-qualified durable delivery
    -> one tracking mutation boundary with source-local lanes
    -> typed source facts and completeness
    -> one production history facade
    -> existing product surfaces
```

Do not restore the removed generic attribution/contribution/dirty-day platform, build six unused
materializers, or make a full Days-first UI a prerequisite. Shared machinery is justified only after
at least two real source verticals demonstrate the same need.

## Source and product truth

No source/mode row is `DONE` yet.

| Source | Current honest verdict | Immediate product/writer boundary |
| --- | --- | --- |
| Location | `DEGRADED`; manual and automatic scenarios remain `IN_REVIEW`. | Keep the established writer as sole owner. Prove real origin, qualified post-start fixes, shadow parity if a candidate exists, and production history visibility. |
| Steps | `DEGRADED`; manual/session is `IN_PROGRESS`/`IN_REVIEW`; automatic and ambient are `BLOCKED`. | The candidate lane, historical selector, owner fence, transition engine, one selected-session observable facade, dormant exact-run deletion fence, and one contained read-only Trip Detail consumer now exist. The first production activation and fence producer have no ordinary callers; ordinary source-only discovery, live/day/list composition, safe deletion, export/import, localization, and device proof remain. |
| Activity | `FAILED` as an end-product source; base scenarios remain `BLOCKED`. | Control acquisition is materially more mature, but control facts must remain separate from captured Activity. A typed captured-history path is absent. |
| Wi-Fi | `FAILED` as an end-product source; base scenarios remain `BLOCKED`. | Freshness/privacy containment exists in parts. Durable cross-process identity, typed identity-free product facts, query/export/delete, optional context, and device scan evidence remain. |
| Cell | `FAILED` as an end-product source; base scenarios remain `BLOCKED`. | Callback/cache/request semantics are designed, but stable durable delivery, identity-free product facts, multi-SIM completeness, query/export/delete, and device evidence remain. |
| Pressure | `FAILED` as an end-product source; base scenarios remain `BLOCKED`. | Session-first qualified windows/FIFO behavior, typed trend/completeness, production query, and device FIFO/write evidence remain. Continuous ambient Pressure stays off. |

Ambient persistence is a confirmed product requirement where it creates useful history, but every
ambient source remains independently default-off until its direct consent, acquisition limits,
freshness, minimization, retention, export, deletion/key rotation, sessionless query, explanation,
and device evidence pass. Enrichment never starts hardware. Optional Location may enrich Wi-Fi or
Cell only when independently running under compatible purpose/consent and sufficiently fresh.

## Atomic Steps writer-transition checkpoint

The second merge adds a source-specific transition engine with three explicit operations:

1. promote an already verified, caught-up Steps shadow lane and destination owner atomically;
2. contain candidate capture, latch a cutoff, drain, retire the candidate, and restore legacy
   ownership through an explicit rollback sequence;
3. after full collected-data deletion, reconstruct a coherent empty generation without performing
   the first candidate cutover.

Important containment facts:

- there is no ordinary production caller for first candidate activation or rollback;
- full deletion may call only the re-arm operation while deletion/startup barriers remain closed;
- all transitions require idle/finalized lifecycle authority, exact lease/rollout/owner/lane state,
  source command drain, callback closure, and monotonic generations;
- canonical Steps rollout authorization and WAL admission now recheck exact candidate destination
  ownership, so metadata cannot authorize capture after owner drift;
- legacy generation 1 remains the protected owner until an explicit, verified activation action;
- this code changes no provider registration, cadence, timer, ambient setting, or battery policy.

This is the smallest cutover foundation called for by TI-D103/TI-VS28. It is not yet a production
Steps cutover or a queryability claim.

## Adversarial review disposition

### Prior program findings that shaped this checkpoint

- Current global owner cannot select historical Steps. Older segments now resolve immutable
  service-run manifest provenance and exact lane/completeness evidence.
- A provider-owning cancelled `APPLYING` action cannot disappear from retirement. Exact attempted
  and accepted run claims remain retirement authority until strict stop evidence exists.
- Missing owner authority cannot silently acknowledge queued Steps. Event-exact owner/generation is
  frozen before acknowledgement and checked in the destination transaction.
- Retention and deletion cannot expose an older Steps revision. Expired UPSERT payload is removed at
  the monotonic privacy floor while redacted retractions remain.
- Candidate activation cannot be a metadata-only toggle. It must be a source-local transaction over
  rollout, lane, owner, cursor, and lifecycle boundaries.

### Fresh second-merge review

Three independent perspectives reviewed the second merge before final integration:

| Perspective | Finding/disposition |
| --- | --- |
| Database/DAO atomicity and replay | No `BLOCKER`, `HIGH`, or `MEDIUM` remained. Activation owner CAS, rollout and lane promotion are one Room transaction; rollback containment/drain/retirement/legacy restore, deletion re-arm ABA generation, run-boundary gates, and candidate receipt/cursor atomicity were verified. A `LOW` pending-signal half-pair gap was mitigated by checking either provenance column and adding a raw malformed-row test. |
| Android lifecycle and owner authority | A `HIGH` showed canonical Steps rollout/admission could survive destination-owner drift. It was mitigated by requiring candidate owner authority during rollout save/load/repair/authorization and rechecking it transactionally during durable capture admission, with missing/legacy-owner and post-drift tests. |
| App integration, deletion, and maintainability | No `BLOCKER` or `HIGH` remained. A `MEDIUM` residual `step_interval` row could survive full deletion and was mitigated with a DAO guard plus focused re-arm test. The oversized coordinator/test were decomposed into narrowly named transition, boundary, state, activation, rollback, deletion-rearm, contract, and test files without changing behavior or adding suppressions. |

At the 2026-08-27 review, one `MEDIUM` test-hardening item remained: the complete production
`DefaultCollectedDataDeletionService -> AppDatabase -> StepsSessionFactWriterTransitionCoordinator`
seam had no end-to-end test proving deletion barriers remained closed through the re-arm callback
and resulting generation. TI-B160 closes that narrow same-process seam at local commit `3b2365547`,
after two reviewer-found `HIGH` fixture/determinism defects were corrected and the final target test
passed twice on retained app state. This does not prove cold-process restart, provider capture,
production query/export/UI, full connected-suite isolation, activation, or rollout. Lower-priority
follow-ups remain the debug `TestDataSeeder` recovery/preferences race and the line-oriented
architecture scan.

### Fresh deletion/rearm integration review (2026-08-29)

Three fresh `gpt-5.6-sol` high-reasoning reviewers attacked the new diff independently:

| Perspective | Disposition |
| --- | --- |
| Android lifecycle/privacy | Two `HIGH` findings were `MITIGATED`: setup no longer raw-deletes a real pending marker/reopens the singleton barrier, and setup now accepts the retained canonical writer left by a prior successful run. A transient post-retry readiness assertion was also replaced with explicit old-generation rejection and new-generation reconciliation. |
| Data/replay/deletion | The proposed stale-work resurrection timeline is `MITIGATED_BY_COMPOSED_EVIDENCE`: the app test proves the production deletion/rearm seam, while seven existing engine suites directly exercise stale collected-data epochs, owner-generation drift, stale in-memory WAL, recovery discard, projection fencing and deletion high-water rebasing. Cold process restart and real post-rearm provider capture remain open and are not attributed to this test. |
| Product/validation/maintainability | No `BLOCKER`/`HIGH`. The long scenario was decomposed until Detekt passed without suppression. The test deliberately remains an exact targeted destructive-database check on a disposable AVD, not arbitrary connected-suite ordering or product `QUERYABLE` evidence. |

Two reviewers suggested an `androidTest`-local Hilt entry point based on an older test pattern. That
approach was tried and failed with `ClassCastException` in both host and on-device execution for the
current application graph. The retained production entry point exposes only the lifecycle store and
Steps transition coordinator required to reach the actual singleton authorities.

## Observable Steps session-history checkpoint (2026-08-30)

Commit `c5118e186` exposes the existing immutable Steps selector through the minimum production data
contract. The public API is deliberately narrow: `observeSession(segmentId)` accepts a local
`SessionSegment` row identity and is suitable for one selected session, not list rows, portable
identity, day composition, or export. Its Room implementation observes the exact source policy,
manifest, run, lane, failure, evidence, fact, completeness, and segment tables, then resolves one
transactional snapshot on the IO dispatcher. It is read-only and cannot start hardware or mutate a
writer, projection, lifecycle, or rollout state.

The contract preserves four independent truth axes plus stable causes:

- availability: explicit historical disabled, available, or evidence-insufficient unavailable;
- acquisition: none, starting, active covered-zero, or qualified positive recording;
- product: materializing, partial, ready, degraded, or failed;
- coverage: none, complete, partial lower bound, or unknown legacy coverage.

Three focused adversaries drove the final shape. API/product review rejected false disabled states,
legacy-unknown lower-bound claims, availability-coupled completeness, baseline-as-active, unnamed
incompleteness, and complete coverage while the candidate lane is behind. Room/performance review
found no blocker/high and accepted whole-table invalidation only for one selected session; batching
or shared composition is required before list/day fan-out. A fresh product/scope reviewer required
explicit `enabled = false` evidence and rejected zero plus `RECORDED`; both have regression tests. A
final read-only audit found no blocker/high/medium and only normalized indentation.

At `c5118e186`, this was not `QUERYABLE`: no Today, Timeline, Calendar, session detail, goal,
streak, achievement, widget, notification, export, or deletion consumer injected the repository.
`f9b1c3c45` later adds only the contained read-only Trip Detail consumer described below and removes
that screen's unsafe presentation-only delete. It does not change ordinary discovery, another
consumer, or the `QUERYABLE` gate. Do not create one observer per list row.

## Contained Trip Detail Steps consumer checkpoint (2026-08-30)

### Outcome

Commit `f9b1c3c45` makes selected Trip Detail the first production consumer of
`TrackingHistoryRepository.observeSession(segmentId)`. A known segment renders immediately with
Steps `Materializing`, then maps durable availability, evidence, product, coverage, and causes into
complete covered-zero/positive, partial lower-bound, legacy-unverified, not-captured, disabled,
unsupported, permission-required, OS-limited, unavailable, no-observation, deleted, or failed UI
states. History failure is isolated to Steps and offers an explicit user-triggered selected-history
retry; unrelated trip and Location content remains visible. Corrections update the selected screen.

The observer is `WhileSubscribed` and Compose collection is lifecycle-aware. Optional Location/Ski
reads start only while the screen is composed and share one cancellable job. This slice starts no
provider, changes no demand, writer, schema, cadence, activation, or rollout state, and does not fan
one Room observer across a list.

### Adversarial disposition and evidence

- Product review's commit-local `HIGH` findings were mitigated: a Steps read failure no longer hides
  valid Location content, and partial deletion preserves a surviving lower bound. Misleading
  `sampleCount`/icon inference was withdrawn; English fallback copy remains a localization gap.
- Android review's two commit-local `HIGH` findings were mitigated by lifecycle-scoped observation
  and isolated history failure. Disabled precedence and duplicate supplemental waiters were also
  corrected.
- Validation review's two commit-local `BLOCKER` findings were removed: Trip Detail no longer calls
  presentation-only deletion, and no new branch treats `sampleCount` as Location evidence. Its
  separate list-discoverability `HIGH` remains `ACCEPTED_AS_QUERYABLE_BLOCKER`: positive-
  `sample_count` list queries can still hide a source-only session.
- A fresh corrected-diff reviewer required explicit terminal-failure retry, UI-owned cancellable
  optional reads, correct `NotCaptured` cause mapping, and immediate materializing content; all were
  corrected before commit. Thus every commit-local `BLOCKER`/`HIGH` is mitigated, not every
  program-level gate.
- Four focused suites pass `33/33` with zero failures/errors/skips: history mapping `10/10`,
  presenter `9/9`, ViewModel `6/6`, and Compose `8/8`; Gradle finished in `36s` with 194 tasks. Root
  Detekt passed in `18s`. Statistics lint passed in `32s` with 338 tasks and no new issue; its
  existing baseline filters 137 errors and 32 warnings.

### Gate boundary

`SessionSegment.sampleCount` is a generic collection-cycle count, not proof that Location was
captured. Exact historical capture-set and qualifying-Location evidence do not yet exist, so the
remaining Location-shaped map/export/fact layout is not source-adaptive. Direct Trip Detail deletion
is removed because it could orphan append-only Steps facts; `HistoryPresenterViewModel` and
`StatsPresenterViewModel` still use presentation-row deletion and remain unsafe for this contract.
Ordinary History/Stats list discovery, Today/Timeline/Calendar/live composition, other numeric
consumers, localization, `ciUnitTest`, `ciCheck`, emulator/device, visual, accessibility, provider,
energy, `QUERYABLE`, activation, and rollout proof remain absent.

## Verification evidence

All commands are Windows/PowerShell examples and use the repository's serial Gradle boundary:

```powershell
.\gradlew.bat <tasks> --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Evidence already established before the second merge:

| Scope | Result | Boundary |
| --- | --- | --- |
| Reconciled first-merge lint: `:app:lintRelease :feature:dashboard:lintRelease :feature:tracker:lintRelease :tracker:api:lintRelease :tracker:engine:lintRelease` | `BUILD SUCCESSFUL in 4m 20s` | Release lint for the most affected product/tracker modules. |
| Reconciled first-merge focused tracker tests: `RawLocationObservationRepairTest`, `TrackerServiceStopCommandOrderingTest`, `TrackerServiceSourceSessionTest` | `BUILD SUCCESSFUL in 2m 4s` | Conflict-adjacent location repair, stop ordering, and source session behavior. |
| Reconciled first-merge `ciCheck --continue` | `BUILD SUCCESSFUL in 18m 37s`; 1,991 tasks: 116 executed, 4 from cache, 1,871 up-to-date | Authoritative repository-owned host/build/lint/Detekt/Room/release-evidence gate at `1a112bbd5`; not final second-merge or device proof. |
| Final second-merge `ciCheck --continue` | `BUILD SUCCESSFUL in 21m 46s`; 1,991 actionable tasks: 224 executed, 1,767 up-to-date | Exact code commit `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a`; includes Room drift, architecture, release lint, unit tests, Detekt, SQLite runtime/linkage, 27 release-evidence tests, and 16 reviewed dependency-metadata components. |
| Exact populated v27-to-v28 migration at the latest pre-reconciliation schema boundary | `8/8` on `Medium_Phone(AVD) - 16` | Migration/open/reopen/query/delete/no-resurrection evidence; not a current final-tree rerun unless separately recorded. |
| Pre-reconciliation Steps selector boundary | core `876/876`, stats data `139/139`, tracker engine `1,649/1,649`, `:app:assembleDebug`; `BUILD SUCCESSFUL in 8m 46s` | Dormant selector and source-local evidence only; no production consumer/device/provider proof. |
| TI-B160 targeted `CollectedDataDeletionStepsRearmIntegrationTest`, twice consecutively on retained `Medium_Phone(AVD) - 16` / API 36 plus one post-rebase replay | pre-handover source-tree runs `BUILD SUCCESSFUL in 1m 6s` and `47s`; post-rebase run `BUILD SUCCESSFUL in 48s`; each `1/1` and 714 tasks; latest XML zero failures/errors/skips | Exact source tree committed as `3b2365547`; proves only same-process production deletion provider/default database/closed barrier/Steps rearm through diagnostics failure and retry. Not full suite, cold process, provider capture, product query/export/UI, activation, or rollout. |
| TI-B161 focused engine/app/static complement | tracker engine `BUILD SUCCESSFUL in 3m 11s`, 234 tasks; app deletion suite `BUILD SUCCESSFUL in 52s`, 575 tasks; Detekt `BUILD SUCCESSFUL in 33s`, then post-rebase in `18s`, 5 tasks | Seven engine suites cover stale epoch/owner work, WAL recovery, projection non-resurrection and high-water rebasing; app service contracts and final Kotlin structure remain green. |
| TI-B162 observable Steps history contract | exact committed-code `ciCheck --continue --no-parallel` `BUILD SUCCESSFUL in 8m 32s`, 1,991 tasks (662 executed, 297 from cache, 1,032 up-to-date); focused stats gate `BUILD SUCCESSFUL in 49s`, 219 tasks; `ciUnitTest` `BUILD SUCCESSFUL in 12m 18s`, 990 tasks; stats lint/Hilt `BUILD SUCCESSFUL in 40s`, 569 tasks; Detekt `BUILD SUCCESSFUL in 30s` | Three focused adversaries and one final read-only audit leave no scoped blocker/high. This proves API invariants, Room observation/transaction mapping, Hilt compilation, and host regression only—not any production consumer, `QUERYABLE`, provider/device behavior, export, UI, activation, or rollout. |
| TI-B163–TI-B165 dormant deletion-fence checkpoint | three fresh R1 adversaries returned aggregate `NO_GO` for production wiring; narrowed production compile `BUILD SUCCESSFUL in 2m 17s` (136 tasks); focused XML fence DAO `3/3`, Steps fact DAO `9/9`, candidate lane `14/14`, selected-session selector/observer `27/27`; Detekt/app Hilt `BUILD SUCCESSFUL in 2m 38s` (366 tasks); post-commit Room guard `BUILD SUCCESSFUL in 22s` (46 tasks) | Production wiring was withdrawn. `03bbda2f1` proves only a dormant opaque fence, candidate pre-write suppression, later-drain terminal-failure release, and observable selected-session invalidation. Active legacy Steps, writer teardown, dirty days, retention/import/export, automatic wake, connected migration/device execution, product delete, consumer, `QUERYABLE`, activation, and rollout remain blocked or unverified. |
| TI-B166 unsafe cleanup containment | Room DAO `BUILD SUCCESSFUL in 1m 13s` (79 tasks; XML `11/11`); worker/quiescer/full architecture `BUILD SUCCESSFUL in 1m 9s` (575 tasks; `3/3`, `3/3`, 37 architecture assertions); root Detekt `BUILD SUCCESSFUL in 34s` (5 tasks); lifecycle/cleanup/final-diff review | `88309387d` proves only that the periodic database mutation is inert, UI maintenance requests cancellation, deletion awaits cancellation, an identity-stamped zero placeholder survives, and the first daily/live/source/activity reads require positive samples. A persisted request may still wake until cancellation completes. Durable segment identity, acknowledgement, process-death reclamation, device execution, full current-tree `ciUnitTest`/`ciCheck`, product deletion, and rollout remain open. |
| TI-B167 remaining zero-sample reads and next-wave scope review | code commit `24b9aeffb`; Room DAO `BUILD SUCCESSFUL in 1m 5s` (79 tasks; XML `12/12`, zero failures/errors/skips); corrected Detekt `BUILD SUCCESSFUL in 39s` (5 tasks); evidence reviewer plus independent scope reviewer/document falsifier | `24b9aeffb` adds positive-sample predicates to app-age/hour/night/dawn and ActivityRecognition reads. Combined with `88309387d`, every zero-sample row is excluded from those and the named daily/live/source/all-time activity reads; other DAO reads remain unchanged. Four `HIGH` and three `MEDIUM` next-wave findings are mitigated in TI-D111 by source-local services, typed unsupported UX, product-first contained surfaces, exact Steps-only registration assertions, documented zone authority, and the existing post-commit drain. |
| TI-B168 contained selected Trip Detail Steps consumer | code commit `f9b1c3c45`; three independent product/Android/validation adversaries plus one fresh corrected-diff reviewer; focused history/presenter/ViewModel/Compose tests; root Detekt; statistics lint | focused `BUILD SUCCESSFUL in 36s` (194 tasks; XML `33/33`: mapping `10/10`, presenter `9/9`, ViewModel `6/6`, Compose `8/8`); Detekt `BUILD SUCCESSFUL in 18s`; statistics lint `BUILD SUCCESSFUL in 32s` (338 tasks), no new issue. Every commit-local `BLOCKER`/`HIGH` was mitigated; the validation adversary's separate list-discoverability `HIGH` is accepted and still blocks `QUERYABLE`. Existing lint baseline: 137 errors/32 warnings. No `ciUnitTest`, `ciCheck`, emulator/device, visual, accessibility, provider, or energy proof. |

The exact TI-B164/TI-B165 command shapes were:

```powershell
.\gradlew.bat :core:base:compileDebugKotlin :tracker:engine:compileDebugKotlin `
  :stats:data:compileDebugKotlin --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache

.\gradlew.bat :core:base:testDebugUnitTest `
  --tests '*SourceDeletionFenceDaoTest' --tests '*StepFactRevisionDaoTest' `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache

.\gradlew.bat :tracker:engine:testDebugUnitTest `
  --tests '*StepsSessionFactProjectionLaneTest' `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache

.\gradlew.bat :stats:data:testDebugUnitTest `
  --tests '*StepsSegmentHistorySelectorTest' `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache

.\gradlew.bat detekt :app:hiltJavaCompileDebug --no-daemon --no-parallel `
  --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache

.\gradlew.bat :core:base:compileDebugAndroidTestKotlin checkRoomSchemaDrift `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache
```

The final command's Android-test compilation completed before the pre-commit schema guard rejected
the intentionally uncommitted regenerated `28.json`. After `03bbda2f1`, `checkRoomSchemaDrift` alone
passed with the same serialized flags. The connected Android migration test itself was not executed.

The exact TI-B166 command shapes were:

```powershell
.\gradlew.bat :core:base:testDebugUnitTest --tests '*SessionSegmentDaoTest' `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache

.\gradlew.bat :app:testDebugUnitTest --tests '*DatabaseMaintenanceWorkerTest' `
  --tests '*DefaultCollectedDataWriterQuiescerTest' --tests '*ArchitecturalFitnessTest' `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache

.\gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

TI-B167 reran the same focused Room and Detekt command shapes after the two-file correction. The
final Room run was `BUILD SUCCESSFUL in 1m 5s`, and the final Detekt run was `BUILD SUCCESSFUL in
39s`. The first follow-up Detekt run reported five missing DAO contracts; those findings were fixed
before `24b9aeffb` and are not represented as a passing run.

The exact TI-B168 commands were:

```powershell
.\gradlew.bat :feature:statistics:testDebugUnitTest `
  --tests "com.adsamcik.tracker.statistics.presenter.TripDetailHistoryPresentationTest" `
  --tests "com.adsamcik.tracker.statistics.presenter.TripDetailPresenterTest" `
  --tests "com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelTest" `
  --tests "com.adsamcik.tracker.statistics.ui.TripDetailRouteComposeTest"

.\gradlew.bat detekt
.\gradlew.bat :feature:statistics:lintDebug
```

The first focused wrapper attempt could not access the shared Gradle cache under the restricted
sandbox; the ordinary cached rerun succeeded and is the only assertion result recorded above.

One pre-execution connected attempt compiled successfully but ended with `No connected devices` after
the earlier AVD had stopped. It is excluded from pass counts. The same `Medium_Phone` AVD was started
again, recorded as Android 16 / API 36, and used for the two final consecutive runs above.
After the branch was confirmed up to date with local `dev/v10`, the same exact target passed once more
in `48s` (`1/1`; 714 actionable tasks), and root Detekt passed in `18s` (5 up-to-date tasks).

The exact TI-B160 connected command, run twice without clearing installed state, was:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.tracker.app.settings.CollectedDataDeletionStepsRearmIntegrationTest" `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache
```

The exact TI-B161 engine complement was:

```powershell
.\gradlew.bat :tracker:engine:testDebugUnitTest `
  --tests "*StepsSessionFactWriterDeletionRearmTest" `
  --tests "*StepsSessionFactWriterTransitionCoordinatorTest" `
  --tests "*RoomDurableSourceIngressTest" `
  --tests "*PersistenceProcessorTest" `
  --tests "*StepsSessionFactProjectionLaneTest" `
  --tests "*TrackingCoordinatorTest" `
  --tests "*SourcePipelineRecoveryTest" `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache
```

The app contract and static complements used the same serial flags:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*CollectedDataDeletionServiceTest" `
  --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' `
  --console=plain --no-configuration-cache
.\gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

The TI-B162 selected-session history gates were:

```powershell
.\gradlew.bat :stats:api:allTests :stats:data:testDebugUnitTest --no-parallel
.\gradlew.bat :stats:data:lintDebug :app:hiltJavaCompileDebug --no-parallel
.\gradlew.bat detekt --no-parallel
.\gradlew.bat ciUnitTest --no-parallel
.\gradlew.bat ciCheck --continue --no-parallel
```

The exact final second-merge `ciCheck` command was:

```powershell
.\gradlew.bat ciCheck --continue --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

This green `ciCheck` is host/build-quality evidence. It is not emulator provider behavior,
process-death/reboot/FGS legality, power measurement, OEM coverage, the 12 source scenarios,
production query/UI, canary, or rollout evidence.

## Root work that had to be preserved on this integration device

The main `dev/v10` checkout on this integration device contains six user-owned paths deliberately
excluded from the continuation worktree. They must survive any later local integration byte-for-byte
and intentionally remain uncommitted there. A clean checkout on another device must not recreate
them.

| Path | Pre-integration SHA-256 |
| --- | --- |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImport.kt` | `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportCollisionTest.kt` | `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportTest.kt` | `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6` |
| `docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx` | `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28` |
| `docs/TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md` | `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332` |
| `feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardManualStartDecisionTest.kt` | `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6` |

All six hashes above were recomputed after the exact-path stash, fast-forward, and restore, and all
six remained identical. The first three import/export paths remain tracked modifications and the
final three paths remain untracked. The continuation intentionally changes statistics strings only
on `codex/ti410-deletion-rearm` as part of `f9b1c3c45`; that reviewed branch change is distinct from
the six protected root paths. Do not stage, commit, or push those six root changes as part of this
continuation.

## Decisions and blockers still open

The next session must not infer answers to these behavior-changing decisions:

- whether Step corroboration remains a visible, separately enabled automatic `CONTROL` demand or is
  removed;
- Ambient Steps continuity: opportunistic/background provider choice versus a stronger visible
  foreground promise;
- final retention durations and privacy/legal copy per source and purpose;
- whether ambient Location has an explicit product beyond passive, separately consented updates;
- whether measured active ambient Wi-Fi attempts or a wake strategy for Wi-Fi/Cell are ever exposed;
- whether any pressure-derived vertical estimate is in product scope;
- cross-midnight naming/counting presentation;
- each legacy writer's eventual retire/rollback policy, especially protected Location.

Implementation blockers that do not require a product choice should continue without asking:

- keep the contained selected-detail Steps read, then define an exact historical capture-set and
  qualifying-Location contract before hiding Location-shaped detail controls; add the corresponding
  live Steps state without treating missing Steps as zero;
- fix ordinary source-only discovery: current positive-`sample_count` History/Stats list queries can
  hide a valid source-only session and remain an accepted `QUERYABLE` blocker;
- preserve the implemented exact run/segment binding and post-presentation acknowledgement; add
  the new-v28 Steps reverse-binding check, logical-entry grouping, and an all-source qualified-fact
  evaluator before considering any graceful cleanup;
- resolve newly admitted v28 Steps attribution and monotonic fence enforcement in legacy, candidate,
  and portable-import Steps writes only; migrated unverifiable rows remain a typed blocked state;
- define one typed data-plane `DeleteSelectedSession` command with `Deleted`, accepted `NotFound`,
  `BlockedActive`, `LegacyUnverifiable`/`UnsupportedScope`, and retryable failure; only the first two
  may dismiss the row, and `DefaultTripRepository` must not own writer revisions;
- define the current summary's zone authority, repair affected cross-midnight days, and issue the
  existing post-commit conflated Steps drain hint; startup recovery handles a crash before signaling;
- keep selected deletion, cutoff retention, portable import, and read-only export as distinct
  services over one narrow Steps evaluator; preserve batching/module direction and never raw-merge
  derived daily summaries;
- keep periodic empty-row cleanup retired; move graceful cleanup behind presentation
  acknowledgement and keep previous-exit orphan reclamation as a separate later slice;
- wire the contained live consumer and ordinary source-only discovery, then immediately run the
  exact `{Steps}` smoke without activating another writer;
- add shared/batched history composition before any list/day surface; do not instantiate one Room
  observer per row;
- cover every existing numeric Steps consumer so partial/unavailable/materializing never becomes
  fabricated zero or a falsely awarded goal/streak;
- pass manual Steps with no Location, Activity, Pressure, Wi-Fi, Cell, control, or ambient demand
  through provider evidence, durable fact, production query, truthful UI, and listener removal;
- obtain process/reboot/provider and representative energy evidence before source rollout.

## Exact first steps for the next device/session

Start from a clean checkout. Do not reset or overwrite an existing dirty checkout.

```powershell
git fetch --prune origin
git switch dev/v10
git status --short --branch
git merge --ff-only origin/dev/v10
git rev-parse HEAD
git log --graph --decorate --oneline -n 30
git merge-base --is-ancestor 4ab17fc9c origin/dev/v10
git merge-base --is-ancestor 9a65148a1 origin/dev/v10
git diff --check
```

Expected results:

- both `merge-base --is-ancestor` commands exit zero;
- the graph contains `1a112bbd5` and `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a`
  with the parent relationships above;
- the checkout is clean;
- the remote result agrees with the exact delivery evidence recorded in this handover's Outcome.

On the current integration device, also inspect the unpushed continuation before integration:

```powershell
git show --stat --oneline d067704a9
git log --oneline dev/v10..codex/ti410-deletion-rearm
git diff --check dev/v10...codex/ti410-deletion-rearm
```

On another device, those commands cannot succeed until the local continuation is deliberately pushed;
remote `dev/v10` still ends at `ffd5d372f` at this checkpoint.

Then read the documents listed at the beginning and verify the current evidence boundary:

```powershell
Get-Content -Raw AGENTS.md
Get-Content -Raw docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md
Get-Content -Raw docs/tracking-infrastructure/CONTINUATION_HANDOVER.md
Get-Content -Raw docs/tracking-infrastructure/IMPLEMENTATION_STATUS.md
Get-Content -Raw docs/tracking-infrastructure/DECISIONS.md
Get-Content -Raw docs/tracking-infrastructure/VERIFICATION_MATRIX.md
```

The status, decision, and verification-matrix artifacts already record the final reconciliation
merge and host-test checkpoint. Confirm their delivery section agrees with the remote push result;
do not reinterpret the recorded host evidence as device, provider, product-query, or rollout proof.

On this integration device, first finish reviewing and integrating the existing
`codex/ti410-deletion-rearm` worktree; do not create a redundant branch from `dev/v10` that omits its
local commits. On another device, the continuation cannot be resumed until it is deliberately pushed.
After that delivery is visible on the target device, create a dedicated worktree and scoped branch
from updated clean local `dev/v10`, per `AGENTS.md`. The next code wave is TI-410 manual/session Steps,
not another shared framework:

1. On this device, integrate or continue from the local branch through test-contract commit
   `d067704a9` (production code `9aeb8853a`) and this handover only after confirming the exact
   branch/source-tree status. This continuation is not
   on the remote at this checkpoint.
2. Keep TI-D107–TI-D113, the current status checkpoints, and TI-B158–TI-B170 synchronized with any
   correction or newly reproduced result. Do not reinterpret the dormant fence or cleanup
   containment as product deletion or process-death reclamation.
3. Preserve the contained read-only Trip Detail Steps states. Define an exact historical capture-set
   and qualifying-Location contract before hiding map, route, speed, accuracy, coordinates, or
   export; never infer Location from `sampleCount`. Add the contained live Steps surface and fix
   ordinary source-only list discovery. Define typed delete UX now, but keep mutation and activation
   off.
4. Preserve the exact Room run/segment binding and post-presentation acknowledgement in
   `9aeb8853a`. Require the Steps selector to validate that reverse binding for new-v28 rows, then
   add logical-entry product grouping and a typed all-source evidence evaluator. Do not relocate or
   restore graceful empty-row cleanup until that evaluator exists. Keep previous-exit crash-orphan
   reclamation as a later bounded slice.
5. Extend newly admitted v28 `SESSION_CAPTURE` Steps with exact manifest/run/purpose/epoch
   attribution and monotonic fences in legacy, candidate, and portable-import Steps writes only.
   Migrated unverifiable rows remain `LegacyUnverifiable`/`UnsupportedScope`; never infer ownership
   from wall-time overlap or assign session ownership to future ambient facts.
6. Implement a typed `DeleteSelectedSession` data-plane command. Re-resolve manifests/lifecycle in
   its mutation boundary, fence/retract exact Steps facts, and return `Deleted`, accepted `NotFound`,
   `BlockedActive`, unsupported/legacy-unverifiable, or retryable failure. Only the first two dismiss
   the row. Define the existing daily summary's zone authority, then repair affected days with
   cross-midnight/zone-change tests. Issue the lane's existing post-commit `requestDrain()` hint;
   startup recovery covers a crash before the hint.
7. Wire the remaining live/list product boundaries and immediately run the contained manual
   Steps-only smoke. Assert the exact capture/registration set is `{Steps}`, with no Location,
   Activity, Pressure, Wi-Fi, Cell, control, or ambient demand; prove listener removal after stop,
   no polling/scheduled recovery, and `RECORDING -> MATERIALIZED -> QUERYABLE` plus truthful UI.
8. Add distinct `RetainStepsBefore`, `ImportPortableSteps`, and read-only `ExportPortableSteps`
   services over the narrow Steps fence/retraction evaluator. Preserve module direction, batch
   retention, provenance-bearing import, and derived-summary recomputation; never raw-merge
   `daily_summary` or route these operations through the selected-row command.
9. Audit applicable numeric consumers—goals, streaks, achievements, widgets and notifications—and
   add a measured shared/batched history query only before list/day fan-out. Only complete ready
   values affect awards or progress.
10. Run focused host gates, the complete affected suites, `ciCheck`, then connected migration and
    device/provider/process evidence. Production activation remains blocked until deletion,
    retention, portable round-trip, numeric consumers, and exact Steps-only evidence all pass.

Only after manual/session Steps is product-queryable and independently gated should work begin on:

- automatic Steps with explicit Activity `CONTROL` and no control leakage;
- default-off Ambient Steps with one selected continuity provider, civil-day identity, retention,
  export/delete, and sessionless product visibility;
- Pressure, then protected Location, Activity, Wi-Fi, and Cell as independent source verticals.

## Commit, push, and rollout boundary

The user explicitly authorized committing and pushing the completed reconciliation and handover.
The lead declared the six protected root paths above outside that scope. The authorization does
not permit a force push, history rewrite, release, tag, APK publication, Play submission, feature
activation, remote flag change, canary, deployment, or destructive migration/deletion.

That earlier authorization and push were completed through remote `ffd5d372f`. The local sequence
through `3b2365547`, `411bfe144`, `c5118e186`, `d6e887d41`, `03bbda2f1`, `bfe2e1f36`,
`88309387d`, `24b9aeffb`, `412882194`, `f9b1c3c45`, `dba200183`, `9aeb8853a`, and
`d067704a9`, plus this updated handover, is local-only at
this checkpoint. The latest instruction was applied as commit-and-prepare work; no additional push
was performed. Do not tell another device that this continuation is remotely available until a
later ordinary push is explicitly executed and verified.

Delivery rules:

- keep reconciliation, review corrections, and handover/evidence in coherent commits rather than one
  opaque squash;
- do not stage, commit, or push the six still-dirty protected root paths;
- stage exact reviewed paths and run `git diff --cached --check` before each commit;
- use an ordinary push to `origin dev/v10`; never force push;
- fetch immediately before pushing. If `origin/dev/v10` changed after the audited
  `4ab17fc9c` boundary, stop and reconcile the new commits rather than overwriting them;
- after pushing, verify the remote tip and both ancestry checks, then record the exact result in the
  Outcome and acceptance checklist;
- leave every source flag contained/default-off. Repository delivery is not product rollout.

External source rollout remains governed by `ROLLOUT_RUNBOOK.md`: independent source gates,
production-query truth, privacy/export/deletion, device power/quality, rollback rehearsal, and no
unresolved blocking fresh-review finding. A later session must request separate authorization before
performing any external rollout action.

## Handoff acceptance checklist

- [x] `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a` is the committed second merge with
  parents `1a112bbd5` and `9a65148a1`.
- [x] The authoritative historical reconciliation command and the exact TI-B160–TI-B170 commands,
  results, task counts, review dispositions, and tested code commits are recorded above.
- [x] All six protected root paths on this integration device remain byte-for-byte identical and
  intentionally uncommitted.
- [x] This integration device's root `git status --short` contains exactly those six protected
  paths and no reconciliation residue.
- [x] At the verified `cd9cecb74` delivery checkpoint, `origin/dev/v10` contains both former tips
  and the handover preparation; the ordinary-push result above is exact. The containing evidence
  commit intentionally resolves through `git rev-parse origin/dev/v10` rather than self-attestation.
- [x] No force push, release, tag, deployment, feature activation, or destructive migration occurred.
- [x] Implementation artifacts close the same-process collected-data deletion/rearm seam, add one
  selected-session Steps facade plus one contained read-only Trip Detail consumer, add one dormant
  exact-run deletion fence, and retire the unsafe periodic empty-session mutation while excluding
  every zero-sample row from the named product and ActivityRecognition reads. Exact Room ownership
  now binds one presentation segment per physical service run and records post-writer quiescence
  without deleting the row. Other DAO reads remain unchanged. Direct Trip Detail delete is removed.
  The artifacts accurately preserve ordinary
  source-only discovery, safe app-wide deletion, durable orphan reclamation, legacy-writer, day
  repair, retention/import/export, cold-process, broader product-query/UI, provider/device, source,
  activation, and rollout gates.

Next-session instruction: preserve the contained Trip Detail read and exact presentation settlement.
First enforce the new-v28 Steps reverse binding, logical-entry grouping, and ordinary source-only
discovery with exact capture/qualified-source evidence. Then resolve new-v28 Steps attribution,
source-local fences, typed deletion/unsupported UX, documented-zone day repair, and the existing
post-commit drain before activation. Never use `sampleCount` as Location evidence or `QUIESCED` as a
deletion predicate. Add the contained live surface, then run the exact `{Steps}` smoke. Keep periodic
cleanup retired, previous-exit orphan cleanup off the critical path, and retention/import/export as
distinct services. Do not create a generic mutation platform, permanent observer, per-row fan-out,
or claim orphan reclamation, product deletion, `QUERYABLE`, another source, ambient mode, device,
activation, or rollout gates without reproducible evidence.
