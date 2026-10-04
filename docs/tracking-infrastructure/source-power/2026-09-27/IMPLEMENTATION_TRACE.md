# Source-specific power implementation trace

## October 4 current-input correction

The actor-local-only description below is historical. Later commit `a2a4cddc91`
already saves one active request per session in existing local preferences. Keep
that implementation; a second Room checkpoint is not required just to persist
the limit. The current source trace is
`../../convergence/2026-10-04/RADIO_REQUEST_LIMIT_SOURCE_TRACE.md`. The independently
reviewed two-path repair in `RADIO_ORIGINAL_COOLDOWN_V2_SELECTED` now saves the
original interval in that ledger. Eight additional tests are authored, unexecuted;
complete source-power and provider evidence remain open. This correction does not
open validation.

Status: bounded independent slice IMPLEMENTED_UNVALIDATED; entire source-power item OPEN.
Base: codex/source-native-map-context-root at 815a4285b0658ef9dc8b3949ab667d364c4030c5.
Main dev/v10 remains 13f1a99871b59d29b3d059f374fc415490da6943.

Original task: **Implement source-specific power behavior.** Preserve cheap Steps continuity and
passive radio callbacks; enforce finite active radio budgets, cooldowns and request ownership
without replenishing budgets through reconfiguration.

## Implemented independent source slice

- SharedStepSourceController uses the tighter of requested hardware reporting latency and the
  actual demand limit. A broad useful floor cannot erase requested quality. Existing direct counter
  backend, count domain, native aggregate backend and checkpoint-only reuse remain preserved.
- Wi-Fi wakeup captures immutable registration, plan and earliest deadline. Execution checks all
  three against current ownership, reads live prerequisites including Doze, and uses the existing
  provider operation mutex. A stale wakeup reconciles current eligibility without deleting a valid
  successor's schedule. Passive broadcasts/admission remain independent of optional scan outcome.
- Android Wi-Fi broadcast does not carry app scan identity. Later or synchronously delivered
  results remain RECEIVE_TIME_ONLY with no invented request linkage or request configuration
  revision. Existing carrier authorization/physical registration remains present.
- Cell wakeup captures immutable callback context and earliest deadline. Final execution checks
  owner, plan, mode, API availability, prerequisite state, outstanding request and finite current
  allowance. The provider operation mutex serializes requests with lifecycle ingress fences;
  callbackLock is released before calling Android or acquiring the backend monitor.
- Only the exact Cell request callback closure may settle its request. Each actual registered SIM
  responds at most once within the original timeout. Passive, duplicate, foreign, stale and late
  callbacks still use normal observation admission but cannot settle another request. Timeout
  callbacks name the exact request and cannot clear a successor. No physical registrations now
  returns PROVIDER_FAILED instead of falsely reporting a requested refresh.

## Required test sources authored, not compiled or executed

- SharedStepSourceControllerTest: requested 5s report survives useful 300s floor; stricter actual
  5s demand still limits requested 60s batching.
- WifiSourceRuntimeTest: Doze entered after scheduling suppresses scan and preserves passive
  observations; old authorization wakeup cannot spend successor allowance; existing synchronous
  scan callback fixture expects truthful unlinked attribution and null configRevision.
- CellSourceRuntimeTest: queued request after passive downgrade denied while physical callbacks
  retained; passive result cannot settle request; duplicate wakeup cannot spend twice; Android
  request can synchronously wait for a callback on another thread without holding ingress lock.
- CellRefreshResponsesTest: exact SIM set, duplicates, foreign SIM, immutable deadline, default
  modem scope and empty scope.
- CellSourceBackendTest: no registered physical callbacks cannot claim a requested refresh.

Existing SourcePlan fingerprint and StepSourceRuntime compatible-refresh code already exclude
checkpoint timing from physical counter ownership. Existing resolver source cases preserve cheap
Steps and passive radio callbacks under power pressure. This is source trace, not device evidence.

## Outstanding and dependent work

- Confirm RECOVERY_CONTRACT_DECISION.md. There is no existing versioned radio power checkpoint
  contract. No proposed persistence contract or transaction has been applied.
- Replace actor-local per-registration budgets with authenticated logical-intent reservations;
  preserve spent allowance across mode/profile/authority/SIM/physical reconfiguration and recovery.
- Carry source-wide cooldown and original backoff deadline across intent transitions, cleanup,
  process restart and boot ambiguity. Current nextAttemptDeadlineMs still recomputes relative
  backoff and actor shutdown still loses timing; this slice does not claim to fix those defects.
- Apply 60s desired freshness separately from 10min useful item admission for adopted profiles,
  retaining exact legacy/custom behavior. Current qualified-evidence suppression remains legacy.
- Author durable reservation/recovery/adaptation/cleanup contract and Room regression cases, then
  independent static review of the entire source-power task.
- Shared per-item radio contract and aggregate ambient Steps proof questions remain separate.

Mandatory phase boundary: implementation/source review only. No Gradle, compilation, test, lint,
Detekt, Room generation/drift, diff checks, device/emulator/UI/battery, CI, release or rollout gate
was executed. No activation, push or new dev/v10 integration. Protected root files and other drafts
remain untouched. Written tests are not passing evidence.
