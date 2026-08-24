# Tracking Infrastructure Execution Plan

Last updated: 2026-08-24

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
- Materializers and production UI wiring remain blocked until the fresh R1 review of actual
  authority, broker, lifecycle, admission, and schema diffs has no unresolved `BLOCKER` or `HIGH`.
- Local commits are authorized and are the unit of integration. Each commit must contain one
  gate-sized, dependency-coherent slice, stage only reviewed paths, record its scoped verification,
  and leave known blocked assertions explicit. A failing or unverified slice is not committed as a
  completed checkpoint merely because it compiles.

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
| FND-01 startup/lifecycle/rollout containment | Lead orchestrator | app startup/deletion/receivers; tracker API/service/coordinator/recovery/rollout/broker; focused tests | committed as `f14a4a2b1` | isolated-index cross-module checkpoint passes; every default source is contained; durable intent precedes external start; no product writer activated |
| EP-02 execution evidence | Lead orchestrator | `docs/tracking-infrastructure/*.md` | write | checkpoint, schema count, verification, blockers, and rollback state agree with `f14a4a2b1` |
| R1F-01 data/migration adversary | fresh `gpt-5.6-sol` reviewer | committed authority/startup/rollout/ledger diff and v27→v28 boundary | read-only, pending | falsification timelines for ordering, replay, migration, deletion, and writer ownership; every finding dispositioned |
| R1F-02 Android/power/privacy adversary | fresh `gpt-5.6-sol` reviewer | committed service/lifecycle/broker/provider/action diff | read-only, pending | falsification timelines for FGS legality, process death, boot, permissions, consent, provider ownership, and battery scope |
| R1F-03 product/scope adversary | fresh `gpt-5.6-sol` reviewer | committed diff plus Steps-first plan and existing product queries | read-only, pending | rejects overbuilding and any unproven only-X, ambient, completeness, or query claim |
| ST-02 first Steps vertical | unassigned until R1 disposition | Steps runtime, source-local cursor/facts/correction/deletion/export/query, existing product adapter | blocked | one manual/automatic/allowed-ambient Steps path reaches a real production query with no hidden source and no generic platform |

No two current owners may edit the same file. Read-only findings become lead-owned only after
disposition. The worktree is already dirty; unrelated and pre-existing changes are preserved.

## Unreleased-v28 table boundary

The current schema contains 51 released-v27 tables and exactly 14 narrowly owned v28 additions. This constrains
TI-180 as follows:

| Treatment | Tables / records | Rule |
| --- | --- | --- |
| Preserve | All 51 v27 tables, including legacy facts, projection/outbox state, rollout state, summaries, and radio rows | No physical drop or reinterpretation in the first slice. Retire a released table only after its production callers and pending effects are drained under a later explicit migration decision. |
| Keep as v28 spine | `source_policy_authority`, `source_consent_epoch`, manifest/source versions, lifecycle-intent versions, desired actions | Complete and test these records; do not replace them with another authority. |
| Reshape directly | `source_policy`, `source_demand`, `provider_registration_generation`, `provider_registration_eligibility` | Make policy purpose-specific; make demands express floors/ceilings/retention; make provider generations physical-only; replace eligibility with independently revisioned observed-time `source_authorization` intervals. |
| Extend additively | `source_event_wal` and released runtime/plan state | Preserve legacy rows as `LEGACY_UNQUALIFIED`; add delivery identity/range, authorization, all applicable epochs, use/retention class, counts, and origin. Runtime baselines rotate only with real physical/data-epoch changes. |
| Add only when its transaction contract is ready | source cursor/gap, scoped deletion fence, destination owner, fact membership, day/source completeness, Wi-Fi/Cell headers | Keep records narrow and source-driven. Do not create generic contribution/accounting tables. |

