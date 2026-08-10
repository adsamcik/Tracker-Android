# Tracking Source-Event Rework

> **Status:** Phase 10 code present; terminal-consumer, physical-device, calibration, performance, and canonical-writer gates pending
> **Branch:** codex/tracking-source-event-rework
> **Last updated:** 2026-08-10
> **Decision:** Replace global collection-cycle acquisition with centrally coordinated,
> source-native event acquisition. Retain one service lifecycle, one durable ingress,
> one ordering authority, and explicit temporal projections.

---

## 1. Executive decision

Tracker will move from a model in which one location or ambient trigger starts a
TrackingCycle and then polls every enabled producer, to a model in which each physical
source uses its Android-native acquisition mechanism and emits durable, timestamped
source events.

This is not a design for several independent timers or services. There remains:

- one TrackerService for an active tracking run;
- one foreground-capability owner;
- one durable ingress and admission order;
- one coordinator for source plans, lifecycle, backpressure, and recovery;
- one session authority;
- one projection pipeline for persisted records, live state, policy, and statistics.

The rework separates four concerns that the current cycle conflates:

1. Acquisition: when Android or hardware obtains an observation.
2. Delivery: when Android delivers a callback or batch to Tracker.
3. Projection: when observations are joined or aggregated into domain facts.
4. Persistence: when durable facts are committed and downstream effects run.

User controls will describe source-appropriate behavior rather than presenting every
source as if it had an exact polling frequency.

---

## 2. Motivation and current-state diagnosis

The current flow is approximately:

~~~text
Location trigger or AmbientCollectionTrigger
    -> TrackerService.onUpdate(TrackingCycle)
    -> TrackingCycleDispatcher
    -> DataProducerManager.getData()
    -> every active producer mutates one TrackingCycleBuilder
    -> tracking component pipeline
    -> signal/WAL pipeline and Room
~~~

Relevant implementation points include:

- tracker/engine/.../service/TrackerService.kt
- tracker/engine/.../component/trigger/AmbientCollectionTrigger.kt
- tracker/engine/.../component/DataProducerManager.kt
- tracker/engine/.../service/TrackingCycleDispatcher.kt
- tracker/engine/.../data/collection/TrackingCycle.kt
- tracker/engine/.../service/TrackingOrchestrator.kt

This creates the following problems.

### 2.1 One cadence controls unrelated work

A manually initiated non-location session selects an ambient trigger, but its timer can
be updated from the user-initiated tracking policy and the global minTimeSeconds value.
That makes a location-oriented setting drive activity snapshots, step drains, pressure
windows, Wi-Fi attempts, cell reads, policy heartbeats, and persistence work.

### 2.2 A cycle implies temporal correlation that may not exist

Location, activity, step, radio, and pressure data have different observation times and
delivery behavior. Co-locating them in one TrackingCycle encourages consumers to treat
them as simultaneous. Existing consumers combine these fields for altitude fusion,
activity inference, Wi-Fi interpolation, and sport classifiers.

### 2.3 Overflow merging is not source-semantic

TrackingCycleDispatcher can coalesce overloaded cycles. Generic coalescing can overwrite
payloads, detach freshness flags from the observation they describe, or discard source
window and sequence metadata. A valid merge for pressure statistics is not a valid merge
for activity transitions, Wi-Fi scans, or cumulative step-counter windows.

### 2.4 Durability is later than some logical effects

Session and post-processing stages can observe a cycle before its downstream signal has
been durably admitted. Producers can also clear volatile windows while building a cycle.
Process death between consumption and admission can lose evidence while leaving derived
state advanced.

### 2.5 Shutdown samples rather than proving completeness

The current final cycle does not define a common cutoff watermark across all sources.
Asynchronous Wi-Fi, batched sensors, in-flight location callbacks, and cached activity
updates can cross the stop boundary without an explicit acknowledgment contract.

### 2.6 Battery estimates are configuration scores, not energy estimates

TrackingPresetSettings.calculateBatteryImpact() adds fixed points for settings and maps
the result to Low, Moderate, High and fixed tracking-hour claims. It does not account for
actual Android delivery, shared wakeups, failed or throttled scans, sensor batching,
foreground runtime, database work, or device variance.

---

## 3. Goals

1. Give each physical source an acquisition contract that matches Android semantics.
2. Preserve observation time, receive time, sequence, quality, provenance, and qualified
   plan attribution.
3. Admit source evidence durably before non-idempotent logical effects.
4. Make ordering, joins, deduplication, compaction, and lateness explicit.
5. Keep foreground-service ownership and permission validation centralized.
6. Support independent source plans without independent wakeup storms.
7. Make session stop and process recovery bounded, observable, and deterministic.
8. Preserve current public TrackerStateReader and UI behavior during migration.
9. Communicate battery impact honestly using ranges, drivers, and confidence.
10. Permit incremental rollout with one physical owner per source and immediate flag rollback.

---

## 4. Non-goals

- Multiple foreground services, one per sensor.
- One coroutine, alarm, or WorkManager schedule per source.
- Exact delivery-frequency promises that Android cannot provide.
- A single generic frequency slider for all sources.
- Immediate replacement of every TrackingCycle consumer.
- Network telemetry, remote calibration, or uploading raw device observations.
- Rewriting the statistics architecture in the same migration.
- Exposing raw source-event contracts through tracker/api-module to feature modules.
- Deleting existing storage tables before the new path has passed dual-run validation.

---

## 5. Design principles and invariants

### 5.1 One physical owner

At any point, exactly one runtime owns a physical source registration. Shadow mode may
project or compare events, but it must not register a second location request, sensor
listener, activity request, Wi-Fi scan loop, or telephony callback.

### 5.2 Durable before effect

A durable evidence unit must receive a durable admission result before it changes session
totals, classifiers, policy, notifications, public live state, achievements, or statistics.
At-least-once delivery starts only after successful WAL admission. Android callback
delivery before commit is best-effort: process death or storage failure can lose a
callback, and provider redelivery is never assumed.

### 5.3 At-least-once and idempotent

Process recovery and WAL replay can redeliver admitted events. Every admitted evidence
unit and projection has a stable identity. Consumers deduplicate by identity rather than
assuming exactly-once delivery. Provider callbacks without a provider-stable identity
receive their event identity at first durable admission; no claim is made that a
pre-admission callback can be recovered.

### 5.4 Two orders, not one

- Admission ordinal is the deterministic order for effects and replay.
- Observation time is the order for temporal joins and domain interpretation.

Receive time is retained for latency diagnostics. Wall time is presentation metadata and
must never be the only ordering clock.

### 5.5 No implicit latest-value joins

Every multi-source projection declares a JoinSpec with maximum age, direction,
window-overlap rules, clock-domain rules, allowed lateness, and late-event policy.

### 5.6 Source-aware backpressure

No generic merge function may compact all source kinds. Each source defines the only
valid deduplication and compaction operations for its payload.

### 5.7 Configuration is revisioned

AcquisitionPlanRevision is one atomic desired-plan document, not an atomic Android-side
application. Each source applies and acknowledges its plan independently; mixed applied
revisions are valid during start and reconfiguration and are persisted rather than hidden.
Callbacks created by Tracker retain their registration generation and applied source-plan
revision when that causal attribution is available. Provider-wide or otherwise
unattributable deliveries use an explicit weaker attribution; they are never silently
described as having been created by the current settings.

### 5.8 Bounded shutdown

Each source closes callback entry for its registration generation, drains every callback
that entered app code before that barrier to a terminal ingress result, and reports what
the provider can actually prove. A session can finalize with a declared incomplete source
after a bounded timeout, but app-drain completeness and provider coverage are persisted
separately. Most Android providers cannot prove that every pre-cutoff physical observation
was delivered.

### 5.10 Collected-data deletion barrier

Every admission and projection participates in the existing collected-data lifecycle
epoch. A callback captured before full deletion or retention advancement cannot commit
after the barrier and recreate deleted data. Full deletion atomically advances the
lifecycle epoch and clears raw events, join state, projection state, bindings,
completeness records, and canonical destinations.

### 5.9 Privacy remains local

Raw events, estimates, calibration evidence, and diagnostics remain on-device. Logs use
fixed codes and counts, never coordinates, SSIDs, BSSIDs, cell identifiers, or raw activity
payloads.

---

## 6. Target architecture

~~~text
Android callback, sensor, or durable PendingIntent ingress
                         |
                         v
                 SourceRuntime<T>
        start(plan) / reconfigure(plan) / quiesce()
                         |
                         v
              SourceEvidenceCandidate<T>
                         |
                         v
          DurableSourceIngress (Room transaction)
               event id + admission ordinal
                         |
                         v
             TrackingCoordinator actor
      dedupe / reorder / source watermarks / plan state
             |                  |                 |
             v                  v                 v
       Raw projections     Event-time joins   Policy evidence
             |                  |                 |
             +------------------+-----------------+
                                |
                                v
                    Processor and persistence sinks
             sessions / paths / summaries / stats / live state
~~~

Android providers never call downstream consumers directly. A source runtime converts
provider samples into source-specific durable evidence units and submits those units to
DurableSourceIngress. The coordinator consumes committed admissions in ordinal order.
High-rate step and pressure provider samples may be combined into a bounded evidence
window before admission; the open window is explicitly incomplete if the process dies.

---

## 7. Package and ownership layout

The first implementation stays inside tracker/engine to avoid exposing an unstable raw
event API or adding a module before boundaries are proven.

~~~text
tracker/engine/.../source/
    model/
        SourceKind.kt
        SourceEvidenceCandidate.kt
        AdmittedSourceEvent.kt
        SourcePayload.kt
        SourcePlan.kt
        SourceQuality.kt
        SourceWatermark.kt
    runtime/
        SourceRuntime.kt
        SourceRuntimeRegistry.kt
        location/
        activity/
            AutomaticStartTransitionMonitor.kt
            SessionActivityRuntime.kt
            ActivityRegistrationArbiter.kt
        steps/
        pressure/
        wifi/
        cell/
    ingress/
        DurableSourceIngress.kt
        SourceEventWalEntity.kt
        SourceEventWalDao.kt
        SourcePayloadCodec.kt
    coordinator/
        TrackingCoordinator.kt
        SourcePlanResolver.kt
        ForegroundCapabilityManager.kt
        WakeupPlanner.kt
        SessionBoundaryCoordinator.kt
    projection/
        Projection.kt
        ProjectionContext.kt
        JoinSpec.kt
        EventTimeJoiner.kt
        LegacyTrackingCycleProjection.kt
    battery/
        BatteryImpactEstimator.kt
        BatteryEvidenceRecorder.kt
