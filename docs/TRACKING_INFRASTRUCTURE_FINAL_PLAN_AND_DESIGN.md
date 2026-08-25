*TRACKER ANDROID  /  ARCHITECTURE & PRODUCT DESIGN*

# Tracking Infrastructure: Final Plan & Design

> Single-source operation, ambient acquisition, canonical materialization, and a day-first history experience

**Status:** Authoritative target architecture • expert-refined and fresh-adversary iterated • implementation/device gates blocked

**Date:** 21 August 2026

**Scope:** Location • Wi-Fi • Cell • Activity • Steps • Pressure

**Evidence:** Repository inspection • focused tests • prior adversarial rounds • fresh adaptive-collections product, Android-power, and runtime-simplicity review on 22 August 2026

> **Executive decision:** Adopt a purpose-aware source broker, source-qualified durable observations, and source-owned typed product facts before claiming universal single-source support. Provider registration may outlive sessions; provider acquisition is direct-purpose and cost-bounded; optional enrichment never starts hardware.

*This document is the consolidated source of truth for architecture, Android lifecycle behavior, privacy boundaries, day-history UX, migration, rollout, and acceptance.*

## 2026-08-22 authoritative execution addendum

The product owner subsequently established four hard requirements: v28 never shipped and may be reshaped; every source must work as the sole captured source; an authorized running provider must produce durable source-qualified observations while any materially paid acquisition remains optional and never an enrichment dependency; and the redesign should improve tracking quality per unit of battery rather than merely preserve it. Decisions TI-D049 through TI-D081 and `tracking-infrastructure/ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md` are incorporated into this source of truth.

They supersede the following older execution details where they conflict:

- the generic observation-attribution, writer-activation, contribution-receipt/correction, persisted arbitrary-join, dirty-day, and persisted `DayOverview` platform is not a prerequisite and has no current production owner;
- a full Days-first/M3 journal does not precede the first source vertical; existing Today, Timeline, Calendar, selected-day, export, deletion, and map consumers form the minimum product proof;
- source ownership activates independently only after its typed writer and production queries pass; all sources must not be force-promoted to event-canonical together;
- durability is defined by a source-qualified admission unit that preserves the approved product quality—provider batch/fix, radio snapshot header, Activity delivery batch, Step boundary, or Pressure window—not by one Room transaction per raw high-rate callback;
- physical provider configuration lifetime is distinct from purpose authorization/session-attribution revisions, so unrelated manifest or policy changes do not restart unchanged hardware;
- adaptive power reduction is bounded by each direct demand's minimum useful quality and may not disable or silently degrade the sole requested capture source.
- the optimizer is quality-seeking inside the user's acquisition ceiling: retain a hard quality floor, reject dominated plans, and prefer measurable improvements in freshness, coverage, accuracy, continuity, completeness, or useful context per unit of battery. A source cutover cannot ship when it is both lower quality and no more efficient than the protected current path.
- one app-scoped `SourceSupervisor` owns the six physical runtimes and a single `TrackingWriter` owns tracking Room mutations; up to six source-local projector/cursor lanes prevent one source from blocking another, but each lane is instantiated only with its real typed source vertical;
- stable typed delivery identity is the idempotence receipt, while destination-specific logical fact/range identity and explicit mutations handle correction. Generic activation/contribution/correction infrastructure remains absent unless a real non-recomputable destination demonstrates the need;
- one production `TrackingHistoryRepository` composes source facts for the existing Today, Timeline, Calendar, selected-day/detail, map, export, and deletion consumers; it is not synonymous with a persisted universal `DayOverview` cache;
- automatic cold start requires a documented legal trigger—normally an Activity Transition `PendingIntent`—whose complete epoch/origin/foreground-service envelope is durably accepted before the service call;
- ambient execution uses provider-owned continuity plus one inexact maintenance/reconciliation chain. WorkManager and in-process timers are not sampling clocks.
- every source has a source-specific ordered acquisition ladder and source-local switching contract. One runtime means one owner of the minimal compatible Android handle set, not an artificial one-registration limit; enrichment never escalates a mode.
- acquisition labels describe real platform mechanisms, not assumed battery tiers: Activity distinguishes Transition events from sampled classifications; Pressure separates sampling rate from actual FIFO/immediate delivery; Wi-Fi and Cell distinguish cache reads, passive callbacks and paid requests. Unmeasurable adjacent rungs collapse.
- freshness/relevance admission precedes identity-bearing persistence. Cache reads and request attempts cannot create observation time; mixed-age Wi-Fi/Cell batches are filtered per item; stale, pre-effective, clock-unverifiable or irrelevant payload is neither stored nor used. Fresh empty/unchanged deliveries may retain a compact coverage header.
- an identical cached content/provider-time vector is discarded with zero durable or product effect. Only a genuinely new provider observation may extend coverage; unchanged fresh content reuses prior qualified children rather than storing them again.

The corrected thin path is: policy/consent authority → direct demands → one `SourceSupervisor`/physical runtime per source → source-native delivery → one `TrackingWriter`/WAL → source-local typed projection lanes → one production history facade → existing product surfaces → independently gated source rollout. The larger day-first experience remains a later product evolution after the source facts are trustworthy and queryable.

For avoidance of doubt, sections 7.2–7.7 are a deferred product concept, `pagedDays` is not part of
the minimum history contract, and Phase 3 creates one source lane with each completed vertical. They
must not be interpreted as prerequisites for manual/session Steps, automatic Steps, ambient Steps,
or any other first source rollout.

# Document map

The plan is organized from decision to delivery. Section numbering is stable for review comments and implementation epics.

- 1. Executive decision and current verdict
- 2. Scope, definitions, and non-negotiable invariants
- 3. Current architecture assessment
- 4. Target tracking architecture
- 5. Source-by-source operating design
- 6. Ambient and out-of-session acquisition
- 7. Day-first information architecture and M3 Expressive UX
- 8. Canonical data, attribution, and day materialization
- 9. Android lifecycle, scheduling, and recovery
- 10. Migration, rollout, and implementation workstreams
- 11. Verification, observability, and acceptance
- 12. Risks, decisions, and adversarial-review disposition
- Appendix A. Acceptance checklist
- Appendix B. Code evidence and reference points

> **Reading shortcut:** Product and design reviewers can start with sections 1, 6, and 7. Platform and data reviewers should focus on sections 2–5 and 8–11.

# 1. Executive decision and current verdict

## 1.1 Decision

The tracking system uses one thin, source-owned pipeline:

1. **Authorize and demand by purpose.** `SourcePolicy` is the sole authority; direct `CONTROL`, `SESSION_CAPTURE`, and `AMBIENT_PRODUCT` demands are explicit and enrichment cannot create one.
2. **Supervise physical acquisition.** One app-scoped `SourceSupervisor` merges same-source demands and owns exactly one physical runtime per source. Physical configuration generations and observed-time authorization revisions have separate lifetimes.
3. **Admit before classifying.** Source-native deliveries are durably, generation-fenced, and bulk-admitted through the single `TrackingWriter` before session/ambient/control product classification.
4. **Project by source.** Six isolated projector/cursor lanes create typed facts and memberships. Stable fact identity provides exactly-once effect; source-specific correction/recompute and deletion fences prevent resurrection.
5. **Prove value through one product facade.** `TrackingHistoryRepository` supplies the existing Today, Timeline, Calendar, selected-day/detail, applicable map, export, and deletion consumers with source facts, in/out-of-session classification, provenance and completeness. A persisted day cache or full Days-first UI is added only when product scope or profiling justifies it.

