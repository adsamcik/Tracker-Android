# Passive Location delivery and automatic capture: bounded proposal

Source-only trace at committed `ab25a6dbfaab82d838a22ed97099cdd92bf2a2e7`; related current
source resolution is `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`. The exact setup/owner/startup
source pins are unchanged; the passive cap slice is now selected in that current candidate.
**Automatic setup and retained-gap details are PROPOSED_NOT_IMPLEMENTED.** The separately frozen
PASSIVE_LOCATION_DELIVERY_CHUNKS_SELECTED count-cap repair is selected IMPLEMENTED_UNVALIDATED;
its seven new methods and one revised method are NOT_RUN. No live source edits, gates or providers run.
The implementation-only phase remains active; all 25 full-chain prerequisites/T25 remain open.

## A. Passive Location: remove the total cap without changing identity

### Actual seams

- `tracker/engine/.../source/runtime/PassiveLocationReceiver.kt:38-77`: explicit non-exported
  receiver, application IO coroutine, five-second monotonic work budget; startup admission gate.
  The current timeout and exception branches discard the returned outcome.
- `PassiveLocationReceiver.kt:108-149`: caps the provider list with `take(64)`, resolves permission,
  original observed time and original registration independently per fix, then creates one-fix
  `locationDeliveryCandidate` and uses the existing unbound durable sink.
- `SourceRegistrationRepository.kt:372-465`: original registration/authorization/retention/consent,
  original clock/epoch, current admission fences and exact saved provider request.
- `source/ingress/DurableSourceEventSink.kt:125-142,168-179`: delivery admission commits the WAL;
  successful/duplicate admission requests the existing protected Location drain.
- `DurableSourceIngress.kt:591-594,708-715,1045-1093`: original delivery identity and unit indexes,
  purpose qualification, sequence allocation and immutable WAL rows share the Room transaction.
- `source/coordinator/SourcePipelineRecovery.kt:37-53`: existing recovery consumes already admitted
  WAL; it has no raw passive-delivery inbox.
- `LocationSourceRuntime.kt:1281-1354`: active callbacks have bounded in-process admission retries and
  runtime metrics. Those metrics are not a retained passive-delivery gap record.
- `SensorRuntimeSupport.kt:101-121`: atomic SensorAdmissionCheckpoint is for Steps/Pressure only.
- `SourceRuntimeStateEntity.kt:6-22`: one replaceable baseline/watermark row per source/owner,
  expressly not source evidence. It is unsuitable as a multi-delivery queue without a new design.

### Approved bounded cap slice, selected IMPLEMENTED_UNVALIDATED

The independent PASSIVE_LOCATION_DELIVERY_CHUNKS_SELECTED packet, selected at3a3476e6, removes the
total take64 cap. Its exact production SHA is1b25cd917f7b43867bf3a25ae21c6f9ebbc570dadd2559f26972d36c839ee7c1.
It copies and normalizes the complete delivery once, then processes cancellable64-item work chunks
under the unchanged five-second receiver timeout. Each fix retains the existing one-fix delivery
hash/unit0, globally ordered original source fields and exact duplicate removal. Do not normalize
chunks independently or combine points into new delivery identities: either can change replay
ordering/dedup. Per-fix startup, permission, resume, original registration and observed-clock checks
remain; cancellation is checked at entry/between originals and each chunk yields.

The obsolete internal overflow field is removed; only admittedOrDuplicate/unverified remain. The
receiver still does not consume the outcome. No raw tail is launched after its deadline. Copy/sort
is synchronous, not itself an interruptible sort. This source slice does not add admission retries,
commit-uncertainty accounting or durable gaps. Already committed WAL uses the existing drain.

Those further choices must not be described as part of the authored repair. If later bounded
retries are approved, reuse each immutable candidate and original identity, revalidate through
existing ingress and never infer loss from a cancelled commit/return boundary.

### Remaining genuinely new data-handling choice

No current table/helper provides retained Location delivery-loss metadata. The smallest proposed
addition is a **source-owned compact delivery-gap receipt in the existing Location join-state
storage**, with a dedicated namespace and versioned payload; no coordinates, raw-tail inbox,
new database engine, provider demand or transfer framework.