~~~

Android-free policy concepts remain in tracker/control. That module may emit SourceDemand
values, but it must not depend on Android provider types. Feature modules continue to
consume tracker/api-module snapshots rather than raw source events.

Activity transition capture crosses an existing module boundary. Define a minimal
ActivityRecognitionEventIngress DTO/interface in sensor/activity-api. tracker/engine
implements the port, and app binds the implementation at the composition root.
ActivityReceiver in sensor/activity uses goAsync(), submits every event from the delivered
ActivityTransitionResult batch on bounded I/O, and calls finish() after durable commit or
a typed terminal failure. SourceEvidenceCandidate and AdmittedSourceEvent remain internal
to tracker/engine.

Extraction into a tracker/source-api KMP module is a later option only if another module
needs to implement or replay source runtimes.

---

## 8. Core contracts

### 8.1 Source evidence candidate and admitted event

~~~kotlin
data class SourceEvidenceCandidate<T : SourcePayload>(
    val providerDedupKey: String?,
    val logicalTrackingId: LogicalTrackingId?,
    val serviceRunId: ServiceRunId?,
    val source: SourceKind,
    val sourceInstanceId: SourceInstanceId,
    val registrationGeneration: Long,
    val sourceSequence: Long,
    val configRevision: Long?,
    val planAttribution: PlanAttribution,
    val clockDomainId: String,
    val observedElapsedRealtimeNanos: Long,
    val receivedElapsedRealtimeNanos: Long,
    val wallTimeMs: Long?,
    val wallTimeUncertaintyMs: Long?,
    val capturedCollectedDataEpoch: Long,
    val acquiredAtMs: Long,
    val quality: SourceQuality,
    val payloadVersion: Int,
    val payload: T,
)

enum class PlanAttribution {
    CAPTURED_REGISTRATION,
    LINKED_ATTEMPT,
    RECEIVE_TIME_ONLY,
}

data class AdmittedSourceEvent<T : SourcePayload>(
    val eventId: SourceEventId,
    val admissionOrdinal: Long,
    val evidence: SourceEvidenceCandidate<T>,
)
~~~

logicalTrackingId is nullable for durable background ingress that occurs before a
tracking session exists, such as an activity transition that may start automatic
tracking. The coordinator creates or resolves the session and persists an association;
it does not rewrite the immutable source event.

Reserve and persist sourceInstanceId and registrationGeneration before registering with
Android. sourceSequence is monotonic only inside one sourceInstanceId; comparing sequences
across instances is forbidden.

configRevision is interpreted only together with planAttribution:

- CAPTURED_REGISTRATION means the revision installed the callback, listener, or distinct
  PendingIntent registration that delivered the evidence;
- LINKED_ATTEMPT means the evidence is causally linked to a specific provider attempt made
  under that revision, using a source-defined correlation contract;
- RECEIVE_TIME_ONLY means configRevision, when present, is only the applied revision at app
  receipt and did not necessarily cause the provider observation. It is null when even that
  receipt context is unavailable.

A system-wide Wi-Fi scan-results broadcast is RECEIVE_TIME_ONLY unless Android supplies an
unambiguous correlation token accepted by the source contract. Temporal proximity to an
accepted startScan() call, one locally outstanding attempt, or EXTRA_RESULTS_UPDATED does
not by itself qualify the broadcast as LINKED_ATTEMPT.

eventId and providerDedupKey are distinct. When Android supplies a provider-stable
identifier, it can form the provider deduplication key. Durable ingress assigns eventId in
the same transaction that creates the WAL row, unless a duplicate provider key resolves
to an existing row. Source-specific semantic deduplication applies only where its collision
contract is safe. Payload equality alone never proves duplication.

Each source specification defines its durable evidence unit:

- location: one evidence unit per delivered fix;
- activity: one evidence unit per provider transition or recognition result;
- steps: a contiguous cumulative-counter checkpoint/window;
- pressure: one delivered batch or bounded aggregate window;
- Wi-Fi: attempt/result or result-snapshot evidence;
- cell: callback/snapshot/refresh-result evidence.

Intermediate step and pressure samples need not receive individual WAL rows. Their window
bounds, sequence range, recovery behavior, and incomplete-open-window condition are
mandatory metadata.

### 8.2 Source runtime

~~~kotlin
interface SourceRuntime<P : SourcePlan> {
    val source: SourceKind
    val capabilities: StateFlow<SourceCapabilities>

    suspend fun start(plan: P, sink: SourceEventSink): SourceStartResult
    suspend fun reconfigure(plan: P): SourceApplyResult
    suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck
    suspend fun close()
}
~~~

SourceStopAck contains:

- source kind;
- source instance and registration generation;
- applied source-plan revision;
- callback-entry barrier sequence;
- last durably admitted callback/evidence sequence;
- last admission ordinal;
- failed or lost admission count and unresolved sequence interval;
- registration removal outcome;
- provider flush outcome;
- provider coverage: FIFO_COMPLETE_AT_FLUSH_CALL,
  CALLBACKS_ENTERED_BEFORE_BARRIER, or PROVIDER_COMPLETENESS_UNOBSERVABLE;
- app-drain complete flag;
- Complete, TimedOut, PermissionLost, ProviderFailed, or ProcessRestarted status.

### 8.3 Durable ingress

~~~kotlin
interface DurableSourceIngress {
    suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult
    fun committedEvents(afterOrdinal: Long): Flow<AdmittedSourceEvent>
    suspend fun checkpoint(consumer: String, ordinal: Long)
}
~~~

AdmissionResult is Admitted(ordinal), Duplicate(existingOrdinal), RetryableFailure, or
PermanentFailure. Only Admitted and Duplicate permit the provider callback to be treated
as safely handed off.

Admission captures the authoritative CollectedDataLifecycleStore snapshot before waiting
for Room and validates capturedCollectedDataEpoch and acquiredAtMs against
SourceEvidenceState inside the insertion transaction. Older-epoch or pre-retention
evidence is terminally rejected.

### 8.4 Projection

~~~kotlin
interface Projection {
    val id: String
    val version: Int
    suspend fun apply(event: AdmittedSourceEvent, context: ProjectionContext)
    suspend fun flush(cutoffOrdinal: Long)
    suspend fun checkpoint(): ProjectionCheckpoint
}
~~~

Projection effects are idempotent by projection id, version, and input event or frame id.
A canonical destination write, its idempotency record or outbox entry, durable projection
state, and checkpoint advancement must commit in one AppDatabase transaction. A
cross-store or external effect is emitted only from an outbox committed in that
transaction. No live state, notification, policy mutation, classifier mutation, or
feature-facing event occurs before this commit.

### 8.5 Demand and source plans

Policy and consumers express desired evidence, not platform calls:

~~~kotlin
sealed interface SourceDemand {
    val source: SourceKind
    val maximumAgeMs: Long
    val desiredLatencyMs: Long
    val quality: EvidenceQuality
    val reason: DemandReason
}
~~~

SourcePlanResolver combines demands with:

- user profile and advanced settings;
- permission and hardware capabilities;
- battery policy and power-saver state;
- Android restrictions;
- shared wakeup opportunities;
- thermal state;
- foreground-service legality.

The resolver emits one atomic desired AcquisitionPlanRevision containing all source plans.
The coordinator persists per-source apply acknowledgments and the transient mixed-revision
state until every required source is applied, rolled back, or explicitly degraded.

---

## 9. Durable source-event storage

Add an append-oriented source_event_wal table rather than overloading processed-signal
storage during migration.

Suggested columns:

| Column | Purpose |
| --- | --- |
| admission_ordinal | INTEGER PRIMARY KEY AUTOINCREMENT; deterministic replay order |
| event_id | Stable unique identifier with UNIQUE index |
| provider_dedup_key | Optional source-specific provider identity |
| logical_tracking_id | Nullable session association at capture time |
| service_run_id | Nullable Android service epoch |
| source_kind | Stable numeric source code |
| source_instance_id | Persisted source registration identity |
| registration_generation | Generation fenced before Android registration |
| source_sequence | Monotonic only within source_instance_id |
| config_revision | Nullable qualified acquisition-plan revision |
| plan_attribution | CAPTURED_REGISTRATION, LINKED_ATTEMPT, or RECEIVE_TIME_ONLY |
| clock_domain_id | Boot/monotonic clock identity |
| observed_elapsed_nanos | Source observation time |
| received_elapsed_nanos | App receipt time |
| wall_time_ms | Optional presentation timestamp |
| wall_time_uncertainty_ms | Optional uncertainty |
| captured_collected_data_epoch | Full-deletion lifecycle generation |
| acquired_at_ms | Retention-boundary validation |
| quality_flags | Stable bit set |
| payload_version | Codec version |
| payload | Versioned local binary payload |
| created_at_ms | Retention and diagnostics |

Required indexes:

- unique event_id;
- logical_tracking_id plus admission_ordinal;
- source_kind plus source_instance_id plus source_sequence;
- captured_collected_data_epoch plus acquired_at_ms;
- created_at_ms plus admission_ordinal for bounded retention scans.

Payload codecs are versioned and covered by golden tests. Radio identifiers retain the
existing privacy policy and hashing/tokenization behavior. Raw payloads never enter
Tracebox.

source_event_wal is immutable after admission and has no global Projected state. Multiple
projections use separate durable records:

| Table | Purpose |
| --- | --- |
| projection_registration | Projection ID/version, activation ordinal, retention requirement, status |
| projection_checkpoint | Last contiguous ordinal and state version per projection version |
| projection_failure | Projection-local event failure, attempts, code, terminal status |
| projection_join_state | Durable pending joins, aggregates, and source watermarks |
| projection_outbox | Idempotent cross-transaction effects, indexed by kind/delivery/ordinal |
| coordinator_lease | Exactly one ordinal-dispatch owner with expiring lease |
| location_projection_observation | Narrow, normalized location inputs ordered by logical session and event time |
| location_projection_point | Latest semantic revision per location input; cascades with its normalized observation |

Adding a projection starts it at an explicit activation ordinal and does not retroactively
block deletion of all historical rows. Raw codec failure may globally quarantine an
event; a projection failure is local. Required and optional failure behavior is declared
before rollout.

High-volume canonical tables keep compact typed columns rather than JSON or serialized
object graphs: coordinates use integer E7 where the public storage contract permits it,
step and pressure evidence is stored as bounded windows, and radio observations use
stable typed identity/time columns. The location-domain correction projection is the
exception that needs full source precision; it stores one narrow normalized observation
and one revision row per source event. It must never serialize and rewrite the complete
logical-session history as a join-state BLOB.