## 1.2 Current verdict

> **Overall verdict:** The current infrastructure is not yet production-ready for every ‘only one source enabled’ scenario. Steps and pressure have a conditional end-to-end path; location, activity, Wi-Fi, and cell can acquire durable raw events but do not all have a reliable terminal path into a coherent user-visible history.

| **Source** | **Acquisition today** | **Current verdict** | **Target contract** |
| --- | --- | --- | --- |
| Location | Durable acquisition; canonical path exists | **Conditional / multi-writer risk** | Keep one location writer; prove session and day query output |
| Wi-Fi | Durable observations | **Not end-to-end** | Source materializer + privacy-safe day summaries |
| Cell | Durable observations | **Not end-to-end** | Source materializer + quality/technology day summaries |
| Activity | Durable events and auto control | **Not end-to-end** | Split control from capture; coalesced history bands |
| Steps | Frame projection and accumulators | **Conditionally works** | Shared observer; baseline/delta evidence; day totals |
| Pressure | Frame projection | **Conditionally works** | Session-first product; calibrated, defensible summaries |

*Verdict means user-visible, correction-safe, queryable output—not merely a running service or durable raw event.*

## 1.3 What ‘only X’ means

The term must be precise across modes:

- **Manual mode:** X is the only captured source. No control dependency is required unless the user explicitly enables one.
- **Automatic mode:** X is the sole captured source, while declared control dependencies may remain active. Activity recognition is currently fundamental to automatic start/stop; step corroboration must be explicit if retained.
- **Ambient mode:** no logical session is required. The provider may remain registered only for an explicit control or ambient product purpose with its own consent and retention policy.

> **Naming rule:** Never present automatic ‘Location only’ as literally using only location hardware. Present it as ‘Location captured; Activity used for automatic control.’

## 1.4 Success outcome

The target is achieved only when all six sources pass both manual and automatic base scenarios, each allowed ambient mode is intentional, and the resulting information is visible through the production `TrackingHistoryRepository` and the applicable existing UI/export/deletion consumers.

# 2. Scope, definitions, and non-negotiable invariants

## 2.1 Goals

- Every source can be the sole captured source without silently starting another capture provider.
- Automatic control dependencies are explicit, purpose-tagged, consented, observable, and never mistaken for captured history.
- Crashes, retries, corrections, upgrades, and backfills cannot double-count or strand canonical output.
- Low-cost providers can be shared outside sessions without creating surprise surveillance or unbounded retention.
- All retained information is discoverable by day, with sessions and between-session facts clearly separated.
- A health model tells the app and user whether tracking is starting, active, recording, materialized, queryable, degraded, unsupported, or failed.

## 2.2 Non-goals

- Promising reliable periodic Wi-Fi or cell scans while the device sleeps before an OEM/device wake strategy is measured.
- Persisting every technically available sensor event merely because registration appears inexpensive.
- Keeping trip-centric navigation as the only way to discover history.
- Selecting final retention durations or legal copy in this architecture document; those require product/privacy approval.
- Replacing the existing proven location canonical projection with an unvalidated second writer.

## 2.3 Operational definition of ‘works’

| **State** | **Meaning** |
| --- | --- |
| DISABLED | Policy or consent says the source must not run or persist. |
| UNSUPPORTED | The device or OS cannot provide the requested capability. |
| STARTING | The logical session exists and providers/actions are being reconciled. |
| ACTIVE | At least one requested runtime has accepted registration and can produce evidence. |
| RECORDING | A source-qualified, post-start observation has been durably committed. |
| MATERIALIZED | The canonical output contract contains the corresponding fact or aggregate. |
| QUERYABLE | The production repository returns it under the correct day/session context. |
| DEGRADED | Tracking continues but a source, permission, scheduler, or writer is impaired. |
| FAILED | No requested source can produce, or a terminal policy/permission failure prevents tracking. |

*Foreground service/process uptime is not equivalent to ACTIVE or RECORDING. UI completion is not equivalent to QUERYABLE unless the production repository returns the fact.*

Product presentation does not flatten these lifecycle terms into one enum. `TrackingHistoryRepository` exposes orthogonal availability, evidence, and product/completeness axes under TI-D074. This allows, for example, an OS-limited source with partial prior evidence still materializing. Numeric zero requires covered evidence.

## 2.4 Invariants

| **Invariant** | **Contract** |
| --- | --- |
| One authority | SourcePolicy is the single runtime authority for enabled state, frequency/QoS, consent revision, and persistence eligibility. |
| Immutable intent | Each session starts from an immutable manifest snapshot; mid-session changes create a new effective manifest revision. |
| Purpose separation | CONTROL events cannot enter captured session history unless the same source is separately enabled for SESSION_CAPTURE. |
| One writer | Exactly one persisted owner generation may mutate each actual typed destination; every legacy/candidate mutation rechecks that source/destination fence. |
| Replay safety | Materialization is idempotent and correction-aware; additive consumers do not process the same logical contribution twice. |
| No ghost sessions | Stale automatic sessions are finalized as interrupted; stopping/final states are fenced against restart. |
| Privacy epochs | Every registration and observation carries consent/policy generation; revoked epochs cannot be newly attributed. |
| Product proof | Every rollout gate includes a production day-query assertion. |

# 3. Current architecture assessment

## 3.1 What is already valuable

- Durable source events and acquisition coordination provide a strong ingestion foundation.
- EventTrackingFrameProjection gives steps and pressure a concrete bridge into the current tracking frame/session stack.
- Location already has a canonical projection path that should be protected as the single writer during migration.
- History, Trips, and Calendar screens provide a shell to evolve rather than a greenfield UI rewrite.
- Ridgeline motion, shapes, typography, dynamic color, and glass primitives can support an expressive day journal.
- Focused unit and integration tests for ownership, planning, prerequisites, accumulators, projections, and source sessions currently pass.

## 3.2 Cross-cutting failure modes

| **Area** | **Observed shape** | **Impact** |
| --- | --- | --- |
| Configuration | Boolean enablement and frequency/QoS are read through different paths. | A plan can request a disabled source or onboarding can disagree with runtime. |
| Ownership | Plan, service gates, permissions, and active descriptors intersect late. | Zero applied sources or stale descriptors can still look like a session. |
| Control vs capture | Automatic control and captured activity share ingress; step corroboration is hidden. | Control facts can leak into history or disabled sources can still listen. |
| Materialization | TrackingCycle consumers are additive; several sources lack terminal consumers. | Replay can double-count; adding generic consumers creates unsafe multi-writer behavior. |
| Outbox | Consumption may precede durable acknowledgement; a poison item can block later work. | Loss, duplication, or head-of-line blocking after crash/retry. |
| Lifecycle | Automatic session recovery can reuse stale intent; start legality is context-sensitive. | Ghost sessions, illegal background FGS starts, or indefinite STARTING. |
| Scheduling | Wi-Fi/cell periodic loops use in-process delays. | They are opportunistic and cannot be described as wake-reliable. |
| Product | Daily summaries are rebuilt mainly from SessionSegment; Wi-Fi/cell reports are separate. | Out-of-session facts are technically present but practically undiscoverable. |

## 3.3 Specific defects to resolve early

- Review the STILL + inactive + permission branch in resolveAutoTrackingPreferenceAction; the inspected branch can resolve to ENABLE and invert expected behavior.
- Remove or declare StepActivityCorroborator as a purpose-aware control demand; it must not remain a hidden step listener when Steps capture is off.
- Stop evaluating automatic location start under a ‘session already foreground’ context when the actual origin is a background automatic start.
- Treat Wi-Fi permission and system location-service prerequisites as Android-version-specific capability, not a single generic permission flag.
- Do not introduce a second location terminal writer while the existing canonical projection is active.