The receipt would retain only original epoch/boot/registration identity, received time, the known
unprocessed/uncertain delivered-item count, reason, and original observed bounds only where those
bounds are actually known and attributable. A delivered-item count is not an exact count of unique
missing Location points: the tail can contain duplicates or ineligible observations. No arbitrary
new daily time window should be invented. A stopped/new-epoch delivery cannot create a surviving
new history record. A gap does not grant purpose authorization or erase independently valid data.

To make process interruption honest, a pending receipt must exist before the first source-owned
attempt, be advanced at bounded checkpoints, and be completed only after the delivery settles.
Readers treat an unfinished checkpoint conservatively; it is not a raw-data replay queue. Receipt
writes/cleanup must share epoch/deletion authority. Gap save failure is itself unknown coverage,
not a claimed durable gap. Reserve receiver time for settlement; do not start an unbounded
NonCancellable cleanup after the hard deadline.

Before implementing that choice, confirm the exact namespace/encoding/retention contract. The
existing SourceProjectionJoinStateEntity146-164 is keyed by projectionID/version/stateKey and already
provides versioned payload plus updatedAtMs; SourceProjectionStateDao285/289/347-378 provides
save/insert-if-absent/bounded metadata page/exact read/delete. Its minimumRequiredOrdinal participates
in global WAL retention (DAO466). Reuse the existing no-required-WAL Long.MAX_VALUE representation
used by ProtectedLocationCanonicalHandoff1034 for settled receipts; do not pin ordinal0 or add a
fake observation/admission ordinal.
It needs source-owned bounded read/prune, consent erase and full-clear handling. Existing full clear
already deletes all join state; source-specific retention currently knows only its existing
curation/receipt prefixes, so those cleanup paths need the new namespace explicitly. No synthetic
admission ordinal, fake Location observation, or provider failure/retirement flag should be used to
smuggle a gap into unrelated records.

A resumable raw-tail queue could save otherwise unfinished coordinates, but would need durable
payloads, concurrent delivery keys, retry scheduling, permission/Stop/epoch handling and cleanup.
That is a different, materially larger choice and is not recommended for this bounded repair.

### Focused test-source plan (all NOT_RUN)

- 65/129+ otherwise eligible fixes: all work chunks reached with unchanged point identities.
- Exact duplicate redelivery with different work chunk boundaries: no additional WAL products.
- Mixed duplicate/invalid/eligible points around 64 boundary: healthy tail is not discarded.
- Original authorization changes inside a delivery: each point retains its real authority.
- Retryable storage failure then success/duplicate; permanent failure does not loop.
- Deadline before a chunk, during one admission, and after a commit before return: accurate uncertain
  remainder, saved prefix still present, bounded completion, cancellation preserved.
- Off/permission loss/clear/boot change during processing: late admissions stay fenced.
- Gap receipt (if approved): pending interruption, malformed metadata, failure to save, retained
  sibling preservation, bounded retention and full clear; no invented point/count/window.

## B. Automatic capture: finish the same source flow

### Existing behavior and limits

`BackgroundTrackingApi.kt:373-380,1229-1270` loads the production rollout, intersects chosen sources
with AUTOMATIC reachability and legal foreground capabilities, then terminally suppresses a start
with zero accepted capture sources. `TrackerServiceSourceSession.kt:59-68` rejects legacy source
ownership; only the same event coordinator is eligible.

`TrackingRolloutStateStore.kt:217-289` retains manual Steps generation1 and adds Steps generation2
with manual+automatic mask3. Activity/Pressure/Wi-Fi/Cell have only manual generation1. Location is
absent from the production default catalog. `withoutUnbackedProductLanes:800-834` contains any source
whose lane does not match the catalog. Protected Location's real writer exists, but its lane and
persist guards also require manual mask1 (`ProtectedLocationCanonicalHandoff:1119,2519`).

These are incomplete assembly contracts, not a demonstrated permanent Android limitation.
TODO-AUTO-BINDINGS:2234-2235 and source LOC-004/PRESS-009/ACT-005/WIFI-012/CELL-012 require completion.
Provider/permission/background FGS legality and the pre-T25 activation gate remain necessary.

### Prospective exact binding plan

1. Keep every existing generation1 record/lane/manual mask unchanged. Add explicit prospective
   manual+automatic contracts for Activity/Pressure/Wi-Fi/Cell using the Steps generation2 precedent;
   keep each existing writer ID, data model, provider owner and original observation semantics.
