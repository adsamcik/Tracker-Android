# Tracking Infrastructure Continuation Handover

Last updated: 2026-08-27

This handover is for the next device or Codex session continuing the tracking-infrastructure
program after the local and remote `dev/v10` histories were reconciled. It is an execution
checkpoint, not a replacement for the architecture or evidence ledgers.

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
- remote delivery result: not yet executed at this handover-preparation checkpoint; a follow-up
  evidence commit is required after ordinary-push verification.

The implementation is still intentionally contained. This reconciliation does **not** mean the
tracking-infrastructure program, manual Steps, automatic Steps, ambient Steps, another source,
product history, UI, device rollout, or general availability is complete. It preserves the critical
path and adds a tested, explicit Steps writer-transition mechanism; it does not invoke the first
candidate activation in production.

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
| D. Data/writers | Checksummed WAL, source-local containment, frozen-v27 drain, dormant Steps facts/receipts/cursor, retention, event-exact queued provenance, service-run selector, exact owner fence, and explicit activation/rollback/deletion-rearm transactions exist. | Production-visible Steps state, typed correction/export/import, complete deletion-to-rearm integration proof, source-qualified lifecycle evidence, and a real source vertical before generalizing any mechanism. |
| E. History product | An internal Steps selector preserves availability, evidence, materialization, and coverage without reading current global ownership. Existing legacy readers remain available. | One production `TrackingHistoryRepository`/compatible facade and observable Steps query used consistently by Today, Timeline, Calendar, selected-day/session detail, export, and deletion. No persisted universal `DayOverview` is required. |
| F. UI | Manual source selection/prerequisite logic is source-aware and no longer assumes Location for every source. Policy-error/status presentation exists. | Truthful Steps-only product states and completeness in existing consumers; no source history UI or broad day-first journal is authorized yet. |
| G. Quality | Focused host, Room migration, architecture, lint, Detekt, release-evidence, and full repository gates have substantial evidence. Exact final code merge `9b8ab4b43` passed `ciCheck` in 21m 46s. | Current migration/device rerun where relevant, manual only-Steps end-to-end proof, device/provider/power tests, and later source-specific dynamic/failure gates. |

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
| Steps | `DEGRADED`; manual/session is `IN_PROGRESS`/`IN_REVIEW`; automatic and ambient are `BLOCKED`. | The candidate lane, historical selector, owner fence, and transition engine are foundation only. The first production activation has no ordinary caller; product query/export/import/UI and device proof remain. |
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

One `MEDIUM` test-hardening item remains: the complete production
`DefaultCollectedDataDeletionService -> AppDatabase -> StepsSessionFactWriterTransitionCoordinator`
seam does not yet have one end-to-end test that proves deletion barriers remain closed through the
re-arm callback and resulting generation. Lower-priority follow-ups are the debug `TestDataSeeder`
recovery/preferences race and the line-oriented architecture scan. These gaps prohibit a claim that
full deletion/re-arm is production-proven, even if the final full host gate passes.

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

The exact final command was:

```powershell
.\gradlew.bat ciCheck --continue --no-daemon --no-parallel --max-workers=1 `
  '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

This green `ciCheck` is host/build-quality evidence. It is not emulator provider behavior,
process-death/reboot/FGS legality, power measurement, OEM coverage, the 12 source scenarios,
production query/UI, canary, or rollout evidence.

## Root work that had to be preserved on this integration device

The main `dev/v10` checkout on this integration device contained user-owned edits that were
deliberately excluded from the reconciliation worktree. The lead declared these seven paths outside
the reconciliation delivery scope: they must survive local integration byte-for-byte and
intentionally remain uncommitted here. A clean checkout on another device must not recreate them.

| Path | Pre-integration SHA-256 |
| --- | --- |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImport.kt` | `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportCollisionTest.kt` | `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportTest.kt` | `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6` |
| `feature/statistics/src/main/res/values/strings.xml` | `EEE3E5D83C841B4ED0E0E726957E6399D48BC94A87788B1B933231D897E3DE8B` |
| `docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx` | `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28` |
| `docs/TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md` | `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332` |
| `feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardManualStartDecisionTest.kt` | `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6` |

The first four are tracked modifications; the final three are untracked. The final integration must
recompute all seven hashes before and after any stash/fast-forward/restore operation. The expected
root status after delivery contains these seven paths and no reconciliation residue; do not stage,
commit, or push them as part of this task.

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

- close the collected-data deletion-to-Steps-rearm end-to-end test gap;
- add the observable, completeness-preserving production Steps query without activating another
  writer by default;
- add typed portable Steps export/import and deletion/replay/no-resurrection proof;
- cover every existing numeric Steps consumer so partial/unavailable/materializing never becomes
  fabricated zero or a falsely awarded goal/streak;
- pass manual only-Steps through real provider evidence, durable fact, production query, and UI;
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

For implementation, create a dedicated worktree and scoped branch from clean local `dev/v10`, per
`AGENTS.md`. The next code wave is TI-410 manual/session Steps, not another shared framework:

1. Add the full production deletion-service-to-rearm integration test and any correction it proves.
2. Keep the existing TI-D107 decision, status checkpoint, and TI-B158/TI-B159 verification evidence
   synchronized with any correction or newly reproduced result.
3. Expose the existing internal Steps selector through the minimum production history/query seam,
   carrying independent availability, evidence, materialization, and coverage axes.
4. Wire all applicable existing consumers—Today, Timeline, Calendar, selected day/session, goals,
   streaks, achievements, widgets/notifications where present—without converting absence or partial
   coverage to zero.
5. Implement typed Steps portable export/import and repeat deletion/replay/no-resurrection proof.
6. Run the manual only-Steps scenario with no Activity or Location demand through
   `RECORDING -> MATERIALIZED -> QUERYABLE` and an honest UI state.
7. Run focused host gates, the complete affected suites, `ciCheck`, then device/provider/process
   evidence before considering an activation commit.

Only after manual/session Steps is product-queryable and independently gated should work begin on:

- automatic Steps with explicit Activity `CONTROL` and no control leakage;
- default-off Ambient Steps with one selected continuity provider, civil-day identity, retention,
  export/delete, and sessionless product visibility;
- Pressure, then protected Location, Activity, Wi-Fi, and Cell as independent source verticals.

## Commit, push, and rollout boundary

The user explicitly authorized committing and pushing the completed reconciliation and handover.
The lead declared the seven protected root paths above outside that scope. The authorization does
not permit a force push, history rewrite, release, tag, APK publication, Play submission, feature
activation, remote flag change, canary, deployment, or destructive migration/deletion.

Delivery rules:

- keep reconciliation, review corrections, and handover/evidence in coherent commits rather than one
  opaque squash;
- do not stage, commit, or push the seven protected root paths;
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
- [x] The authoritative command, result, task counts, and exact tested commit are recorded above.
- [ ] All seven protected root paths on this integration device remain byte-for-byte identical and
  intentionally uncommitted.
- [ ] This integration device's root `git status --short` contains exactly those protected paths and
  no reconciliation residue.
- [ ] `origin/dev/v10` contains both former tips and the handover commits; the delivery result above
  is exact.
- [ ] No force push, release, tag, deployment, feature activation, or destructive migration occurred.
- [ ] Implementation artifacts accurately preserve open medium/low review findings and source gates.

Next-session instruction: begin with TI-410 manual/session Steps and do not claim another source,
ambient mode, product query, UI, device, or rollout gate without reproducible evidence.