## 3.4 Why the first proposal was rejected

> **Adversarial finding:** ‘Fill the missing consumers inside TrackingCycle’ does not solve replay, correction, cutover, privacy-purpose, or multi-writer problems. The accepted direction is source-specific, versioned materialization from a durable observation ledger.

# 4. Target tracking architecture

## 4.1 System flow

The target separates physical registration, authorization, durable facts, session meaning, typed products, and presentation while keeping one mutation owner.

> **1  POLICY:** SourcePolicy + consent revision define eligible sources, QoS, persistence, and retention.

*↓*

> **2  DEMAND:** Sessions, automatic control, and ambient products issue SourceDemand records.

*↓*

> **3  SUPERVISE:** One app-scoped SourceSupervisor merges same-source demands and owns one physical runtime per source. It rotates authorization without restarting unchanged hardware.

*↓*

> **4  ADMIT:** Source-native deliveries are partitioned at observed-time authorization boundaries and enter source_event_wal through the single TrackingWriter before product classification.

*↓*

> **5  PROJECT:** Six source-local cursor/projector lanes produce typed facts, membership, gaps, and completeness. One poison source cannot block another.

*↓*

> **6  OWN:** Stable delivery identity prevents duplicate ingestion; destination-specific logical range identity supports correction. Each real destination has one fenced source-specific owner and deletion fences prevent resurrection.

*↓*

> **7  PRESENT:** One TrackingHistoryRepository composes source facts for Today, Timeline, Calendar, selected-day/detail, applicable maps, export, and deletion. A persisted day cache is optional, not foundational.

## 4.2 SourcePolicy: one runtime authority

```kotlin
data class SourcePolicy(
    val source: Source,
    val enabled: Boolean,
    val qos: SourceQos,
    val captureConsentRevision: Long?,
    val ambientConsentRevision: Long?,
    val persistence: PersistencePolicy,
    val effectiveAtElapsedRealtimeMs: Long
)
```

- Plan creation, provider registration, foreground-service type calculation, UI status, and materializer eligibility all read this snapshot.
- An enabled boolean cannot be inferred from a nonzero frequency, and a frequency cannot revive a disabled source.
- Policy changes increment a monotonic revision and create an observable reconciliation event.

## 4.3 Session manifest and logical history

TrackingHistoryEntry is the stable user concept. A logical entry may own multiple Android service runs and source segments after restart, crash, handoff, or midnight.

| **Entity** | **Responsibility** |
| --- | --- |
| TrackingHistoryEntry | logicalTrackingId, mode, start/end, outcome, title, source summary, provenance |
| SessionManifestVersion | capturedSources, controlDependencies, policy/consent revisions, effective interval, zoneId |
| ServiceRun | process/service generation, boot identity, monotonic lease, start origin, terminal reason |
| SourceSegment | source, registration generation, runtime status, first/last qualified evidence |
| DaySessionSlice | portion of a logical entry that overlaps a local day; metrics split by day |

## 4.4 Purpose-aware SourceBroker

```kotlin
data class SourceDemand(
    val consumerId: String,
    val purpose: CONTROL | SESSION_CAPTURE | AMBIENT_PRODUCT,
    val source: Source,
    val qos: SourceQos,
    val persistence: PersistencePolicy,
    val consentEpoch: Long,
    val logicalTrackingId: UUID? = null
)
```

- The app-scoped supervisor keeps one provider registration when compatible same-source demands can share it. Every callback carries a physical configuration generation and the observed-time authorization revision.
- Every generation records allowed purposes: CONTROL_AUTOSTART, CONTROL_CONTINUATION, SESSION_CAPTURE, and AMBIENT_PRODUCT.
- Removing the last session demand does not stop a legitimate control or ambient demand. Authorization-only changes do not restart unchanged hardware; revoking consent fences use immediately and provider cleanup follows.

## 4.5 Two-phase session start and fenced recovery

1. Create a Room-backed logical entry and immutable manifest in STARTING.
2. Persist desired Android actions and source demands in the same database transaction.
3. The reconciler applies provider/service actions and records acceptance per source.
4. Move to ACTIVE only when at least one requested runtime is accepted; zero accepted sources becomes FAILED.
5. Move to RECORDING only after durable, source-qualified post-start evidence.
6. On recovery, CAS and a boot-domain monotonic lease fence old runs. STOPPING/FINALIZED entries never restart.

> **Automatic recovery:** Finalize stale automatic entries as interrupted and require fresh motion evidence carrying observed/received elapsed time, clock domain, registration generation, and automation epoch before a new start.

## 4.6 Canonical materialization

Do not extend additive session consumers as the universal terminal path. Source-local projectors consume their source WAL at least once and make output exactly-once in effect through stable typed destination identities.

```text
DeliveryReceipt(sourceEventId, itemIdentity, projectorVersion)
LogicalFactKey(sourceSpecificStableRangeOrFactIdentity)
```

- Typed fact/membership changes and that source cursor commit in one `TrackingWriter` transaction.
- Projectors emit destination-specific `UPSERT`, `DELETE`, and bounded `REPLACE_RANGE`/supersession mutations. Delivery idempotence is distinct from stable logical fact/range identity, so corrections and projector upgrades may change cardinality safely.
- A narrow contribution receipt is permitted only for a demonstrated non-recomputable additive destination and must commit with that destination.
- Source-specific owner flags and destination uniqueness fence legacy/candidate writers. Location remains on its protected writer until shadow parity and cutover approval.
- Replay starts from the qualified retained floor or now. Unqualified/pruned history yields `PARTIAL`/`UNAVAILABLE`; it is never fabricated.

# 5. Source-by-source operating design

Each source has a capture contract, automatic-control relationship, ambient policy, recording evidence, canonical output, and honest limitation.

| **Source** | **RECORDING evidence** | **Automatic dependency** | **Ambient policy** | **Canonical product** |
| --- | --- | --- | --- | --- |
| Location | Post-start point observed under capture generation; freshness/accuracy eligible | Activity control in automatic mode | Passive/opportunistic only with separate location consent | Route, distance, places, presence |
| Wi-Fi | Fresh item-level scan result from a successful post-start completion; cache read/attempt alone does not qualify | Activity control in automatic mode | Passive scan-result broadcasts; no ambient active scans by default | Unique/new networks, observations, band mix |
| Cell | Fresh timestamp-qualified callback item after start; cache read/request alone does not qualify | Activity control in automatic mode | Timestamp-qualified change callbacks; no ambient forced refresh | Technology, quality distribution, weak periods |
| Activity | Post-start captured transition/classification above product threshold | Same hardware, distinct CONTROL purpose | Control state allowed; durable history only by capture consent | Coalesced movement bands, active time |
| Steps | Positive delta after a post-start baseline | Optional corroboration only if explicitly declared | One selected system continuity adapter; direct counter for live demand | Day total with coverage; in-session and between-session deltas |
| Pressure | First committed qualified microsegment under capture generation | Activity Transition control in automatic mode | No continuous ambient listening by default | Pressure trend/window with coverage; no default elevation claim |

*Evidence must be durably committed and attributable to the active registration generation and manifest revision.*

## 5.1 Location

