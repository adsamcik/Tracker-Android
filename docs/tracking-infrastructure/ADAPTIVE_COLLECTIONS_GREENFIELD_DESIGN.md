# Adaptive Collections — Complete Target Pipeline

Status: authoritative implementation pipeline after specialist synthesis and fresh R4 adversarial iteration; schema freeze and runtime cutover remain blocked on the gates below
Owner: tracking-infrastructure integration lead
Date: 2026-08-22

## 1. Outcome

Tracker should collect each enabled source independently, persist a quality-preserving representation of every valid source-qualified delivery produced by an authorized running provider, improve useful tracking quality within the user's acquisition ceiling, and opportunistically combine already-collected facts. One source must never require another source to run. A provider with material battery cost may run only because the user directly enabled that source or an explicit automation control—not because another source would be nicer with its context.

The target flow is:

```text
SourcePolicy
    -> direct CAPTURE / AMBIENT / CONTROL demands
    -> one app-scoped SourceSupervisor
    -> per-source acquisition optimizer
    -> exactly one physical runtime per source
    -> source-native delivery
    -> one TrackingWriter / one Room mutation owner
    -> generation-stamped source_event_wal
    -> six source-local projectors and cursors
    -> typed facts + memberships + completeness
    -> one TrackingHistoryRepository
    -> optional query-time Location context
    -> Today / Timeline / Calendar / detail / map / export / deletion
```

The design deliberately has no cross-source `ENRICHMENT` demand. Enrichment has permission to read eligible persisted observations; it has no permission to start hardware.

## 2. Product rules

1. **Every source stands alone.** Location, Wi-Fi, Cell, Activity, Steps, and Pressure each have their own qualified evidence, durable fact, query, and truthful unavailable/partial states.
2. **Provider activation is explicit.** A provider runs only for `SESSION_CAPTURE`, `AMBIENT_PRODUCT`, or a separately enabled automation-control purpose.
3. **Running means durable.** Every valid source-qualified delivery accepted under the current physical and authorization generations receives a durable WAL representation before projection, attribution, or enrichment. Source-native batching changes transaction granularity, not approved product quality.
4. **Purpose still matters.** Capture and ambient observations may become product facts. Control-only observations are durable operational evidence, have a bounded retention class, and cannot silently become Activity or other source history.
5. **Paid context is never a dependency.** A non-free acquisition mode cannot be activated or kept alive to enrich another source. Missing context reduces fidelity, not validity.
6. **Already-paid context is reused.** If Location is independently running, a Wi-Fi or Cell observation may receive an event-time location context. If Location is off, the radio observation persists without it.
7. **No fabricated simultaneity.** A “collection” is a primary observation plus explicitly qualified optional contexts, not an assumption that all providers sampled at one instant.
8. **No source waits for context.** Primary fact persistence and `RECORDING` evidence never wait for an optional join window to close.
9. **Cost is a property of an acquisition mode.** Passive Location differs from high-accuracy Location; Wi-Fi broadcasts differ from `startScan()`; Cell callbacks differ from `requestCellInfoUpdate()`.
10. **Honest coverage beats invented cadence.** Opportunistic ambient gaps are `PARTIAL`, `OS_LIMITED`, or `NO_OBSERVATION`, never zero or complete by assumption.
11. **Quality floors are binding.** Adaptive power reduction may choose the cheapest plan satisfying a direct demand, but cannot disable the sole requested source or silently lower a manual request below its minimum useful product quality.
12. **Callbacks stay fast.** Provider delivery performs bounded validation and bulk admission; typed projection is conflated, asynchronous, and isolated per source so one slow/poison source cannot block another.
13. **Quality should improve.** Within the selected acquisition ceiling, prefer a non-dominated plan that improves freshness, coverage, accuracy, continuity, completeness, useful context, or error handling per unit of battery. Do not ship a cutover that is lower quality and no more efficient than the protected current path.
14. **One mutation owner.** Tracking callbacks, projections, lifecycle reconciliation, deletion, and import submit commands to one `TrackingWriter`; no other component owns a tracking `withTransaction` boundary.
15. **One product path.** A source is not complete until the same production history repository used by the existing product surfaces returns it with purpose, membership, and completeness.
16. **Zero requires coverage.** A numeric zero is product data only when qualified boundaries or coverage prove it. A baseline, null, disabled provider, or missing observation is never converted to zero.
17. **Central owners stay thin.** Supervisor reconciles registrations, projectors compute commands, writer applies fenced transactions, history is read-only, and export/deletion/context remain separate services.

## 3. What exists in the repository

There are two collection shapes today.

The legacy path builds one mutable multi-source cycle through `DataCollectionStage`, `TrackingCycle`, `TrackingCycleDispatcher`, and `PersistenceProcessor`. `WifiTrackerComponent` already makes Location optional and interpolates only between nearby bracketing fixes, but the composite cycle still encourages unrelated providers to be treated as if they formed one synchronous record. Queue pressure can merge cycles, and Wi-Fi/Cell rows currently embed optional coordinates.

The v28 path is a better foundation:

- `SourcePolicy` and Room policy rows hold source enablement, consent epochs, QoS, and effective revisions.
- `SourceBroker` owns purpose demands, one physical runtime per source, physical configuration generations, and independently revisioned immutable authorization vectors.
- `SemanticAcquisitionPlanFactory` and `SourcePlanResolver` already produce source-native plans and reduce cost under power saver, Doze, thermal, and motion constraints.
- `SourceEventWalEntity` and `RoomDurableSourceIngress` provide generation-fenced, checksummed, append-only admission.
- typed Location, Wi-Fi, Cell, Activity, Steps, and Pressure entities already carry stable source-signal identities or observation stamps.
- `EventTimeJoiner` and `TrackingJoinSpecs` demonstrate bounded optional joins, but `ExplicitTrackingJoinProjection` is more generic than this app currently needs.

The principal mismatches are:

- sessionless WAL admission currently depends on `controlPersistenceEligible` or `ambientPersistenceEligible`, so an authorized callback can be rejected even though its provider was intentionally running;
- capture enablement and ambient/control acquisition authority are not expressed cleanly as three direct user/product purposes;
- the legacy composite cycle remains coupled to production persistence;
- the generic joined-frame projection can become a second abstraction layer without a concrete product destination;
- automatic Step corroboration can still become a hidden physical listener rather than an explicit control choice;
- the current motion optimizer can reduce plans, but the architecture does not yet state the hard rule that advisory evidence may never create a provider demand;
- global plan/eligibility changes can rotate physical registrations even when provider configuration is unchanged, resetting Step baselines and Pressure windows;
- synchronous global projection draining places database/projection latency on callback processing and permits cross-source blockage;
- rollout state force-promotes all sources to event-canonical even where no terminal typed writer/product query exists;
- trip-driven product queries can hide otherwise durable Wi-Fi-only and Cell-only sessions.

## 4. Core model

### 4.1 Policy

`SourcePolicy` remains the single authority. Each source has three independently revisioned purpose policies:

```kotlin
data class SourcePurposePolicy(
    val source: SourceKind,
    val capture: CapturePolicy,
    val ambient: AmbientPolicy,
    val control: ControlPolicy,
)

data class CapturePolicy(
    val enabled: Boolean,
    val qosCeiling: AcquisitionMode,
    val consentEpoch: Long,
)

data class AmbientPolicy(
    val enabled: Boolean,
    val acquisitionCeiling: AcquisitionMode,
    val consentEpoch: Long,
    val retentionClass: RetentionClass,
)

data class ControlPolicy(
    val enabled: Boolean,
    val acquisitionCeiling: AcquisitionMode,
    val consentEpoch: Long,
    val retentionClass: RetentionClass,
)
```