2. Extend the existing coordinator exact-binding methods to check the requested mode against the
   exact recognized original binding, rather than hard-code manual or accept arbitrary generations.
   Carry that exact binding in the existing immutable manifest and SourceCaller receipt.
3. Complete the same binding recognition in each projection lane/WAL adapter/fact writer. Preserve
   original registration, consent, epoch, lease, cutoff, checksum, interval and child qualification.
   Activity CONTROL remains a separate purpose and is never captured merely because it starts a
   session. Wi-Fi/Cell automatic capture still requires its own chosen source; no Location demand.
4. Update source-owned current and retained validators, retention/truncation proofs, selected
   deletion and lifecycle proof for the original binding. Later activation of generation2 must not
   hide valid generation1 history. Use exact original lane/owner/manifest; do not equate current
   runtime authority with retained-data eligibility.
5. Update native history/export selection to recognize the new exact generation. The existing
   Activity/Wi-Fi/Cell portable formats already include AUTOMATIC, and Pressure carries real
   measurement windows without a session-mode field. Preserve those formats; no new file type is
   required. User-edited imports remain imported data and never become live recording authority.
6. Derive automatic availability from the same chosen/accepted source contract, plus separate
   CONTROL readiness and actual platform constraints. Current Settings723-728 can call automatic
   operational from CONTROL alone while BackgroundTrackingApi379 suppresses all capture. Reuse the
   existing unavailable/repair surface; containment is not a permission the person can fix.

The complete source scope includes TrackingRolloutStateStore/SourceProductFactories,
AuthoritativeSessionCoordinator, the existing source-specific writer transition coordinator,
source projection/WAL adapter/fact writer, Activity/Cell/Wi-Fi fact entities/maintenance,
PressureRetentionTruncation, captured radio retention proof, source selected-delete services,
DefaultSourceLifecycleProofAdapter, Activity/Pressure/Wi-Fi/Cell retained composers and native typed
exporters. Preserve source contracts in one source-local recognized-binding helper where existing
ownership boundaries allow; do not add another organization/fact engine.

### Smallest final contract with preserved candidate data

Only main was released. Native generation1 candidates are unreleased, but actual local candidate
data must remain usable and must not require full erase to gain automatic capture. Do not repurpose
a stored generation/mask or build a general upgrade engine. Keep exactly two recognized contracts:

| Role | Binding | Mask | Writer ID/version | Destination owner generation |
| --- | --- | --- | --- | --- |
| Original Activity/Pressure/Wi-Fi/Cell candidate compatibility | 1 | manual1 | Existing source writer/version | Original canonical2; contained3 |
| Final prospective Activity/Pressure/Wi-Fi/Cell | 2 | manual+automatic3 | Same source writer/version | Canonical4; contained5 |

The2N owner values follow the existing SourceWriterGenerationContract. Steps and protected Location
retain their separate established owner arithmetic. Recognize immutable original contracts in
readers/writers/maintenance; original generation1 manual history stays manual. Typed imports keep
their own original modes and never authorize live recording.

Use the existing source-specific transition engine and RoomTrackingRolloutStateStore; add only an
exact final-contract selection and allowed predecessor branch. No new engine, transition journal,
proof table, generation registry or arbitrary future-binding declaration is needed.

1. **No prior lane:** prefer exact final generation2. The inert install remains source-contained and
   starts at the existing WAL high-water. First canonical activation accepts the existing legacy
   owner1 (Activity/Pressure) or absent original radio owner only when there is genuinely no prior
   product lane/canonical candidate. Preserve acknowledged legacy quiescence and all original
   lifecycle/callback/cursor checks. Write canonical owner4 directly; do not create phantom gen1.
2. **Old canonical gen1:** preserve the exact original binding while finishing any existing session.
   Do not terminate a current user session to upgrade it. Once ordinary End/recovery has acknowledged
   provider/caller retirement and the lifecycle is idle, use existing beginCandidateRollback467-541
   to contain capture and persist its exact cutoff; request the same source drain. Complete rollback
   only after cursor==cutoff and exact owner/lane/lease/revision checks543-639. This retires the old
   canonical lane with CONTAINED_AFTER_DRAIN and advances owner2→contained3, without deleting data.
3. **Old inert gen1 shadow:** use existing beginLaneContainment559-600 and finishLaneRetirement604-651
   after the exact old shadow cursor reaches its persisted cutoff. Its original legacy/absent owner
   remains unchanged because it never owned canonical products. Do not briefly activate an obsolete
   writer simply to retire it or invent a canonical owner3.
