# Independent review: shared source setup proposal

## Scope and evidence

Review-only static inspection of committed candidate `ab25a6dbfaab82d838a22ed97099cdd92bf2a2e7` and proposal SHA-256 `e07aadf5507ab4c8157f6d1de5a5af77cbc08c5d2b0144bbc39a86a1a7d0485b`. Original user messages, corrections and settled approvals were inherited. Exact committed files and proposal are preserved in `INPUTS/`, `PROPOSAL_INPUT.md` and `INPUT_PINS.json`.

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** No production edits, provider or writer activation, Git mutation, build, compilation, tests, lint, schema, diff-check, emulator/device, battery or CI execution. No recursive delegation. This is architecture review, not implementation acceptance or T25 completion. The separately reviewed passive cap packet and unapproved delivery-gap store are outside this review.

## Conclusion

One shared chosen-source setup caller and exact prospective manual/automatic contracts are a suitable small completion of the existing architecture. Preserve generation-1 contracts and retained products; do not introduce a new upgrade engine or general future-generation declarations. The proposal needs the concrete corrections below before it is a complete implementation design. None requires asking again whether automatic tracking should support the chosen sources: that behavior is already approved.

## Confirmed missing normal-user caller

Committed production call searches for `installInertCandidate`, `installInertShadowLane`, `activateCandidate`, and `rearmInertShadowLane` in `app/src/main`, `tracker/engine/src/main` and `core/base/src/main` find only transition declarations/wrappers and the transition implementation itself. No startup or Start entry point installs and activates the capture lanes.

- `SourcePipelineRecovery.kt:346-366` recovers released history, registers ambient radios, and requests existing drains; it does not perform capture setup.
- `TrackerModuleInitializer.kt:103-122` reconciles policy/purposes, locks and Activity CONTROL; it has no capture setup call.
- `TrackingRolloutStateStore.kt:382-395` initializes missing/unknown rollout to all sources contained.
- `DefaultTrackingStartRequestCoordinator.kt:297-313` loads and intersects that rollout, then rejects an empty capture selection.
- `BackgroundTrackingApi.kt:373-380` computes the automatic mask from the same rollout and suppresses a zero mask.
- `TrackingRolloutStateStore.kt:282-289` lacks Location and prospective automatic contracts for Activity/Pressure/Wi-Fi/Cell. Protected Location's own writer exists, but this cannot bypass the admission/readiness path.

The dormant setup is intentional during this phase. It is incomplete final production wiring; implementing its caller does not authorize invoking it here.

## Required design corrections

### R1 — Clear all must have an exact rearm path for every final source

**Blocking design gap.** `AppDatabase.kt:1238` deletes all product lanes while `SourceDestinationOwnerEntity.kt:6-12` deliberately retains destination owners. `InfrastructureModule.kt:243-248` runs only Steps rearm after the database clear. A final Activity/Pressure/Wi-Fi/Cell owner4 therefore survives with no lane. The proposed initial legacy1/absent case rejects it, and its retired-gen1 case has no predecessor row to inspect.

The generic `rearmAfterFullDeletion` is not an existing working substitute: `SourceSpecificWriterTransitionCoordinators.kt:671-681` requires a preserved retired canonical lane, while the actual full clear deleted it; it also requires a dynamic support declaration and production has only the empty `@Multibinds` set (`SourcePipelineModule.kt:167-171`).

**Smallest correction:** include a dedicated exact completed-clear branch in the shared setup/full-clear composition, using the existing deletion operation and collected-data epoch. Prove the matching clear receipt and absence of old source data/callers, then prepare the final known contract under existing stop/deletion fences. Do not infer a clear from an empty lane table, reset a surviving owner as if it were a fresh install, or invoke the dynamic arbitrary-generation path. Keep this separate from the non-erasing generation-1 handoff. The plan must specify the exact owner/epoch transition for first and repeated clears before authoring it. Location retains its established owner1 and validates the new epoch/lane, rather than adopting 2N owner arithmetic.

### R2 — Protected Location cannot currently drain an inert shadow

**Blocking design gap.** `ProtectedLocationCanonicalHandoff.kt:217-226` resolves the lane before draining; `resolveLane:1131-1132` rejects every noncanonical stage. Recovery routes Location only to that handoff. Installing an inert Location shadow and waiting for its cursor to reach the moving global WAL high-water can remain pending indefinitely after any later admitted WAL row.

**Smaller recommended architecture:** for first use or an exactly settled retired predecessor, install/publish the final protected Location canonical lane atomically at the existing WAL high-water under the existing Ready/lifecycle lease, idle/callback fences, same owner1 and registration-collision checks. There was no prior captured Location authority to replay in that new interval. Its existing ambient data must remain independently materialized and qualified. Continue to drain a real old canonical lane through its persisted cutoff before retiring it.

If retaining an inert Location shadow is necessary, explicitly implement a small product-inert classifier for its interval before claiming it can drain; do not create a second Location writer/fact pipeline or silently advance an interval that may contain captured products. The proposal's old-shadow case likewise needs an actual classifier or a concrete proof that no captured interval exists. The current protected drain is not that proof.

### R3 — Registering Location also requires the shared owner check

`TrackingRolloutStateStore.kt:867-938` checks exact destination ownership for the other captured sources but currently returns `true` for Location through the final `else`. Once the catalog registers Location, add its explicit exact established projection/owner1 branch. Otherwise rollout/admission can advertise and acquire a Location source whose later protected writer refuses to save. This is routine implementation of the proposed same-owner representation, with no new storage.