### 9.1 Retention and disk pressure

Projected events can be deleted only after:

1. every required projection checkpoint is beyond their ordinal;
2. their destination writes are committed;
3. the rollback window for the current rollout phase has elapsed.

Disk-pressure policy is source-aware. Lifecycle events, activity transitions, session
boundaries, and unprojected events are never silently dropped. If storage cannot admit
required evidence, the coordinator degrades expensive acquisition and records a visible
incomplete-data condition before stopping as a last resort.

Retention advances by the minimum safe projection ordinal and event acquisition time, not
by checking whether the WAL table is empty. Existing pending_signal.hasAny-style guards
must be replaced or scoped so a healthy long-lived source WAL does not indefinitely block
canonical raw-data, Wi-Fi, or cell retention.

Physical deletion is performed in bounded batches (1,000 rows by default) using the
minimum required projection checkpoint and the oldest durable join ordinal. The same pass
removes delivered outbox payloads only when their source ordinal is safe. This prevents a
long-offline device from holding SQLite's single writer for one unbounded retention
transaction. Normalized location projection rows follow raw-location retention and their
revision rows are removed by foreign-key cascade.

Full collected-data deletion or retained-from advancement updates SourceEvidenceState and
deletes source_event_wal, projection registrations/checkpoints/failures/join state,
coordinator leases, session bindings, source completeness, and canonical destinations in
the same deletion transaction. Admission and projection revalidate the captured lifecycle
epoch inside their write transactions. Fault tests cover deletion before admission,
during admission, after admission before projection, and during projection.

---

## 10. Ordering, clocks, lateness, and deduplication

### 10.1 Clock model

All events require observedElapsedRealtimeNanos and clockDomainId. Wall time is derived
or supplied for display/export and carries uncertainty. Events from different boot clock
domains cannot be directly joined by elapsed time.

### 10.2 Admission order

SQLite admission_ordinal is authoritative for deterministic effect order and replay.
Projection code must not rely on Flow scheduling order before admission.

### 10.3 Event-time handling

Each source maintains:

- highest received sequence;
- highest observed event time;
- completeness watermark;
- allowed lateness;
- late-event policy.

Location batches are sorted and deduplicated by observation time and provider identity
before projection, while retaining original receive order metadata.

### 10.4 Duplicate policy

Duplicate admission returns the original ordinal. Replayed events do not rerun completed
projection effects because checkpoints and output identities are stable.

Every source has a normative identity table documenting source instance creation,
sequence scope/reset, provider deduplication key, semantic deduplication, collision
behavior, and what process gaps cannot be recovered. Registration generation is never
inferred from the currently applied configuration at receive time.

### 10.5 Late events

Late events are never silently reassigned to a newer session. The session-boundary
projection uses observation time, cutoff, clock domain, and source watermark:

- event observed before cutoff and admitted within grace: attach to ending session;
- event observed before cutoff but admitted after finalization: apply the projection's
  registered late-correction policy: versioned recomputation, append-only correction, or
  quarantine. No projection decides eligibility ad hoc;
- event observed after cutoff: attach to the next eligible session or remain unbound;
- ambiguous clock domain: quarantine with diagnostics, never guess.

Bindings are immutable revisioned decisions in source_event_session_binding, not a
mutable logical_tracking_id update on the raw event. Each binding stores event ID,
logical session ID, binding revision, reason, decision status, clock domain, and the
observation-time interval/cutoff used. Exactly one binding revision is canonical while
correction history remains auditable.

---

## 11. Temporal projections and joins

JoinedFrame replaces the implicit correlation of TrackingCycle.

~~~kotlin
data class JoinedValue<T>(
    val eventId: SourceEventId,
    val value: T,
    val observedElapsedNanos: Long,
    val ageMsAtFrame: Long,
    val quality: SourceQuality,
    val joinOutcome: JoinOutcome,
)

data class JoinedFrame(
    val frameId: ProjectionFrameId,
    val anchorEventId: SourceEventId,
    val anchorTimeNanos: Long,
    val values: Map<SourceKind, JoinedValue<*>>,
)
~~~

JoinSpec declares:

- anchor source;
- required and optional sources;
- nearest, previous, next, bracketed, or overlapping-window strategy;
- maximum age before and after anchor;
- same-clock-domain requirement;
- allowed lateness and frame-finalization watermark;
- behavior for missing, stale, low-quality, or late inputs.

Initial projection contracts:

| Consumer | Anchor | Join requirements |
| --- | --- | --- |
| Route/location persistence | Location | No synthetic non-location dependency |
| Pressure altitude fusion | Location or pressure window | Same boot domain and bounded overlap |
| Activity inference | Activity transition/update | Steps window overlap; location speed within declared age |
| Wi-Fi interpolation | Wi-Fi scan observation | Bracketed accepted locations; no unbounded latest reuse |
| Cell presence | Cell observation | Location optional with explicit age/quality |
| Ski/plane/sailing classifiers | Domain-selected anchor | Explicit source ages and missing-data states |

Maximum ages are named policy constants with tests and diagnostics. They are not buried
inside component implementations.

Join state is durable. Applying an input, updating buffered join state, emitting finalized
frames, and advancing the projection checkpoint are transactional. A checkpoint may pass
an input ordinal only when that input is durably represented by a finalized frame or
durable pending join state.

Every source defines watermark progress on callback entry, explicit idle progress,
permission loss, provider failure, flush outcome, and session cutoff. A silent source
cannot block forever: JoinSpec declares a bounded wait and deterministic missing-input
finalization. Provider completeness remains Unobservable where Android supplies no
stronger contract.

Frame IDs are deterministic from projection ID/version, join-spec version, anchor event
ID, and contributing event IDs. Late corrections append a correction or versioned
recomputation; they never invisibly mutate an already consumed frame.

### 11.1 Legacy TrackingCycle projection

LegacyTrackingCycleProjection is temporary and downstream of durable ingress. It may
create a cycle only from a declared JoinSpec. Every projected field retains provenance in
internal metadata. It must not register hardware, poll producers, or clear source windows.

No new consumer may be written against TrackingCycle after Phase 2.

---

## 12. Backpressure and compaction

The coordinator owns bounded in-memory queues backed by the durable WAL. Memory pressure
does not imply evidence loss because committed events can be reread.

Valid source-specific operations:

| Source | Permitted compaction |
| --- | --- |
| Location | Deduplicate identical provider observations; otherwise spill, do not average route points |
| Activity | Preserve transitions; compact repeated identical state snapshots only with time range |
| Steps | Merge only contiguous sequence/window ranges and preserve start/end/cumulative baseline |
| Pressure | Merge mathematically valid sufficient statistics with exact window bounds |
| Wi-Fi | Semantic dedupe identical scans while preserving freshness, attempt result, and observation time |
| Cell | Semantic dedupe unchanged cell sets while preserving modem timestamp and subscription identity |
| Lifecycle/policy | Never compact across distinct transitions |

Fairness prevents a high-rate location source from starving lifecycle, activity, or stop
events. Poison payloads move to Quarantined after bounded retries and produce payload-free
diagnostics.

---

## 13. Source-specific acquisition design

### 13.1 Location

Use exactly one selected location backend registration during active acquisition. Preserve
the current user-selectable FUSED and FRAMEWORK backends; Google Play Services absence
must not disable the framework LocationManager path.

LocationPlan contains:

- backend: FUSED or FRAMEWORK;
- Disabled, Passive, LowPower, Balanced, HighAccuracy, or Probe mode;
- requested interval and minimum update interval;
- minimum displacement;
- maximum batch delay;
- duration or probe deadline;
- precise-versus-approximate capability;
- plan revision.

Rules:

- Requested intervals are goals, not guarantees.
- Preserve every Location elapsedRealtimeNanos and accuracy.
- Sort batched results by event time before domain projection.
- Apply backend-specific request, batching, flush, error, and removal semantics.
- Flush provider batches during quiesce when supported, without treating flush as a
  universal provider-completeness watermark.
- Re-registration is generation-fenced.
- Location permission and foreground-service prerequisites are validated before applying
  a location plan.
- Passive mode must not be presented as guaranteed periodic collection.

### 13.2 Activity

Activity acquisition has two lifecycles behind one physical-registration authority.

AutomaticStartTransitionMonitor is application-scoped. While automatic tracking is
enabled and its lock, permission, and product prerequisites are satisfied, it requests the
transition set needed to start or stop automatic tracking and emits durable unbound events
even when no tracking session or TrackerService exists. Ending a tracking session does not
quiesce this monitor.

SessionActivityRuntime is session-scoped. It requests transitions or optional continuous
recognition needed only by the active session and quiesces at the session cutoff.
ActivityRegistrationArbiter combines monitor and session demands and is the sole owner of
the physical GMS activity registrations and PendingIntent identities. Neither lifecycle
registers with GMS directly.

The application-scoped monitor plan contains enabled state, transition set, automatic-start
prerequisites, and plan revision. SessionActivityPlan contains:

- Off, TransitionsOnly, or ContinuousRecognition;
- desired detection latency;
- confidence threshold;
- transition set;
- plan revision.

PendingIntent receiver ingress writes the event durably before attempting service start.
It must work when the process is cold and no SharedFlow collector exists. An unbound
transition can cause the coordinator to create an automatic session transactionally.
Every event in a delivered ActivityTransitionResult batch is admitted. Stable provider
deduplication uses provider elapsed timestamp, activity, transition type, and persisted
registration identity where available.

Activity recognition latency is a goal: delivery may be faster or slower, can change
during prolonged STILL, and may degrade further in power saver. A persisted registration
generation is encoded into a distinct explicit PendingIntent identity when backend
semantics permit it. Reusing one PendingIntent can replace the existing request and cannot
retroactively prove the generation of a late result.

Disabling automatic tracking, losing its permission, or force-stop ends the monitor's
desired ownership. Full collected-data deletion tells ActivityRegistrationArbiter to close
both monitor and session registration generations, drain or terminally reject callbacks
captured under the old collected-data epoch, and complete the deletion barrier. If
automatic tracking remains enabled after deletion, the arbiter starts a new monitor
generation only after the epoch advance and deletion transaction complete.

### 13.3 Steps

TYPE_STEP_COUNTER is cumulative since reboot and is event-driven. The user does not
control a scan frequency.

StepsPlan contains:

- enabled state;
- sensor delivery latency/batching request;
- projection checkpoint interval;
- whether movement policy needs low-latency deltas;
- plan revision.