- Preserve the existing canonical location projection as the sole active writer; shadow-compare any replacement before cutover.
- Manual location-only must not start activity or step capture. Automatic location-only declares activity as CONTROL, not captured Activity.
- Ambient location is never implied by session consent. Use passive fused updates only under a separate, explainable background/ambient setting.
- Day output supports route/distance/places where quality allows; otherwise show point coverage and completeness rather than inventing a trip.

## 5.2 Wi-Fi

- Session capture can request scans subject to OS limits; ambient mode consumes successful passive scan-result broadcasts and is explicitly opportunistic. `getScanResults()` is a cache read/payload retrieval step, not an acquisition mode.
- Persist privacy-safe network identity using a per-install keyed HMAC; rotate keys on deletion or consent reset. Conceal SSID/BSSID in the default UI.
- A successful completion qualifies `RECORDING` only when at least one result item has a defensible timestamp after the effective boundary and within the source age limit. An attempt, timer tick, cached list, failed update, permission check, or receipt-time substitution does not. Mixed-age lists retain only qualified items.
- Day summaries favor useful patterns—unique networks, new networks, observation count, band mix—not a surveillance-like device inventory.

## 5.3 Cell

- Prefer timestamp-qualified change callbacks. `getAllCellInfo()` on current target levels reads cache without refreshing it; `requestCellInfoUpdate()` is an explicit, rate-limited, non-guaranteed paid request. Neither the cache read nor request qualifies without a fresh returned provider item.
- Minimize subscription identifiers and hash any stable radio identity with the same deletion/rotation guarantees as Wi-Fi.
- Day summaries show radio technology, quality distribution, weak periods, and availability. Tower identifiers remain hidden by default.

## 5.4 Activity

- Automatic control and captured activity may share one OS registration, but every event carries purpose eligibility. CONTROL-only events cannot materialize into session activity.
- Coalesce noisy transitions into understandable time bands. Retain raw control state briefly and only for debugging/automation needs.
- Fix the STILL/inactive preference branch and verify start/stop semantics with fresh observed-time evidence.

## 5.5 Steps

- Use direct `TYPE_STEP_COUNTER` boundaries for live session evidence. Ambient continuity selects exactly one system adapter: prefer Health Connect on-device Steps on API 34 + Extension 20 where capable, otherwise evaluate the accountless Recording API. Session metrics are deltas, not independent continuously owned listeners.
- RECORDING requires a positive delta after baseline; registering the sensor or reading an unchanged counter is insufficient.
- Two qualified unchanged same-generation boundaries prove a covered zero-step interval even though they do not advance lifecycle RECORDING. A lone baseline remains no-observation/partial, and queries never collapse those states to the same zero.
- The Step canonicalizer gives deterministic precedence to overlapping provider intervals; direct, Health Connect, and Recording data are never added for the same interval. Ambient Steps still needs explicit retention, export, deletion, capability/completeness and UI status.
- If step corroboration remains part of automatic tracking, expose it as a CONTROL demand and setting; never hide it behind another source.

## 5.6 Pressure

- Default to session capture. Continuous ambient pressure sampling is not justified merely because the sensor callback is locally inexpensive.
- Present pressure trends plainly. Show elevation/vertical range only when calibration and sensor fusion make the inference defensible, and label estimates.
- A committed source-qualified microsegment/window advances RECORDING. It contains sample count, endpoints, range, mean/variance, trend/fit, accuracy and actual/expected elapsed coverage. An earlier preview is provisional; a crash before close produces a bounded explicit gap rather than per-sample Room writes.

# 6. Ambient and out-of-session acquisition

## 6.1 Principle

> **Decouple lifecycle, not responsibility:** Provider registration may outlive a session. Durable ambient collection may not. Registration, observation, attribution, persistence, retention, presentation, export, and deletion are separate decisions.

A provider is not ‘free’ merely because the OS already exposes it. The cost model includes power, wakeups, OS quota, privacy sensitivity, storage, processing, support burden, and user expectation.

## 6.2 Ambient eligibility matrix

| **Source** | **Ambient candidate** | **Allowed acquisition** | **Required authority** | **Default retention stance** |
| --- | --- | --- | --- | --- |
| Steps | **Yes** | One selected Health Connect/Recording continuity adapter; direct counter only for live demand | Daily steps consent and provider permission | Provider intervals + live boundaries + overlap precedence + compact aggregates |
| Activity | **Yes for control** | Debounced recognition state | Automatic-control consent | Short TTL unless Activity history is captured |
| Location | **Conditional** | Passive/opportunistic updates | Separate ambient/background location | Purpose-limited raw TTL + canonical day facts |
| Wi-Fi | **Conditional** | successful passive scan-result broadcasts; cache retrieval is not acquisition | Ambient connectivity consent | Keyed identity; no active ambient scans by default |
| Cell | **Conditional** | timestamp-qualified change callbacks; cache reads are bootstrap only | Ambient connectivity consent | Minimized radio identity; no forced refresh |
| Pressure | **No by default** | None | Future explicit product only | Session retention only |

## 6.3 Observation and attribution

```text
source_event_wal(
  providerEventId, source, physicalGeneration, authorizationRevision,
  observedInterval, receivedClocks, purposeEligibility, consentEpoch,
  payloadVersion, checksum
)

source_fact_membership(
  typedFactId, purpose, logicalTrackingId?, consentEpoch, retentionClass
)
```

- One WAL provider delivery may contain several authorization-homogeneous subranges. Location/Activity split by item observed time, Pressure closes at known boundaries, radio uses its defensible provider observation time, and an unknowable Step delta becomes a new baseline plus explicit gap.
- WAL deliveries and typed facts keep stable identity and are never rewritten to ‘belong’ to a session.
- Membership is auditable and derived from immutable authorization intervals, manifest source set, observed time, freshness, purpose and consent. The app currently needs at most the active logical session plus ambient/control classification; do not generalize to an arbitrary attribution graph.
- Optional context is purpose-compatible, not merely nearby: same-session capture may use capture Location from that session, while ambient radio may use only independently ambient-eligible Location under a compatible consent epoch. Control-only, deleted, cross-session capture and incompatible-purpose context are excluded.
- A source disabled for capture cannot be attributed to session history even if the same hardware was active for CONTROL or AMBIENT_PRODUCT.
- Day totals count each canonical fact once. Attribution classifies a fact as in-session or between-session; the UI does not add overlapping aggregates blindly.

## 6.4 Privacy and lifecycle guarantees

- Ambient controls are visible separately from session-source controls and state the benefit, cadence, and retention in plain language.
- Consent revocation removes broker demand immediately, blocks new attribution for the old epoch, schedules scoped deletion, and rotates per-install radio identifiers where required.
- ‘Why was this recorded?’ explains source, purpose, approximate time, consent setting, and whether the fact was inside or outside a session.
- Export and deletion operate by purpose and day as well as all-data scope; tombstones prevent deleted facts from reappearing during replay/backfill.
- Ambient absence has explicit reasons: disabled, unsupported, permission missing, OS-limited, no observation, materializing, or historical data unavailable.

# 7. History information architecture and optional M3 Expressive evolution

The immediate product gate is truthful source visibility through the existing Tracker surfaces and one shared history facade. The Days-first concepts below remain the preferred future experience, but they do not precede the first source vertical or justify a persisted universal day platform.

## 7.1 Current product gap

The repository has useful fragments but no coherent ambient-history product:

| **Surface** | **What exists** | **Gap** |
| --- | --- | --- |
| History | Timeline, Trips, Calendar tabs | Timeline is primarily built from trips; discovery entries exist but are not emitted. |
| Calendar day | Distance, steps, trip count, trip list | No source completeness, ambient section, or non-trip history entries. |
| Daily summary | Day, distance, steps, duration, trips, active tracking | Aggregated mainly from SessionSegment, so ambient facts are not first-class. |
| Wi-Fi / Cell | Separate repositories and report screens | All-time/browse views are detached from day/session context. |
| Dashboard Today | Progress summary | Null/empty reads as ‘no activity,’ conflating disabled, unavailable, and not yet materialized. |

## 7.2 Deferred information architecture concept

If a later separately approved product gate adopts the Days-first experience, a day becomes the
primary History container and sessions plus between-session information become its children. The
following interaction model is not part of the current source-vertical dependency graph.

- Dashboard Today deep-links to today’s DayDetail.
- History opens a pageable Days journal. Trips remains available as a filter/legacy lens, not the data model.
- Calendar selection opens the same DayDetail, preserving one repository and one interaction model.
- Filters: All, Sessions, Steps, Movement, Environment, and Discoveries.
- Session detail links back to the day; source detail preserves in-session vs between-session attribution.

## 7.3 Day card anatomy

```text
HISTORY

AUG 21  •  THURSDAY
╭─────────────────────────────────────────────────────╮
│ A lively Thursday                                   │
│ 8,421 steps  •  5.4 km  •  1 h 34 min              │
│ 2 sessions  •  Activity  •  Steps  •  Wi-Fi        │
│ 00 ━━━●━━━[ MORNING WALK ]━━●━━━━[ EVENING ]━━ 24  │
╰─────────────────────────────────────────────────────╯

SESSIONS
  Morning walk      07:42–08:18   3.2 km • 4,260 steps
  Evening activity  18:21–19:04   Activity • Pressure

BETWEEN SESSIONS
  4,161 steps  •  12 networks  •  mostly good 5G

COLLECTION
  Complete for enabled sources  ·  Why was this recorded?
```

*Conceptual wireframe; final Compose implementation should use semantic cards, not monospaced text.*

## 7.4 Adaptive hero and truthful empty states

| **Day shape** | **Hero metric** | **Supporting treatment** |
| --- | --- | --- |
| Location present | Distance / active time | Route or place chip; steps secondary |
| Steps only | Step total | Hourly sparkline; in-session vs between-session split |
| Activity only | Active time | Coalesced movement bands and dominant activity |
| Pressure only | Pressure trend | Qualified vertical estimate only when defensible |
| Wi-Fi only | Networks observed | New/known counts and band mix; identities concealed |
| Cell only | Coverage quality | Technology mix and weak periods |
| Nothing collected | Status, not zero | Disabled, unavailable, permission, OS-limited, or no observation |

## 7.5 M3 Expressive direction

Use the project’s existing Ridgeline foundations to make days feel lively without obscuring data integrity.

| **Principle** | **Application** |
| --- | --- |
| Adaptive shape | A softly asymmetric day hero changes emphasis by the dominant source while retaining stable layout and touch targets. |
| Time as texture | A compact 24-hour ribbon uses bold spans for sessions and quieter dots/bands for ambient facts. |
| Motion with meaning | Spring expansion reveals session or ambient detail; restrained shape morphs communicate state changes, not decoration. |
| Friendly summaries | Deterministic local rules generate headlines such as ‘A lively Thursday’—never opaque generative claims. |
| Dynamic color | Use Material color roles, sufficient contrast, and distinct error/caution semantics; never encode completeness by color alone. |
| Haptics | Reserve haptics for explicit expansion, filter selection, and meaningful milestones. |
| Accessibility | Support large text, TalkBack reading order, 48 dp targets, reduced motion, non-color status labels, and plain-language privacy explanations. |

## 7.6 History domain contract

```text
HistoryDay(
  dayKey, zoneId, sourceMask,
  totals: Map<Metric, DayMetricBreakdown>,
  sessionSlices: List<DaySessionSlice>,
  ambientHighlights: List<AmbientHighlight>,
  completeness: Map<Source, DayMaterializationState>,
  revision
)

DayMetricBreakdown(total, inSessions, outsideSessions)
```

- Cross-midnight sessions appear as a slice on each day with a ‘continued’ badge. Metrics split by the day boundary; the session is counted once according to the selected counting rule.
- Day identity uses the zone captured with the observation/history entry so old facts do not migrate when the user changes time zone.
- Example: 8,421 total steps = 2,330 in sessions + 6,091 between sessions. The day total is canonical; the classification is a partition, not another sum. This may be composed on demand by `TrackingHistoryRepository`; a persisted cache is introduced only if profiling proves necessary.

## 7.7 Source detail and delight

- Location: route preview, distance, places, and coverage confidence.
- Steps: total, hourly trace, session subtotal, and between-session subtotal.
- Activity: coalesced bands and active-time composition.
- Pressure: sparkline, range, and explicitly qualified vertical estimate.
- Wi-Fi: unique/new counts and band mix, with sensitive identifiers concealed.
- Cell: technology composition, quality bands, and weak periods without tower identifiers.
- Microcopy distinguishes ‘quiet day’ from ‘nothing was collected’ and invites the user to understand or adjust collection.

# 8. Durable data, typed projection, and history composition

## 8.1 Core records

| **Record** | **Purpose** |
| --- | --- |
| source_policy | Authoritative enablement, QoS, consent epochs, retention |
| source_demand | Consumer/purpose request; may be session-bound or app-scoped |
| source_registration | Physical provider configuration generation, capabilities, lifecycle |
| source_authorization | Effective purpose/demand/consent intervals over a physical source |
| source_event_wal | Immutable source-native delivery with clocks, physical/authorization identities and integrity hash |
| tracking_history_entry | Logical user-visible tracking record across service runs |
| session_manifest_version | Effective captured/control source contract and zone |
| source_projection_cursor | Source-local replay position, projector version and bounded quarantine state |
| typed source facts | Location fixes, radio snapshot headers/children, Activity facts, Step boundaries/deltas and Pressure windows |
| source_fact_membership | Auditable session/ambient/control classification and retention provenance |
| source_gap / source coverage | Explicit missing/OS-limited/provider/runtime intervals and product completeness |
| deletion_fence / collected-data epoch | Replay/import/delayed-callback no-resurrection boundary |
| source_destination_owner | Narrow persisted source/destination owner generation fencing legacy and candidate mutations |

## 8.2 Correction-safe materialization

1. A source worker reads only its source after its durable cursor and verifies the WAL bytes before decode.
2. It computes pure typed `UPSERT`/`DELETE`/bounded `REPLACE_RANGE` mutations from the immutable delivery, authorization interval and projector version.
3. `TrackingWriter` rechecks deletion/consent fences and cursor identity in one transaction.
4. It upserts stable typed facts, memberships and completeness/gaps, supersedes the same logical interval where needed, and advances only that source cursor.
5. Deterministic poison is quarantined for that source with an explicit completeness effect; retryable storage failure leaves its cursor unchanged while other sources continue.
6. Derived source/day summaries are recomputed idempotently. If a persisted cache is introduced, its invalidation revision and acknowledgement are monotonic so a newer update cannot be lost.

## 8.3 Source-local maintenance and optional caches

The correctness boundary is typed facts plus source cursors, not an unconditional dirty-day subsystem. Existing compatible summaries can be recomputed from stable facts. Add a narrow persisted invalidation/cache only for a named consumer whose measured query cost requires it. New/corrected facts, session boundaries, deletion/key rotation, time uncertainty, retained-floor replay and source cutover must invalidate every actual derived consumer.

