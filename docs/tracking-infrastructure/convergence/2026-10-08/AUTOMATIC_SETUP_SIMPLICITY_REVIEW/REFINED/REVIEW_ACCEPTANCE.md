# Refined shared setup: independent architecture acceptance

## Exact reviewed input

- Current committed source: `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`.
- Proposal SHA-256: `22bf2815010b63ec8b42b96b05867ee06e5b293b06b866d2806ae2454c93bcd2`.
- Source pins SHA-256: `2e883863e0e46a023e50ecbd08bf95de889de8954a0702edbe266622baedfd68`.
- All 37 proposal source pins were independently compared with `git show` of that exact commit and the frozen current input copies; they match. Supplemental committed deletion, Pressure projection and automatic outbox files are recorded separately in `SUPPLEMENTAL_PINS.json`.
- `PROPOSAL.md` is byte-identical to the updated parent `PASSIVE_LOCATION_AND_AUTOMATIC_PROPOSAL.md`.

Original full human messages, follow-ups and approvals were inherited. This is a fresh source-only review against the prior five findings, not an implementation or runtime acceptance.

## Disposition

**ARCHITECTURE_SUPPORTED_FOR_SINGLE_HUMAN_APPROVAL.** The refined proposal addresses R1–R5. No additional architecture blocker was found in the inspected source. Production authorship must still implement and independently review the concrete predicates below. The setup mechanics are PROPOSED_NOT_IMPLEMENTED and await the human answer; the supported feature behavior was already approved. All 25 full-chain prerequisites/T25 remain open.

### R1: exact completed-clear reset

The proposal now composes all known source resets under the actual deletion operation/epoch and publishes the single WRITERS_REARMED phase only after that composition. It explicitly discloses the narrow known contained5→final4 reset across a proven new epoch, instead of pretending empty lanes are a fresh install or using the generic dynamic rearm that needs deleted predecessor lanes. Existing original command epochs, deleted WAL high-water, removed session/caller/registration authority, acknowledged physical writer shutdown and retained import/replay fences provide the necessary independent boundaries for reusing the fixed final contract.

Implementation acceptance requires first/repeated/interrupted clear with actual stale commands for each source; ordinary same-epoch owner CAS must stay monotonic. Epoch and retainedFromMs are distinct fields with different units: compare each against the matching deletion/lifecycle field, never compare an epoch number with a time floor. Unknown owners remain preserved and source-contained; they cannot be rebased as a recognized owner or silently become a healthy source. The genuine full-clear phase must not be published early by the present Steps receipt wrapper, nor call startup recovery while the deletion barrier is closed.

### R2: canonical Location setup and ambient recovery floor

The new canonical publication removes the nonexistent protected shadow drain. An existing real canonical interval still drains its exact cutoff before retirement. First use or a precisely settled predecessor publishes the final protected Location lane under the existing owner1/fences; no second writer or destination is introduced.

The refined original-ambient recovery floor fixes the important caveat in direct high-water setup: lack of captured authority does not mean independently authorized ambient WAL was already saved. Find the earliest genuinely unsettled current-epoch retained ambient original in a bounded source-owned read, then let the existing canonical handoff/qualifier/ambient writer settle it. Preserve original IDs/times/consent and actual receipt dedup. Do not borrow future capture authority, inherit an old captured interval, or manufacture observations/cursors. If the old inert interval has captured or ambiguous evidence, retain its pin and source repair debt. An empty retained payload range is sufficient only with its actual existing lifecycle/deletion/continuity authority, never inferred from missing rows alone.

This is a narrow one-time setup/recovery branch. Use the existing WAL/receipt keys and finite high-water machinery; no raw inbox, second cursor store or Location shadow engine is needed.

### R3: shared Location owner predicate

Explicitly recognizing the known Location projection/binding/mask and EXISTING_LOCATION_CANONICAL_PIPELINE owner1 in shared rollout admission fixes the existing fall-through true. Current acquisition readiness and original retained-data eligibility remain separate. New commands/manifest/receipts must carry the actually selected exact binding; original generation1 data must not be relabelled generation2.

