# Tracking Infrastructure Execution Plan

Last updated: 2026-09-02

This is the durable execution ledger for the architecture in
`docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md` and the accepted refinements in
`ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md`. It records implementation boundaries and proof; it
does not restate the architecture.

## Program truth

- Database v28 has never shipped. We may reshape `MIGRATION_27_28` and schema `28.json` directly,
  but the migration must preserve every released v27 user fact and must be executed against a
  populated v27 database before schema freeze.
- Keep the proportional safety spine: one policy authority, immutable intent, direct purpose
  demands, one physical owner per source, qualified source-native delivery, one mutation owner,
  source-local projection, typed facts, and one production history facade.
- Do not rebuild the removed generic attribution/accounting platform. A mechanism becomes shared
  only after at least two real source verticals need the same semantics.
- Ambient collection is a first-class, explicit purpose where it produces useful history. It is
  independently consented and default-off. A provider only runs for direct capture, control, or
  ambient demand; enrichment never starts or retains a provider. When a demanded provider produces
  a fresh, eligible delivery, that delivery is durable before later capture/ambient classification.
- Every source can be the only captured source. Expensive sources are optional enhancements, never
  hidden prerequisites. Optional Location context may enrich Wi-Fi or Cell only when Location is
  already running under compatible authority and freshness.
- The corrected R1 review authorized TI-410's dormant source-local manual/session Steps writer,
  durable identity bridge, and exact destination fence. Merge `9b8ab4b43` now adds immutable
  service-run history selection and a contained, source-specific atomic activation, rollback, and
  post-deletion re-arm boundary. Commit `9aeb8853a` additionally binds one presentation segment per
  physical service run and records exact post-writer quiescence without reclaiming rows. Legacy
  generation 1 remains the only active production owner; ordinary source-only discovery, logical-
  entry grouping, contained source materialization/read surfaces, and exact selected deletion/day
  repair now have host proof through `b5698e635`. Portable typed export/import, completeness-safe
  numeric consumers, the provider-to-query device gate, ordinary activation, and an explicit release
  action remain blocked. Test correction `d067704a9` and the passing full host aggregate preserve the
  no-reclamation boundary. Other materializers, automatic/ambient Steps, broad history UI, and every
  other source remain blocked by their independent gates. Commit `b7d4900cf` adds a second exact,
  immutable Steps session-fact binding for manual plus automatic capture while keeping generation 1
  manual-only and executable for retained history. It changes no rollout state, provider demand,
  destination owner, or ordinary activation; full automatic Steps remains independently gated.
- Local commits are authorized and are the unit of integration. Each commit must contain one
  gate-sized, dependency-coherent slice, stage only reviewed paths, record its scoped verification,
  and leave known blocked assertions explicit. A failing or unverified slice is not committed as a
  completed checkpoint merely because it compiles.
- Wi-Fi durable admission is locally integrated through `8000f4b16`. Fresh nonempty observations
  now use a privacy-minimized, replay-stable delivery identity; Room owns source-sequence allocation;
  delayed callbacks must retain exact historical registration, manifest, policy, purpose, session,
  run, lease, and cutoff authority; and finite active attempts remain direct-demand-only. This is
  admission proof, not a Wi-Fi product fact/query/UI, deletion/export, device-radio, activation, or
  rollout decision.

## Critical path and gates

```text
v28 minimum schema + populated migration proof
        ↓
physical ownership + observed-time authorization
        ↓
item-level admission + stable delivery identity
        ↓
thin data-plane writer + fences instantiated with a real source
        ├─ Steps (preferred first) ────┐
        ├─ Pressure                   │
        ├─ Location                   ├─ source query adapter → existing product surfaces → source gate
        ├─ Activity                   │
        ├─ Wi-Fi                      │
        └─ Cell ──────────────────────┘
```

Steps → Pressure → Location → Activity → Wi-Fi → Cell is the default scheduling order, not a
dependency chain. A source may proceed when its shared and source-specific prerequisites pass; it
never inherits another source's capability, device, privacy-product, or rollout blocker.

Android lifecycle legality, cancellation, privacy, deletion/import, performance, and evidence run
across every step. A later row cannot be `DONE` while an earlier acceptance assertion it depends on
is unverified.

## Current wave and file ownership