### R4 — Correct the reentrancy explanation and maintain one lock order

The proposal's categorical statement that a nested Ready operation takes the same non-reentrant lock is inaccurate. `DefaultTrackingStartupGate.kt:358-375` already recognizes `ReadyGenerationOperationContext` for same-gate nesting, and `reconcile:433-437` returns immediately when Ready. Thus ordinary same-generation nesting is not by itself a deadlock.

The cautious ordering remains appropriate: perform shared setup after startup/recovery authorization and before the existing initializer handoff, and before manual prepare's Ready-operation block (`DefaultTrackingStartRequestCoordinator.kt:144`). Recheck generation/command authority for the later handoff. If Ready has closed or Stop changes during nesting, reconcile may enter recovery (`DefaultTrackingStartupGate.kt:521`), which does take the underlying mutex; the lifecycle lease is also not generally reentrant. Avoid initiating recovery or acquiring another lifecycle lease inside a held operation. Prefer the existing external transition entry points and one stable lock order over adding another within-held-operation API solely to work around this.

### R5 — Specify how pending setup progresses without losing a Start

The proposal rightly does not poll or block all storage readiness, but a one-shot setup call can install a shadow, request a drain and return `SHADOW_CURSOR_BEHIND`. Existing source recovery only requests/drives drains; it does not rerun setup. State the exact existing recovery/readiness event or Start retry that completes setup once the cursor advances. A busy or ordinary drain delay should not become a permanent unavailable label or consume an otherwise unchanged one-use Start. Keep bounded retry/debt and cancellation through the existing command/readiness machinery; no per-callback transition loop, new journal, or global all-source wait is needed.

## Suitable small final shape

1. One source-selection setup operation, reused by initialization and Start. It resolves the sources required by chosen supported capture and all-day purposes; setup itself does not grant consent, request permission, or create sensor demand. Activity CONTROL alone must not install or authorize captured Activity.
2. Per-source exact state branch: already-final canonical is read-only Ready; no prior data uses the known final first-use contract; old canonical waits for normal End and drains its cutoff; old inert/retired state uses the precisely proven handoff; completed clear uses R1's separate epoch-bound branch.
3. Activity/Pressure/Wi-Fi/Cell retain immutable generation1 manual and prospective generation2 manual+automatic contracts, existing writer IDs/versions and original fact semantics. Existing retained readers/maintenance/export recognize the original exact contract instead of current owner state.
4. Protected Location keeps its existing physical owner, writer, canonical point storage and owner generation1. Its exact original lane/manifest/receipt binding separates old/new intervals. Use R2's atomic final setup where no old captured interval exists.
5. Chosen sources are attempted independently. Catch source-owned unavailable/retryable debt per source, preserve healthy sibling results, and propagate cancellation/global storage failure honestly. Ready data readers and saved history are not held behind all setup results.
6. Reuse exact state/cursor/owner/revision CAS and existing recovery signals. On an ordinary race, reload durable state once or return existing retryable debt. Do not add a second registration/activation engine, a saved mode flag, or a user-facing developer rollout control.
7. Automatic availability uses actual accepted chosen capture readiness plus Activity trigger/platform requirements. Preserve source selections and truthful existing repair states; containment/setup debt is not a missing permission.

## Approval boundary

Already approved: manual/automatic/all-day supported behavior, independent sources, one shared collection flow, preserve all recorded history across quality/End/Off, retained generation-1 data, stop/permission/deletion fencing, source-local recovery, and no data erase to enable automatic tracking.

The genuinely new architecture choice is the shared chosen-source setup caller plus the exact non-erasing static contract handoff and protected Location setup representation, amended by R1/R2. Confirm those together once in plain language. Do not ask the feature question again, ask for a general upgrade engine, or present data erasure as a reasonable alternative when the user already required preservation. A separate retained passive-delivery gap store remains a separate unapproved choice.

## Necessary test-source coverage for the corrected design

All remain **NOT_RUN** in this phase; this review authored no production tests.

- Real production setup composition: fresh database and released-main upgrade, initialization plus each source-only manual/automatic Start, then representative subsets. Provider activation is deferred; use actual source lifecycle/Room seams with explicit provider acknowledgements.
- Known generation1 canonical/shadow/retired and exact final states; preserved generation1 reads/deletes/exports after handoff. Unknown/mismatched states preserve bytes and contain only the affected source.
- Real startup gate nested-operation context, Stop/deletion race, lifecycle busy, concurrent initialization/Start, revision conflict and cancellation. Do not claim a fake always-Ready gate proves production lock behavior.
- Pending shadow drain advances, then setup and the unchanged original Start complete through the specified recovery path; no unbounded polling or duplicate command.
- Location final first use and old-canonical cutoff drain with WAL from other sources/all-day Location arriving during setup; no dead inert shadow, no invented owner generation, exact catalog owner check.
- Clear all after every source's final activation, resume after clear, repeated clear, interrupted clear/reopen and stale callback/new epoch rejection. Existing independently chosen history/consent semantics remain distinct; a missing lane alone never proves clear.
- One failed/poisoned source with healthy siblings, actual accepted automatic mask and UI readiness agree, CONTROL does not become Activity capture, no implicit Location demand for radio, no duplicated physical owner.

## Review disposition

**PROPOSAL_REQUIRES_CORRECTIONS; architecture direction supported.** R1 and R2 need a concrete corrected design; R3 is required companion wiring; R4 corrects an overstatement; R5 needs an explicit existing completion path. No runtime correctness, tests, activation authority, integration readiness or assembly completion is claimed.