These are conceptual types, not a requirement to add three tables. The current per-source Room row can hold this state while v28 remains unreleased. Final retention durations still require product/privacy approval; the architecture needs stable retention *classes* now so code cannot confuse control evidence with product history.

Capture toggles answer “what appears in session history.” Ambient toggles answer “what is collected outside a session.” Automation settings answer “what may wake/start/stop tracking.” One setting cannot silently grant another purpose.

### 4.2 Acquisition modes and cost

Use a small ordered vocabulary instead of fake battery percentages:

```text
OFF
PASSIVE_CALLBACK       OS or another client already produced the observation
HARDWARE_BATCHED       sensor/provider batches while the application processor sleeps
ACTIVE_LOW_POWER       this app requests work with a conservative plan
ACTIVE_HIGH_FIDELITY   this app requests frequent/high-accuracy work
```

The order is a planning vocabulary, not a claim that all sources have identical energy curves. Each runtime maps supported modes to concrete Android APIs and publishes capability/cost facts. Device measurements may later override qualitative labels, but unmeasured drain must not be presented as a numeric promise.

### 4.3 Demands

A `SourceDemand` is always direct:

- `SESSION_CAPTURE`: user started a session that captures this source;
- `AMBIENT_PRODUCT`: user enabled between-session collection for this source;
- `CONTROL_AUTOSTART` or `CONTROL_CONTINUATION`: user enabled an automation strategy that consumes this source.

There is no `ENRICHMENT` purpose. A Wi-Fi projection cannot demand Location. A Location projection cannot demand Pressure. `CollectionMotionController` may recommend a cheaper already-demanded plan, but it cannot add a demand or increase a policy ceiling.

Each direct demand also carries the minimum useful acquisition contract for its consumer:

```kotlin
data class DemandQualityFloor(
    val minimumMode: AcquisitionMode,
    val maximumAgeMs: Long,
    val desiredLatencyMs: Long,
    val adaptiveReductionAllowed: Boolean,
    val qualityTarget: QualityTarget,
)
```

Policy is the ceiling; demands carry hard floors and a target. The optimizer first removes plans that violate a floor or ceiling, then removes dominated plans, and finally selects according to the user's acquisition profile: Battery Saver chooses the least-cost floor-satisfying plan, Balanced chooses the best measured quality/energy tradeoff within budget, and Responsive chooses the highest useful supported quality within the ceiling. If no plan satisfies the floor, the source is explicitly `DEGRADED` or `UNAVAILABLE`. The optimizer cannot silently substitute a cheaper unusable product.

### 4.4 Registration merge

For each source independently:

1. Load current direct demands and the effective policy revision.
2. Reject/fail closed demands that fail consent, permission, capability, effective-time, or policy-ceiling checks.
3. If no eligible direct demand remains, fence authorization immediately and stop the provider.
4. Resolve one supported plan: the cheapest legal plan satisfying every accepted demand floor without exceeding purpose/policy ceilings.
5. Prefer provider batching and the longest report latency allowed by those floors.
6. Compare the normalized physical plan with the active physical configuration generation. Purpose, session, manifest, or consent changes alone rotate only the immutable authorization revision and its effective-time intervals.
7. Reconfigure the physical provider only when its normalized plan changes. Use a provider-specific handoff: update in place when supported; make-before-break only when the API proves safe; otherwise break-before-make and record a completeness gap.
8. Every callback records both the physical configuration generation and the observed-time authorization revision.

Starting a session raises QoS only for its enabled sources. Ending a session downgrades a source to its still-valid ambient/control plan instead of unregistering and re-registering it unnecessarily.

### 4.5 Complete pipeline and transaction boundaries

The implementation has ten stages. Each stage has one owner and a narrow output; skipping a stage is a correctness failure, not an optimization.

| Stage | Owner | Input → durable/output contract |
| --- | --- | --- |
| 1. Effective authority | `SourcePolicyRepository` | Settings, grants, consent and effective time → one complete six-source policy revision. |
| 2. Intent | lifecycle coordinator | User/automatic/ambient request → immutable session intent or app-scoped direct demand. Automatic start includes authenticated trigger identity, real origin, intended FGS type mask and automation epoch. |
| 3. Reconciliation | app-scoped `SourceSupervisor` | Effective direct demands → one normalized physical plan per source plus immutable authorization intervals. Enrichment cannot enter this stage. |
| 4. Provider side effect | six `SourceRuntime`s | Reserved physical token + plan → accepted/failed Android registration. Only a physical-key change touches hardware. |
| 5. Delivery | source runtime/receiver | Android callback/batch → bounded source-native `SourceDelivery` with item/subrange provider clocks and physical token. No projection or cross-source read occurs here. |
| 6. Admission | singleton `TrackingWriter` | `SourceDelivery` → one Room transaction that validates generation/epochs/fences, partitions items/ranges at observed-time authorization boundaries, allocates sequence ranges, inserts WAL and updates provider progress. Commit returns durable delivery receipts. |
| 7. Projection | six source-local read workers | Unprojected WAL for one source → pure `FactMutation`. The writer atomically upserts facts, memberships, completeness/gaps and advances only that source cursor. |
| 8. Product classification | typed projector/history query | Immutable authorization vector + observed time → session, ambient, both, or control-only membership without rewriting the raw delivery. |
| 9. Context and history | `TrackingHistoryRepository` | Typed facts → stable day/session/outside-session product state. `LocationContextResolver` may read already-retained Location facts but cannot demand a provider or mutate the primary fact. |
| 10. Maintenance | writer-owned retention/deletion/import plus WorkManager reconciliation | Actual retained facts and fences → pruning, repair, export/deletion and inexact ambient import/reconciliation. WorkManager is never the sampling clock. |

The writer is a singleton actor on a dedicated dispatcher because SQLite already has one writer. Reads and source projection computation remain concurrent. Provider callbacks use dedicated executors and suspend until admission commits; `BroadcastReceiver`/`PendingIntent` entry points hold `goAsync()` only for the bounded admission and legal-start handoff. Queue saturation may backpressure that source, but it may not silently drop a qualified delivery.

One read worker and cursor per source prevents poison or retryable input from blocking the other five. Deterministic malformed input is quarantined for that source and its explicit gap/completeness effect is committed before its cursor advances. Projected WAL is pruned per source only after the typed destination exists, retention permits it, and no compact radio reference pins it.

Exactly-once effect is deliberately source-specific. `(sourceEventId, itemIdentity, projectorVersion)` is the immutable delivery receipt. Each destination separately defines a stable logical fact/range identity and explicit `UPSERT`, `DELETE`, or bounded `REPLACE_RANGE`/supersession mutations, so correction or a projector upgrade may safely change cardinality. The writer retracts obsolete facts/memberships, applies replacements and advances the source cursor atomically. If a legacy destination truly requires an additive update, its narrow contribution receipt must be written in the same destination transaction. Do not restore a generic contribution/accounting platform without such a production consumer.

### 4.6 Minimum v28 data shape

Because v28 never shipped, reshape `MIGRATION_27_28` and schema 28 directly rather than adding a compensating v29. Keep the minimum tables needed by the pipeline:

- complete `source_policy` revisions with capture/ambient/control authority, consent epochs, retention classes and boot-aware effective time;
- immutable `source_demand` rows with purpose, quality floor/target, ceiling, session binding and retirement boundary;
- `source_registration` physical generations keyed only by normalized provider configuration;
- `source_authorization` intervals carrying exact demand/purpose/consent eligibility without restarting unchanged hardware;
- a compact logical `tracking_session`/intent state and narrow `automation_action` outbox for external Android start/stop effects;
- `source_event_wal`, one source cursor/projector version per source, `source_gap`, collected-data epoch and scoped deletion fences;
- narrow `source_destination_owner` generations for actual legacy/candidate mutation fencing;
- six typed destinations, fact membership, and per-day/source completeness; and
- existing compatibility summaries only while named product consumers still require them.