Persist boot clock domain, raw cumulative count, baseline, source sequence, and window
bounds. Reboot and sensor reset create explicit baseline events. Quiesce flushes sensor
FIFO where supported and returns the final contiguous sequence watermark.

The durable evidence unit is a contiguous cumulative-counter checkpoint/window, not every
SensorEvent callback. Intermediate callbacks can be coalesced in memory. The next
cumulative counter reading can recover count deltas after process restart within the same
boot, but timing distribution inside the missing interval remains unknown; reboot before
recovery marks the open interval incomplete.

### 13.4 Pressure

PressurePlan separates:

- hardware sample period;
- maximum report latency;
- aggregation window;
- movement-gated burst behavior;
- plan revision.

Pressure observations are accumulated as sufficient statistics:
count, sum, squared-deviation accumulator, minimum, maximum, first/last observation time,
and sequence range. Merging is allowed only for adjacent compatible windows. Hardware
FIFO capability and wake-up behavior are recorded.

The durable evidence unit is one delivered batch or bounded aggregate window. If process
death occurs before admission, the open window is recorded as an incomplete interval on
recovery; the design does not claim individual provider samples were durable.

### 13.5 Wi-Fi

Wi-Fi frequency is a maximum attempt policy, not a delivery promise.

WifiPlan contains:

- Off, CachedOnly, BroadcastDriven, or ActiveAttempts;
- minimum attempt interval;
- maximum acceptable result age;
- unchanged-result dedupe window;
- exponential backoff;
- plan revision.

The runtime records separate events for:

- scan attempt;
- accepted or rejected attempt;
- results available broadcast;
- result snapshot with platform timestamp/freshness;
- throttled, permission-blocked, location-services-disabled, and provider-failure states.

Android permission checks must reflect the APIs actually used and target SDK. For
startScan() and getScanResults() in this application, NEARBY_WIFI_DEVICES does not replace
ACCESS_FINE_LOCATION; the applicable Wi-Fi permissions and enabled Location Services are
also required. In Doze, active attempts become DEFERRED_IDLE and WakeupPlanner does not
schedule them. The UI shows Effective, Throttled, Permission blocked, Deferred idle, or
Cached rather than claiming an exact cadence.

### 13.6 Cell

CellPlan contains:

- Off, ObserveChanges, or ObserveAndSparseRefresh;
- minimum refresh-attempt interval;
- maximum acceptable cached age;
- subscription scope;
- backoff;
- plan revision.

Prefer TelephonyCallback change delivery. getAllCellInfo is treated as a cached snapshot
on modern Android. requestCellInfoUpdate is sparse, rate-limited, and not guaranteed.
Preserve CellInfo timestamps and subscription identity; never stamp receive time as modem
observation time.

TelephonyCallback is used on API 31+. API 26–30 use the documented legacy listener or
cache/refresh fallback with equivalent evidence semantics. Maintain a subscription-pinned
TelephonyManager and callback/listener per active subscription, reconcile subscription
changes, and check FEATURE_TELEPHONY_RADIO_ACCESS. Convert observation time from
CellInfo.getTimestampMillis() on supported APIs or the older nanosecond timestamp below
that boundary. Permission requirements are evaluated for the exact callback/read API.

---

## 14. Central wakeup planner

Source-native does not mean timer-per-source.

- Callback and sensor sources rely on provider delivery and hardware batching.
- While TrackerService is active, polling attempts such as Wi-Fi and cell are submitted
  to one WakeupPlanner using monotonic deadlines.
- The planner coalesces compatible deadlines into shared wake windows.
- It does not claim to wake a suspended CPU with coroutine delay.
- WorkManager is used for deferred WAL drain, recovery, and maintenance, not sub-minute
  acquisition.
- Alarms are not a general cadence engine.
- Policy can request freshness, but SourcePlanResolver may return a degraded applied plan
  with an explicit reason.

Wakeup metrics include requested deadlines, actual execution delay, coalescing count,
provider work, and wake-lock duration.

### 14.1 Lifecycle deadlines

Persisting a deadline does not execute it. DeadlineScheduler classifies each deadline:

- Opportunistic: evaluated on the next callback, service wake, or user interaction.
- In-service: one coalesced monotonic scheduler while the CPU/service is already active.
- User-visible exact: eligible for an alarm only after a separate product and permission
  decision.

Automatic-stop and lock deadlines are allowed to be late during Doze unless product
requirements explicitly justify a documented allow-while-idle path. WorkManager and
ordinary coroutine delays are never described as prompt deadline mechanisms.

---

## 15. Foreground service and permission model

ForegroundCapabilityManager is the only owner of foreground-service requirements.

It computes the union of capabilities required by the applied plan:

- location;
- health/activity where applicable;
- special-use only when no documented type covers the work.

Before applying a plan it validates:

- runtime permissions;
- while-in-use and background-start constraints;
- Android version;
- foreground-service type declarations and permissions;
- sensor/provider availability;
- user-visible notification state.

FGS type membership is monotonic within one Android service instance. Adding a capability
promotes the service with the required union before source access. Types are not narrowed
in place, and Tracker does not restart solely to remove a type; a source may stop while
its already-declared type remains until the natural end of that service run. A replacement
run requires the full quiesce/restart protocol, creates an acquisition gap, and must have
a separately legal start context.

Manual starts and automatic/background starts are distinct legality paths. An activity
transition exemption from background-start restrictions does not by itself grant location
access. Failure returns a typed result and a user-visible recovery path rather than being
silently skipped.

ActivityWatcherService is audited during Phase 0. If durable transition ingress can
perform re-entry without an always-running watcher, remove it. If retained, use the
documented foreground type that matches its actual work.

Maintain a normative start-legality matrix keyed by API level, visible/background origin,
documented exemption, requested FGS type, foreground/background location grant, activity
permission, and provider state. POST_NOTIFICATIONS denial does not itself prohibit
starting an FGS, although notification visibility and product recovery UX still matter.

---

## 16. Session lifecycle

### 16.1 States

~~~text
IDLE
  -> STARTING
  -> RUNNING
  -> RECONFIGURING
  -> QUIESCING
  -> DRAINING
  -> FINALIZING
  -> CLOSED
~~~

STARTING persists logical session identity, service-run identity, clock domain, and plan
revision before source registrations become authoritative.

Room is the canonical authority for logical-session lifecycle, service-run epochs,
cutoffs, source completeness, and source-event bindings. ActiveTrackingSessionStore
becomes an Android restart mirror of the canonical Room lifecycle revision. Room commits
first; the DataStore mirror updates afterward and is reconciled from Room on startup. No
atomic cross-store write is assumed.

Add immutable logical_tracking_session, service_run, and
source_event_session_binding records. Preserve current semantics in which one logical
session may contain multiple SessionSegments across service-run restarts; every segment
stores its logical session and service-run identity.

### 16.2 Automatic start

1. Activity receiver durably admits an unbound transition.
2. Start decision checks lock, charging, permission, and policy state.
3. Coordinator creates logical session and service-run records.
4. The triggering event is associated with the new session without rewriting it.
5. Foreground capability prerequisites are validated.
6. Sources begin applying plans from one desired revision and persist their individual
   applied acknowledgments.

### 16.3 Reconfiguration

1. Persist desired AcquisitionPlanRevision.
2. Compute diff per source and new foreground capability union.
3. Apply prerequisite promotions.
4. Reconfigure sources; each independently returns its applied revision, registration
   generation, and AppliedAt sequence/time.
5. Persist each source acknowledgment and the resulting mixed-revision/degraded state.
6. Publish the desired plan as fully effective only after all required acknowledgments;
   otherwise publish the per-source applied state.
7. Fence older callbacks by registration generation and retain their qualified plan
   attribution.

Partial application rolls back to the previous revision or persists a degraded plan; it
never reports the desired plan as fully effective.

### 16.4 Stop and finalization

1. Persist session cutoff using monotonic and wall clocks.
2. Transition to QUIESCING and reject new plan changes.
3. Request every session-scoped runtime quiesce at the cutoff. The application-scoped
   AutomaticStartTransitionMonitor remains registered when its independent desired plan
   is enabled.
4. Close each registration generation to new callback entry, flush/remove where
   supported, and collect SourceStopAck values with per-source bounded deadlines.
5. Drain every callback that entered app code before the barrier to a terminal admission
   result. Persist unresolved sequence intervals.
6. Drain projections through the final admission ordinal.
7. Commit projection checkpoints and destination data.
8. Finalize session with completeness status per source.
9. Publish session ended and stop the service.

Late events follow the explicit policy in Section 10.5. Session completeness records
APP_DRAIN_COMPLETE independently from source-specific PROVIDER_COVERAGE. For most
providers, provider coverage is Unobservable rather than a claim that all pre-cutoff
observations were delivered.

---

## 17. Process death and recovery

Persist:

- logical session and service-run IDs;
- lifecycle state;
- desired and applied plan revisions;
- source registration generations;
- last admitted source sequences;
- source watermarks and stop acknowledgments;
- projection checkpoints;
- step-counter baseline and boot domain;
- foreground capability union.

On restart:

1. Load unfinished logical session and latest service-run epoch.
2. Acquire the single coordinator lease and resume immutable WAL dispatch.
3. Replay committed events from each projection checkpoint.
4. Resolve incomplete reconfiguration by persisted applied acknowledgments.
5. Start a new service-run epoch if Android restarts the service.
6. Re-register sources once with new generations.
7. Mark the old run ProcessRestarted and continue the logical session.

PendingIntent receivers use goAsync with bounded I/O to admit events directly to Room.
They do not depend on process-local channels for durability. Failure to admit is surfaced
as a fixed diagnostic code and count.

### 17.1 Recovery trigger matrix

Persisted state does not itself restart Android execution:

| Run/origin | Recovery trigger and guarantee |
| --- | --- |
| Manual user run | Use service intent redelivery when Android elects to recreate the service; detect and persist the acquisition gap |
| Automatic run | Re-enter on the next activity transition, user-visible interaction, or another documented exemption; uninterrupted restart is not promised |
| WAL projection | WorkManager may perform eventual drain/maintenance; it does not promise prompt active acquisition or legal background location-FGS start |
| Force-stopped package | No automatic recovery is promised until Android permits execution after user interaction |

If uninterrupted automatic acquisition becomes a product requirement, it requires a
separate durable-provider-registration and start-legality design. Every process gap is
detected, persisted, and classified; providers without replay cannot guarantee recovery
of callbacks lost before WAL admission.

---

## 18. Projection and persistence ordering

