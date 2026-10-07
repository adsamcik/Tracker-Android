# Start coordination selection

Phase: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Tests: **NOT_RUN**.
Selection: **ACCEPTED_STATIC_ONLY**. Exact final review is recorded in SOURCE_REVIEW.md.
Base input: `78eb255e59`. `FILES.json` pins the exact base and selected bytes; `SELECTION.patch`
uses normalized line endings only for readable review. The author has not replaced shared source
files, staged, committed, run gates, activated providers, or integrated this packet.

## Implemented scope

- Ready Activity effect drains use the existing deletion-owned structured child registry without
  holding the command mutex while an effect reserves Start. Serialized control-plane operations
  retain their existing entry point.
- The service's foreground acceptance uses one existing current-command mutex. Foreground
  promotion is followed by the direct existing Room acknowledgement, without nested gate entry.
  Exact current-command checks reject older accepted commands after a newer reservation.
- Manual preparation while Off grants only the exact initial delivery exception. Off and its
  previous collection boundary remain saved until foreground, Room, and durable acceptance succeed.
  Failure and cancellation revoke only that pending exception. Process recreation restores neither
  it nor a pending permission intent from the previous process.
- Automatic Start carries the receiver's original command generation through the existing batch,
  callback permit, effect consumer, and API. No suspension refreshes it. Retry serialization omits
  this process-local value and durable replay never gains fresh callback permission.
- The atomic automatic reservation also uses the existing factual Stop clock. Current-boot evidence
  observed at or before a same-boot End cutoff cannot revive tracking. Off always blocks it.
  Missing/corrupt Stop clock blocks automatic Start only; manual Start remains usable. Distinct boot
  counters are not compared. Independently authorized CONTROL/background history is unchanged.
- Prepared service work and its cancellation cleanup run inside the registered child. Clear cancels
  and physically joins that child before closing its data generation. Existing bounded compensation
  addresses only the exact obsolete token/command. The runtime is checked again after suspension;
  an Android-owned, partly applying, applied, or replacement delivery keeps its shell and descriptor.
- One service-private monitor couples exact synchronous ownership checks, request adoption,
  descriptor/foreground publication, APPLY flags and obsolete shell/flight release. Compensation,
  Room, provider work and other suspended operations stay outside it. Suspended results publish only
  into their same request/run. The old service object synchronization was moved onto this same
  reentrant boundary, avoiding separate lock orders for foreground and Stop field updates.

The Ready child joins the PREPARE/recovery, claim, acceptance and cancellation cleanup it owns.
Initialization and APPLY are launched separately, as before. Their cancellation/join and physical
writer/provider retirement use the existing durable Stop and teardown flow; APPLY remains fenced by
`withReadyGenerationOperation`. This packet does not claim that the Ready child itself joins APPLY.

## Bounds and existing recovery

Activity callback work retains its existing 7,500 ms deadline. The providerless service startup
retains 3,000 ms when already Ready and 30,000 ms when awaiting Ready. Cancellation compensation
uses the existing 250 ms service cleanup bound. Physical deletion join retains the existing
configured quiescence timeout: a nonquiescent operation keeps deletion closed/retryable rather
than permitting erase. SharedPreferences publication still follows the existing noncancellable IO
commit behavior; no disk latency guarantee is asserted. Closed-gate or timed-out compensation leaves
the exact PREPARED receipt/descriptor to the existing startup reconciliation.

No Stop observer, coroutine context marker, general startup engine, persisted callback authority,
new database schema, CONTROL revocation, source shutdown policy, dependency, or UI repair was added.

## Authorship evidence and limits

`CASE_INVENTORY.json` lists the authored methods, all NOT_RUN. Tests invoke the actual durable
command store, real Default startup gate, actual acceptance coordinator, actual callback receiver,
actual effect drain, retry codec, and real service cancellation method with controlled dependencies.
The source tests cover the seven temporal scenarios, exact cutoff, stale process handoff, failed
acceptance, deletion join, duplicate callback fencing, mixed-purpose preservation, missing fresh
context, historical accepted-action acknowledgement, and successor shell protection.
The final-match collision test pauses the real service rollback, then checks through JVM thread
monitor identity that actual successor adoption waits on the same source-private monitor. It then
checks the successor's coupled fields. Additional cases retain partial APPLY and independent hosting;
Android foreground promotion/stopping and Room compensation remain controlled.

Room admission/acknowledgement, foreground promotion and Android stopping are controlled where
stated. These tests are not Hilt composition, migration, real provider, full service lifecycle,
battery, device, or convergence evidence. T02/T09 full chains and all 25 assembly prerequisites
cannot be marked proven from this packet.

## Composition boundaries

`TrackerServiceSourceSession.kt` contains only the selected providerless Off preparation condition;
Steps continuity has an overlapping hunk and root must compose the exact reviewed copies. Its
selected SHA-256 is `b3e0f97f79acbcc7b28b8c982e5ec4dc6aa867c3b1764a83af098eaecf279eff`.
Caller snapshot/guard changes may also overlap Steps authority work. Root must review that final
composition. `AuthoritativeSessionCoordinatorTest.kt` selects only the consumer signature matcher;
unrelated preserved broad edits are excluded. `WORKING_BEFORE` and historical draft metadata are
preservation evidence, never automatic implementation input. Only `FILES.json` identifies this
final proposed source selection.

## Review revision

`REVISION_BEFORE_OWNERSHIP_MONITOR` preserves the previously reviewed metadata and both changed
selected files. The fresh review identified the unchecked gap between final identity comparison and
unconditional rollback on different service/Android threads. The source-private monitor and exact
publication helpers repair that concrete gap; no new persisted state or state engine was introduced.
`OWNERSHIP_REVISION.md` records the final proposed mutation map and revision hashes.