4. **Settled retired gen1:** install exact final generation2 using the existing static
   rearmInertShadowLane438-447. This method already permits a known static newer binding after a
   RETIRED lane, no active lane, source capture fence, same-source writer identity and revision checks
   (462-550). The coordinator adds exact allowed predecessor validation: retired canonical gen1
   must have CONTAINED_AFTER_DRAIN, cursor==cutoff and contained owner3; retired shadow gen1 must
   have the exact settled shadow and original legacy/absent owner. Unknown/corrupt history stays
   source-contained and preserves bytes, with an existing repair state. Healthy siblings continue.
5. **Final gen2 inert:** drain its already-admitted exact shadow interval, then standard canonical
   activation379-465 under source lease, callback fences and actual legacy writer quiescence. The
   narrow first-use/shadow-predecessor branch accepts legacy1/absent; the canonical-predecessor branch
   accepts contained3; both publish final owner4/mask3 atomically. An already exact final canonical
   source is read-only Ready; do not increment its binding or reconfigure it each Start.

InstallInertCandidate316-377 currently always uses spec.bindinggen1 and blocks any prior lane349-350.
Extend that original method with the exact static final-binding/settled-predecessor case instead of
adding a general upgrade service. Activation currently accepts binding2 only from contained3
(requireInitialOwner824-845/promoteOwner868-905); extend its exact first-use/retired-shadow proof
branch, including absent radio owner insertion, without accepting arbitrary owner generations.
Resolve original stored binding for rollback/idempotent recovery as executableBinding790-797 already
does. Never loosen rearmAfterFullDeletion641-765: that separate dynamic rearm requires completed
full-clear authority. The ordinary static handoff must not pretend a deletion happened.

An interrupted handoff resumes from the original durable lane/owner/cutoff, not a saved new mode flag.
A busy/incomplete session waits for its real End/recovery; poison/storage failure retains the old
WAL pin and source repair state. This is temporary recoverable debt, not a permanent manual-only
restriction or a requirement to clear history. No captured gen1 product or independently consented
background sibling is deleted. Root must confirm these exact transition branches before authorship.

### Completed Clear all: same final contract in a proven new epoch

This is distinct from the non-erasing gen1→gen2 handoff. Empty lane tables alone never prove a clear.
The exact existing clear chain is:

- DefaultCollectedDataWriterQuiescer234-245 calls stopServiceAndAwaitQuiescence; TrackerServiceApi
  835-856 reserves/routes/awaits a durable STOP. TrackingLifecycleCommandState.issueStop invalidates
  pending Start. Deletion also closes/enrolls and physically joins active provider/transfer work.
- CollectedDataDeletionService556-598 keeps its durable file journal and startup deletion barrier
  closed, establishes the provider fence, commits DataStore target epoch and invokes database clear.
- AppDatabase1088-1150 preserves imported count-domain/Pressure/radio replay/deletion authority,
  then1151-1175 atomically advances SourceEvidenceState epoch/revision/deleted WAL high-water,
  deletes source products/lanes/callers/registrations/WAL and writes exact operationId+targetEpoch
  DATABASE_CLEARED receipt. Destination owners survive deliberately.
- Deletion service600-614 runs postDatabaseDeletion and requires the same receipt in WRITERS_REARMED
  before clearing its marker and reopening startup664. The present callback only rearms Steps.

Proposed correction: extend that existing postDatabaseDeletion composition with an **exact clear-
only reset of ephemeral writer setup**, inside the same original completed-clear operation. Reuse
the fixed final contracts; do not manufacture new binding generations for each Clear all.

1. Under the already acknowledged deletion fence, one Room transaction reads the exact operationId.
   It must be DATABASE_CLEARED, targetEpoch must equal both DataStore lifecycle epoch and Room
   SourceEvidenceState epoch/retained floor, and the existing deleted WAL high-water must be the
   preserved pre-clear allocation fence. All old capture payload/lane/session/caller/registration
   rows and source drains must actually be gone; a retention-floor receipt or a missing lane cannot
   substitute. The callback receives the actual active deletion operation, not an inferred ID.