One unique WorkManager maintenance chain can reconcile ambient subscriptions, import provider continuity data, repair projectors/caches, and apply retention. Its execution is inexact and correctness never treats it as the sampling clock.

## 8.4 Query repositories

```kotlin
interface TrackingHistoryRepository {
  fun observeDay(dayKey: DayKey): Flow<HistoryDay?>
  fun observeToday(zoneId: ZoneId): Flow<HistoryDay?>
  fun observeSession(logicalTrackingId: UUID): Flow<HistorySession?>
  suspend fun explainFact(factId: FactId): RecordingExplanation
}

// Optional later product extension, only after a separate Days-first gate.
interface PagedDayHistoryRepository {
  fun pagedDays(filter: HistoryFilter): Flow<PagingData<HistoryDay>>
}
```

- Today, Timeline, Calendar, selected-day/session detail and applicable maps read through this facade. It is read-only. Separate export, deletion/import and context services share its stable identities and submit mutations through `TrackingWriter`; source report screens can remain specialized but resolve the same day/fact identity.
- Historical backfill labels each source/day as COMPLETE, PARTIAL, UNAVAILABLE, MATERIALIZING, or FAILED; absence is never silently converted to zero.

## 8.5 Retention and deletion

- Raw observations have purpose-specific TTLs; canonical day facts may have longer user-facing retention.
- Deletion writes durable tombstones before physical compaction so replay and backfill cannot resurrect data.
- Radio HMAC keys rotate on full deletion or relevant consent reset; old identifiers become unlinkable.
- Exports include provenance, in-session/between-session classification, time-zone identity, and completeness status.
- Scoped fences are keyed by source, purpose, consent epoch and observed-time scope/boundary. Live admission, replay and import recheck them inside `TrackingWriter`.
- Portable merge import stages provenance-bearing base product facts only, treats identity+checksum as the duplicate test, rejects/quarantines conflicting or unverifiable rows by explicit policy, and recomputes derived products. Whole-database restore is separately labeled and empty-target-only.
- Portable export includes every released source through typed facts/membership/completeness, excludes control-only operational WAL and exposes only approved minimized radio information.

# 9. Android lifecycle, scheduling, and recovery

## 9.1 Desired state and reconciler

Room can atomically update internal state, but Android services and provider registrations are external side effects. Model them as desired state plus an idempotent reconciler.

| **Acknowledgement** | **Meaning** |
| --- | --- |
| START_ACCEPTED | The OS/provider accepted the request; runtime can produce. |
| TEMPORARILY_ILLEGAL | Current foreground/exemption context cannot perform the action; retry on a legal trigger. |
| TERMINAL_FAILURE | Permission, unsupported capability, or policy makes the action impossible. |
| STOP_ACCEPTED | The runtime has accepted unregister/stop; final cleanup can continue. |

Every start request records origin: MANUAL_FOREGROUND_START, AUTOMATIC_BACKGROUND_START, RECOVERY, or POLICY_RECONCILIATION. Foreground-service legality is evaluated against the real origin and current OS context.

## 9.2 Outbox hardening

- Persist the desired action before execution and acknowledge only after acceptance or terminal classification.
- Partition ordering by logical entry/source/action family so one poison item cannot block the entire outbox.
- Use bounded retry with explicit quarantine and operator-visible reason; never silently drop.
- Make action handlers idempotent by registration generation and desired-state revision.
- Record start legality, foreground-service type, permission state, and retry trigger in telemetry.

## 9.3 Clocks and leases

- Session and registration leases use elapsed realtime plus boot identity; wall clock is display metadata, not fencing authority.
- Motion/control outbox payloads carry observed and received elapsed time, clock domain, registration generation, and automation epoch.
- After reboot, old leases are invalid by boot identity. Recovery finalizes or reconstructs state from Room, never from an in-memory descriptor.

## 9.4 Scheduling honesty

- In-process coroutine delays are opportunistic. They do not wake a sleeping CPU and must not underpin product promises.
- Use provider callbacks/broadcasts when available, WorkManager for deferred reconciliation/materialization, and foreground execution only when the user-visible session justifies it.
- If Wi-Fi/cell cadence becomes a product requirement, run device/OEM experiments first and select a measured wake strategy with battery budgets and OS-compliance review.

## 9.5 Failure behavior

| **Condition** | **State** | **Product/recovery behavior** |
| --- | --- | --- |
| No source accepted | FAILED | Explain permission/device/policy cause; offer repair action |
| Some sources accepted | ACTIVE / DEGRADED | Continue accepted sources; name unavailable ones |
| Accepted, no evidence | ACTIVE | Show waiting/OS-limited status; do not claim recording |
| Evidence durable, materializer lag | RECORDING | Show data pending; queue survives process death |
| Materialized, day not rebuilt | MATERIALIZED | Prioritize dirty day; expose diagnostic lag |
| Crash during stop | STOPPING then FINALIZED | Reconciler finishes unregister and interruption reason |

# 10. Migration, rollout, and implementation workstreams

## 10.1 Delivery phases

| **Phase** | **Scope** | **Exit gate** |
| --- | --- | --- |
| 0. Baseline and kill switches | Persist source/mode/version dimensions; lock current fixtures; add per-source capture/materializer switches. | Existing behavior measurable; unexpected-source writes detectable. |
| 1. Policy and state authority | Introduce SourcePolicy, immutable manifests, Room CAS state machine, monotonic leases, explicit start origin. | Zero-source start fails closed; stale automatic sessions finalize interrupted. |
| 2. SourceBroker | Replace session-owned listeners with purpose-aware demands and registration generations. | All provider callbacks identify purpose eligibility and consent epoch. |
| 3. Writer, WAL and typed projectors | Introduce the sole TrackingWriter and source-native bulk admission, then instantiate one source cursor/projector with each real typed vertical plus its identities, membership, gaps and deletion fences. No six-unused-lane prerequisite exists. | Crash/replay gives identical typed state; one poison source cannot block another. |
| 4. History product | Add one TrackingHistoryRepository facade and source completeness to existing Today/Timeline/Calendar/detail/map/export/delete consumers. | Production consumers return sole-source and between-session facts truthfully. |
| 5. Product UX | Repair existing source/ambient/empty/error states; optionally begin Days-first UI after a separate product gate. | Usability/accessibility checks pass; retained facts remain discoverable. |
| 6. Source rollout | Shadow/canary source materializers in a controlled order; retire legacy paths. | Each source passes manual, automatic, transitions, crash, deletion, and device gates. |
| 7. Expressive polish | Motion, shape, haptics, microcopy, performance tuning, retention controls. | Reduced-motion, large-text, low-end, and battery targets pass. |

## 10.2 Recommended source sequence

1. Steps — prove manual/session baselines, deltas, correction, deletion/export, and a production query first; automatic and ambient/day partitioning remain separate gates.
2. Pressure — validate source-qualified evidence and a session-first materializer with limited ambient scope.
3. Location — preserve the existing writer, add attribution/day output, and prove a safe shadow/cutover path.
4. Activity — split CONTROL from CAPTURE while reusing one physical registration.
5. Wi-Fi — add privacy-safe identity, opportunistic ambient semantics, and OS-version prerequisites.
6. Cell — add minimized identity and callback-based quality summaries across subscription/device variants.

## 10.3 Implementation workstreams