| Slice | Owner | Files/modules | Edit permission | Exit evidence |
| --- | --- | --- | --- | --- |
| FND-01 startup/lifecycle/rollout containment | Lead orchestrator | app startup/deletion/receivers; tracker API/service/coordinator/recovery/rollout/broker; focused tests | committed through `648f894a4` | complete tracker-engine, Activity, core, app, and Dashboard host suites pass; every default source is contained; admission requires one executable durable lane; Activity callback durability is bounded; blocked post-deletion recovery rearms only after authoritative repair; no product writer activated |
| EP-02 execution evidence | Lead orchestrator | `docs/tracking-infrastructure/*.md` | write | checkpoint, schema count, verification, blockers, and rollback state agree with the latest verified local commit |
| R1F-01 data/migration adversary | fresh `gpt-5.6-sol` reviewer | committed authority/startup/rollout/ledger diff and v27→v28 boundary | read-only, complete | `FAIL`: acquisition was not atomically coupled to a source lane/cursor; live projection poison was global; retained v27 diagnostics and unknown-end range semantics needed correction |
| R1F-02 Android/power/privacy adversary | fresh `gpt-5.6-sol` reviewer | committed service/lifecycle/broker/provider/action diff | read-only, complete | `FAIL`: partial source rollout was all-or-nothing; terminal optional control retried forever; Cell replay identity remains source-gated |
| R1F-03 product/scope adversary | fresh `gpt-5.6-sol` reviewer | committed diff plus Steps-first plan and existing product queries | read-only, complete | `FAIL`: control reachability was coupled to Activity capture rollout; Steps modes were bundled; status evidence and execution artifacts required correction |
| R1C-01 corrected R1 boundary | Lead orchestrator | source activation/cursor retention, reachable subset, executable admission, callback closure, control-only rollout, source-local recovery, terminal recovery classification, v27 truth | `DONE`; fresh three-perspective pass at `d51723280`, bounded corrections in `bfa0c9da1` and `648f894a4` | no shared blocker/high requires another framework wave; approval is limited to TI-410; Cell identity and each non-Activity pre-WAL handoff remain explicit source gates |
| ST-02a manual/session Steps | Lead orchestrator with non-overlapping focused owners | Steps runtime, source-local cursor/facts/correction/deletion/export/query, existing product/start adapters | in progress; dormant writer/retention, selected-detail/read/list composition, exact presentation settlement, and typed selected deletion/day repair committed through `b5698e635`; numeric-consumer, portable retention/export/import, and device gates remain | manual only-Steps reaches `RECORDING`, `MATERIALIZED`, and a production query with exact capture/registration set `{Steps}`—no Location, Activity, Pressure, Wi-Fi, Cell, control, or ambient demand—and no generic platform; every existing manual-start surface, completeness-sensitive consumer, and typed portable round trip agrees |
| ST-02b automatic Steps | contained Steps binding owner; product continuation unassigned | Steps vertical plus declared Activity control and automation evidence | exact V2 manual+automatic writer attribution and rollback recognition committed at `b7d4900cf`; provider demand, trigger-to-query integration, control retention/export, and activation remain blocked | automatic only-Steps uses explicit control-only Activity, bounded no-export control retention, and a legal fresh trigger; control facts never enter Steps or Activity history |
| ST-02c default-off ambient Steps | unassigned until ST-02a proof | app-scoped counter continuity, ambient consent/retention/export/delete/day allocation/query | blocked | opted-in ambient Steps is sessionless, minimized, deletion/export complete, day-stable, and product-visible; default remains off |
| PAR-01 local foundation convergence | Integration owner | clean local `dev/v10` integration worktree and the 33-commit continuation | `DONE` at `4b25d39e2`; no push | `ciUnitTest` and `ciCheck` pass on the exact continuation, the branch is rebased/up-to-date, local `dev/v10` fast-forwards, and the six handover-protected root paths remain hash-identical |
| PAR-02 exact Steps selected-session deletion | `codex/ti-steps-session-deletion` | Steps deletion API/service, exact DAO mutations, day repair, focused tests | `DONE`; corrected after fresh overflow review, rebased and fast-forwarded into local `dev/v10` at `b5698e635` | exact new-v28 candidate-owned Steps-only scope deletes transactionally; active, legacy, mixed, mismatched, or unverifiable ownership is typed and non-mutating; delayed replay cannot resurrect; stored manifest/summary zone authority repairs affected days; bounded dependency overflow fails before mutation |
| PAR-03 Pressure capability-normalized requests | `codex/ti-pressure-capability-plan` | Pressure request normalization/runtime and focused tests | `DONE`; rebased and fast-forwarded into local `dev/v10` at `afcb009e1` | effective sampling/report latency follows actual sensor minimum-delay/FIFO capability; equivalent physical tuples do not restart; distinct tuples use one fenced replacement; no-FIFO mode never claims batching |
| PAR-04 Cell atomic delivery admission | `codex/ti-cell-durable-admission` | Cell runtime and focused tests | `DONE`; rebased and fast-forwarded into local `dev/v10` at `862e839eb` | one minimized boot-domain/provider-time/content delivery identity enters atomic ingress; ingress allocates sequence; duplicate/restart handling is durable; no radio/subscription identity or unbounded active attempt is introduced |
| PAR-05 Wi-Fi atomic delivery admission | `codex/ti-wifi-durable-admission` | Wi-Fi runtime, shared atomic ingress authority, compatibility checks, and focused Room tests | `DONE`; corrected and hardened after review, rebased, and fast-forwarded into local `dev/v10` at `8000f4b16` | callback/result identity is replay-stable and privacy-minimized; empty or stale state is not repeatedly stored; prerequisites, current policy, exact historical authority, complete manifest checksums, and cutoff are rechecked for every delayed callback/retry; active attempts remain finite and direct-demand-only; exact real-Room replay allocates one sequence while identity collisions fail closed |
| PAR-06 Pressure atomic delivery admission | `codex/ti-pressure-durable-admission` | Pressure runtime, shared atomic ingress/checkpoint authority, and focused Room tests | `DONE`; corrected after fresh NO_GO review, rebased, and fast-forwarded into local `dev/v10` at `3a3bcbadb` | one complete Pressure window is admitted without a preallocated sequence; exact replay is idempotent; checkpoint repair requires the retained WAL row's exact provider and authorization envelope; retention, intrinsic-window, identity-collision, and rollback cases fail closed |
| PAR-07 Location immutable observation floor | `codex/ti-location-observation-floor` | protected Location runtime and focused tests | `DONE`; corrected after fresh review and fast-forwarded into local `dev/v10` at `a4caa9f12` | cached pre-acceptance fixes, synchronous callbacks before durable acceptance, stale/future/cutoff-invalid fixes, and callbacks from superseded contexts fail closed; compatible authorization refresh raises but never weakens the immutable floor; duplicate older WAL ordinals cannot regress stop completeness; the existing canonical Location writer remains unchanged |
| PAR-08 Activity callback freshness envelope | `codex/ti-activity-freshness-envelope` | Activity receiver, source delivery factory, atomic Room ingress, and focused tests | `DONE`; corrected after fresh review and fast-forwarded into local `dev/v10` at `3c7b28545` | negative/overflow/future/pre-acceptance provider times are excluded without fabricating elapsed zero; valid siblings retain original indexes; durable selection alone chooses recognition-versus-transition Activity publication; discarded and retry settlement remain truthful |
| PAR-09 Activity automatic trigger provider authority | `codex/ti-activity-trigger-envelope` | Activity automatic projection, outbox validator, action repository, and focused tests | `DONE`; fresh review and local fast-forward at `0a6a8f545` | only complete control-eligible Activity stamps can create an automation effect; the exact historical provider generation must cover the observation under its accepted-inclusive/retired-exclusive lifetime at projection delivery and every pre-start authority recheck; control remains absent from captured history |
| PAR-10 exact automatic-capable Steps binding | `codex/ti-steps-automatic-binding-v2` | Steps executable binding catalog, contained transition/re-arm/deletion/projector recognition, and focused tests | `DONE`; fresh exact-diff review and local fast-forward at `b7d4900cf` | generation 1 remains immutable manual-only history authority; generation 2 is exact manual+automatic authority; manifests, rollout, lanes, deletion, rollback, and re-arm resolve one installed generation/mask without activating a writer or registering Activity/provider demand |