The target order for each admitted event is:

~~~text
source_event_wal admission
    -> raw/source projection
    -> event-time join or aggregate
    -> session and domain projection
    -> processed tracking signal admission
    -> stats/domain consumers
    -> public live state and notifications
    -> checkpoint advance
~~~

Canonical destination writes, idempotency/outbox records, durable projection state, and
checkpoint advancement occur in one AppDatabase transaction. Cross-database/module effects
use an outbox/domain-event record committed with the source projection and consumed
idempotently. No effect is published from uncommitted in-memory projection state.

The existing DurableSignalBuffer and TrackingPersistenceTransactor should be reused where
their guarantees fit, but source_event_wal remains the primary raw-evidence boundary.
The migration must not create two non-coordinated durability authorities.

### 18.1 Consumer migration registry

Before a source or projection becomes authoritative, maintain a checked-in registry for
every current consumer:

| Required field | Meaning |
| --- | --- |
| Current input/state/output | Existing TrackingCycle/component behavior and mutable state |
| Target input | Source event, aggregate, or explicit JoinedFrame |
| Canonical writer | The only path allowed to mutate destination state |
| Shadow behavior | Read-only comparison output and mismatch metrics |
| Stable output identity | Deduplication key for retries/replay |
| Checkpoint/state store | Transactional projection state |
| Late correction policy | Recompute version, append correction, or quarantine |
| Flag/rollout dependency | Required source owner and projection mode |
| Retirement gate | Evidence required to disable the legacy consumer |

Shadow mode is read-only for sessions, controllers, notifications, classifiers,
achievements, domain events, public live state, and canonical tables. Hardware ownership
alone is insufficient; canonical-writer exclusivity is independently enforced. Existing
independent asynchronous writers, including sport segment writers, must be moved behind
transactional projection/outbox ownership or disabled before cutover.

---

## 19. Policy engine integration

tracker/control consumes typed evidence derived from admitted source events. It never
receives direct Android callbacks.

Policy produces SourceDemand and session intent. It does not mutate a timer directly.
SourcePlanResolver translates demand into an applicable Android plan and reports:

- requested plan;
- applied plan;
- degraded fields;
- reason codes;
- plan revision;
- effective time and source sequence.

Policy heartbeats become coordinator lifecycle ticks or DeadlineScheduler work, not a side
effect of arbitrary collection cycles. A persisted deadline alone never implies prompt
execution. User-initiated sessions can request responsive evidence without forcing every
source to the location cadence.

---

## 20. Battery-impact model

### 20.1 Model shape

Candidate-plan relative impact is modeled from:

~~~text
fixed foreground runtime
+ provider acquisition modes and duty cycles
+ application-processor wakeups
+ wake-lock duration
+ CPU and database work
+ delivery and persistence batching
+ source interaction terms
~~~

Per-source costs are not simply added because one wakeup may drain several sensor FIFOs,
location delivery can provide a shared processing window, and OS throttling may prevent
requested work.

### 20.2 Estimate output

~~~kotlin
data class BatteryImpactEstimate(
    val level: ImpactLevel,
    val estimatedPercentPerHour: ClosedFloatingPointRange<Double>?,
    val estimateTarget: EstimateTarget,
    val candidatePlanId: String,
    val comparisonBaselineId: String?,
    val evidenceSource: EvidenceSource,
    val sampleCount: Int,
    val observationDurationMs: Long,
    val confidence: EstimateConfidence,
    val uncertainty: ClosedFloatingPointRange<Double>?,
    val dominantDrivers: List<ImpactDriver>,
    val assumptions: List<ImpactAssumption>,
    val calibrationVersion: Int,
)
~~~

EstimateTarget distinguishes:

- QualitativeRelativeTrackerImpact for a candidate versus a named Tracker plan;
- ObservedTotalDeviceDrain during comparable tracking sessions;
- EstimatedIncrementalTrackerDrain only when a validated counterfactual model exists.

Observed battery or charge-counter delta is total device drain, not Tracker's marginal
cost. It must never be labeled incremental Tracker drain without a counterfactual.
Settings preview evaluates a candidate plan, while diagnostics may describe an applied
plan; the model keeps these identities separate.

Confidence:

- Low: generic source and Android-version priors.
- Medium: repeated measurements for the device family and comparable state.
- Higher: stable on-device evidence across representative sessions.

Until calibration is credible, the production UI shows Low, Moderate, or High, dominant
drivers, assumptions, comparison baseline, and Low confidence. It removes fixed
12/8/4-hour claims. Per-source percentages remain prohibited unless identifiability tests
pass.

### 20.3 Local evidence

Record payload-free local aggregates:

- applied source plans and time in each mode;
- delivered events and batch sizes;
- requested versus actual delivery latency;
- Wi-Fi/cell attempts, throttling, and failures;
- wakeups and coalescing;
- wake-lock milliseconds;
- CPU time where available;
- database transactions and bytes;
- screen, charging, power-saver, and thermal state;
- battery level/charge-counter deltas when reliable.

Calibration excludes charging, very short, thermally unstable, and screen-dominated
sessions. It reports uncertainty. If sources are too correlated to attribute separately,
show only a total estimate and dominant likely driver.

No calibration data leaves the device.

---

## 21. Settings and UX

### 21.1 Basic mode

Keep understandable presets:

- Power save;
- Balanced;
- High detail;
- Custom.

Presets map to a complete AcquisitionPlanRevision. They are goals, not hard-coded global
intervals.

### 21.2 Advanced source controls

Use source-specific language:

- Location: accuracy mode, responsiveness, minimum displacement, batching.
- Activity: transitions only or richer active recognition.
- Steps: enabled and summary responsiveness.
- Pressure: off, efficient, responsive, or custom sampling/aggregation.
- Wi-Fi: cached/broadcast data or occasional active attempts.
- Cell: observe changes or permit occasional refresh attempts.

Do not show an exact frequency where Android supplies only a hint or maximum attempt rate.
Show Requested and Effective state when they differ. The desired value remains stored and
visible; permission or capability filtering never overwrites it with the degraded applied
value. Advanced source controls ship behind Advanced mode initially.

### 21.3 Cost presentation

Each change previews:

- estimated total impact level and confidence;
- dominant cost drivers;
- expected data effect;
- platform caveats;
- permissions or foreground requirements;
- whether the change increases wakeups or can share existing batches.

Advanced settings can show actual delivered cadence from recent sessions. Per-source
percentage attribution remains hidden unless confidence is sufficient.

### 21.4 Validation

The settings transaction prevents a configuration with no usable capture source. Hardware
and permission absence are distinguished from a disabled source. Desired settings can be
saved, but the running plan reports degraded status until requirements are satisfied.

### 21.5 Settings persistence migration

Tracking settings have one versioned Proto/DataStore source of truth:

1. Persist a semantic-settings schema version.
2. Map existing locationEnabled, source toggles, minTimeSeconds, minDistanceMeters,
   requiredAccuracyMeters, presetName, and legacyMigrated state into one desired plan.
3. Keep legacy reads and forward writes during the compatibility window.
4. Record the first release in which legacy fields stop influencing runtime.
5. Define downgrade behavior explicitly; an older binary may ignore new semantic fields,
   but must not be described as restoring their meaning.

Migration is idempotent and covered for fresh install, already-migrated preferences,
partial legacy state, and every preset.

---

## 22. Observability

Add payload-free counters and timings:

- source events admitted, duplicated, late, quarantined, compacted, and projected;
- source sequence gaps and clock-domain changes;
- desired versus applied plan revisions;
- plan apply latency and degraded reasons;
- queue depth by source and WAL disk size;
- join success, missing, stale, low-quality, and future-leak prevention;
- projection lag and checkpoint ordinal;
- stop acknowledgment duration and incomplete sources;
- provider actual delivery latency and batch size;
- wakeups, wake-lock duration, and DB transactions;
- replay count and recovery duration.

Tracebox receives only fixed diagnostic codes, counts, durations, enum values, and hashed
configuration identifiers. Debug builds may expose a local source-event inspector with
payload redaction.

---

## 23. Testing strategy

### 23.1 Pure contract tests

- Source-specific event/provider identity, instance reset, collision, and codec golden files.
- Admission idempotency.
- Plan revision diff and fencing.
- JoinSpec behavior for missing, stale, late, bracketing, and clock-domain mismatch.
- Source compaction algebra: associativity where required, window preservation, and
  forbidden merges.
- Session-boundary assignment.
- Battery-estimate confidence and caveat rules.

### 23.2 Source runtime tests

- Permission grant/revocation while active.
- Duplicate and late callbacks after reconfiguration.
- Provider batches delivered out of order.
- Step counter reboot/reset and FIFO flush.
- Pressure FIFO present/absent.
- Wi-Fi throttled, cached, permission-blocked, and location-services-disabled.
- Cell cached snapshots, callback changes, refresh timeout, and multi-SIM identity.
- Multi-event ActivityTransitionResult batches and unique PendingIntent generations.
- Framework and fused location backends, including GMS absent/outdated.
- Callback entry concurrent with unregister, provider flush, and revision change.

### 23.3 Durability and recovery tests

Kill or fail at every boundary:

- before admission;
- after WAL commit before coordinator delivery;
- during projection;
- after destination write before checkpoint;
- during plan application;
- during quiesce;
- after source acknowledgments before session finalization.
- collected-data deletion before/during admission and before/during projection.
- Room migration timeout and process kill during activity receiver goAsync work.

Assert every detectable gap is persisted and classified, no duplicate logical effects,
deterministic post-admission replay, and bounded recovery. Providers without replay are
not required to recover pre-admission callbacks.

### 23.4 Property and stress tests

Generate randomized:

- source reorder, duplication, delay, and burst rates;
- config revisions and stale generations;
- boot clock changes;
- disk latency/failure;
- permission revocation;
- process death;
- queue saturation;
- stop/reconfigure races.

Verify source fairness, disk bounds, valid compaction, watermarks, and projection
determinism.

### 23.5 Android device matrix

At minimum:

- representative API 26–30 fallbacks plus Android 12, 13, 14, 15, 16, and target API 37
  behavior;
- Google Play Services present and absent where supported;
- approximate and precise location;
- foreground/manual and activity-triggered automatic start;
- screen off, Doze, power saver, low battery, and thermal pressure;
- step/pressure FIFO variants;
- Wi-Fi throttling and multi-SIM cell devices;
- OEM background-restriction samples where available.
- force-stop/package-stopped behavior;
- telephony-radio feature absent, Location Services disabled, GMS outdated, notification
  channel blocked, and permission revocation during registration.

