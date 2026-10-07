# Start caller chronology

Phase: IMPLEMENTATION_ONLY. Source trace only; no execution evidence.

## Fresh automatic callback

1. `ActivityReceiver.onReceive` reads the existing command generation synchronously before launching
   asynchronous startup reconciliation. `parseDelivery` keeps it as nullable ephemeral batch context.
2. `RoomActivityRecognitionEventIngress` independently qualifies and durably admits capture/CONTROL
   evidence. Only LIVE callback drain receives that original generation; historical evidence is not
   removed merely because its automatic Start becomes stale.
3. `SourcePipelineRecovery.drainCommittedActivityCallbackWork` holds its existing effect mutex inside
   cancellable Ready work, not the Start/Stop mutex. It projects committed evidence, creates the
   existing FreshTransitionCallback permit, and preserves the original field across a cold wait.
4. `ActivityAutomationOutboxDispatcher` resolves the exact eligible callback effect. Its sole
   `BackgroundTrackingActivityAutomationEffectConsumer` forwards the original generation through
   `BackgroundTrackingApi.handleDurableActivityEvidence`'s existing request lambda. The two private
   Activity/transition update paths only invoke this lambda; they do not directly reserve Start.
5. `TrackerServiceApi.startServiceAndAwaitEnqueueResult` requires original generation for automatic
   triggers. Under the existing command mutex, `reserveStartIfGeneration(generation, trigger)` checks
   the unrefreshed generation, Off, current boot, and factual latest Stop cutoff. Missing original
   context and stale reservation return terminal outcomes before PREPARE or Android enqueue.
6. Existing PREPARE persists the session/run/token envelope, then the API makes its bounded platform
   enqueue. No extra source consent or capture is inferred from CONTROL.
7. `TrackerService` enters bounded providerless startup, registers Ready service work, and obtains
   the exact persisted claim. `acceptForeground` rechecks the reserved command under the command
   mutex, promotes the allowed foreground type, directly acknowledges Room, then durably accepts.
8. Existing APPLY rechecks its exact source/caller/session authority. End/Off invalidates the original
   reservation or final acceptance. Delayed evidence observed before End but first delivered after
   End is rejected by its original elapsed timestamp, even if the receiver sees the newer generation.
   Initialization/APPLY are separate children cancelled/joined by the existing durable Stop and
   writer/provider retirement flow; the Ready callback/service child does not itself join APPLY.

## Manual Start and permission continuation

The original one-use selection receipt is reserved in the existing command store. Its saved process
marker uses the existing process identity: current-process screen handoff may consume it; a recreated
process cannot. End, Off, changed selection, or a newer command invalidates it. Source-owned caller
snapshot ties preparation to the exact STARTING run, boot, live lease, source manifest and command.

If collection is Off, `prepareCollectionResumeForManualStart` grants only the exact delivery exception.
It does not open all-day collection or APPLY. The service uses the same acceptance flow; durable Off
is released only after successful platform/Room acceptance. All-day owners reconcile outside the
command mutex and only while that exact accepted command remains current. Failure retains Off.

## Replay, redelivery, and cancellation

- Retry-spool encoding does not store the live generation. Decode cannot restore it; durable replay
  cannot manufacture a FreshTransitionCallback start permission.
- An already accepted historical Activity action may acknowledge its outbox using existing exact
  lifecycle intent reconciliation. It never requests another session.
- Queued/Boolean and awaited automatic compatibility APIs also require original callback generation;
  API-entry reads never replace the original receiver capture. Manual/recovery callers keep their
  existing authority-specific path.
- Passive Android redelivery is independently resolved by the existing exact token/run/command
  flow. Off does not restore a pending delivery exception. A successfully rebased Android-owned
  successor retains its own lifecycle even if the predecessor coroutine is cancelled.
- Clear cancels the registry's actual child. Its catch performs existing bounded exact compensation,
  atomically rechecks runtime identity and releases only its old shell, and its finally revokes only its old pending
  Off exception. The child does not complete before this cleanup. The outer catch covers cancellation
  before Ready registration, and a repeated exact cleanup cannot release a successor.
- The same source-private monitor protects synchronous successor adoption, exact acceptance fields,
  APPLY flags, descriptor publication and shell release. It is never held across compensation/Room
  awaits. A partial APPLY retains its receipt until the existing physical Stop/init join finishes.

## Unchanged physical acquisition boundary

The providerless Off exception does not grant acquisition. Generic caller admission, process recovery,
automatic CONTROL, ambient demand and active APPLY remain strict. Service foreground hints remain
non-authoritative hints; persisted source envelopes and actual legal foreground type determine capture.