No two current owners may edit the same file. Read-only findings become lead-owned only after
disposition. The root checkout's six handover-protected paths remain outside this program; every
implementation and convergence worktree starts clean and stages exact reviewed paths.

### Parallel convergence rules

The user-directed parallel wave supersedes the former *development scheduling* embargo after the
manual Steps device harness, but it does not convert missing device evidence into proof and does not
authorize any source, writer, automatic mode, ambient mode, or rollout. Work is parallel only when
its file ownership and semantics are independent:

1. Source-local acquisition/admission/runtime changes may proceed beside Steps product-safety work.
2. Schema, migration, shared history, shared export/import, shared deletion, and documentation have
   one integration owner; workers may not opportunistically generalize them.
3. A completed branch is reviewed and runs focused tests, Detekt, lint, and schema drift where
   applicable. It then rebases onto the latest local `dev/v10`, reruns the affected gate, and merges
   from the clean integration worktree. Documentation is updated immediately after acceptance.
4. Only one branch is integrated at a time. The next free worker takes the highest dependency-ready
   thin slice among remaining Steps, Pressure, protected Location, Activity, Wi-Fi, and Cell work.
5. Provider, process-death, reboot, FGS, battery, OEM, rendered UI, and accessibility claims remain
   blocked until their named device evidence runs. Host work can reduce code risk but cannot satisfy
   those rows by implication.

## Unreleased-v28 table boundary

The current schema contains 51 released-v27 tables and exactly 18 narrowly owned v28 additions. This constrains
TI-180 as follows:

| Treatment | Tables / records | Rule |
| --- | --- | --- |
| Preserve | All 51 v27 tables, including legacy facts, projection/outbox state, rollout state, summaries, and radio rows | No physical drop or reinterpretation in the first slice. Retire a released table only after its production callers and pending effects are drained under a later explicit migration decision. |
| Keep as v28 spine | `source_policy_authority`, `source_consent_epoch`, manifest/source versions, lifecycle-intent versions, desired actions | Complete and test these records; do not replace them with another authority. |
| Reshape directly | `source_policy`, `source_demand`, `provider_registration_generation`, `provider_registration_eligibility` | Make policy purpose-specific; make demands express floors/ceilings/retention; make provider generations physical-only; replace eligibility with independently revisioned observed-time `source_authorization` intervals. |
| Extend additively | `source_event_wal` and released runtime/plan state | Preserve legacy rows as `LEGACY_UNQUALIFIED`; add delivery identity/range, authorization, all applicable epochs, use/retention class, counts, and origin. Runtime baselines rotate only with real physical/data-epoch changes. |
| Add only when its transaction contract is ready | source cursor/gap, scoped deletion fence, destination owner, fact membership, day/source completeness, Wi-Fi/Cell headers | Keep records narrow and source-driven. Do not create generic contribution/accounting tables. |