### 23.6 Performance gates

Track:

- p50/p95/p99 admission and projection latency;
- wakeups/hour;
- wake-lock milliseconds/hour;
- DB transactions/hour and bytes/hour;
- database bytes per 10,000 admitted events by source and projection;
- rows read/written and B-tree indexes maintained per admitted event;
- memory queue depth and WAL size;
- events delivered per source;
- drop, compaction, quarantine, and sequence-gap counts;
- startup, stop, and recovery latency;
- measured battery range and confidence.

Initial hard guardrails, owned jointly by the tracker maintainer and release approver:

- unprojected WAL: at most 64 MiB and 24 hours under supported presets;
- durable pending join state: at most 16 MiB per active logical session;
- admission p99: at most 500 ms outside injected storage-failure tests;
- stop grace: 10 seconds hard bound, with p95 at most 5 seconds;
- warm replay: p95 at most 5 seconds for 10,000 pending events on the reference device;
- balanced-plan DB write transactions/hour: no more than 1.25 times the Phase 0 baseline;
- no projection may rewrite an unbounded session-history BLOB; normal in-order storage
  growth must be O(1) rows and O(1) payload bytes per admitted event;
- every retention, replay-cursor, source-dedup, and effect-dispatch query must have an
  automated schema/index assertion or recorded `EXPLAIN QUERY PLAN` benchmark;
- retention batches must release the writer after at most 1,000 WAL/effect rows;
- balanced-plan wakeups/hour: no more than the Phase 0 baseline;
- controlled measured-energy regression: at most 5% versus the equivalent legacy plan,
  with the benchmark protocol and uncertainty reported.

Phase 0 may tighten or relax a guardrail only through a recorded design amendment with
measurement evidence and an accountable approver.

---

## 24. Migration plan

Every phase is independently releasable through the persisted rollout state machine. The
old path remains available until the phase gate passes. Only one physical owner and one
canonical writer exist per source/output.

### Phase 0 — Correctness and instrumentation prerequisites

- Correct Wi-Fi permission/capability checks for actual scan APIs.
- Make activity-transition receiver ingress durable for a cold process.
- Add non-location metadata and overflow tests to TrackingCycleDispatcher.
- Audit Android 14+ foreground-service background-start prerequisites.
- Audit ActivityWatcherService type and necessity.
- Add the collected-data lifecycle epoch to the source design and redesign retention
  barriers around safe ordinals rather than WAL table emptiness.
- Define the API/origin/permission foreground-start legality matrix.
- Reconcile ActiveTrackingSessionStore with canonical Room session authority.
- Define the consumer migration registry and canonical-writer ownership.
- Instrument current queue, wakeup, wake-lock, DB, and source delivery behavior.

Gate:

- platform tests pass;
- no payload data in diagnostics;
- baseline performance dataset captured;
- Section 23.6 guardrails measured and approved.

### Phase 1 — Source envelopes under current scheduling

- Add SourceEvidenceCandidate/AdmittedSourceEvent, source instance, registration
  generation, nullable qualified plan revision and attribution,
  lifecycle epoch, source sequence, and clock domain.
- Existing producers emit envelopes when polled or when callbacks arrive.
- Keep current cycle scheduling and persistence behavior.
- Shadow-project envelopes to current TrackingCycle and compare outputs.

Gate:

- serialized current signals, session totals, policy inputs, and classifiers match;
- source-specific identity contracts pass, distinguishing admitted replay, provider
  redelivery, and a new registration after restart.

### Phase 2 — Durable source ingress

- Add immutable source_event_wal, lifecycle deletion validation, coordinator lease,
  projection registrations/checkpoints/failures, durable join state, codecs, and DAO.
- Admit envelopes durably before shadow projection.
- Add crash replay and duplicate suppression.
- Drain old pending signal WAL before cutover or support versioned dual readers.

Gate:

- process-kill matrix passes;
- no duplicate logical effects;
- bounded WAL growth and successful retention;
- full deletion cannot resurrect older-epoch or pre-retention data;
- multiple required/optional projections advance independently without unsafe deletion.

### Phase 3 — Steps and pressure ownership

- Move steps and pressure to source-native runtimes.
- Define contiguous step windows and pressure sufficient-statistic compaction.
- Disable their legacy producer ownership when flags are enabled.
- Continue LegacyTrackingCycleProjection for existing consumers.

Gate:

- every sequence/process gap is detected, persisted, and classified;
- aggregate windows match legacy within defined tolerance;
- shutdown watermarks pass FIFO and no-FIFO tests.

Phase 3 parity tolerances are executable rather than subjective:

- step delta, reset flag, cumulative end value, post-drain callback bounds, and provider
  sequence bounds must match exactly;
- pressure sample count and min/max must match exactly, mean and sample standard deviation
  must be within 0.0001 hPa, and standard-atmosphere altitude must be within 0.001 m;
- a completed FIFO flush may claim FIFO_COMPLETE_AT_FLUSH_CALL only for a registration
  that actually requested supported batching; no-FIFO registrations claim only
  CALLBACKS_ENTERED_BEFORE_BARRIER; failed or timed-out FIFO flushes remain unobservable.

### Phase 4 — Explicit join engine

- Implement JoinSpec, EventTimeJoiner, JoinedFrame, watermarks, and late policies.
- Shadow existing pressure/location, activity, Wi-Fi, and sport projections.
- Add provenance and staleness diagnostics.

Gate:

- no future leakage;
- golden join tests pass for reordered and late input;
- domain owners accept projection equivalence or documented corrections.

### Phase 5 — Activity ownership and automatic-start re-entry

- Transition API becomes durable event ingress.
- Add ActivityRecognitionEventIngress in sensor/activity-api with app composition binding.
- Add application-scoped AutomaticStartTransitionMonitor and session-scoped
  SessionActivityRuntime behind the sole ActivityRegistrationArbiter physical owner.
- Keep the automatic-start monitor registered across session finalization while its
  independent desired plan remains enabled.
- Optional active recognition becomes a session activity source runtime.
- Remove reliance on process-local receiver flows.
- Remove ActivityWatcherService if no longer justified.

Gate:

- cold-process automatic start and locked/charging modes pass;
- background-start failures are typed and user recoverable;
- every post-admission transition replays deterministically; pre-admission terminal
  failures and process gaps are detected where observable and never misreported as durable.
- after an automatic session finalizes and the process becomes cold, a later transition is
  still durably delivered and can start another automatic session;
- full collected-data deletion closes the old activity generations and cannot admit an
  old-epoch callback after a replacement monitor generation starts.

### Phase 6 — Location ownership

- Move both Fused and framework LocationManager backends into LocationSourceRuntime.
- Sort/dedupe batches by event time.
- Centralize location foreground capability and permission application.
- Migrate route, altitude, speed, and policy projections.

Implementation status (Phase 6 worktree): both backends are owned by `LocationSourceRuntime`;
provider batches are event-time sorted and identical provider observations are deduplicated;
`LocationPrerequisiteEvaluator` is the single coarse/fine, GMS, Location Services, foreground
capability, and start-legality matrix; and `LocationDomainProjection` produces replayable,
append-only-corrected route, distance, speed, raw-altitude, and policy-evidence effects. These
effects remain shadow/read-only with `CanonicalWriter.LEGACY` until the Phase 8 coordinator and
domain-owner cutover. The executable and physical matrix is recorded in
`docs/TRACKING_LOCATION_DEVICE_MATRIX.md`.

Gate:

- route and distance parity on replay fixtures;
- approximate/precise and background-start matrix passes;
- no second location registration in any flag combination.
- existing tracker timer/backend preference migrates without disabling GMS-free tracking.

### Phase 7 — Wi-Fi and cell ownership

- Implement source-specific attempt scheduling and backoff through WakeupPlanner.
- Preserve scan/modem timestamps and freshness.
- Migrate interpolation/presence projections.

Implementation status: `WifiSourceRuntime` and `CellSourceRuntime` are registered source-native
runtimes. Their polling work is submitted to one `CoalescingSourceWakeupScheduler`, which applies
`WakeupPlanner` windows, minimum attempt intervals, and bounded exponential backoff. Wi-Fi records
attempt/result/broadcast/cache/idle semantics and uses receive-time-only attribution for
provider-wide broadcasts that cannot be linked to an app attempt. Cell uses subscription-pinned
API 31+ callbacks with API 26-30 listener/cache fallback and preserves modem timestamps. Existing
rollout ownership prevents the legacy Wi-Fi/cell producers from being constructed when the event
runtime owns the source. The bracketed Wi-Fi interpolation and bounded cell-presence joins remain
shadow effects with `CanonicalWriter.LEGACY` until domain-owner acceptance. Automated and physical
gates are recorded in `docs/TRACKING_CONNECTIVITY_DEVICE_MATRIX.md`.

Gate:

- throttling and cache semantics are visible and tested;
- no exact-frequency UI claim;
- wakeups and provider calls do not regress the selected budget.

### Phase 8 — Coordinator cutover

- TrackingCoordinator becomes the authoritative lifecycle and projection actor.
- Policy emits SourceDemand and plan resolver applies revisions.
- Remove global timer dependence for policy heartbeat and non-location work.
- Stop creating producer-polled cycles.

Implementation status (Phase 8 worktree): `TrackerService` now snapshots one persisted rollout
revision before source initialization and passes that immutable ownership map to both the legacy
orchestrator and `TrackerServiceSourceSession`. Event-owned plans are built from semantic settings,
combined with typed policy `SourceDemand`, resolved against Android constraints, persisted as
monotonic revisions, and applied by `AuthoritativeSessionCoordinator`. An all-event ownership
snapshot constructs neither `DataProducerManager` nor a location/ambient collection trigger;
partial or legacy snapshots retain the legacy path only for rollback ownership. Policy lease
heartbeats run from an in-service coordinator lifecycle tick rather than arbitrary collection
cycles. Shutdown fences event runtimes before draining projections and compatibility effects, and
involuntary Android service replacement closes only the service-run epoch so the same logical
session can resume with a new service-run identity. Session telemetry records projection drain
count/time, projected events, plan revisions, tracking frames, and frame
wake-lock duration for device comparison.

Phase 10 upgrades the persisted runtime snapshot to event-canonical ownership before a session can
start. Release qualification still requires the Phase 3/6/7 device matrices, domain-owner
acceptance, canonical-writer exclusivity, and the performance/battery comparison below.

Gate:

- randomized multi-source stress suite passes;
- shutdown/recovery invariants pass;
- performance and battery metrics meet or improve baseline.