| **Workstream** | **Deliverables** | **Primary area** |
| --- | --- | --- |
| A. Policy | SourcePolicy repository, settings migration, consent epochs, manifest snapshots | Platform + Settings |
| B. Broker | Demand merge, provider adapters, registration generations, purpose stamping | Tracking Engine |
| C. Lifecycle | Room state machine, leases, action reconciler, FGS legality, recovery | Tracking Engine + App |
| D. Data | One TrackingWriter, source WAL/cursors, typed facts/membership/completeness, deletion fences | Tracking Engine + Stats/Data |
| E. History product | One TrackingHistoryRepository facade, existing consumer integration, optional profiled caches | Stats/Data |
| F. UI | Existing Today/Timeline/Calendar/detail truth states; optional later Days journal | Statistics + Dashboard |
| G. Quality | Matrix harness, crash injection, device lab, soak, telemetry and gates | QA + Platform |

## 10.4 Migration safety

- Dual-read or read-only shadow before cutover; never activate two canonical writers for the same actual destination.
- Key rollout by source, mode, destination owner/projector version, schema capability and control-dependency version.
- Each source cursor starts at qualified genesis, a retained floor, or now; legacy-unqualified inputs make historical reconstruction explicitly unavailable.
- Backfill creates explicit PARTIAL/UNAVAILABLE completeness when raw history is missing; it does not fabricate zeros.
- Each source has a rollback switch that fences its candidate owner without deleting committed facts or lowering consent/deletion epochs.

# 11. Verification, observability, and acceptance

## 11.1 Base scenario matrix

The minimum static matrix is 6 sources × 2 session modes = 12 base scenarios. Every row executes the same end-product contract.

| **Source** | **Mode** | **Provider assertion** | **Product assertion** |
| --- | --- | --- | --- |
| Location | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Location | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |
| Wi-Fi | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Wi-Fi | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |
| Cell | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Cell | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |
| Activity | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Activity | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |
| Steps | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Steps | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |
| Pressure | Manual | Only source captured; no hidden control | RECORDING → MATERIALIZED → QUERYABLE |
| Pressure | Automatic | Only source captured; declared control only | Fresh motion → session → source evidence → day query |

## 11.2 Dynamic and failure matrix

- Enable/disable a source before start, during STARTING, while ACTIVE, after RECORDING, and during STOPPING.
- Grant/revoke permission and consent at every state; rotate consent epochs and verify blocked attribution.
- Crash after each transaction boundary: manifest/desired action, provider acceptance, WAL admission, typed fact/membership plus source cursor, history/cache invalidation, deletion fence, physical removal.
- Reboot, wall-clock jump, time-zone change, process kill, provider callback reordering, duplicate delivery, and stale automatic motion.
- Upgrade/replay from retained floor; legacy/candidate owner shadow, cutover, rollback, retirement, and source-scoped WAL pruning.
- Deletion during capture, after materialization, during backfill, and after sync/export; verify tombstones prevent resurrection.
- Android versions, permission states, battery saver, doze, background restrictions, multiple SIMs, missing sensors, and representative OEMs.
- Long soak with delayed/noisy providers, poison events, source-cursor blockage, low storage, and repeated session transitions.
- Automatic cold starts across API 26–37 use only legal origins and persist the exact trigger/type/epoch envelope before service start. Current official Activity Recognition documentation lists `ON_FOOT` as Transition-supported; the pinned Play services/provider/device path must still prove request acceptance and retain WALKING/RUNNING coverage rather than relying on an outdated unsupported-type assumption.
- Health Connect/Recording/direct Step overlaps, provider changes, delayed imports and resets produce one canonical interval total.

## 11.3 Required assertions per scenario

| **Layer** | **Assertion** |
| --- | --- |
| Policy | The manifest matches SourcePolicy and consent epochs; disabled capture sources are absent. |
| Registration | Only requested capture plus declared control/ambient demands are registered. |
| Lifecycle | ACTIVE and RECORDING transitions follow accepted runtime and qualified evidence. |
| Durability | Crash/retry produces no qualified-unit loss, duplicate typed effect, or global head-of-line block. |
| Materialization | Exactly one destination owner writes; typed identity, supersession/recompute and deletion fences are idempotent. |
| Product | TrackingHistoryRepository returns the correct historical day total, session/outside classification, source facts, optional-context provenance and completeness to every applicable consumer. |
| Privacy | Control-only and revoked-epoch observations are absent from captured history and export. |
| UX | The user sees an honest state and can understand why the fact exists. |

## 11.4 Telemetry and proposed service targets

| **Signal** | **Initial target** | **Definition** |
| --- | --- | --- |
| Unexpected source writes | 0 | Captured source not present in effective manifest |
| Duplicate typed effect | 0 | Duplicate logical fact/interval mutations beyond the unique typed identity |
| Stale automatic entries after recovery | 0 | Nonterminal entry without live lease/recovery disposition |
| Manual start acceptance | p95 < 3 s | From user action to accepted runtime or actionable failure |
| Foreground materializer lag | p95 < 60 s | Durable observation to canonical product |
| Deferred ambient history lag | p95 < 15 min | Eligible ambient observation to the production history facade |
| Outbox oldest eligible age | Alert by action class | Age excludes TEMPORARILY_ILLEGAL until retry trigger |
| History recompute failure | < 0.1% / day | Actual cached/derived consumer not repaired after bounded retries |

*Latency/error targets are proposed starting points and should be calibrated against device-lab and production baselines before becoming SLOs.*

Persist dimensions with each event: source, manual/automatic/ambient mode, writer/materializer version, schema capability, control-dependency version, OS/device class, cohort, policy revision, consent epoch, and start origin.

## 11.5 Rollout gates

- Shadow: typed/history output diffs within defined tolerance and no destination multi-writer activation.
- Internal: all 12 base scenarios plus crash/deletion suites pass on reference devices.
- Canary: duplicate balance, unexpected writes, lag, battery, and product visibility remain within bounds.
- Expansion: OEM/device and upgrade cohorts meet the same gates; quarantines are understood.
- General availability: all applicable existing product consumers are truthful, privacy controls and explanations are complete, and rollback has been rehearsed. A Days-first redesign is a separate product rollout unless explicitly approved.

# 12. Risks, decisions, and adversarial-review disposition

## 12.1 Product and architecture decisions

| **Decision** | **Position** | **Rationale / next action** |
| --- | --- | --- |
| Automatic control dependency | **Adopt** | Activity is explicit CONTROL for automatic mode; captured only when Activity is enabled. |
| Step corroboration | **Decide before broker rollout** | Either remove it or expose it as a declared CONTROL demand and setting. |
| Ambient steps | **Recommended, capability-gated** | Select one Health Connect/Recording continuity adapter; canonicalize live/import overlaps and add retention/export/deletion/completeness controls. |
| Ambient location | **Opt-in only** | Separate consent; passive/opportunistic acquisition; prominent explanation. |
| Ambient Wi-Fi/cell | **Conditional** | Passive/timestamp-qualified callbacks only by default; cache reads are not acquisition and active scan/refresh budgets require separate device evidence. |
| Ambient pressure | **No by default** | Require a concrete future product and battery/privacy review. |
| History navigation | **Future product direction** | First repair the existing Today/Timeline/Calendar/detail flows through one history facade; gate a Days-first redesign separately. |
| Retention durations | **Open** | Product/privacy decision per purpose and source; encode in SourcePolicy. |
| Pressure vertical metric | **Conditional** | Ship only with defensible calibration and clear estimate labeling. |

## 12.2 Principal risks and mitigations