The current 69-table `28.json` is therefore not frozen. The bounded v28 additions now include the
narrow released-v27 recovery obligation/target records, Activity automatic-action/epoch state,
source-local product-lane activation/cursor/retention state, immutable selected-session history,
and the dormant source deletion fence required by later reviewed slices. They do not recreate the
removed generic Phase 3 platform. Populated Android
migration/open/reopen execution and the host-tested process-wide startup fence are necessary but
not sufficient to close TI-184: connected migrate-to-runtime ordering, backup recovery,
export/import, and production-query assertions must also pass.

## Work items

Statuses are `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `IN_REVIEW`, or `DONE`. `DONE` means local
implementation is integrated, scoped verification passes, blocking review findings are resolved,
and a production-query assertion exists where the item produces user-visible facts.

| ID | Design / invariant | Workstream | Files or modules | Dependencies | Acceptance assertions | Verification | Flag and rollback | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TI-180 | Final plan §4.6; TI-D057/TI-D068; one authority, privacy epochs | A/B/D | `:core:base` v28 entities, DAOs, `AppDatabase.kt`, `AppDatabaseMigrations.kt`, `28.json` | TI-100, v28 audit | Keep all 51 released-v27 tables; reshape only unshipped v28 state; physical configuration generation is independent from immutable authorization intervals; WAL can stamp all applicable epochs, use/retention class, and immutable structural-zone/day-allocation identity for sessionless facts | `:core:base:testDebugUnitTest`; `:core:base:compileDebugAndroidTestKotlin`; schema drift task | No runtime flag; rollback binary remains v28-schema-capable and fail-closed | `IN_PROGRESS` |
| TI-181 | Final plan §4.3–4.4; TI-D057; immutable intent, purpose separation | A/B | broker entities/DAO, `SourceRegistrationRepository`, supervisor boundary | TI-180 | Unchanged normalized provider configuration survives session/manifest/consent changes without stop/start; authorization revision rotates at the exact boot/elapsed boundary; delayed callbacks resolve authorization by observed time | broker/registration property tests; provider fake crash/retry test | `broker_v2:<source>` required; rollback retires new demand and retains facts | `IN_PROGRESS` |
| TI-182 | Final plan §8.3; TI-D055/TI-D071; replay safety | D | WAL envelope/DAO/ingress/payload codecs | TI-180, TI-181 | Stable logical delivery identity is independent of local retries; authorization-homogeneous units are stored; exact cache replay is zero-effect across process death; retained-floor metadata cannot wait for pruned ordinals | Atomic delivery admission, sparse authorization filtering, delayed-generation acceptance, exact replay/collision and rollback tests pass; Activity now uses the seam and exact duplicates produce no repeated motion/backend effect; the other five adapters, cursor/gap processing, deletion fences and retained-floor/no-resurrection proof remain | `writer_v2:<source>` off; rollback stops consumption, never deletes WAL/facts | `IN_PROGRESS` |
| TI-183 | Final plan §8–9; one writer, no resurrection | D | source cursors/gaps, deletion fences, destination owner fence | TI-180 | One cursor/gap lane per source; scoped source/purpose/time deletion fences replay and import; one persisted destination owner generation fences both legacy and target writes | Steps exact owner checks, immutable service-run history selection, atomic activation/rollback, and ABA-safe empty-generation re-arm pass at `9b8ab4b43`. `03bbda2f1` adds the payload-free source/purpose/logical-run fence honored by the dormant candidate Steps lane and selected-session reader. `b5698e635` is its first narrow production producer: exact candidate-owned selected deletion writes the fence/retractions and stored-zone day repair transactionally. Active legacy Steps and import remain outside that contract; portable import no-resurrection and every other source fence remain blocked | Per-source owner flag; rollback changes owner only after contain/drain/catch-up | `IN_PROGRESS` |
| TI-184 | TI-D068/TI-D085/TI-D088/TI-D091; migration safety | A/C/D/G | v27 recovery tables/DAO, frozen decoder/projectors, process startup fence, `AppDatabaseMigration27To28Test`, backup, production query/export/delete/import paths | TI-180–TI-183 | Populated v27 opens, migrates, closes, reopens, queries, exports, deletes, and recovers without fabricated authority or lost facts; a transaction-captured admission high-watermark isolates exact released v1 targets from live v2 projections; pending legacy effects retain their WAL until bridged or terminally suppressed; checksum-valid v27 WAL drains once through only its frozen released contract before any provider/service/policy path opens; interrupted automatic state and old Activity effects cannot restart | Eight populated migration cases pass on `Medium_Phone(AVD) - 16`; host startup-gate, deletion precedence, frozen-drain, writer-consumer, redelivery and finalizer shards pass. Connected migrate→startup ordering, production backup-wrapper proof, cold empty-target restore, partial-database merge containment, portable export and production history queries remain | No rollout flag; startup fails closed. Migration backup remains recoverable; live v2 writers start at cutoff + 1 and Location stays legacy-owned | `IN_PROGRESS` |
| TI-210 | Final plan §4.4; TI-D057/TI-D059; one physical owner | B | app-scoped `SourceSupervisor`, six adapters, demand reconciler | TI-181 | Exactly one physical owner/source; only direct capture/control/ambient demands affect lifetime; compatible demands merge; removal/reconcile is idempotent; optional context cannot acquire a provider | six manual/automatic registration-set integration tests; process-death reconciliation tests | `supervisor_v2:<source>`; rollback hands ownership back only after callback barrier | `BLOCKED` |
| TI-211 | Final plan §4.5/§9; no ghosts, Android legality | C | coordinator, service gateway, desired-action outbox/reconciler | TI-180, TI-210 | Durable intent precedes external start; accepted runtime follows; real start origin and exact FGS type union are used; boot/automation epoch fences stale starts; `STOPPING`/`FINALIZED` never restart | host transaction-boundary, redelivery, stop-ordering, previous-exit/force/explicit finalizer and permission-revocation tests pass; reboot/process-kill instrumentation and Android-version/device legality remain | `lifecycle_v2`; rollback finalizes or safely resumes durable intent | `IN_REVIEW` |
| TI-212 | TI-D078/TI-D083; battery honesty | B/G | plan optimizer, source adapters, diagnostics | TI-210 | User-visible modes correspond to measured physical mechanisms; identical rungs collapse; passive/callback/FIFO modes are preferred; active probes are finite, direct-demand-only, stop after qualified evidence, and cannot be started by enrichment | fake plan properties plus Android Studio Power Profiler/system trace/Macrobenchmark and source-relevant device runs per rung | Per-source QoS ceiling; rollback selects cheaper proven rung | `BLOCKED` |
| TI-213 | TI-D054; sole-source start truth | C/F | Dashboard start action, requested-source prerequisite evaluator, permission launcher tests | authoritative effective source selection | Manual only-X requests exactly X's current platform prerequisites; Pressure/Steps/Activity start with Location denied; Wi-Fi/Cell may request fine Location only for their own platform operation and never enable Location capture | 17 parameterized source-aware decision assertions and focused Dashboard compile/tests pass; device permission-result flow and other entry-surface audit remain | No rollout flag; fail closed only for the selected source's unmet prerequisite | `IN_REVIEW` |
| TI-310 | TI-D078–TI-D083; freshness, minimization, no fabricated history | B/D | connectivity runtime support, radio backends/runtimes, payload codec, permission surfaces and tests | current runtime foundation | Freshness is evaluated per child; missing/non-positive/future/stale provider time cannot become payload; confirmed fresh empty differs from absent/failed/stale; Wi-Fi v2 retains item time with v1 decode compatibility; new payloads expose no radio/subscription identifier; scan permission matches used APIs; exact live replay is zero-effect only after durable/duplicate handoff; paid scans/refreshes are finite and direct-capture-only while passive callbacks remain registered | focused admission/prerequisite/codec/cancellation/budget tests pass; app compiles; durable restart identity, runtime-fake tests, calibrated budgets and device proof remain | No flag; fail-closed containment on existing path | `IN_REVIEW` |
| TI-311 | TI-D055/TI-D056; purpose separation | B/D | source delivery models, WAL admission, source-specific batch splitters | TI-181, TI-182 | Provider observations and operational request outcomes are distinct; attempts/ticks never qualify `RECORDING`; every admitted unit is authorization-homogeneous; control evidence is bounded and absent from product/export | Activity callback batches commit once, preserve canonical-to-original sparse selection, and real Room proof omits pre-registration members while retaining writer-stamped capture/control eligibility; the other five boundary splitters and control-only history/export assertions remain | Behind per-source writer flag; rollback retains bounded operational evidence | `IN_PROGRESS` |
| TI-312 | TI-D060/TI-D063; performance and cancellation | D/G | `DurableSourceIngress`, sink, dispatcher, runtimes, writer queue | TI-182 | Bulk admission uses bounded transactions; synchronous global drain removed; `CancellationException` always propagates and never consumes poison retry budget; pressure checkpoint writes are amortized | generic cancellation guard test passes; boundary injection and stress/queue/latency tests remain | Writer queue kill switch; rollback drains or leaves durable cursor | `IN_PROGRESS` |
| TI-320 | Final plan §8; TI-D061/TI-D071; one writer | D | thin data-plane `TrackingWriter`, source lanes created only with real verticals, typed destinations | TI-182, relevant TI-183 fence, TI-312 | One serialized data-plane mutation boundary; the active source lane commits cursor, receipt, typed mutation, membership, and invalidation together; poison blocks only that lane. Policy and lifecycle retain their own atomic control-plane transactions | the dormant Steps lane commits self-contained fact receipt, source evidence, failure, and exact fenced cursor atomically and isolates poison; `9b8ab4b43` adds immutable run selection plus contained atomic activation/rollback/deletion re-arm. TI-B171–TI-B178 prove bounded selected/live/recent query composition, and `b5698e635` proves exact selected candidate membership removal, evidence/dirty invalidation, and stored-zone day repair. Ordinary activation, typed export/import, legacy/import deletion coverage, broader consumers, and an explicit release action remain | `writer_v2:<source>` individually gated | `IN_PROGRESS` |
| TI-321 | Final plan §8.3; correction safety | D | source-specific logical fact/range IDs and mutation commands delivered with each source vertical | TI-320 and relevant source vertical | Delivery receipt is distinct from stable logical fact identity; `UPSERT`, `DELETE`, and bounded `REPLACE_RANGE` retract obsolete facts/memberships; replay reaches identical state | one→zero→two projector upgrade and correction/retraction tests per implemented source | Same source writer flag; rollback preserves last canonical owner | `BLOCKED` |
| TI-322 | Final plan §8.5; deletion/export privacy | D/E | deletion, import staging, export, keyed radio identity | Steps-local proof in TI-410 and TI-410C, then each relevant source vertical | Generalize only proven per-source mechanics: delete then replay/backfill/import cannot resurrect; merge import stages provenance-bearing base facts through writer; portable export includes released sources and excludes control/raw radio identity; radio key rotation fences old tokens | delete/import/replay and source-only export tests added with each vertical | Import/export format version; rollback never bypasses fences | `BLOCKED` |
| TI-323 | One writer; Location protection | D/G | rollout owner state, legacy and target destination gateways | TI-183 and an actual candidate writer for the source | Owner generation is rechecked inside destination transaction; cutover waits for cursor catch-up and legacy drain; rollback keeps all facts queryable; Location legacy owner remains until shadow decision | Steps proves exact owner checks plus contained activation, contain/drain rollback, and deletion generation reconstruction at `9b8ab4b43`; production query/export/deletion continuity, process/crash interleaves, explicit activation, and every non-Steps writer remain gated. Location stays legacy-owned | `destination_owner:<source>`; data-preserving rollback | `IN_REVIEW` |
| TI-410 | Source contract: Steps manual/session vertical | B/D/E | Steps runtime, typed boundaries/intervals, Steps-local writer/cursor/delete/export/query adapter | corrected R1 activation/recovery boundary, relevant TI-180–TI-183/TI-312 substrate | App-scoped counter; durable baseline; baseline/covered-zero/positive/partial distinctions; positive post-baseline delta is `RECORDING`; session-local correction, no-resurrection, export and one production query work while every unrelated projector is absent | Dormant writer replay/poison/retention, paired session/run identity, zero-covered retention, exact owner fencing, immutable run selection, contained atomic activation/rollback/deletion re-arm, and the production deletion-service/barrier seam pass through `3b2365547`. `c5118e186` adds a read-only observable selected-session facade; `03bbda2f1` adds a dormant exact-run deletion fence that the candidate lane/read path honor; `88309387d` retires the racy periodic zero-row database mutation and filters daily/live/source/all-time activity reads to positive samples; `24b9aeffb` adds the same rule to app-age/hour/night/dawn and ActivityRecognition reads. Every zero-sample row is excluded from those named queries; other DAO reads remain unchanged. Fresh R1 adversaries blocked production wiring because legacy Steps, teardown races, daily summaries, retention/import, and typed UI failure remain outside that fence. `9aeb8853a` gives each physical service run one exact presentation segment and post-writer acknowledgement. The reverse binding, logical-entry composition, contained live/recent history surfaces, and source-aware discovery now precede `b5698e635`, which adds the exact candidate-owned selected deletion fence/retractions plus bounded stored-zone day repair. Remaining gates are the exact `{Steps}` device smoke, portable retention/export/import, numeric consumers, automatic control separation, default-off ambient, and ordinary activation. | `writer_v2:steps`, `history_v2:steps`; no ordinary production activation binding exists, and the deletion-fence producer accepts only the exact selected candidate scope | `IN_PROGRESS` |
| TI-410B | Source contract: Steps automatic mode | B/C/D/E | TI-410 Steps lane plus declared Activity control, automation lifecycle, bounded control evidence | TI-410; explicit step-corroboration decision; legal fresh Activity trigger; purpose-limited control retention/no-export proof | Only Steps is captured; Activity is `CONTROL` only; fresh control starts the logical session; positive Steps delta records; control-only observations never enter history/export; contained optional control is terminal rather than retry work | `b7d4900cf` proves exact immutable V1 manual-only and V2 manual+automatic writer attribution, deletion recognition, rollback, and re-arm with `188/188` focused host tests. Trigger-to-provider-to-writer-to-query integration, process/reboot/stale-trigger, bounded control retention/no-export, device proof, and activation remain | `automatic_v2:steps`; rollback disables automatic demand without disabling manual Steps | `BLOCKED` |
| TI-410C | Source contract: Steps ambient mode | A/B/D/E | app-scoped shared counter, ambient policy/consent, civil-day identity, retention/export/delete/query | TI-410; immutable ambient day allocation; approved retention controls | Default off; opt-in sessionless continuity; no fabricated zero before coverage; explicit freshness/coverage/completeness; delete/export and consent reset are complete and visible | ambient-only day, session overlap partition, time-zone/DST, process/reboot, consent/delete/export, production query assertions | `ambient_v2:steps`; rollback removes ambient demand and retains already authorized facts per policy | `BLOCKED` |
| TI-411 | Source contract: Pressure | B/D/E | Pressure FIFO/window runtime, typed segments/trend | shared minimum substrate, Pressure-relevant TI-212/TI-312 | Fresh samples durable; recording differs from stable summary; real FIFO/batching is used when supported; no default ambient; no uncalibrated elevation claim | source tests, FIFO/non-FIFO device matrix, production history assertion | `writer_v2:pressure`; preserve compatibility reads | `BLOCKED` |
| TI-412 | Source contract: Location | B/D/E | location runtime, existing canonical writer, shadow comparator | shared minimum substrate, Location-relevant TI-211/TI-212/TI-323, explicit owner decision | Passive↔active changes follow direct floors and legality; post-start accuracy/freshness qualifies recording; sole-source works; optional context never blocks primary; no second active canonical writer | source/device/shadow/query tests | Existing writer remains owner until explicit cutover; immediate owner rollback | `BLOCKED` |
| TI-413 | Source contract: Activity | B/D/E | shared transition/activity runtime and typed movement bands | shared minimum substrate, Activity-relevant TI-211/TI-212 | Transition control is purpose-limited; capture is separate; fresh STILL semantics hold; sampled mode used only for direct quality need; stale automation cannot start | atomic PendingIntent delivery and zero-effect replay tests pass; freshness threshold/epoch trigger fencing, source-local materializer, process/reboot and production query tests remain | `writer_v2:activity`; control remains independently revocable | `BLOCKED` |
| TI-414 | Source contract: Wi-Fi | B/D/E | Wi-Fi backend/runtime, identity-free headers first; keyed snapshots only if identity products are approved | TI-180–TI-183, TI-210/TI-212/TI-310/TI-312, Wi-Fi product-tier decision | Cached/broadcast/opportunistic acquisition persists only fresh new-in-effect results; bounded active attempts only when selected; identity-free counts/band/quality tier is truthful; identity-dependent screens stay unavailable until purpose/epoch HMAC and rotation exist; optional Location context follows the explicit compatibility matrix | manual/automatic/ambient, cache replay/restart, API/OEM, query/export/delete and product-tier tests | `writer_v2:wifi`; disable attempts first, retain passive demand if authorized | `BLOCKED` |
| TI-415 | Source contract: Cell | B/D/E | Cell backend/runtime, identity-free headers first; ephemeral SIM grouping/keyed identity only if justified | TI-180–TI-183, TI-210/TI-212/TI-310/TI-312, Cell product-tier decision | Callback/cached state uses per-item provider time; sparse refresh is bounded and never promised wake-reliable; partial SIM failures are explicit when a non-identifying slot can be established; identity-free technology/quality output is queryable and identity products remain unavailable until proven | source, multi-SIM/API/OEM/doze/query/export/delete and product-tier tests | `writer_v2:cell`; disable refresh while retaining callbacks if authorized | `BLOCKED` |
| TI-500 | TI-D059/TI-D069; product proof | E | minimum `TrackingHistoryRepository` facade over existing Today/Timeline/Calendar/detail/map; no paged Days contract yet | first source production-query slice, then each source | `observeToday`, `observeDay`, `observeSession`, stable fact lookup and source completeness expose every implemented source/ambient fact without Location/session fabrication; verified zero differs from absent/disabled/unavailable/materializing/partial/degraded/failed; structural day identity is stable; optional context is separate | production repository assertions per source/mode; midnight/DST/time-zone tests; every steps/goal/widget/notification/game/achievement/streak consumer preserves completeness | `history_v2:<source>`; dual-read fallback while typed facts remain accessible | `BLOCKED` |
| TI-510 | Final plan §7, proportional UI scope | F | existing product surfaces, source detail/status/explanation | TI-500 | Sole-source and ambient facts are visible and actionable in existing surfaces; accessibility and truthful completeness pass; a new day journal is built only if evidence shows existing surfaces cannot satisfy the contract | presenter/Compose/screenshot/TalkBack/large-text/reduced-motion/device tests | UI flag only; rollback cannot hide typed-only facts | `BLOCKED` |
| TI-600 | Final plan §10–11; evidence rollout | G | verification matrix, runbook, diagnostics | per-source vertical | Each source passes independently through production query/UI; energy and quality are measured; rollback rehearsed; no unresolved fresh-review blocker/high; external rollout still requires user authorization | exact commands/device links recorded in `VERIFICATION_MATRIX.md` | Steps → Pressure → Location → Activity → Wi-Fi → Cell by default | `NOT_STARTED` |

## Immediate gate

1. The contained Steps transition is `IN_REVIEW`, not activated. Its full
   `DefaultCollectedDataDeletionService -> AppDatabase -> closed barriers -> re-arm` integration
   test, exact run-to-presentation settlement, reverse binding, bounded logical-entry/list
   composition, contained live/recent UI, and exact selected deletion/day repair now pass. Preserve
   TI-410's remaining product gates: truthful numeric consumers, portable retention/export/import,
   one exact `{Steps}` provider-to-query device execution, centralized typed manual start, automatic
   control separation, and default-off ambient Steps.
   Do not broaden this into another platform or invoke first activation as an implementation shortcut.
2. Continue TI-180 from its accepted slices: v28 preserves all 51 released-v27 tables, replaces the
   unshipped eligibility table with `source_authorization`, separates physical provider generation
   from authority, and now contains source-local use/retention, cursor, deletion, and owner records
   where a concrete vertical justified them. General scoped deletion/import and additional source
   lanes remain deliberately unimplemented until their verticals require them.
3. Preserve the now-passing populated v27→v28 fixture on `Medium_Phone`: all six typed source
   families survive, unprovable runtime is terminal before production DAO reads, authority remains
   fail-closed, exact v1 projection targets are durably isolated, and production deletion/reopen
   proves no Location-child resurrection. The eight-case suite also covers an unknown target,
   pruned-WAL admission high-watermark, and an outbox-only recovery obligation.
4. Treat the process-single-flight startup fence, deletion precedence, prepared service start,
   recovery finalizers, and contained rollout state as implemented host-tested foundation. Fresh
   corrected R1 found no additional shared blocker; connected/device proof remains required.
   Continue adopting the atomic delivery-admission seam source by source. Activity is the
   first integrated adapter: one PendingIntent callback becomes one canonical delivery, sparse Room
   admission maps back to exact original members, exact duplicates are transiently zero-effect, and
   publication is withheld unless recovery reaches the admitted ordinal. The remaining five
   splitters, source cursors/gaps, scoped deletion and retained-floor no-resurrection remain separate
   gates.
5. Carry the locally passing TI-213 Dashboard decision into device permission-result evidence and
   audit every other manual start entry point; do not broaden this into history UI work.
6. Make physical reconfiguration provider-specific. Preserve the prior registration through
   replacement acceptance only where the Android/provider API offers generation-addressable overlap;
   otherwise use a fenced break-before-make transition whose unavoidable gap is explicit and whose
   rollback/retry behavior is proven with provider fakes. Do not claim a universal zero-gap handoff
   before enabling any `broker_v2:<source>`.
7. Continue manual/session Steps from the existing source-local receipt/fact/coverage/owner records,
   cursor/drain, immutable service-run selection, contained transition, and exact presentation
   settlement. Preserve the implemented reverse binding, logical-entry grouping, source-aware
   discovery, typed membership, contained production query/UI, and exact selected deletion. Add
   portable retention/export/import and make every numeric consumer completeness-aware without
   activating the candidate writer by default. Gate automatic Steps separately on declared
   control-only Activity plus bounded no-export control retention and a legal fresh trigger. Gate
   default-off ambient Steps separately on consent, civil-day identity, retention, export/delete,
   and sessionless product proof. Do not restore the removed generic accounting or dirty-day
   platform; generalize only after concrete Steps and Pressure evidence.

## Recorded blockers and explicit decisions

- A local `Medium_Phone` AVD was available and the seven-case populated v27→v28 migration suite passes.
  This proves schema/open/reopen/fact/deletion containment, not the runtime legacy drain. Process
  startup ordering is host-tested at `f14a4a2b1`; connected migrate-to-runtime races, production
  backup-wrapper recovery, provider behavior, reboot, Doze, permission, FIFO, radio, and
  representative OEM/device evidence remain unverified.
- Final retention durations and privacy copy remain product/privacy decisions. Implement policy
  fields and deletion mechanics without choosing final values.
- Whether step corroboration remains automatic control still requires explicit resolution before
  TI-410/TI-413 automatic gates. It must be removed or exposed as a direct control demand; it may
  not remain hidden.
- Ambient Location strategy, any measured wake strategy for Wi-Fi/Cell, pressure-derived vertical
  metrics, cross-midnight naming, and each legacy-writer retirement remain explicit decisions at
  their source gate.
- Local scoped commits are authorized. Push, publish, deployment, external rollout, and destructive
  migration remain unauthorized.