### Phase 9 — Settings and battery model

- Replace global cadence UI with semantic source controls.
- Replace fixed score/hours with level, range where available, drivers, and confidence.
- Add Requested versus Effective plan status.
- Begin local calibration only after metrics validation.

Implementation status (2026-08-10): the settings screen now preserves requested intent and
shows separately resolved/applied state, fixed degradation codes, desired/applied revisions,
and payload-free coordinator counters. Advanced mode exposes per-source Off, Efficient,
Balanced, and Responsive goals rather than a shared cadence. The production battery preview
uses the source-plan qualitative estimator and shows level, dominant drivers, assumptions,
comparison identity, and confidence; it does not invent percentages or tracking hours. Local
calibration remains deliberately disabled until the Phase 8 device metrics are validated.

Gate:

- settings accessibility and migration tests pass;
- presets remain safe defaults;
- estimates never imply unsupported precision.

### Phase 10 — Legacy retirement

- Remove AmbientCollectionTrigger and producer polling.
- Remove LegacyTrackingCycleProjection after all consumers migrate.
- Remove old settings fields only after additive compatibility period.
- Retire old WAL path after all upgrade cohorts drain successfully.

Implementation status (2026-08-10): the service and orchestrator no longer implement or accept a
global timer callback. `AmbientCollectionTrigger`, every location/handler collection trigger, the
trigger interfaces, `DataProducerManager`, the producer base classes, and the activity/step/
pressure/Wi-Fi/cell producer implementations have been removed. Policy-tier changes now update
processing metadata only and cannot create a platform acquisition registration. Persisted rollout
state is upgraded transactionally to schema 2 event-canonical ownership; compatibility rollout
rows remain decodable solely so an upgrade can be fenced and audited. The old
`LegacyTrackingCycleProjection` identity and outbox kind are gone. An event-owned, source-event
identified tracking-frame adapter remains as a downstream storage-model boundary; it performs no
hardware acquisition, polling, or mutable producer drain.

The additive preference fields and source-event WAL are intentionally retained. Their deletion is
not part of this code cutover: the supported rollback window and upgrade-cohort drain gates above
must close first. Physical-device and release telemetry gates also remain release requirements even
though the legacy acquisition code is no longer reachable in this binary.

Gate:

- no runtime reference to TrackingCycle acquisition;
- database upgrade/recovery fixtures pass;
- rollback remains possible through the supported release window.

---

## 25. Feature flags and rollback

Use one persisted TrackingRolloutState, not independent booleans:

~~~kotlin
data class TrackingRolloutState(
    val revision: Long,
    val schemaVersion: Int,
    val coordinatorMode: CoordinatorMode,
    val projectionMode: ProjectionMode,
    val sourceOwners: Map<SourceKind, SourceOwner>,
    val semanticSettingsEnabled: Boolean,
    val batteryEstimateMode: BatteryEstimateMode,
)

enum class SourceOwner { LEGACY, EVENT }
enum class ProjectionMode { LEGACY_ONLY, SHADOW_READ_ONLY, EVENT_CANONICAL }
~~~

Validate rollout dependencies as one state machine and snapshot the effective rollout
revision at session start. Physical ownership cannot flip mid-session; changing it
requires a fenced reconfiguration where safe or a new service-run epoch. Every applied
plan, source instance, service run, and projection output records the rollout revision.

Canonical-writer ownership is validated separately from physical-source ownership.
Shadow mode cannot write canonical tables or mutate singleton consumer state.

Database changes are additive. Rollback means selecting LEGACY ownership inside a
forward-compatible binary that understands the newer schema. Installing an older APK over
the newer Room schema is unsupported unless an explicit downgrade migration exists and
passes tests. Rollout state never bypasses collected-data deletion epochs, codec
readability, projection retention, or canonical-writer exclusivity.

The implementation branch must reassess whether the current Room schema version is still
unreleased before folding changes into an existing migration. Released schemas are
immutable and require a new version. If a new event codec is unreadable, affected rows are
quarantined rather than crashing startup.

---

## 26. Settings and database compatibility

- Existing TrackingParamsState fields continue to deserialize.
- Presets map old minTime/minDistance/accuracy values into location plans.
- Non-location source plans start from safe semantic defaults.
- Existing session, location, Wi-Fi, cell, summary, and stats tables remain destination
  projections during migration.
- TrackerStateReader and feature snapshots retain current public contracts.
- Structured GPX, KML, and JSON history exports read canonical projections and exclude
  source_event_wal.
- The existing database backup copies the entire application database and therefore
  includes retained source events, pending joins, projection metadata, and quarantine
  records. Treat it as a sensitive raw-data export and update consent/warning text,
  manifest metadata, privacy documentation, and tests.
- If product requires raw events to be excluded from database backup, replace raw
  database-file copying with a sanitized SQLite snapshot; a table inside main_database
  cannot be excluded from the existing whole-file backup.
- Database migration tests cover upgrades from every supported schema fixture.
- Raw-event retention is independent from user-visible history retention but never shorter
  than the unprojected/replay requirement.

---

## 27. Security and privacy review

- All data stays on-device.
- source_event_wal uses application-private Room storage.
- Structured exports exclude raw WAL. Full database backup includes it and requires
  explicit sensitive-raw-data disclosure as described in Section 26.
- Radio identifiers use existing minimization/tokenization rules.
- No raw observation payload enters logs, crash reports, notifications, or analytics.
- Debug inspection is opt-in, local, redacted, and excluded from release builds.
- Retention deletion includes raw events after projection safety conditions are met.

---

## 28. Expected file impact

Likely modified areas:

- tracker/engine/src/main/java/.../tracker/service/TrackerService.kt
- tracker/engine/src/main/java/.../tracker/service/TrackingOrchestrator.kt
- tracker/engine/src/main/java/.../tracker/service/TrackingCycleDispatcher.kt
- tracker/engine/src/main/java/.../tracker/component/DataProducerManager.kt
- tracker/engine/src/main/java/.../tracker/component/producer/*
- tracker/engine/src/main/java/.../tracker/component/trigger/*
- tracker/engine/src/main/java/.../tracker/pipeline/*
- tracker/engine/src/main/java/.../tracker/control/*
- tracker/engine/src/main/AndroidManifest.xml
- tracker/control source-demand and decision contracts
- sensor/activity-api activity-ingress contract
- sensor/activity receiver and backend integration
- data/preferences tracking settings and migrations
- app composition binding, settings/onboarding screens, and ViewModels
- core/base Room database, entities, DAOs, and migrations
- tracker/api-module live-state completeness/effective-plan additions if product-approved
- source, projection, recovery, and Android integration tests

No feature module should gain direct Android sensor/provider ownership.

---

## 29. Acceptance criteria

The rework is complete only when:

1. No global location/ambient timer polls all sources.
2. Each source has exactly one registered owner and an explicit applied plan.
3. Every admitted durable evidence unit carries event time, receive time, source instance,
   registration generation, sequence, clock domain, nullable qualified config revision,
   plan attribution, collected-data epoch, quality, and stable post-admission identity.
4. Raw evidence is durably admitted and lifecycle-epoch validated before logical effects.
5. Replay is deterministic and idempotent after process death.
6. Every multi-source consumer uses a documented JoinSpec.
7. Generic cycle overflow merging is removed.
8. Stop persists APP_DRAIN_COMPLETE, unresolved callback intervals, provider flush/removal
   outcome, and honest source-specific provider coverage.
9. A cold-process activity receiver directly admits every delivered batch event through
   the sensor/activity-api ingress boundary; provider delivery before commit is never
   described as guaranteed.
   The application-scoped automatic-start monitor remains available across session stop
   behind the sole ActivityRegistrationArbiter owner.
10. Foreground-service type and permission legality is centrally validated.
11. Wi-Fi and cell UI describes platform-constrained attempts/delivery honestly.
12. Battery UI shows ranges only with confidence and never uses fixed tracking-hour claims.
13. Existing public tracker snapshots, structured exports, and history remain compatible;
   full database backup explicitly discloses retained raw source evidence.
14. Performance and measured battery impact meet the numeric Section 23.6 guardrails,
   including normalized high-volume storage, bounded pruning, and measured per-source
   database bytes/write amplification.
15. All legacy flags and adapters are removed after the supported rollback window.

---

## 30. Initial open decisions

These require implementation-phase prototypes or product decisions:

1. Exact default JoinSpec ages for pressure/location, activity/location/steps, and radio interpolation.
2. WAL payload encoding: Proto versus existing signal serializer conventions.
3. Maximum raw-event retention after all projections checkpoint.
4. Whether source-plan and completeness summaries should become public tracker/api-module contracts.
5. Which devices/API levels qualify on-device battery estimates for Medium confidence.
6. Whether Advanced-mode source controls graduate to the default settings surface.
7. Maximum per-source shutdown grace inside the 10-second hard bound and product treatment
   of incomplete sessions.
8. Whether a future source-api module is justified after contracts stabilize.
9. Whether database backup remains a sensitive full snapshot or becomes sanitized.

Open decisions must not weaken the invariants in Section 5.

---

## 31. Adversarial review record

Two independent gpt-5.6-sol subagents reviewed this document against the repository on
2026-08-09. Neither edited files. Both initially rated the draft no-go for implementation
and conditional-go on the architecture. The blocking findings were incorporated as
follows.

### 31.1 Android platform and source-runtime review

| Finding | Severity | Disposition |
| --- | --- | --- |
| At-least-once incorrectly began at provider callback; activity receiver had no legal module dependency to engine Room ingress | Blocker | At-least-once now begins after WAL commit; sensor/activity-api ingress port and app binding are normative |
| Universal provider-completeness watermarks are impossible | Blocker | Stop contract split into app-drain completeness and source-specific provider coverage |
| Recovery state had no Android execution trigger | Blocker | Added manual/automatic/WorkManager/force-stop recovery trigger matrix |
| FGS type narrowing implied an unsafe service restart | Blocker | FGS type union is monotonic per service instance; no restart solely to narrow |
| Every provider sample durability conflicted with step/pressure batching | High | Defined source-specific durable evidence units and incomplete open windows |
| Design omitted framework location backend and API-specific activity/cell behavior | High | Added FUSED/FRAMEWORK plan, registration identity, API 26–30 cell fallback, and multi-SIM rules |
| Wi-Fi prerequisites and Doze behavior were ambiguous | High | Made fine-location/Wi-Fi/Location Services prerequisites and DEFERRED_IDLE explicit |
| Persisted deadlines were described without an executor | High | Added DeadlineScheduler classes and allowed-late Doze behavior |
| Test matrix stopped before target API and omitted force-stop/capability absence | High | Expanded tests to API 26–37 and failure/capability states |

Primary Android references used by the reviewer:

- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/develop/connectivity/wifi/wifi-scan
- https://developer.android.com/reference/android/hardware/SensorManager#flush(android.hardware.SensorEventListener)
- https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient

### 31.2 Data correctness, durability, migration, battery, and UX review

| Finding | Severity | Disposition |
| --- | --- | --- |
| New WAL bypassed collected-data deletion epoch and could resurrect deleted data | Blocker | Added epoch/acquired-time validation, atomic deletion scope, and deletion fault tests |
| One mutable Projected state could not support multiple projections | Blocker | WAL is immutable; added projection registration/checkpoint/failure/join state and one coordinator lease |
| Session authority conflicted with ActiveTrackingSessionStore and late binding was undefined | Blocker | Room is canonical; DataStore is a reconciled mirror; added logical session/run/binding records |
| Stable ID/sequence claims were impossible across registration/process boundaries | Blocker | Added source instance/generation, provider dedup key, per-instance sequence scope, and honest pre-admission gaps |
| Join checkpoint could pass and delete inputs needed for a future bracket | Blocker | Pending join state and checkpoint advancement are transactional with bounded missing-input finalization |
| Raw WAL exclusion from exports was false for whole-database backup | Blocker | Structured versus full backup semantics and sensitive-data disclosure are explicit |
| Shutdown lacked a callback-entry fence | High | Quiesce now fences generation entry and drains entered callbacks to terminal admission |
| Dual-run did not prevent legacy and shadow consumers from both mutating state | High | Added consumer migration registry and read-only shadow/canonical-writer invariants |
| Independent booleans allowed invalid rollout combinations and overstated rollback | High | Replaced with persisted rollout state machine; old-binary downgrade is explicitly unsupported |
| Percent/hour was labeled marginal without a counterfactual | High | Added estimate targets, baseline/evidence/sample metadata, and prohibited unsupported marginal claims |
| Semantic settings migration and executable performance budgets were missing | High | Added versioned settings migration and numeric Phase 0/release guardrails |

### 31.3 Readiness after disposition

The architecture is ready to begin Phase 0 design-validation and instrumentation work.
Later implementation phases remain gated by the contracts, tests, measurements, and
canonical-owner registry defined here. Open decisions in Section 30 may tune policy or
product behavior but cannot weaken Section 5 invariants.

### 31.4 Closure audit

The data-correctness reviewer re-audited the revised design against the repository and
returned **READY FOR PHASE 0** with no remaining P0 contradiction. The only remaining P1
wording ambiguity was resolved by requiring finalized late evidence to use the projection's
registered late-correction policy rather than an ad hoc eligibility decision.

### 31.5 Android platform closure audit

The Android platform reviewer re-audited the revised design and initially found two
remaining contradictions. Both are now resolved:

| Closure finding | Severity | Disposition |
| --- | --- | --- |
| Session shutdown could remove the transition registration required to start the next automatic session | P0 | Split application-scoped AutomaticStartTransitionMonitor from SessionActivityRuntime behind the sole ActivityRegistrationArbiter; session stop preserves the monitor, deletion fences both old generations, and Phase 5 includes a cold-process subsequent-session gate |
| Desired-plan atomicity and unconditional callback revision attribution contradicted partial Android application and provider-wide Wi-Fi broadcasts | P1 | AcquisitionPlanRevision is atomic only as desired state; applied state is persisted per source and may be mixed; evidence uses CAPTURED_REGISTRATION, LINKED_ATTEMPT, or RECEIVE_TIME_ONLY with a nullable qualified configRevision |

---

## 32. Implementation status (2026-08-10)

This section records implementation state; it does not weaken any phase gate above.

Completed foundations:

- source contracts, revisioned plans, semantic per-source settings, qualitative battery drivers;
- immutable Room event WAL, lifecycle/deletion fencing, registration identity, projection
  checkpoints/failures/outbox/join state, coordinator/session leases, and recovery;
- Room-first logical-session lifecycle and bounded per-source shutdown acknowledgements;
- durable cold-process activity receiver ingress and post-admission automatic-start effects;
- persisted, monotonic rollout state with reciprocal single-owner guards;
- source-native step and pressure runtimes with persistent same-session baselines,
  bounded callback actors, Android FIFO flush handling, event-time windows, and durable handoff.
- versioned step/pressure runtime checkpoints that persist callback overflow, allocation/admission
  failures, drain timeouts, and missing/active-checkpoint process restarts as classified gaps;
- an event-owned tracking-frame adapter with a transactionally committed, session-qualified
  outbox, stable source-event identities, serialized replay into the initialized downstream
  consumer pipeline, and live post-admission projection draining;
- executable source-event window, reset, aggregation, and FIFO/no-FIFO coverage tests that no
  longer instantiate legacy producer algorithms.
- a single location runtime with selected fused/framework physical backends, event-time batch
  ordering, live permission degradation, framework fallback without Play Services, durable
  handoff, and backend-specific flush/removal acknowledgements.
- the Phase 4 explicit join engine now shadows the real pressure-altitude, activity-inference,
  Wi-Fi interpolation, cell-presence, ski, plane, and sailing consumers with named bounded
  JoinSpecs instead of a shared `TrackingCycle` latest-value assumption;
- joined frames carry deterministic identity, input event provenance, input age/result,
  session/boot-domain isolation, provisional/final/correction state, revision, and supersession;
  reordered inputs and late corrections are append-only;
- candidates, anchors, source watermarks, revisions, and the oldest buffered admission ordinal
  are stored in `source_projection_join_state` in the same transaction as frame outbox effects
  and projection checkpoint advancement; restart/replay, retention-floor, codec, no-future-leak,
  bracket, quality, clock-domain, and late-correction tests are executable;
- all Phase 4 migration-registry entries declare `EVENT_PROJECTION` ownership. Explicit joined
  frames remain durable and correction-aware; individual domain effects still require their
  release acceptance gates before old compatibility data formats can be deleted.
- Phase 9 settings use the same semantic acquisition-plan factory and constraint resolver as the
  runtime. Requested source goals are never overwritten by missing permission/hardware; the UI
  reports blocked or degraded effective state instead. Applied event revisions are published by
  `TrackerServiceSourceSession`, and live coordinator telemetry is payload-free and local-only.
  Fixed score/hour battery
  claims have been removed; generic-prior estimates remain Low confidence until validation permits
  on-device calibration.
- motion-aware acquisition is now part of the authoritative event-source plan. Durable activity,
  step, location, cell, and pressure evidence feeds one session-scoped fusion controller; only
  acquisition-profile changes create plan revisions, preventing evidence-provenance churn from
  restarting Android registrations.

### 32.1 Motion-aware battery optimization

The runtime treats motion classification as a plan modifier, never as a second acquisition owner.
It cannot re-enable a source the user turned off. Every change is resolved into a normal
`AcquisitionPlanRevision` and applied through `AuthoritativeSessionCoordinator`.

The evidence hierarchy is:

1. Activity Recognition transitions/updates provide the primary low-power wake and transport-mode
   signal. A fresh automatic activity start seeds MOVING so acquisition is not delayed while the
   initiating transition is being bound to the new service run.
2. Step-counter deltas immediately corroborate pedestrian movement without polling the sensor.
3. Accurate location speed or accuracy-qualified displacement corroborates motion, but missing
   fixes never provide stationary evidence.
4. A cell identity change can extend recent independently established vehicle/general motion, but
   cell reselection never creates motion by itself. This avoids stationary radio churn becoming a
   movement false positive.
5. A rapid pressure change extends recent motion but cannot independently wake a stationary
   session.

Stationary requires at least two confirmations separated by 20 seconds plus a 90-second dwell with
no newer motion evidence. Vehicle evidence has a ten-minute continuity hold when no explicit
vehicle exit is received, and a cell handover or corroborating pressure change refreshes that hold.
This deliberately preserves location fidelity through tunnels and urban GPS outages. An explicit
vehicle exit shortens the tail to 90 seconds. These are hysteresis bounds, not claims that GPS loss
or lack of callbacks means the user stopped.

Effective acquisition by state is:

| Source | Unknown | Confirmed stationary | Moving |
| --- | --- | --- | --- |
| Location | Low-power sentinel | Passive provider-sharing request when Activity Transition can wake it; otherwise low-power sentinel | User/consumer-requested fidelity |
| Activity | Requested mode | Transitions-only | Requested mode |
| Steps | Registered with long batching | Registered with long batching | Requested batching/latency |
| Pressure | Gated when Activity Transition can wake it | Gated when Activity Transition can wake it | Requested sampling |
| Wi-Fi | No active attempts; broadcast/cached results only | No active attempts; broadcast/cached results only | Requested mode |
| Cell | Observe changes; no explicit refresh | Observe changes; no explicit refresh | Requested mode |

The service advances hysteresis every 30 seconds even without callbacks. The controller is reset
per service run, rejects evidence from other logical sessions/runs, and starts recovery sessions in
UNKNOWN rather than assuming stale motion. If activity permission or hardware is unavailable,
location never drops to passive-only because there would be no reliable vehicle wake source.
Payload-free telemetry counts acquisition-profile changes, stationary optimizations, and
full-fidelity restorations; the existing effective-plan status and battery estimate update from the
same resolved plan shown to the Android runtimes.

The runtime now initializes or upgrades to one all-event rollout snapshot. A service session rejects
mixed or legacy ownership, and no producer manager or trigger subsystem remains to acquire hardware.
The code-level gates pass, while production release remains blocked on the physical-device matrices
and performance/battery evidence described above.

Still required before the full design is production-canonical:

- Phase 3 physical-device FIFO/no-FIFO shutdown verification and rollout evidence;
- physical-device validation of the activity, location, and Wi-Fi/cell runtimes and their shutdown
  gates;
- Phase 4 device/session replay evidence and domain-owner acceptance of the declared maximum ages,
  missing-input behavior, and documented corrections before any joined consumer becomes canonical;
- validated Phase 9 on-device calibration evidence before any percentage range is shown;
- Phase 8 device stress, shutdown/recovery, and performance/battery comparison evidence;
- closure of the supported rollback/upgrade-cohort window before deleting additive compatibility
  preference fields or historical WAL data.

After incorporation and a targeted consistency re-audit, the Android platform reviewer
returned **READY FOR PHASE 0** with no remaining P0 or P1 contradiction in these contracts.
