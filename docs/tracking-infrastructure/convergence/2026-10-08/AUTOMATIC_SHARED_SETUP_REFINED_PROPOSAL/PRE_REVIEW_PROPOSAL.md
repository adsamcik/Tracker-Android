# Passive Location delivery and automatic capture: bounded proposal

Source-only trace of committed candidate `ab25a6dbfaab82d838a22ed97099cdd92bf2a2e7`.
**Automatic setup and retained-gap details are PROPOSED_NOT_IMPLEMENTED.** The separately frozen
PASSIVE_LOCATION_DELIVERY_CHUNKS_SELECTED packet authors the approved count-cap repair privately;
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

### Approved bounded cap slice, authored privately

The independent PASSIVE_LOCATION_DELIVERY_CHUNKS_SELECTED packet removes the total take64 cap.
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

- Do not invoke boundary.run from inside the initializer's existing Ready-operation block: it calls
  reconcile and acquires the same non-reentrant operation lock (boundary950-962). Place setup after
  startup/foreground recovery authorization but before the existing purpose handoff, letting each
  existing transition own its short Ready-operation+lifecycle lease. Recheck the exact Ready
  generation before the later handoff. A narrowly named within-held-operation variant is an
  alternative only if it reuses the same lease and predicates; do not nest either lock.
- Manual prepare must reach the same readiness before its own withReadyGenerationOperation144;
  automatic capture must reach it before computing the exact accepted mask/trigger envelope.
  Setup may be called concurrently by startup/Start; existing source lifecycle lease+Room owner/
  revision/cursor CAS serialize effects. A revision conflict reloads the durable state once or
  returns existing retryable debt; no unbounded polling or per-callback transition loop.
- Ready storage/retained history must not globally wait for all source setup. Run chosen sources
  independently; a failed source remains unavailable while valid siblings can start. Return a
  source-specific pending state via existing readiness/product availability rather than claiming
  a permission can fix containment. No new generic readiness framework/table is proposed.
- Do not retrofit the module's process-only initialization flag as durable success. Reopen/current
  chosen source/Start and existing recovery paths recheck original durable state. Full-clear
  follow-up must use existing exact completed-epoch rearm handling, not erase the handoff predicate
  or silently grant all-day consent. All source rearm/cleanup chains still need final assembly.

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
retirement and idle lifecycle, then persist capture containment+exact WAL cutoff atomically. Reuse
the existing Room store fencing/query/CAS rather than the four-source owner2 rollback arithmetic.
Protected resolveLane1097-1144/requireWriterAuthority2507-2551 already recognizes an active fenced
canonical lane whose cursor is not past its cutoff, under the same exact owner1. Drain that existing
canonical lane through its cutoff, then use finishLaneRetirement604-651 (which excludes candidate
Steps, not Location) and retain its original products/receipts. The current beginLaneContainment572
accepts only an EVENT_SHADOW lane, so a narrowly checked protected-canonical containment branch is
required; do not claim that the existing method already performs this transition.

Install static final2 with existing rearmInertShadowLane after the exact retired old lane, or
install directly for proved first use. Complete a source-local canonical activation that checks
acknowledged shared provider/caller retirement, lifecycle lease/revision, exact same owner1, no
global registration collision and final shadow cursor==high-water, then publishes final mask3.
Do not pass protected Location through SourceSpecificWriterTransitionEngine.promoteOwner, which
would incorrectly manufacture canonical owner4. Keep this small branch beside its current protected
writer/setup semantics, sharing existing coordinator lease/Room lane operations.

This exact protected Location lane/setup representation, and the shared chosen-source setup caller
above, are the genuinely new architecture details to confirm. Manual/automatic shared collection
for every supported chosen source is already approved; do not ask that feature question again.
The old candidate need not be discarded or left permanently manual-only. Actual provider/writer
activation remains prohibited until the final convergence batch and bounded authority.

### Suggested single plain-language architecture approval

Recommendation: use one setup step in Tracker's existing startup and Start flow for the sources the
person chose. It prepares the same collection pipeline for manual and automatic sessions. If the
phone already has our older candidate pipeline, finish its current session, stop its callbacks,
save everything already received, then switch future sessions to the final pipeline while keeping
all history. Location keeps its existing writer and saved-point storage. Failed setup affects only
that source and uses existing recovery states; other healthy sources keep working. No new backup
system, data store or general upgrade engine.

The alternative is a separate initializer for each entry point or erasing old candidate data. Both
are less maintainable or lose data, so neither is recommended. This question covers implementation
architecture only and does not authorize running providers during the current phase.

### Focused test-source plan (all NOT_RUN)

- Each source alone plus representative subsets, automatic trigger with captured Activity off.
- Generation1 manual accepted, generation1 automatic rejected; prospective generation2 both modes.
- Exact lane/owner/caller/manifest/fact mismatch rejected, no arbitrary future-generation acceptance.
- Generation1 retained reads/deletion/exports remain valid after generation2 activation/rollback.
- End/Off/permission/provider loss/recovery/delayed callbacks: same shared owner lifetime and original
  observed-time fences as manual; background sibling continues only under its own authorization.
- Current capability display agrees with zero/partial accepted automatic source mask.
- Typed export/import reads both supported session modes without changing file shape or granting
  collection authority; genuine original windows retained.

No automatic setup, gap storage, activation, validation or assembly completion is claimed by this
proposal. The independent approved passive cap packet is source-authored only and remains NOT_RUN.
