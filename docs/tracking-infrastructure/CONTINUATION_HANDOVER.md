# Tracking Infrastructure Continuation Handover

Last updated: 2026-08-30

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

The current local continuation code tip is `f9b1c3c45` on `codex/ti410-deletion-rearm`. Its local
sequence is deletion/re-arm proof `3b2365547`, evidence handover `411bfe144`, the observable Steps
history facade `c5118e186`, its handover `d6e887d41`, the dormant source-deletion fence `03bbda2f1`,
its handover `bfe2e1f36`, unsafe empty-session cleanup containment `88309387d`, remaining zero-sample
product-read exclusion `24b9aeffb`, its handover `412882194`, and the contained read-only
Trip Detail Steps consumer `f9b1c3c45`, directly atop local and remote `dev/v10` at
`ffd5d372fafafceb7d9d595b95e47b89b949de83`. TI-B160 records the retained-AVD deletion proof;
TI-D108/TI-B162 record the one-selected-session read contract; TI-D109/TI-B163–TI-B165 record the
fresh R1 deletion review, scope withdrawal, dormant authority, and focused gates; TI-D110/TI-B166–
TI-B167 record the cleanup race, containment, and corrected product-read evidence; TI-D111 records
the source-local, product-first next-wave correction; TI-D112/TI-B168 record the contained Trip
Detail consumer and its adversarial boundary. These commits and this handover update are local-only:
they are not integrated into local `dev/v10` or pushed, and no new remote delivery is claimed.

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
- persist the generated segment ID into durable session state, validate exact logical/run/segment
  membership on resume, and add durable post-presentation acknowledgement before graceful cleanup;
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
git show --stat --oneline f9b1c3c45
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

1. On this device, integrate or continue from the local branch through code commit `f9b1c3c45` and
   this handover only after confirming the exact branch/source-tree status. This continuation is not
   on the remote at this checkpoint.
2. Keep TI-D107–TI-D112, the current status checkpoints, and TI-B158–TI-B168 synchronized with any
   correction or newly reproduced result. Do not reinterpret the dormant fence or cleanup
   containment as product deletion or process-death reclamation.
3. Preserve the contained read-only Trip Detail Steps states. Define an exact historical capture-set
   and qualifying-Location contract before hiding map, route, speed, accuracy, coordinates, or
   export; never infer Location from `sampleCount`. Add the contained live Steps surface and fix
   ordinary source-only list discovery. Define typed delete UX now, but keep mutation and activation
   off.
4. Persist the generated segment ID and exact logical/run/segment membership, add durable
   post-presentation acknowledgement, and relocate graceful empty-row cleanup behind it. Keep
   previous-exit crash-orphan reclamation as a later bounded slice.
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
`88309387d`, `24b9aeffb`, `412882194`, and `f9b1c3c45`, plus this updated handover, is local-only at
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
- [x] The authoritative historical reconciliation command and the exact TI-B160–TI-B168 commands,
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
  every zero-sample row from the named product and ActivityRecognition reads. Other DAO reads remain
  unchanged. Direct Trip Detail delete is removed. The artifacts accurately preserve ordinary
  source-only discovery, safe app-wide deletion, durable orphan reclamation, legacy-writer, day
  repair, retention/import/export, cold-process, broader product-query/UI, provider/device, source,
  activation, and rollout gates.

Next-session instruction: preserve the contained Trip Detail read, then persist exact segment
ownership and post-presentation acknowledgement. Resolve new-v28 Steps attribution, source-local
fences, typed deletion/unsupported UX, documented-zone day repair, and the existing post-commit drain
before any activation. Define exact historical capture-set/qualifying-Location evidence before
source-adaptive detail layout; never use `sampleCount` as Location evidence. Add the contained live
surface and ordinary source-only discovery, then run the exact `{Steps}` smoke. Keep periodic cleanup
retired, previous-exit orphan cleanup off the critical path, and retention/import/export as distinct
services. Do not create a generic mutation platform, permanent observer, per-row fan-out, or claim
orphan reclamation, product deletion, `QUERYABLE`, another source, ambient mode, device, activation,
or rollout gates without reproducible evidence.