The current 65-table `28.json` is therefore not frozen. Two additions are the narrowly scoped
released-v27 recovery obligation/target tables and two are the Activity automatic-action/epoch
records; they do not recreate the removed generic Phase 3 platform. Populated Android
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
| TI-183 | Final plan §8–9; one writer, no resurrection | D | source cursors/gaps, deletion fences, destination owner fence | TI-180 | One cursor/gap lane per source; scoped source/purpose/time deletion fences replay and import; one persisted destination owner generation fences both legacy and target writes | cursor/gap, delete→replay/import, latched cutover/rollback tests | Per-source owner flag; rollback changes owner only after drain/catch-up | `BLOCKED` |
| TI-184 | TI-D068/TI-D085/TI-D088/TI-D091; migration safety | A/C/D/G | v27 recovery tables/DAO, frozen decoder/projectors, process startup fence, `AppDatabaseMigration27To28Test`, backup, production query/export/delete/import paths | TI-180–TI-183 | Populated v27 opens, migrates, closes, reopens, queries, exports, deletes, and recovers without fabricated authority or lost facts; a transaction-captured admission high-watermark isolates exact released v1 targets from live v2 projections; pending legacy effects retain their WAL until bridged or terminally suppressed; checksum-valid v27 WAL drains once through only its frozen released contract before any provider/service/policy path opens; interrupted automatic state and old Activity effects cannot restart | Seven populated migration cases previously pass on `Medium_Phone`; host startup-gate, deletion precedence, frozen-drain, writer-consumer, redelivery and finalizer shards pass in the isolated `f14a4a2b1` checkpoint. Connected migrate→startup ordering, production backup-wrapper proof, cold empty-target restore, partial-database merge containment, portable export and production history queries remain | No rollout flag; startup fails closed. Migration backup remains recoverable; live v2 writers start at cutoff + 1 and Location stays legacy-owned | `IN_PROGRESS` |
| TI-210 | Final plan §4.4; TI-D057/TI-D059; one physical owner | B | app-scoped `SourceSupervisor`, six adapters, demand reconciler | TI-181 | Exactly one physical owner/source; only direct capture/control/ambient demands affect lifetime; compatible demands merge; removal/reconcile is idempotent; optional context cannot acquire a provider | six manual/automatic registration-set integration tests; process-death reconciliation tests | `supervisor_v2:<source>`; rollback hands ownership back only after callback barrier | `BLOCKED` |
| TI-211 | Final plan §4.5/§9; no ghosts, Android legality | C | coordinator, service gateway, desired-action outbox/reconciler | TI-180, TI-210 | Durable intent precedes external start; accepted runtime follows; real start origin and exact FGS type union are used; boot/automation epoch fences stale starts; `STOPPING`/`FINALIZED` never restart | host transaction-boundary, redelivery, stop-ordering, previous-exit/force/explicit finalizer and permission-revocation tests pass; reboot/process-kill instrumentation and Android-version/device legality remain | `lifecycle_v2`; rollback finalizes or safely resumes durable intent | `IN_REVIEW` |
| TI-212 | TI-D078/TI-D083; battery honesty | B/G | plan optimizer, source adapters, diagnostics | TI-210 | User-visible modes correspond to measured physical mechanisms; identical rungs collapse; passive/callback/FIFO modes are preferred; active probes are finite, direct-demand-only, stop after qualified evidence, and cannot be started by enrichment | fake plan properties plus Android Studio Power Profiler/system trace/Macrobenchmark and source-relevant device runs per rung | Per-source QoS ceiling; rollback selects cheaper proven rung | `BLOCKED` |
| TI-213 | TI-D054; sole-source start truth | C/F | Dashboard start action, requested-source prerequisite evaluator, permission launcher tests | authoritative effective source selection | Manual only-X requests exactly X's current platform prerequisites; Pressure/Steps/Activity start with Location denied; Wi-Fi/Cell may request fine Location only for their own platform operation and never enable Location capture | 17 parameterized source-aware decision assertions and focused Dashboard compile/tests pass; device permission-result flow and other entry-surface audit remain | No rollout flag; fail closed only for the selected source's unmet prerequisite | `IN_REVIEW` |
| TI-310 | TI-D078–TI-D083; freshness, minimization, no fabricated history | B/D | connectivity runtime support, radio backends/runtimes, payload codec, permission surfaces and tests | current runtime foundation | Freshness is evaluated per child; missing/non-positive/future/stale provider time cannot become payload; confirmed fresh empty differs from absent/failed/stale; Wi-Fi v2 retains item time with v1 decode compatibility; new payloads expose no radio/subscription identifier; scan permission matches used APIs; exact live replay is zero-effect only after durable/duplicate handoff; paid scans/refreshes are finite and direct-capture-only while passive callbacks remain registered | focused admission/prerequisite/codec/cancellation/budget tests pass; app compiles; durable restart identity, runtime-fake tests, calibrated budgets and device proof remain | No flag; fail-closed containment on existing path | `IN_REVIEW` |
| TI-311 | TI-D055/TI-D056; purpose separation | B/D | source delivery models, WAL admission, source-specific batch splitters | TI-181, TI-182 | Provider observations and operational request outcomes are distinct; attempts/ticks never qualify `RECORDING`; every admitted unit is authorization-homogeneous; control evidence is bounded and absent from product/export | Activity callback batches commit once, preserve canonical-to-original sparse selection, and real Room proof omits pre-registration members while retaining writer-stamped capture/control eligibility; the other five boundary splitters and control-only history/export assertions remain | Behind per-source writer flag; rollback retains bounded operational evidence | `IN_PROGRESS` |
| TI-312 | TI-D060/TI-D063; performance and cancellation | D/G | `DurableSourceIngress`, sink, dispatcher, runtimes, writer queue | TI-182 | Bulk admission uses bounded transactions; synchronous global drain removed; `CancellationException` always propagates and never consumes poison retry budget; pressure checkpoint writes are amortized | generic cancellation guard test passes; boundary injection and stress/queue/latency tests remain | Writer queue kill switch; rollback drains or leaves durable cursor | `IN_PROGRESS` |
| TI-320 | Final plan §8; TI-D061/TI-D071; one writer | D | thin data-plane `TrackingWriter`, source lanes created only with real verticals, typed destinations | TI-182, relevant TI-183 fence, TI-312 | One serialized data-plane mutation boundary; the active source lane commits cursor, receipt, typed mutation, membership, and invalidation together; poison blocks only that lane. Policy and lifecycle retain their own atomic control-plane transactions | first Steps lane crash/replay proof, then the same adapter contract per added source; no six-unused-lane prerequisite | `writer_v2:<source>` individually gated | `BLOCKED` |
| TI-321 | Final plan §8.3; correction safety | D | source-specific logical fact/range IDs and mutation commands delivered with each source vertical | TI-320 and relevant source vertical | Delivery receipt is distinct from stable logical fact identity; `UPSERT`, `DELETE`, and bounded `REPLACE_RANGE` retract obsolete facts/memberships; replay reaches identical state | one→zero→two projector upgrade and correction/retraction tests per implemented source | Same source writer flag; rollback preserves last canonical owner | `BLOCKED` |
| TI-322 | Final plan §8.5; deletion/export privacy | D/E | deletion, import staging, export, keyed radio identity | Steps-local proof in TI-410, then each relevant source vertical | Generalize only proven per-source mechanics: delete then replay/backfill/import cannot resurrect; merge import stages provenance-bearing base facts through writer; portable export includes released sources and excludes control/raw radio identity; radio key rotation fences old tokens | delete/import/replay and source-only export tests added with each vertical | Import/export format version; rollback never bypasses fences | `BLOCKED` |
| TI-323 | One writer; Location protection | D/G | rollout owner state, legacy and target destination gateways | TI-183 and an actual candidate writer for the source | Owner generation is rechecked inside destination transaction; cutover waits for cursor catch-up and legacy drain; rollback keeps all facts queryable; Location legacy owner remains until shadow decision | latched legacy-commit race, cutover/crash/rollback tests | `destination_owner:<source>`; data-preserving rollback | `BLOCKED` |
| TI-410 | Source contract: Steps; first vertical | B/D/E | Steps runtime, typed boundaries/intervals, Steps-local writer/cursor/delete/export/query adapter | TI-180–TI-183 minimum substrate, relevant TI-312 cancellation, corroboration decision | App-scoped counter; durable baselines; baseline/covered-zero/positive/partial distinctions; ambient continuity only with consent; Steps-local correction, no-resurrection, export and one real production query work while every other projector is absent | Steps source suite + production query assertions + process/reboot/reset/device tests + execution-plan DAG test | `writer_v2:steps`, `history_v2:steps`; return to legacy read owner | `BLOCKED` |
| TI-411 | Source contract: Pressure | B/D/E | Pressure FIFO/window runtime, typed segments/trend | shared minimum substrate, Pressure-relevant TI-212/TI-312 | Fresh samples durable; recording differs from stable summary; real FIFO/batching is used when supported; no default ambient; no uncalibrated elevation claim | source tests, FIFO/non-FIFO device matrix, production history assertion | `writer_v2:pressure`; preserve compatibility reads | `BLOCKED` |
| TI-412 | Source contract: Location | B/D/E | location runtime, existing canonical writer, shadow comparator | shared minimum substrate, Location-relevant TI-211/TI-212/TI-323, explicit owner decision | Passive↔active changes follow direct floors and legality; post-start accuracy/freshness qualifies recording; sole-source works; optional context never blocks primary; no second active canonical writer | source/device/shadow/query tests | Existing writer remains owner until explicit cutover; immediate owner rollback | `BLOCKED` |
| TI-413 | Source contract: Activity | B/D/E | shared transition/activity runtime and typed movement bands | shared minimum substrate, Activity-relevant TI-211/TI-212 | Transition control is purpose-limited; capture is separate; fresh STILL semantics hold; sampled mode used only for direct quality need; stale automation cannot start | atomic PendingIntent delivery and zero-effect replay tests pass; freshness threshold/epoch trigger fencing, source-local materializer, process/reboot and production query tests remain | `writer_v2:activity`; control remains independently revocable | `BLOCKED` |
| TI-414 | Source contract: Wi-Fi | B/D/E | Wi-Fi backend/runtime, identity-free headers first; keyed snapshots only if identity products are approved | TI-180–TI-183, TI-210/TI-212/TI-310/TI-312, Wi-Fi product-tier decision | Cached/broadcast/opportunistic acquisition persists only fresh new-in-effect results; bounded active attempts only when selected; identity-free counts/band/quality tier is truthful; identity-dependent screens stay unavailable until purpose/epoch HMAC and rotation exist; optional Location context follows the explicit compatibility matrix | manual/automatic/ambient, cache replay/restart, API/OEM, query/export/delete and product-tier tests | `writer_v2:wifi`; disable attempts first, retain passive demand if authorized | `BLOCKED` |
| TI-415 | Source contract: Cell | B/D/E | Cell backend/runtime, identity-free headers first; ephemeral SIM grouping/keyed identity only if justified | TI-180–TI-183, TI-210/TI-212/TI-310/TI-312, Cell product-tier decision | Callback/cached state uses per-item provider time; sparse refresh is bounded and never promised wake-reliable; partial SIM failures are explicit when a non-identifying slot can be established; identity-free technology/quality output is queryable and identity products remain unavailable until proven | source, multi-SIM/API/OEM/doze/query/export/delete and product-tier tests | `writer_v2:cell`; disable refresh while retaining callbacks if authorized | `BLOCKED` |
| TI-500 | TI-D059/TI-D069; product proof | E | minimum `TrackingHistoryRepository` facade over existing Today/Timeline/Calendar/detail/map; no paged Days contract yet | first source production-query slice, then each source | `observeToday`, `observeDay`, `observeSession`, stable fact lookup and source completeness expose every implemented source/ambient fact without Location/session fabrication; verified zero differs from absent/disabled/unavailable/materializing/partial/degraded/failed; structural day identity is stable; optional context is separate | production repository assertions per source/mode; midnight/DST/time-zone tests; every steps/goal/widget/notification/game/achievement/streak consumer preserves completeness | `history_v2:<source>`; dual-read fallback while typed facts remain accessible | `BLOCKED` |
| TI-510 | Final plan §7, proportional UI scope | F | existing product surfaces, source detail/status/explanation | TI-500 | Sole-source and ambient facts are visible and actionable in existing surfaces; accessibility and truthful completeness pass; a new day journal is built only if evidence shows existing surfaces cannot satisfy the contract | presenter/Compose/screenshot/TalkBack/large-text/reduced-motion/device tests | UI flag only; rollback cannot hide typed-only facts | `BLOCKED` |
| TI-600 | Final plan §10–11; evidence rollout | G | verification matrix, runbook, diagnostics | per-source vertical | Each source passes independently through production query/UI; energy and quality are measured; rollback rehearsed; no unresolved fresh-review blocker/high; external rollout still requires user authorization | exact commands/device links recorded in `VERIFICATION_MATRIX.md` | Steps → Pressure → Location → Activity → Wi-Fi → Cell by default | `NOT_STARTED` |