Do not add a generic join graph, generic projection outbox, all-source rollout state, one repository interface per fact table, or a second lifecycle/state graph. A single fact may be eligible for capture and ambient product use through membership rows, but the application supports one active logical session; do not generalize attribution beyond an observed need.

## 5. Durable observation and persistence

### 5.1 Two persistence layers

“Provider runs, therefore its useful evidence persists” is implemented precisely:

1. **Observation durability:** every structurally valid source-qualified delivery from the current physical and authorization generations is represented in `source_event_wal`, including control-only evidence. Admission is bulk/transactional per provider delivery or approved product boundary.
2. **Product durability:** units eligible for `SESSION_CAPTURE` or `AMBIENT_PRODUCT` are idempotently projected into source-owned typed facts.

The source-qualified units are deliberately concrete:

| Source | Durable admission unit | Quality preserved | Write minimization |
| --- | --- | --- | --- |
| Location | one provider delivery containing every qualified fix, partitioned by item observed-time authorization | complete accepted fix sequence | one bulk transaction/drain signal per provider batch even when it creates several authorization-homogeneous WAL subranges |
| Wi-Fi | one fresh callback/snapshot header plus changed/minimized AP children | fresh empty/unchanged callbacks remain distinct from no callback; cache replay creates no fact | unchanged fresh header may reference the prior qualified payload; do not duplicate children |
| Cell | one fresh provider/subscription callback header plus changed/minimized cell children | fresh empty/multi-SIM/availability state remains explicit; cache replay creates no fact | one batch transaction; unchanged fresh header may reference prior qualified children |
| Activity | one complete PendingIntent delivery batch, partitioned by item observed-time authorization | every transition/classification needed to replay the automation decision | expand/project bands after one bulk admission |
| Steps | cumulative counter boundary plus boot/baseline/gap identity | exact deltas over known-authorized intervals; an unknowable cross-boundary delta becomes baseline plus gap | no redundant per-tick aggregate rewrite |
| Pressure | completed authorization-homogeneous statistical window with sample count/range/mean/variance/trend/time coverage and gaps | the approved pressure-trend product | close at known authorization boundaries; no Room checkpoint per 5–20 Hz raw sample |

This is not permission to discard product-quality evidence after receipt. It defines the observation before provider activation and matches what Tracker promises to retain. Attempts, timer ticks, permission checks, stale generations, malformed/unbounded payloads, and cached state without defensible provider time are operational outcomes rather than source observations; retain only bounded diagnostics for them.

The WAL envelope records source, source instance, physical configuration generation, authorization revision, exact purpose eligibility, policy revision, applicable purpose consent epochs, observed and received elapsed time, wall-time metadata, source sequence/range, payload checksum, use/retention class, and immutable integrity identity.

Purpose is evaluated at *observed time* against immutable authorization intervals. One authorization stamp cannot cover evidence on both sides of a boundary. Location/Activity keep one provider-delivery identity but split admitted subranges by item time; Pressure closes its window at a known boundary; Wi-Fi/Cell use the defensible provider observation instant/interval; Steps establishes a new baseline and explicit gap when a delta cannot be apportioned honestly. A delayed callback cannot borrow receipt-time authority.

Control-only evidence remains durable long enough for restart, recovery, audit, and automation correctness. It is excluded from normal history, default export, source totals, and ambient enrichment. Its eventual expiry follows an approved control-retention class and a deletion fence; “durable” does not mean “kept forever.” Keep it in the shared WAL initially. Add a second operational store only if measured volume, encryption, or export isolation proves necessary.

### 5.2 Typed facts

Keep source-owned typed facts rather than restoring a generic contribution/accounting platform:

- Location: immutable point/fix observation with quality and provider time.
- Wi-Fi: one compact scan/callback header plus zero-or-more minimized access-point children.
- Cell: one compact callback/snapshot header plus zero-or-more minimized radio/cell children.
- Activity: coalesced captured movement facts; raw control evidence stays in its bounded operational stream.
- Steps: generation-bound counter boundaries, deterministic deltas, and covered zero intervals after two qualified unchanged boundaries. A lone baseline is not zero; positive delta remains the `RECORDING` threshold.
- Pressure: deterministic source-specific windows/trends with explicit sample coverage; raw high-rate archival is not part of the approved pressure product.

Each typed delivery uses `sourceEventId` plus item identity/projector version as its idempotence receipt. Each typed destination also defines a logical fact/range key independent of the correcting delivery. A projector emits explicit upsert/delete/replace-range mutations, allowing a correction or projector upgrade to change one prior item into zero, one, or several without leaving obsolete facts. Steps treats a corrected boundary as a bounded chain/range recomputation, not an isolated delta update.

Wi-Fi and Cell require a compact callback-level typed header because zero-result and unchanged fresh deliveries are product evidence after WAL pruning. Existing children remain grouped by stable `sourceEventId`/item index. The header owns timestamp, purpose, completeness/outcome, and optional prior-payload reference; children store only minimized changed content. This is a concrete radio-only product requirement, not a generic observation-header platform. Because v28 never shipped, reshape it only after the exact populated v27-to-v28 migration test passes and before the schema is treated as released.

### 5.3 Deletion, import, export, and owner fences

`TrackingWriter` exclusively owns these mutations:

- a scoped deletion first writes `deletion_fence(source, purpose, consentEpoch, observed scope/boundary, fenceGeneration)`, then removes memberships/facts and invalidates actual derived consumers;
- live admission, replay, delayed callbacks and import all recheck that fence in their mutation transaction;
- portable merge import stages only provenance-bearing base product facts. Identical identity+checksum is a duplicate; same identity/different content is an explicit conflict; derived summaries are recomputed rather than imported;
- unverifiable legacy facts follow an explicit import decision and may be quarantined/partial, never assigned current consent;
- portable export is built from typed product facts, membership and completeness for all released sources. It excludes control-only WAL and raw radio identifiers. A whole-database backup/restore is separately labeled and empty-target only; and
- every legacy or candidate destination mutation rechecks `source_destination_owner(source, destination, ownerGeneration, owner)` in the same transaction. Cutover drains the outgoing path and atomically changes this narrow fence; a paused old write must fail after the switch.

These are two narrow safety records, not a revival of generic writer activation/tombstone/contribution infrastructure.

## 6. Collections and optional context

### 6.1 Collection contract

A collection is a product view, not an acquisition transaction:

```kotlin
data class CollectionRecord<T : TypedFact>(
    val primary: T,
    val membership: SessionMembership?,
    val contexts: List<ObservationContext>,
    val completeness: ContextCompleteness,
)
```

The primary is always independently valid. Contexts are optional, source-qualified, time-bounded, and carry provenance and uncertainty.

Examples:

- Wi-Fi only: a Wi-Fi scan with count, band mix, capability/permission state, and no location context.
- Wi-Fi + Location: the same Wi-Fi scan plus `LocationContext` resolved from independently captured or ambient Location facts.
- Location + Pressure: Location remains valid alone; Pressure may support a separate pressure trend or explicitly labeled vertical estimate only if a defensible product algorithm is approved.
- Activity control + Wi-Fi capture: Activity can start the session, but control-only Activity is not exposed as captured movement history and is not used as general product enrichment.

### 6.2 Location context resolution

Start with one concrete resolver because it serves the demonstrated Wi-Fi/Cell use case:

```kotlin
interface LocationContextResolver {
    suspend fun resolve(
        primarySource: SourceKind,
        primaryEventId: String,
        observedInterval: ElapsedInterval,
        membership: FactMembership,
        logicalTrackingId: String?,
        purpose: ProductPurpose,
        consentEpoch: Long,
    ): LocationContextResult
}
```

Resolution rules:

- same boot/clock domain;
- same-session capture uses only capture-eligible Location from that logical session;
- ambient primary facts use only independently ambient-eligible Location under a compatible consent epoch;
- control-only, deleted, cross-session capture and incompatible-purpose Location are excluded;
- direct co-recorded fix first;
- otherwise a quality-qualified nearest fix;
- interpolation only between bracketing fixes;
- explicit maximum age, accuracy, and implied-speed/distance bounds;
- returned method, contributing Location event IDs, accuracy/uncertainty, and algorithm version;
- no result when the evidence is insufficient.

The existing Wi-Fi implementation’s bounded bracketing behavior is a useful starting point, not a permanent universal threshold. Thresholds are source-specific and versioned.

Resolve context at repository/materialization time from immutable facts. Do not rewrite raw Wi-Fi or Cell rows when Location arrives later. Group radio children by source event, load ordered Location facts once for the page/day plus bounded margin, partition by clock domain, and perform one linear nearest/bracketing merge—never one SQL query per child. Existing radio heatmaps are a named consumer and require shadow parity through a privacy-safe derived view. Begin without a generic persisted join graph; add a narrow recomputable cache only if profiling proves the page/day/map query cannot meet its latency target. Location deletion or consent reset then removes the source facts and invalidates/recomputes dependent views without trying to undo mutated raw radio rows.

## 7. Source acquisition strategies

| Source | Live-session provider | Lowest-cost ambient host | Paid/direct option | Durable unit and protected product quality |
| --- | --- | --- | --- | --- |
| Location | FLP callback with timestamp/accuracy gates and the largest acceptable batch delay | explicit passive FLP `PendingIntent`; opportunistic and background-location gated | balanced/high-accuracy request and one bounded `getCurrentLocation()` first-fix probe | provider delivery containing every qualified fix; preserve first-fix, route/distance accuracy, gaps and cross-batch provider order |
| Wi-Fi | process-bound scan-results callback/receiver; cache is payload retrieval/dedup state only | successful callbacks while a legal process host exists; no cache-read maintenance masquerading as collection | one directly authorized `startScan()` probe, with repeated attempts only in an explicit measured mode | fresh callback header including empty/unchanged results plus minimized changed children or a one-hop pinned prior qualified reference; failed updates are operational only |
| Cell | per-subscription `TelephonyCallback`; cache is bootstrap/dedup state only | timestamp-qualified callbacks while a legal process host exists; no cache-read maintenance masquerading as collection | one bounded per-subscription `requestCellInfoUpdate()` group | callback/group header, per-subscription completion/error and minimized children; never let the first SIM complete a multi-SIM request |
| Activity | GMS Sampling only when detailed captured bands are directly demanded during a live session | GMS Activity Transition `PendingIntent` for explicit control or coarse ambient Activity | temporarily sampled initial classification under a direct Activity floor | one complete `PendingIntent` delivery; preserve all transitions and an approved probable-activity ranking before product band coalescing |
| Steps | direct `TYPE_STEP_COUNTER` boundaries for low-latency evidence and preview | one selected system continuity adapter: Health Connect device steps on API 34 + extension 20, otherwise accountless mobile Recording API when supported | shorter live boundary latency; no per-step detector unless a concrete cadence/dead-reckoning product requires it | boot/generation-bound cumulative boundary or provider interval; source-specific overlap precedence prevents continuity imports and live evidence from double-counting |
| Pressure | wake-up pressure sensor when available, otherwise FIFO-batched sensor with explicit suspend gaps | off by default; an explicit future ambient product uses the longest legal FIFO/segment | 1 Hz saver, 5 Hz balanced, and a higher rate only when device evidence proves a useful trend gain | shaped microsegment/window with count, endpoints, mean/M2, range, slope, R², accuracy, expected/actual coverage and gaps |

Android implementation notes:

- Use event callbacks first and active requests second.
- Use provider/hardware batching whenever supported; larger report latency reduces application-processor wakeups.
- GMS Activity **Transitions**, not Sampling callbacks, are the allowed cold automatic-start origin. The supported transition request uses `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `WALKING`, and `STILL`; do not request unsupported `ON_FOOT`.
- Active Wi-Fi scans and Cell refreshes are attempts, not observations. Only a fresh provider callback qualifies as `RECORDING`.
- A direct manual Wi-Fi/Cell capture may perform at most one policy-ceiling-respecting start probe when no fresh qualified observation exists and no spontaneous callback arrives. This cost is authorized by that source's direct demand, never by enrichment. Failure/throttling yields `OS_LIMITED` or `NO_OBSERVATION` with the exact reason.
- A passive result must retain provider time so freshness is evaluated honestly. Re-reading identical cached provider times is not another result.
- Use one bulk WAL transaction and one conflated projection wakeup per source-native delivery/admission unit.
- A single unique ambient-maintenance WorkManager chain may consolidate due provider imports, subscription reconciliation, projection repair and retention for explicitly enabled ambient sources. It does not poll Wi-Fi/Cell caches to manufacture observations. Periodic work is at least 15 minutes, inexact, quota-bound on Android 16+, and deferred by Doze; record actual execution and observation times. It is not a wake-reliable sampling clock.
- An in-process timer is used only while the owning process/service is alive and is never described as device-sleep cadence.

### 7.1 Ambient execution matrix

Ambient persistence is a real product mode, not a fabricated session. Its execution host must match what Android can actually keep alive:

| Source | Allowed ambient execution | Honest completeness |
| --- | --- | --- |
| Steps | OS/GMS continuity subscription plus inexact writer-owned import | potentially full-day within provider subscription/permission intervals; gaps on revoke, unsupported provider, delayed import or reset |
| Activity | GMS Transition `PendingIntent` | transition coverage only; not continuous minute-by-minute classification |
| Location | passive FLP `PendingIntent` with explicit background-location consent | opportunistic points; no route/presence continuity promise |
| Wi-Fi | successful system-scan-result broadcasts while registered; no cache-only acquisition mode | opportunistic; no scan or wake cadence, with explicit process/permission/throttle gaps |
| Cell | timestamp-qualified change callbacks while registered; cache reads are bootstrap only | opportunistic per subscription; no forced refresh or wake cadence |
| Pressure | disabled by default | no ambient claim until an approved product and legal execution host exist |

For ambient Steps, the product setting authorizes *one* selected continuity provider, not two additive streams. Prefer Health Connect on-device steps where API 34, SDK Extension 20, permissions, and current-device origin identification are available; otherwise evaluate the accountless mobile Recording API, which retains up to ten days while subscribed. The direct sensor remains live session evidence. A source-specific Step canonicalizer chooses one provider for overlapping intervals, exposes provisional/reconciled state, and never sums overlapping direct/Health Connect/Recording facts. Google Play services or Health Connect absence degrades Steps but cannot disable the direct sensor on supported hardware.

Health Connect’s June 2026 synthetic-package-name change is a hard compatibility requirement: use aggregation or framework `getCurrentDeviceDataSource()`; never hardcode the former `android` origin. Integrating the broader Health Connect ecosystem or wearable data is a separate product choice and is not implied by on-device Steps.

### 7.2 Per-source mode ladders and switching

“One physical runtime per source” means one app-level owner of that source’s Android handles. It does not force every source into one OS registration when the platform exposes complementary APIs. The runtime owns the smallest compatible handle set required by its direct demands, prevents duplicate physical ownership, and deduplicates any bounded handoff overlap.

| Source | Ordered modes/options | Default use and switching rule |
| --- | --- | --- |
| Location | `OFF` → `PASSIVE` → `LOW_POWER` → `BALANCED` → `HIGH_ACCURACY`; optional bounded `FIRST_FIX_PROBE` | Ambient defaults to passive `PendingIntent`. Direct session demand raises to its floor. End of the last active demand downgrades to still-authorized passive rather than stopping. A bounded probe may accelerate first evidence but never becomes a recurring hidden poll. If no explicit Activity control is available, stationary adaptation retains a low-power sentinel instead of relying on Activity to wake passive Location. |
| Activity | `OFF` → `TRANSITION_EVENTS` → `SAMPLED_CLASSIFICATIONS` | Explicit automatic control normally owns Transition `PendingIntent`; captured detailed Activity adds Sampling only when the product needs detail that transition intervals cannot provide. One Activity runtime may own both complementary handles and stamps each delivery’s purpose. Google recommends Transitions as the lower-power API, but the app records callbacks, wakeups, CPU and measured energy rather than promising a fixed saving. Ending detailed capture removes Sampling while retaining independently authorized Transition control. |
| Steps | `OFF` → `SYSTEM_CONTINUITY` and/or `DIRECT_BATCHED` → `DIRECT_LOW_LATENCY` | Ambient selects exactly one Health Connect/Recording continuity adapter. A live session may additionally use the direct counter for low-latency boundaries. The canonicalizer reconciles overlapping intervals; switching provider or latency never adds totals. Ending the live demand removes the direct sensor while continuity may remain. |
| Pressure | rate profile `OFF` → `LOW_RATE` → `STANDARD_RATE` → bounded `HIGH_RATE_WINDOW`; delivery is independently `FIFO_BATCHED` or `IMMEDIATE` | Session-only by default. `OFF` is the only zero-sensor-cost state. A positive report latency saves application-processor interrupts only when the sensor exposes a real FIFO; when `fifoMaxEventCount == 0`, batching is equivalent to immediate delivery and must not be presented as a saving. Increase sample rate/reduce report latency only within an accepted demand ceiling, close windows at authorization/plan boundaries, and collapse rate profiles that device tests cannot distinguish in quality or cost. |
| Wi-Fi | `OFF` → `PASSIVE_SCAN_RESULTS` → bounded `ACTIVE_SCAN_BUDGET` | A cache read is not a mode. On Android 10+, a registered scan-results receiver can observe full scans performed by the platform or other apps without Tracker requesting a scan. A direct demand may authorize one first-evidence scan or a bounded/backed-off active-scan budget; the attempt never qualifies, only fresh result items from a successful completion do. Doze/throttling causes an honest lower effective mode/status, not fake cadence. |
| Cell | `OFF` → `CHANGE_CALLBACKS` → bounded `REFRESH_BUDGET` | `getAllCellInfo()` is a cache read on Android Q+ and is not a mode or proof of a new observation. A direct high-fidelity demand may authorize sparse per-subscription `requestCellInfoUpdate()` calls, which are rate-limited and not guaranteed. Ending it returns to change callbacks; failure of one SIM cannot terminate the whole snapshot group. |

Every transition follows the same control protocol:

1. Recompute the source plan from all direct same-source demands, policy ceilings, binding quality floors, capabilities, and current power constraints.
2. Persist the desired physical transition and a new authorization boundary before touching Android APIs.
3. If the normalized physical key is unchanged, rotate authorization only—zero provider calls.
4. Escalate promptly when a direct demand requires it. Demote after source-specific hysteresis/cooldown to avoid flapping; consent revocation is an immediate admission fence and never waits for hysteresis.
5. Update in place when the API provides a proven atomic update. Otherwise use a provider-specific make-before-break handoff only when bounded overlap is safe; if not, use break-before-make and persist the exact completeness gap.
6. During overlap, callbacks retain old/new physical tokens and stable provider identities so WAL deduplication prevents double product effect. A failed non-revocation replacement keeps the last still-legal plan when safe; a revocation never does.
7. Persist every qualified delivery once, then attach every compatible capture/ambient membership. An active Location fix can therefore serve independently authorized session and ambient products without a second Location registration or duplicate fact.

Mode changes are source-local. A Wi-Fi escalation cannot restart Steps, close a Pressure window, rotate Location, or modify Activity control. Optional enrichment never causes an escalation.

### 7.3 Freshness, relevance, and cache admission

The promise that an authorized running provider persists its deliveries applies to **qualified source deliveries**, not to stale cache contents, request attempts, timer ticks, or receipt-time guesses. Freshness is decided before identity-bearing payload enters the durable observation WAL. The runtime may retain a bounded, non-identifying outcome counter for rejected input, but stale SSID/BSSID, cell identity, signal, location, activity, counter, or pressure payload is neither stored nor used by attribution, lifecycle, materializers, history, export, or enrichment.

Each source adapter returns one explicit admission classification:

| Classification | Durable representation | Product/lifecycle effect |
| --- | --- | --- |
| `PRODUCT_FACT` | source-native fact or qualified delivery batch | may be attributed and may satisfy source-specific `RECORDING` |
| `COVERAGE_ONLY` | compact header with observed interval, accepted count, rejected count and completeness; unchanged children may reference prior qualified content | proves only the covered interval; never invents a value outside it |
| `OPERATIONAL_OUTCOME` | bounded payload-free status such as requested, throttled, permission-blocked, timeout, or unsuccessful results update | diagnostic/status only; cannot satisfy `RECORDING` or appear as captured history |
| `REJECTED` | aggregate reason counter only | no payload storage or downstream use |

Admission is item-level for batched sources and follows this order:

1. Verify registration token/generation, policy and consent epoch, boot/clock domain, and deletion/retention fences.
2. Require a defensible provider observed time when the source contract supplies one. Receipt time never replaces a missing Wi-Fi/Cell/provider timestamp to make old state look fresh.
3. Reject items observed before the purpose authorization/session effective boundary, beyond the source-specific maximum age, implausibly in the future, malformed, or missing the fields required for that product.
4. Admit only the remaining items. One fresh Wi-Fi access point or CellInfo entry cannot pull stale siblings into the snapshot. Persist accepted/rejected counts and the accepted observed-time range so completeness remains auditable without retaining rejected identity payload.
5. Deduplicate exact delivery identity. An identical cache content **and provider-time vector** is a cache replay: discard it without a WAL row or coverage header because it proves no new observation. Semantic sameness with advanced, qualified provider times is different: a genuinely new successful callback whose content is unchanged or empty proves new coverage, so persist one compact `COVERAGE_ONLY` header while reusing prior qualified children rather than duplicating them.

Wi-Fi terminology is exact: `getScanResults()` reads the most recently updated cache; `SCAN_RESULTS_AVAILABLE_ACTION` reports a scan completion; `startScan()` asks the radio to perform work. A cache read may supply payload after a successful completion or initialize in-memory deduplication, but the read itself never advances `RECORDING` and repeated reads of the same timestamped cache create no durable record. A failed completion (`EXTRA_RESULTS_UPDATED == false`) creates only an operational outcome. Every `ScanResult.timestamp` is gated independently against elapsed realtime and the applicable effective interval.

Cell terminology is likewise exact: `getAllCellInfo()` reads cached modem state for current targets; a `TelephonyCallback.CellInfoListener` reports a provider change; `requestCellInfoUpdate()` explicitly asks for updated state but may be throttled or return no update. Cache state is bootstrap/dedup state only; repeated reads of the same timestamped state create no durable record. Only timestamp-qualified entries from a current eligible callback/refresh can become product facts, and the request itself never advances `RECORDING`. Every `CellInfo.getTimestampMillis()` (or the pre-30 nanosecond equivalent) is gated independently.

The same rule applies elsewhere: Location uses fix elapsed realtime plus accuracy/age; Activity uses event elapsed time and the current automation/purpose epoch; Steps requires same-boot qualified counter boundaries and treats reset/unknown spans as gaps; Pressure uses sensor event time and authorization-homogeneous covered windows. Source-specific thresholds remain configurable policy/QoS values and require representative-device calibration; they are not one global freshness constant.

Current code does not yet satisfy this contract. `WifiSourceRuntime` admits a whole list using its freshest child and substitutes receipt time when the provider timestamp is absent. Its `CACHED_ONLY` and `BROADCAST_DRIVEN` plans both register the same receiver, perform the same startup cache read and issue no scan, so they are not distinct acquisition or energy modes. `CellSourceRuntime` checks maximum age only for cache reads, admits whole groups using their newest child, and persists timestamp-unknown outcomes as capture-attributed snapshots; Battery Saver and Balanced both use the same change-listener registration. Those paths must be corrected before Wi-Fi or Cell materialization, history wiring, or rollout.

## 8. Battery optimizer

The optimizer is deterministic and explainable. It consumes direct demands, policy ceilings, provider capabilities, current Android constraints, and optional already-persisted motion evidence.

Priority order:

1. preserve correctness and user-selected source availability;
2. do not activate a provider without a direct demand;
3. share one registration across same-source purposes;
4. batch and coalesce delivery;
5. choose the lowest-cost plan satisfying every direct demand's minimum useful mode, latency, age, and adaptive-reduction permission;
6. reduce plan under battery saver, Doze, thermal pressure, or stable inactivity only within those floors;
7. use hysteresis, minimum dwell, and cooldown to prevent plan flapping;
8. surface `DEGRADED` with named causes when the requested plan is unavailable.

Motion evidence is advisory. If Steps, Activity, Location, Cell, or Pressure is already active, its recent facts can support a cheaper plan for another already-demanded source. The optimizer never turns on a source to prove that it is safe to turn down a different source, never disables the sole captured source, and never crosses a non-adaptive manual floor. Stale evidence, old boot domains, and old automation epochs are ignored.

Quality is a source-specific vector, not one misleading global score:

| Source | Protected baseline | Improvement targets |
| --- | --- | --- |
| Location | current canonical accepted-fix sequence, first-fix behavior, route/distance output | lower qualified first-fix latency, fewer unexplained gaps/outliers, equal-or-better distance/route error, more useful zero-cost passive context |
| Wi-Fi | fresh result capture and existing radio-map usefulness | retain empty/unchanged delivery evidence, better scan/snapshot coverage, accurate freshness, privacy-safe heatmap parity, optional Location context without changing Wi-Fi identity |
| Cell | current technology/signal facts and map coverage | better timestamp freshness, multi-SIM completeness, fewer false “fresh” cached snapshots, privacy-safe map parity, optional Location context |
| Activity | current transition/classification responsiveness | no lost automatic trigger interval, fewer noisy band flips through hysteresis, better observed-time freshness and confidence explanations |
| Steps | current session delta where continuously observed | exact generation/boot boundaries, fewer listener-reset gaps, explicit coverage, replay-safe totals, better ambient usefulness without fabricated all-day completion |
| Pressure | current accepted samples/legacy trend behavior | no queue-loss ambiguity, more stable trend signal, explicit window coverage/gaps, equal-or-better trend responsiveness with far fewer database writes |

Optional context is a quality gain only when it adds valid information. It never changes the primary source fact count or identity, and a missing/deleted context cannot reduce the primary source below its own baseline.

Every source cutover with a legacy path uses a shadow comparison on representative recordings. A candidate is acceptable when it either improves at least one relevant quality dimension without exceeding its approved energy/write budget and without regressing any hard floor, or materially reduces cost while matching the protected quality vector. A candidate that is both worse quality and no more efficient is rejected.

Do not begin with a learned battery model or numeric percent-per-hour estimate. Emit structured diagnostics—requested mode, applied mode, reason, registration duration, event count, batch count, active request count, and wakeup count—and calibrate policy from device evidence later.

## 9. Concurrency and backpressure

- Provider callbacks do minimal validation/encoding and bulk-append one source-qualified delivery/admission unit to WAL; no cross-source query or synchronous full projection drain runs on the callback thread.
- One serialized ingestion lane per source preserves source order without globally blocking unrelated sources.
- Keep one WAL table, indexed and processed by independent per-source cursors/batches, so one poison/retryable source cannot stop all collection.
- Source-specific typed projections are idempotent and restartable from WAL identity.
- Do not merge unrelated facts to relieve queue pressure. Define source semantics before admission: Pressure emits covered windows, Steps emits counter boundaries, Location batches retain every qualified fix, Activity retains delivery batches, and radio snapshots retain compact headers while unchanged children may reference prior content. After a qualified unit exists, queue pressure cannot silently drop it.
- Database writes may share a transaction batch for efficiency, but transaction batching must not imply one multi-source collection identity.
- `TrackingWriter` schedules small commands fairly and gives already-delivered admission bounded priority over projection/maintenance. Import, deletion, retention, and backfill perform validation outside the writer and submit bounded chunks with durable progress/fences; they cannot hold one unbounded transaction or starve live admission. Operations requiring exclusive consistency enter an explicit maintenance state and providers report `DEGRADED` rather than silently losing callbacks.

## 10. Package and ownership layout

Use four ownership areas conceptually without an initial package-moving campaign:

```text
source/model    policy, direct demands, plans, source-native envelopes, identities
source/runtime  SourceSupervisor, six adapters, physical registrations, Android actions
source/store    TrackingWriter, WAL codecs, six projectors, replay/retention/deletion commands
source/history  TrackingHistoryRepository, completeness and LocationContextResolver
```

Room entities may remain in `core/base` with `AppDatabase`; source-specific product code may remain in current modules until behavior is stable. Ownership and DAO visibility matter more than directory churn. History/context may read typed facts; they cannot depend on provider activation or create demands. UI depends on history, never on provider callbacks or the legacy composite cycle.

Avoid adding a framework interface until two source implementations need the same behavior. Shared code should cover genuine invariants—registration fencing, WAL integrity, purpose eligibility, time domains, and completeness—not erase source-specific Android semantics.

Thinness is enforceable: `SourceSupervisor` contains no provider decoding, SQL, product query, or correction logic; projectors are pure/read-only command producers; `TrackingWriter` contains no provider/product/source algorithms and only rechecks fences/cursors plus applies bounded transactions; `TrackingHistoryRepository` is read-only. `TrackingExportService`, `TrackingDeletionService`, `TrackingImportService`, and `LocationContextResolver` own their domain orchestration and submit mutations through the writer. Architecture tests reject tracking DAO/`withTransaction` access outside the writer, mutation APIs in history, and provider activation from context/export/query code.

## 11. Automatic tracking

Capture-source selection and automation-control selection are separate.

- Manual only-X always works without any hidden control provider.
- Automatic only-X captures only X. It may use a separately visible, consented control such as low-power Activity transitions.
- A source-native automatic trigger may be offered only when X can already run under an explicitly enabled ambient/control policy and the trigger is proven fresh.
- Automatic Activity-only has an explicit trigger handoff: if Activity capture was independently enabled/consented at the trigger's observed time, the durable trigger may seed the first captured band with `AUTOMATION_TRIGGER_HANDOFF` provenance and a session effective boundary at that event. Otherwise it remains control-only and the initial Activity interval is `PARTIAL` until fresh capture-qualified evidence arrives. No other only-X session may capture that Activity trigger.
- If the user disables every available control strategy, the UI reports automatic start as unavailable and preserves manual collection. It must not silently register Activity, Steps, Location, or another provider.
- Step corroboration is not a default hidden dependency. Retaining it requires an explicit control setting and device evidence that the added registration materially improves automation at acceptable cost.

Cold-start legality is part of the durable action contract, not a check performed after a service already exists. Normally, only a fresh GMS Activity Transition `PendingIntent` is the automatic background-start origin. Persist and CAS its trigger identity, observed/received clocks, expiry, boot/automation/policy/consent epochs, intended manifest, actual origin, and exact foreground-service type mask before calling `startForegroundService()`. A Sampling callback is not a Transition exemption. Background-created Location still requires background-location eligibility because foreground-service start exemption and while-in-use permission access are separate gates.

The intended type mask is the union of accepted direct demands: Location uses `location`; Activity Sampling and direct live Step sensing use `health`; Wi-Fi, Cell, and Pressure use the narrowly declared `specialUse` subtype; mixed capture uses the union. The service promotes with that exact persisted mask and records success/failure. Do not start a neutral `specialUse` service and widen it later, and retire the separate `ActivityWatcherService` rather than maintaining two service owners.

Provider `PendingIntent` registrations may survive ordinary process death but cannot be assumed to survive force-stop or app replacement. Boot/update/user-start reconciliation re-arms eligible app-scoped registrations and finalizes stale automatic sessions as interrupted; it never resumes a stopping/finalized session. A broadcast receiver holds `goAsync()` only for bounded WAL admission and the legal-start handoff.

This is the honest interpretation of “best possible”: source capture remains useful, while automatic behavior reports its actual control capability rather than pretending it can start from no evidence.

## 12. Product contract

The history repository returns each source independently and then composes views. It uses one orthogonal status contract:

```text
availability: DISABLED | UNSUPPORTED | PERMISSION_REQUIRED | OS_LIMITED | AVAILABLE
evidence:     NONE | STARTING | ACTIVE | RECORDED
product:      MATERIALIZING | PARTIAL | READY | DEGRADED | FAILED
coverage:     observed intervals + named gaps and repair cause per source/purpose
```

Internal lifecycle labels remain distinct; product consumers map these axes exhaustively. A numeric zero is present only with covered evidence.

The repository also returns:

- source facts for a stable historical day;
- in-session and outside-session classification;
- optional contexts with provenance;
- coverage/completeness per source and purpose;
- “Why was this recorded?” from direct policy purpose and registration generation.

The minimum shipping consumers are the existing Today, Timeline, Calendar, selected-day/session detail, applicable radio map, export, and deletion paths. A source cannot become event-canonical until these consumers reproduce its sole-source fact and completeness. Wi-Fi/Cell-only logical sessions remain visible even with zero distance and no legacy trip segment.

Opportunistic Ambient Steps is never treated as a complete daily total across observer/process/reboot gaps. Every goal, streak, achievement, dashboard, and history consumer receives completeness alongside the numeric value; incomplete coverage cannot award a complete-day result. A continuous/full-day promise is a separate visible-foreground product choice.

The first release should repair existing Today, Timeline, Calendar, and selected-day flows. A full Days-first journal is not required to prove the collection engine. No source-only day may disappear merely because it lacks Location or a legacy trip segment.

## 13. Lean implementation sequence

Because v28 never shipped, converge on the target before treating its schema as public:

1. **Resolve authority and containment.** Apply this addendum to the final plan, stop force-promoting all sources event-canonical, and unbind unused generic join/Location-shadow projections until each has a named experiment/consumer.
2. **Finalize the unreleased v28 safety shape.** Separate physical configuration generation from authorization revision; add self-contained purpose epochs/use-retention class and the two concrete radio snapshot headers; avoid any generic platform tables.
3. **Verify the boundary.** Run exact clean and populated v27-to-v28 open/reopen, deletion, and import/restore cases on an emulator/device before treating v28 as released.
4. **Correct admission and processing.** Remove pre-WAL persistence gates, admit source-qualified units in bulk, schedule one conflated asynchronous drain, and process independent per-source WAL lanes with bounded quarantine.
5. **Prove demand and quality invariants.** Property-test no direct demand/no provider, no enrichment demand, policy ceilings, demand floors, adaptive-reduction permission, and unchanged-source zero provider restarts.
6. **Make Steps the first end-to-end vertical.** Selected ambient continuity adapter plus direct live boundaries/gaps, typed delta and verified-zero coverage, outside-session query, completeness on every goal/history consumer, export, and scoped deletion/no-resurrection.
7. **Make Pressure source-owned.** Prove low-rate/FIFO batching, durable covered windows, stable trend, and session-only completeness without inventing ambient coverage or per-sample Room writes.
8. **Protect Location’s current writer.** Correct real start-origin/capability evaluation and shadow/compare before any cutover; do not introduce a second active location canonical writer.
9. **Separate captured Activity from bounded control evidence.** Preserve one physical registration, atomic delivery-batch admission, explicit automatic-trigger handoff, and no control leakage.
10. **Decouple Wi-Fi from Location.** Make the compact Wi-Fi snapshot independently valid; add the bounded direct-capture start probe, existing-product/map parity, and optional Location resolver.
11. **Add Cell using the same source-event grouping.** Prefer timestamp-qualified change callbacks; use cache only for in-memory bootstrap/dedup, keep refresh explicit/opportunistic, prove multi-SIM completeness, and reuse the Location resolver.
12. **Retire legacy composition.** Remove `DataCollectionStage`/`TrackingCycle` source coupling and `pending_signal` double durability only after all product consumers read source-owned facts.
13. **Tune from evidence.** Use representative devices/Perfetto to calibrate plan defaults, provider restarts, write/transaction budgets, callback latency, CPU/wakeups, batch latencies, and optional active radio modes; do not gate correctness on an unavailable fleet telemetry platform.

This preserves the recorded default source rollout order—Steps, Pressure, Location, Activity, Wi-Fi, Cell. A different activation order still requires a decision backed by repository or device evidence.

After every vertical: assert sole-source manual, declared-control automatic, enabled ambient, enable/disable, permission/consent revoke, delayed callback, process death, replay, deletion/no-resurrection, production query, and truthful UI state.

## 14. Acceptance examples

### Wi-Fi only

- Direct demand: Wi-Fi capture and/or Wi-Fi ambient only.
- Active providers: Wi-Fi only.
- Persistence: every fresh authorized Wi-Fi callback becomes a scan fact; no Location requirement.
- Product: count, band mix, capability and completeness; location context absent.
- Status: `QUERYABLE` when the Wi-Fi product query returns the fact, even with Location disabled.

### Wi-Fi plus Location

- Direct demands: Wi-Fi and Location are independently enabled.
- Active providers: one Wi-Fi registration and one Location registration at their own effective plans.
- Persistence: both streams persist independently.
- Enrichment: the resolver attaches a bounded, uncertainty-labeled Location context to Wi-Fi when eligible evidence exists.
- Failure behavior: revoking Location stops Location and removes or invalidates its product facts/context as policy requires; Wi-Fi collection continues.

### Wi-Fi plus optional Location that is disabled

- Direct demand: Wi-Fi only.
- Active providers: Wi-Fi only; the desired context creates no Location demand.
- Persistence/product: identical to Wi-Fi only.
- Explanation: “Location context was not collected because Location was disabled,” not an error.

### Automatic Pressure with Activity control

- Direct demands before session: explicitly enabled low-power Activity control.
- Fresh Activity evidence creates durable session intent; accepted runtime adds Pressure capture demand.
- Both providers persist their callbacks, but only Pressure becomes captured source history unless Activity capture/ambient was independently enabled.
- If Activity control is disabled, manual Pressure remains available and automatic start is reported unavailable.

## 15. Deliberate non-goals

- no arbitrary cross-source dependency graph;
- no provider registration initiated by an enrichment query;
- no global synchronized polling cycle;
- no generic contribution-receipt/accounting platform without a non-recomputable destination;
- no default ambient active Wi-Fi scan or forced Cell refresh;
- no claim of wake-reliable periodic sampling from WorkManager or in-process delays;
- no machine-learned battery optimizer in the first release;
- no continuous ambient Pressure without a separate approved product case;
- no calibrated elevation claim from Pressure without a defensible, labeled algorithm;
- no mandatory full Days-first redesign before the source facts are queryable.

## 16. Decisions still required

- source/purpose retention durations and exact control-evidence export behavior;
- opportunistic versus visible-foreground continuity for Ambient Steps;
- whether optional active ambient Wi-Fi attempts are exposed as a user mode after device evidence;
- whether Step corroboration remains an explicit automation-control option;
- whether ambient Location has a product setting beyond passive piggyback observations;
- whether any pressure-derived vertical estimate is in product scope.

These decisions change user-visible behavior or privacy/battery contracts. They are not needed to enforce the architectural invariant that enrichment never starts hardware and every valid source-qualified delivery from an authorized provider has a durable representation.

## 17. Proportionality review of this proposal

Three fresh `gpt-5.6-sol` high-reasoning adversaries independently reviewed the plan and live repository. The combined gate is `BLOCK_RESCOPE`: the core architecture survives, but the corrected contracts above must replace the unsafe wording/current wiring before structural implementation continues.

| Perspective | Verdict | Failure it proved | Integrated disposition |
| --- | --- | --- | --- |
| Product/use-case | `BLOCK_RESCOPE` | conflicting rollout authority; missing Activity trigger handoff; radio-only sessions disappear; empty/unchanged radio callbacks and heatmaps lack a safe product path; partial Steps can misstate goals | existing Tracker surfaces are mandatory; radio headers and Activity handoff are concrete contracts; incomplete Steps cannot grant complete-day results; full journal remains deferred |
| Android performance/battery/quality | `BLOCK_RESCOPE` | per-callback Room/checkpoint/drain amplification; bounded-queue loss; provider restart/baseline gaps; adaptive policy can disable the sole source; wrong automatic Location context | source-native bulk admission, conflated per-source drain, split physical/authorization lifetimes, demand quality floors, and real Android origin/capability tests are blocking |
| Data/runtime simplicity | `PASS_WITH_CORRECTIONS` | force-canonical ownership, session-bound admission, synchronous global projections, unused generic joins/shadows, incomplete deletion/export | retain one policy/broker/WAL and typed facts; use per-source lanes and query-time classification; unbind/retire unused generic paths; add narrow source-purpose deletion/export evidence |

The remaining complexity—policy revisions, purpose vectors, registration generations, durable WAL identity, and boot-aware time—is retained because it directly prevents stale callbacks, hidden source capture, consent borrowing, and post-crash relabeling. It is the safety boundary, not a speculative analytics framework.

## 18. Fresh R4 adversarial outcome

Three new independent reviewers attacked the revised pipeline after expert synthesis:

| Perspective | Verdict | Result integrated |
| --- | --- | --- |
| Data correctness/migration | `BLOCK_RESCOPE` | authorization-homogeneous deliveries; separate delivery/logical correction identity; scoped deletion/import and destination-owner fences; realistic v27→v28 gate |
| Android lifecycle/power/source quality | `BLOCK_RESCOPE` | confirmed Transition-only durable start, lossless callback handoff, binding demand floors, exact FGS union/one service, Pressure window writes, and process-resilient ambient adapters remain unimplemented blockers |
| Product/scope/maintainability | `PASS_WITH_CORRECTIONS` | verified-zero Steps, orthogonal status axes, purpose-compatible Location context, and enforceable thin owners |

The combined status remains `BLOCKED` for schema freeze or source activation because the accepted target is not implemented and device migration is unexecuted. The architecture stays intentionally small: one supervisor, six adapters, one mutation executor, six source projectors, typed facts, two narrow fence tables, and one read facade. No reviewer requested the removed generic accounting/join/day platform or a full UI rewrite.

## 19. Android evidence anchors

- Android foreground-service background-start restrictions and documented exemptions, including Activity Recognition Transition events: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>.
- Android foreground-service type prerequisites and while-in-use restrictions: <https://developer.android.com/develop/background-work/services/fgs/service-types>.
- Android documents Wi-Fi scan throttling and notes that apps can receive scan-result broadcasts for scans completed by the platform or other apps: <https://developer.android.com/develop/connectivity/wifi/wifi-scan>.
- `ScanResult.timestamp` is the elapsed-time-since-boot moment each access point was last seen; a successful broadcast does not make every cached child equally fresh: <https://developer.android.com/reference/android/net/wifi/ScanResult>.
- Android documents Wi-Fi permission/version requirements separately from nearby-device discovery; active scan results still require location prerequisites on current target levels: <https://developer.android.com/develop/connectivity/wifi/wifi-permissions>.
- Android’s Location battery guidance distinguishes high-accuracy, balanced, low-power, and passive/no-power strategies and recommends batching: <https://developer.android.com/develop/sensors-and-location/location/battery>.
- Fused Location `PendingIntent` delivery is the process-resilient registration form, while registrations are removed on force-stop/reset/app replacement and batched fixes may require timestamp ordering: <https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient>.
- `TelephonyManager.requestCellInfoUpdate()` is rate-limited and not guaranteed; provider timestamps must determine freshness: <https://developer.android.com/reference/android/telephony/TelephonyManager>.
- `CellInfo.getTimestampMillis()` is elapsed-realtime-based modem-observation time and is the per-item freshness anchor: <https://developer.android.com/reference/android/telephony/CellInfo>.
- SensorManager hardware FIFO/report latency can reduce application-processor interrupts: <https://developer.android.com/reference/android/hardware/SensorManager>.
- Google recommends Activity Recognition Transitions over Sampling for accuracy and lower power, while Sampling exposes finer raw classifications and requires explicit interval/power management: <https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient>.
- Android describes the hardware Step Counter as low-power, cumulative since reboot while registered, and suited to long-running fitness tracking: <https://developer.android.com/reference/kotlin/android/hardware/Sensor>.
- The accountless Recording API is the documented on-device Google Fit Recording replacement and retains subscribed mobile step data for up to ten days: <https://developer.android.com/health-and-fitness/recording-api>.
- Health Connect read guidance documents on-device Steps availability, extension gating, aggregation and current-device origin handling: <https://developer.android.com/health-and-fitness/health-connect/read-data>.
- Android’s Health Connect migration FAQ records Google Fit deprecation and the June 2026 synthetic-package-name compatibility change: <https://developer.android.com/health-and-fitness/health-connect/migration/fit/faq>.
- Periodic WorkManager execution is inexact, has a 15-minute minimum interval, and can be delayed by Doze: <https://developer.android.com/reference/androidx/work/PeriodicWorkRequest>.
- Power Profiler/system tracing and Macrobenchmark `PowerMetric` are the current measurement paths; Battery Historian is no longer maintained: <https://developer.android.com/studio/profile/power-profiler> and <https://developer.android.com/reference/androidx/benchmark/macro/PowerMetric>.