2. For Activity/Pressure/Wi-Fi/Cell, recognize only this binary's original and final owner states:
   exact initial legacy1/absent radio, original candidate2/contained3, final candidate4/contained5.
   Set their now-empty setup to the final candidate owner4 and known binding2 semantics. If already4,
   keep it4. For contained5, use a **narrow completed-clear-only CAS** checked against the same
   operation/epoch/known source owner and actual emptied source state. This explicitly rebases
   ephemeral authority across the proven new epoch; it does not weaken same-epoch monotonic
   SourceDestinationOwnerDao.compareAndSetOwner40-55 or permit reset from an arbitrary owner.
3. Protected Location keeps exact EXISTING_LOCATION_CANONICAL_PIPELINE/owner1. Steps keeps its
   established separate owner-generation semantics and existing clear composer, but chooses the
   final recognized contract for future recording rather than preserving a manual-only future.
   No deleted observation/import tombstone is reset and no recording consent is granted.
4. Publish contained final setup for later chosen-source preparation; sources not chosen need no
   provider or product activity. The ordinary setup operation can then rebuild the final lane
   under a matching current-epoch WRITERS_REARMED receipt and unchanged source-owned empty setup.
   It must not reject final owner4 as a false fresh install. Already rebuilt exact final lanes are
   read-only Ready. Native append work after reopening is new-epoch work and is not mistaken for
   an old uncleared row. A wholly arbitrary/incompatible owner remains source-contained and is
   preserved as repair debt; recognized healthy sources can still be prepared.
5. Move the single DATABASE_CLEARED→WRITERS_REARMED CAS after all known source resets; Steps must
   not mark that phase complete before its siblings have participated. Existing Steps raw Room
   rearm logic can be composed inside this transaction; do not call its already-completing receipt
   wrapper first or nest startup recovery while deletion is closed. Interruptions replay the same
   operation safely; the next genuine clear writes a newer target epoch and resets the same final
   known setup again. Do not call dynamic rearmAfterFullDeletion, which requires deleted predecessor
   lanes and unsupported arbitrary-generation declarations.

Why a same final binding/owner can be reused across clear: old ingress carries its original
capturedCollectedDataEpoch and is rejected against the new state before/inside Room admission.
Writer commands also carry original epoch (ActivityWriter107/251/563, CellWriter236/444,
WifiWriter218/545, PressureProjection414/614, ProtectedLocation1373). Physical work is stopped and
joined before deletion; old session/caller/registration IDs no longer exist, WAL allocation does
not restart below the preserved deleted high-water, and original imported/replay fences remain.
The approved final-source binding changes must preserve each of these predicates; owner4 alone
never admits an old command. Imported data remains imported and cannot become runtime authority.
This narrow cross-epoch reset needs architecture approval and concrete stale-command/clear tests;
source inspection does not claim it implemented or proven safe in execution.

### Actual final-production setup caller is still missing

This is separate from mode recognition. Production search found no installInertCandidate or
activateCandidate caller outside declarations/wrappers (Steps included). The actual normal path is:

- Application327→DefaultTrackingStartupGate534→recoverStartupAuthority346: recover released history,
  register ambient radios and request existing drains; no capture install/cutover.
- TrackerModuleInitializer103-122: reconcile source policy/purposes, initialize locks/CONTROL; no
  writer setup. Its handoff is already inside withReadyGenerationOperation290-303.
- RoomTrackingRolloutStateStore382-395 initializes unknown/absent rollout with all sources contained.
- Manual prepare298-313 and automatic BackgroundTrackingApi373-380 only load/filter that state.
  Fresh manual Start rejects TRACKING_CAPTURE_UNAVAILABLE. Automatic capture likewise suppresses
  its zero mask. The existing protected Location bridge cannot bypass these gates.

The currently dormant candidate is intentional before convergence/provider authority; it is not
a correct final ordinary-user path. Proposed smallest wiring is **one shared chosen-source setup
operation used by the existing TrackerModuleInitializer and the same readiness before Start**.
It resolves only this binary's exact final/source-original bindings and delegates to the existing
source transition methods above. It initializes writers, not sensors; recording still requires
independent chosen purpose/permission/command/caller authority. No settings toggle or developer
rollout action is exposed to users. The post-convergence binary supplies this normal setup path;
no actual activation is permitted in the current implementation-only phase.

Reentrancy/concurrency requirements:

- DefaultTrackingStartupGate358-375 already supports same-gate/same-generation nested Ready
  operations through ReadyGenerationOperationContext, and reconcile433-437 returns immediately
  while Ready. Ordinary nesting is not categorically a deadlock. Keep a single stable order anyway:
  ordinary source setup after startup/foreground authorization, before the initializer's purpose
  handoff, and before manual prepare144. Each existing transition owns its short Ready operation
  then lifecycle lease then Room transaction. Recheck generation/command before the later handoff.
  Do not start recovery after Ready/Stop has changed or reacquire a lifecycle lease inside a held
  operation; recovery521 takes the underlying mutex and that lease is not generally reentrant.
  No new within-held-operation API is needed merely to address the inaccurate nesting assumption.
- Initialization and Start can call the same chosen-source operation concurrently. Existing
  lifecycle leases and exact Room owner/revision/cursor CAS serialize effects. On a state race,
  reload once or return the existing source-owned retryable result, never an unbounded CAS loop.
- Saved data/Room readiness does not wait for all setup. A source failure stays local; healthy
  selected sources retain existing partial acceptance. Activity CONTROL alone does not install
  captured Activity. Setup performs no provider request or permission/consent change.

### Concrete pending setup and unchanged Start continuation

Current read-only ManualTrackingStartReadinessReader31-70 only evaluates policy, rollout and actual
hardware. Current executeManualTrackingStartContinuation123-149 cancels pending requests for
TrackingUnavailable/NoAvailableCaptureSource and consumes the one-use Start before Android enqueue.
That behavior cannot be used for ordinary busy/shadow-drain setup debt.

Add a narrowly typed **SetupPending** readiness/result alongside the existing manual Start
continuation, with its original continuationGeneration. It is not a fake permission repair or a
new journal. Reserve/use the existing durable pendingManualStart+source consent selection when a
user actually taps Start. Keep pure readiness display read-only. Only the action path and normal
module setup may advance writers. Do not consume the command while known setup is still pending.

The shared source setup uses the existing product lane/cursor/owner state plus Room invalidation
for the finite original setup state. Observe the already existing tables for product lanes, owners,
source evidence epoch, relevant service/lifecycle completion and callback barriers. Fold wakeups
into a durable state re-read; use a small source-specific state fingerprint and one in-flight
attempt so own writes or conflated notices cannot produce a loop or lose a completed cursor.
A shadow install requests the existing source drain. Its real lane-cursor update invalidates the
observed product-lane table; once cursor reaches the transaction's actual high-water, the next
state evaluation invokes canonical activation. While behind, merely read Pending and do not acquire
a transition lease per WAL row/callback. On reopen/explicit Retry the initial state read recovers
any missed signal. This is one bounded pending-setup observer beside existing initialization, not
a new persisted scheduler/outbox or global activation engine.

- Initializer: remain enrolled for pending chosen source state after its first setup attempt; use
  cursor/lifecycle readiness events to reattempt only changed candidates. Start CONTROL/purposes
  only under their independent readiness, and do not hold storage history behind setup debt.
- Manual action: retain the exact pendingManualStart and let the same source-state completion wake
  it. Re-read source selection, command disposition, storage/Ready generation and real permissions
  before continuing. Dashboard/widget/shortcut continuations reuse that same existing generation;
  they must not reserve a replacement Start or silently ask for another tap after ordinary setup.
  The current preflight consume call144 precedes actual preparation, so its companion must defer
  one-use consumption until real preparation has succeeded, and keep the original pending request
  on a reached SetupPending result from a late ordinary setup race. Use the same prepared-start
  receipts/current-command gate to serialize one delivery; do not reserve a second command or
  restore a superseded token. A denied repair/dismissal, End, Off, explicit app
  stop, changed selection or deletion cancels it. Clear already reserves STOP through writer
  quiescence, so the old pending generation cannot cross the new data epoch.
- No busy polling: bounded ordinary attempt then pending state until a relevant durable change or
  explicit Retry/open. Real storage/poison/unverifiable source debt uses existing recovery/Retry
  surfaces. A completed failure or cancellation propagates; no application-owned endless timeout
  retry is added. An otherwise healthy accepted sibling keeps the existing partial-source behavior.
- Automatic action: prepare the same writers before calculating its accepted mask/envelope.
  Known setup Pending is source-retryable instead of zero-mask permanent unsupported. Preserve the
  original Activity action/trigger and its real lifetime, command, Stop and epoch checks; if it
  expires or is superseded while waiting, it cannot be freshened into a later unauthorized Start.