## Immediate gate

1. Run three fresh read-only R1 adversaries against committed checkpoint `f14a4a2b1`: data/migration,
   Android/power/privacy, and product/scope. No materializer or production history/UI wiring is
   authorized until all `BLOCKER`/`HIGH` findings are dispositioned and corrected or explicitly
   accepted by the user.
2. Continue TI-180 from its accepted first slice: v28 now preserves all 51 released-v27 tables,
   replaces the unshipped eligibility table with `source_authorization`, and separates physical
   provider generation from authority. Use/retention class, structural-zone identity, source
   cursor/gap, deletion and destination-owner records remain deliberately unimplemented.
3. Preserve the now-passing populated v27→v28 fixture on `Medium_Phone`: all six typed source
   families survive, unprovable runtime is terminal before production DAO reads, authority remains
   fail-closed, exact v1 projection targets are durably isolated, and production deletion/reopen
   proves no Location-child resurrection. The four-case suite also covers an unknown target,
   pruned-WAL admission high-watermark, and an outbox-only recovery obligation.
4. Treat the process-single-flight startup fence, deletion precedence, prepared service start,
   recovery finalizers, and contained rollout state as implemented host-tested foundation. The
   fresh R1 must try to falsify their cross-process and migration ordering; connected/device proof
   remains required. Continue adopting the atomic delivery-admission seam source by source. Activity is the
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
7. Implement the smallest end-to-end vertical, Steps, with one source cursor/gap row, narrow scoped
   deletion and destination-owner fences, typed Step boundaries/facts/membership, deletion/export,
   and one production query. Do not restore the removed generic accounting or dirty-day platform;
   generalize only after concrete Steps and Pressure evidence. The default source order remains a
   scheduling preference, not a cross-source dependency.

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