### R4: lock ordering

The corrected proposal acknowledges the existing same-gate ReadyGenerationOperationContext nesting. Ordinary setup stays outside initialization/manual preparation's held handoff and uses the established Ready operation → lifecycle lease → Room ordering. Recheck generation/command authority before subsequent handoff. Do not enter reconcile recovery or acquire another lifecycle lease under a held Ready/lifecycle operation after Stop/deletion changes state.

Moving pending manual consumption after actual preparation must not move preparation into `withCurrentStart`'s non-reentrant command gate. Existing `TrackerServiceApi.dispatchPreparedStartResult` prepares first and acquires that gate only for platform enqueue; preserve that ordering. If End or a source change wins after Room preparation, compensate the exact prepared token rather than restoring or reserving another Start.

### R5: pending setup event and original manual Start

The proposal now names the actual product-lane/cursor table invalidation, lifecycle/callback completion and initial reopen/Retry read. A cursor update is a real durable event; a process initialization flag is not completion. While behind, evaluate Pending without taking a transition lease on every WAL callback. Attempt activation only when a changed candidate satisfies its exact readiness predicates, with one in-flight attempt and bounded reload on a race. Prefer a compact immutable Flow snapshot plus distinctUntilChanged to a new registry of fingerprints or scheduler.

Pure readiness display remains read-only. Reserve the user's original manual request on the action path, retain it through SetupPending, then consume only after actual preparation. Actual prepared-start receipts and command selection serialize dispatch. End/Off/clear/app stop/changed selection cancel the original request. Healthy accepted siblings retain existing partial-source behavior; source-specific setup debt does not close Room/history globally. Real shared storage failure and cancellation stay honest.

## Automatic context: finite lifetime verified

`ActivityAutomationOutboxDispatcher.kt:406-438` binds FreshTransitionCallback to only the exact ordinals of the currently executing callback and rechecks the original observed/received times. `TrackerServiceApi.kt:80-97,942-944` defines the enqueue deadline as the earliest of the original trigger expiry, observation+60 seconds and callback receipt+7 seconds. The 60-second trigger lifetime alone is not a background enqueue permit.

The current automatic outbox consumer expires reserved/retryable actions reached by DURABLE_REPLAY (`ActivityAutomationOutboxDispatcher.kt:481-490`); ordinary source recovery uses Permit.None (`SourcePipelineRecovery.kt:499-510`). The refined setup must retain those rules. Room setup completion may prepare writers, but cannot save/cache a callback permit for application-owned replay, replace original observed/received times, recreate an expired trigger, or reserve a new later Start from it. A retry in the same still-live callback reuses exact original action/evidence/command and must recheck the deadline before and after setup/preparation and immediately before Android enqueue. Once that callback returns or its deadline expires, source setup may finish independently and a future genuine trigger can start normally. Manual pending requests have their separately approved continuation lifetime.

## Necessary source tests and final implementation review

The proposed test plan includes the concrete normal-user startup/Start wiring, original/final contracts, partial-source failures, old history, cutoff/ambient recovery, exact/repeated clear, stale epoch commands, nested gate/Stop races and pending cursor completion. Add actual callback permit tests: pending setup completes within the original live window; completes after7 seconds but within trigger60 seconds; callback returns before readiness; app reopen uses Permit.None; changed command/epoch/selection supersedes the trigger. Only the first still-current live callback case may enqueue. No fixture should replace original times with the test's current clock to make replay pass.

These tests are NOT_RUN in this phase. Final source review must inspect the assembled real caller composition and actual stale-command/clear fixtures, not just the proposal or isolated always-Ready stubs.

## Scope / simplicity

Keep one chosen-source setup operation, fixed recognized contracts, existing transition/store/lease/recovery seams and the existing pending manual request. No general upgrade engine, extra database, journal, generation registry, raw queue, new provider owner, portable file shape or user-facing developer rollout setting is needed. Activity CONTROL alone never authorizes captured Activity. Optional Location never becomes demanded for radio enrichment. The optional retained passive-delivery gap store remains separate and unapproved.