The small SetupPending type and event continuation are necessary companion wiring in existing
TrackerServiceApi/ManualTrackingStart/readiness/Dashboard/external-start entry points. Their exact
public layout/copy stays existing; no developer rollout switch or new permission dialog is added.

A minimal setup caller is missing implementation, while the phase prohibition on invoking it here
is missing execution authority. Those are separate. Adding gen2 masks alone does not close either.

### Source changes and ownership

- SourceDestinationOwnerEntity/catalog: preserve gen1 constants; add exact prospective gen2 and
  preferred final contract for each4source, original writerID/version and same schema.
- SourceSpecificWriterTransitionCoordinators: narrow static handoff/first-use branches above, no
  arbitrary future generation support; Steps reuses its existing separate engine.
- TrackerModuleInitializer/Start readiness: one setup caller with the non-reentrant order above.
- AuthoritativeSessionCoordinator/source product factories/WAL adapters/projection lanes/facts:
  qualify requested mode against the exact original binding and immutable manifest/caller.
- Retained readers, maintenance, deletion and exporters: recognize both exact original contracts;
  later final setup must not hide old data. Existing portable modes already represent automatic.
- Physical runtime/host: same shared owner/plans/FGS requirements, no second automatic collection
  lifecycle or implicit background consent. Activity CONTROL remains separate.
- Settings status: distinguish CONTROL trigger readiness from actual chosen capture readiness.
  Preserve settings and honest platform repair states; no permanent developer gating in user UX.

### Exact new Location binding/owner representation to confirm

Use the **existing protected Location writer**, not another writer or destination:

| Role | Binding | Mask | Projection ID/version | Destination owner |
| --- | --- | --- | --- | --- |
| Preserved original manual | LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION1 | manual1 | Existing LOCATION_CANONICAL_HANDOFF_PROJECTION_ID/version1 | EXISTING_LOCATION_CANONICAL_PIPELINE, INITIAL_EXISTING_LOCATION_GENERATION1 |
| Final prospective | New exact binding2 | manual+automatic3 | Same existing projection ID/version1 | Same existing canonical pipeline/owner generation1 |

Protected Location deliberately has no2N owner arithmetic (SourceWriterGenerationContract10-11).
Do not rewrite that established destination generation merely to align it with radio/Pressure.
Original lane generation/manifest tuple, activation/cutoff/cursor and original point receipts keep
old/new recording intervals distinct; owner1 alone never authorizes a point. No owner4, second
Location destination, correction store, raw inbox, proof table or file format is introduced.

Register both exact tuples in the same executable catalog and prefer final2 for prospective setup.
Carry and validate its existing writerID/version/binding generation in the existing manifest/caller,
protected canonical commands and saved purpose/receipt dependencies. Existing hard-coded generation1
receipt fields (ProtectedLocationCanonicalHandoff2302) must derive the original selected exact
binding, not relabel old receipts as2. Update retained read/lifecycle/export recognition only to the
two exact original tuples; current acquisition permission and historical saved-data authority stay
separate. Automatic/live Location uses the same provider owner, canonical handoff and saved summary;
sessionless system-rearmable delivery and temporary UI leases keep independent purpose lifetimes.

For an old active protected canonical lane, wait for actual session End/acknowledged capture callback
retirement and idle lifecycle, atomically contain capture and persist its WAL cutoff, then drain
that exact existing canonical lane through the cutoff. Its current fenced canonical writer can
settle that real interval (resolveLane1097-1144 and requireWriterAuthority2507-2551). Retire with
CONTAINED_AFTER_DRAIN while keeping owner1, receipts and products; use existing finishLaneRetirement
604-651, whose dedicated canonical exception is Steps. Add only the narrow exact protected-
canonical containment branch because beginLaneContainment572 currently accepts shadow only.

**Atomic final Location setup; no inert shadow.** Protected handoff217-226/1131 cannot drain a
shadow, so do not install one and wait for a cursor that has no classifier. Under the existing
Ready/lifecycle lease, idle/callback fences, same exact owner1 and no global writer collision,
install/publish the known final canonical Location lane directly in the same Room transaction.
For first use with no pending originals, its activationOrdinal is existing WAL high-water+1 and
cursor is that high-water. After an old canonical predecessor, no captured old interval may be
carried into the new binding before its actual old cutoff has drained. No second writer/store.