| **Risk** | **Failure** | **Mitigation** |
| --- | --- | --- |
| Privacy surprise | Ambient radio/location collection feels unrelated to sessions. | Separate controls, purpose labels, ‘why’ sheet, minimization, TTL, deletion/key rotation. |
| False readiness | Service is alive but source never records or materializes. | State model plus source-qualified evidence and production-query acceptance. |
| Double counting | Replay, overlapping Step providers, corrections, or two writers mutate the same result. | Stable typed identities, interval precedence, source-specific owner fencing, deterministic recomputation, deletion fences. |
| Android illegality | Automatic FGS start loses exemption or uses wrong context. | Real start origin, tri-state ack, desired-state reconciler, deferred legal retry. |
| Ghost automation | Stale motion/session descriptors restart tracking. | Monotonic leases, automation epochs, fresh evidence, interrupted finalization. |
| Unreliable cadence | Wi-Fi/cell look periodic in code but stop in sleep. | Label opportunistic; instrument; measure OEMs before promises. |
| UI overload | Six sources create a dense forensic screen. | Adaptive hero, progressive disclosure, day ribbon, collapsed ambient, friendly summaries. |
| Migration deadlock | A source projector starts before retained qualified evidence or treats legacy-unqualified WAL as product fact. | Qualified retained-floor cursor, explicit partial/unavailable state, exact populated v27→v28 execution. |

## 12.3 How adversarial review changed the design

| **Review** | **Challenge** | **Design response** |
| --- | --- | --- |
| Focused source reviews | Assessed each single-source scenario independently. | Separated acquisition success from user-visible end-to-end success. |
| Adversarial round 1 | Attacked terminal consumers, Android lifecycle/privacy, and rollout. | Rejected generic TrackingCycle consumers; introduced policy normalization, two-phase start and hardened durable effects. |
| Adversarial round 2 | Re-reviewed the updated design from architecture, Android, and validation angles. | Added purpose-stamped generations, monotonic leases, retained-floor modes, qualified evidence, real start origins, radio minimization, and product-query gates. |
| Ambient follow-up | Questioned session ownership of inexpensive providers. | Introduced SourceBroker demands and separated registration, observation, attribution, retention, and product consent. |
| UX follow-up | Asked whether out-of-session data is actually accessible. | Made one shared history contract and existing-product visibility mandatory; retained Days-first M3 as a separately gated product evolution. |
| Platform/source/architecture expert wave | Challenged lifecycle legality, ambient continuity, callback loss, write amplification, global serialization and framework scope. | Added Transition-only durable starts, provider-owned ambient execution, selected Steps continuity, Pressure microsegments, one supervisor/runtime/source, one mutation owner, source-local projectors, typed identity-as-receipt and one production history facade. |
| Fresh R4 data/Android/product wave | Attacked corrected batches, correction identity, deletion/import, owner cutover, API legality/power and product scope. | Added authorization-homogeneous units; logical range mutations; two narrow fences; verified-zero Steps; orthogonal product state; purpose-safe context; enforceably thin supervisor/writer/history owners. Data and Android verdicts remain blocked on implementation/device evidence; product/scope passed with corrections. |

## 12.4 Final recommendation

> **Proceed only through evidence gates:** finish the lean v28 migration boundary, legal two-phase start, one supervisor/writer ownership, source-native admission and the first typed Steps vertical through the production history facade. Generalize only mechanics proven by that vertical. Preserve the Location writer, keep every source/ambient rollout independent, and do not begin a universal day platform or full UI rewrite before actual product evidence requires it.

# Appendix A. Acceptance checklist

☐ SourcePolicy is the only runtime authority; settings, plan, service, FGS types, status, admission and typed projectors agree.

☐ Each logical entry has immutable/effective manifest revisions and declared control dependencies.

☐ Manual only-X registers no hidden capture or control source.

☐ Automatic only-X registers X for capture plus explicitly declared CONTROL dependencies only.

☐ A zero-source request fails closed; partial acceptance becomes DEGRADED with named causes.

☐ RECORDING requires the source-specific qualified evidence defined in section 5.

☐ Control-only observations cannot appear in captured session history, day totals, or export.

☐ One actual owner mutates each typed destination; source cutover and retained-floor cursor are persisted and fenced.

☐ Crash/replay/correction/deletion tests show identical typed state, zero overlap inflation and no resurrection.

☐ Wi-Fi/cell ambient behavior is described as opportunistic unless a wake strategy passes measurement gates.

☐ Radio identities are minimized, keyed per install, and rotated on relevant deletion/consent reset.

☐ TrackingHistoryRepository returns each retained fact under the correct stable day, session/outside classification, provenance and completeness.

☐ Cross-midnight and time-zone behavior is deterministic and does not double-count.

☐ Today, Timeline, Calendar, selected-day/session detail, applicable maps, export and deletion share TrackingHistoryRepository semantics; an optional Days product consumes the same contract.

☐ The UI distinguishes zero from disabled, unavailable, OS-limited, materializing, partial, and failed.

☐ ‘Why was this recorded?’ and ambient consent/retention/export/deletion flows are implemented.

☐ All 12 source×mode base scenarios, dynamic transitions, crash boundaries, upgrades, deletion, device/OEM, and soak suites pass.

☐ Rollout telemetry includes source, mode, versions, capabilities, dependencies, cohort, policy, consent, and origin dimensions.

# Appendix B. Code evidence and reference points

The plan was grounded in the current repository. Paths below are review anchors; implementation should re-check exact lines as code evolves.

| **Area** | **Repository anchors** |
| --- | --- |
| Tracking cycle and projection | tracker/engine/src/main/java/com/adsamcik/tracker/tracker/data/collection/TrackingCycle.kt; tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/projection/EventTrackingFrameProjection.kt |
| Automatic control | tracker/engine/src/main/java/com/adsamcik/tracker/tracker/api/BackgroundTrackingApi.kt; tracker/engine/src/main/java/com/adsamcik/tracker/tracker/api/StepActivityCorroborator.kt |
| Source planning/ownership | tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SemanticAcquisitionPlanFactory.kt; TrackerServiceSourceSession.kt; TrackingSettingsStatusProvider.kt |
| Source consumers | tracker/engine/src/main/java/com/adsamcik/tracker/tracker/component/consumer/data/{Location,Wifi,Cell,Activity}TrackerComponent.kt |
| History presenter and UI | feature/statistics/src/main/java/com/adsamcik/tracker/statistics/presenter/HistoryPresenterViewModel.kt; ui/HistoryRoute.kt; ui/CalendarContent.kt; viewmodel/HistoryTypes.kt |
| Daily summary | core/base/src/main/java/com/adsamcik/tracker/shared/base/database/aggregator/DailySummaryAggregator.kt |
| Connectivity repositories | stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultWifiObservationRepository.kt; DefaultCellSignalRepository.kt |
| Dashboard Today | feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/compose/cards/TodayProgressCard.kt |
| Expressive design primitives | core/ui/src/main/java/com/adsamcik/tracker/shared/utils/style/compose/Motion.kt; Shape.kt; Typography.kt |
| Focused tests | tracker/engine/src/test/.../SemanticAcquisitionPlanFactoryTest.kt; TrackerServiceSourceSessionTest.kt; EventTrackingFrameProjectionTest.kt; TrackingOrchestratorIntegrationTest.kt |

## External design references

Material 3 for Compose: [Android Developers — Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)

Material system guidance: [Material Design 3](https://m3.material.io/)

Android lifecycle, provider, ambient-continuity and power-measurement references are maintained with the executable pipeline in `tracking-infrastructure/ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md` §19.