Background Location uses the same canonical writer: all current writeAmbient calls are inside
this protected handoff, and verifyAmbient1337-1343 also requires a canonical lane. Therefore setup
must precede new Location purpose publication, and lack of old captured authority must **not** be
treated as proof that existing ambient originals are already saved. If exact current-epoch retained
Location WAL has independently authorized ambient originals still unsettled, the atomic setup
uses the earliest such original admission ordinal as its recovery floor (and cursor=floor-1),
then lets the **same existing canonical handoff/ambient qualifier/writer** settle that finite
retained interval. The normal high-water floor applies only when no such original exists.
Preserve original observed times/IDs/consent and existing receipt dedup; recover ambient only from
its own original permission, never by lending future session capture. No raw queue, second cursor
store or synthetic observation is introduced. Other-source WAL after the source floor is classified
by the existing handoff as usual; no data is lost just because initial setup happened later.

An old inert Location shadow cannot be claimed drained by the current handoff. Its exact no-capture
interval must be established from original immutable registration/authorization/WAL and absence
of canonical captured products; then its product-inert cutoff can be settled in the existing lane
transaction while any genuinely pending ambient originals are carried through the same canonical
recovery floor. If captured/ambiguous evidence exists, preserve its pin and report source-owned
repair debt; do not silently advance or erase it. This narrowly scoped original-interval check is
part of the proposed setup, not an assertion that the present shadow already has a classifier.

Registering Location requires the explicit shared owner predicate as well: replace the current
Location fall-through true in hasExactCanonicalDestinationOwner867-938 with exact recognized
protected projection ID/version, known binding/mask and same existing owner1. Rollout cannot
advertise a source whose protected writer then rejects its ownership. The protected command,
manifest, saved purpose and original receipt tuple must all agree with that known binding.

Clear-only Location setup uses the same owner1 and proven new epoch branch described above, with
no old interval after actual full clear. It is atomic canonical setup at high-water, independent
of4-source2N ownership. No protected owner4 is created. Same-original receipt/correction semantics
remain. These exact setup/clear/recovery details, and the shared caller, require architecture
approval; the already settled manual/automatic feature question is not repeated.

### Suggested single plain-language architecture approval

Today the normal app startup and Start button do not prepare the new tracking pipeline. May I
finish one shared setup step for the sources the person chose, reused by startup and Start? It
would use the same pipeline for manual and automatic sessions, finish and preserve any older
recording before switching future collection, and rebuild safely after Clear all using the
existing completed-clear record. Location would keep its current writer and saved-point storage.
A Start would wait through brief setup work and continue only if that original request and its
source choices are still valid. A failed source would use existing recovery controls while other
healthy sources remain usable. This does not authorize activating providers during this phase.

### Focused test-source plan (all NOT_RUN)

- Each source alone plus representative subsets, automatic trigger with captured Activity off.
- Generation1 manual accepted, generation1 automatic rejected; prospective generation2 both modes.
- Exact lane/owner/caller/manifest/fact mismatch rejected, no arbitrary future-generation acceptance.
- Generation1 retained reads/deletion/exports remain valid after generation2 activation/rollback.
- End/Off/permission/provider loss/recovery/delayed callbacks: same shared owner lifetime and original
  observed-time fences as manual; background sibling continues only under its own authorization.
- Current capability display agrees with zero/partial accepted automatic source mask.
- Actual completed-clear operation/epoch proof, all known final sources and repeated clear,
  unchanged final binding/owner reuse, clear-only contained-owner reset, interrupted receipt phase,
  stale callbacks/commands and retained import/replay fences; empty lanes alone cannot rearm.
- Atomic Location setup with other-source WAL and preserved pending ambient originals, exact
  owner1 predicate, no shadow deadlock, no inherited captured interval/consent borrowing.
- Reached real nested Ready context, Stop/deletion/lifecycle races; pending cursor completion
  advances setup and the unchanged original Start once, without polling or losing conflated events.
- Typed export/import reads both supported session modes without changing file shape or granting
  collection authority; genuine original windows retained.

No automatic setup, gap storage, activation, validation or assembly completion is claimed by this
proposal. The independently reviewed passive cap packet is selected IMPLEMENTED_UNVALIDATED; its test
methods remain NOT_RUN.
