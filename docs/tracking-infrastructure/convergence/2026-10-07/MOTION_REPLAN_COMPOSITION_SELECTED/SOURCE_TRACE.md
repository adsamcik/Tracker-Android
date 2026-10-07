# Admitted motion to adaptive collection - source trace

Exact input: `7f72b31259cf67080f75690d0980a7060ec8ce21`. Phase: **IMPLEMENTATION_ONLY**. Test execution: **NOT_RUN**.

## Existing production composition

No missing production connection was found in this trace. This slice adds composition test source and reuses the existing connections:

- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/DurableSourceEventSink.kt:85`: `private suspend fun admitAndDrain(candidate`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/DurableSourceEventSink.kt:106`: `private fun onDurableMotionEvidence`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/DurableSourceEventSink.kt:125`: `private suspend fun admitAndDrain(delivery`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/RoomActivityRecognitionEventIngress.kt:297`: `completion.first.forEach`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionController.kt:37`: `fun configureScope`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionController.kt:89`: `fun onDurableEvidence`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionController.kt:120`: `fun tick`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt:1240`: `private fun observeCollectionMotionPolicy`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt:2340`: `motionProfile = collectionMotionController`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt:1314`: `private suspend fun reconcileLocationMotionScope`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/TrackerServiceSourceSession.kt:508`: `suspend fun reconfigure(inputs`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/TrackerServiceSourceSession.kt:1442`: `val resolved = planResolver.resolve`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/TrackerServiceSourceSession.kt:636`: `val firstAttempt = coordinator.reconfigure`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePlanResolver.kt:164`: `val candidate = if (!hardBlocked`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePlanResolver.kt:168`: `desired.balanceProfileVersion != null`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/AmbientLocationPurposeOwner.kt:140`: `suspend fun refreshActivePlan`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/AmbientLocationPurposeOwner.kt:236`: `motionProfile = motion.policy`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedStepSourceController.kt:35`: `private val unboundSink`.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedLocationSourceController.kt:37`: `private val unboundSink`.

The physical shared Steps/Location owners use the unbound durable sink. Single-item accepted/duplicate admissions publish live motion only after a durable handoff, with per-source chronology suppression. Atomic delivery publishes only the admitted unit indexes. Activity callback ingress separately publishes the writer-reloaded rows after its recovery completion. The controller requires an active collection scope and the matching boot, rejects pre-scope or stale observations and regressing same-source chronology, and additionally checks session/run identity for its legacy session-bound scope. Sessionless collection scopes intentionally accept independently admitted source hints. Radio and Pressure do not manufacture primary motion.

TrackerService subscribes to only the profile fields that can change collection, using distinctUntilChanged. It refreshes the existing ambient Location owner and calls the existing live source-session reconfigure when a session remains active. The existing policy tick advances expiry and power/thermal/permission reevaluation; this packet creates no wakeup or timer. Profile values are included in the current plan fingerprint, making equal input a no-op.

The semantic factory and resolver preserve participation. The adaptive branch changes participating Location cadence and radio active/passive modes, preserves adaptive Steps request values, keeps independent direct demand floors and blocks missing prerequisites. The actual authoritative lifecycle coordinator and physical owners remain responsible for exact provider retirement/application and radio request budgets; this test fixture does not replace those responsibilities.

## New authored composition cases

Seven new methods compose the real durable sink, controller, StateFlow profile selection, TrackerServiceSourceSession, policy/plan Room storage and SourceRuntimeRegistry. Recording owners receive the exact typed plans sent through the registry. The fixture covers Steps, Location and Activity restoring live plan targets from stationary; rejected/revoked, old duplicate, stale and other-boot hints; provenance-only changes and repeated equal input; disabled sources; and Location permission loss preserving radio/Steps sibling requests.

The tests use fixture admission outcomes and a fixture durable lifecycle authority, then record registry dispatch. They do not claim real Room ingress authorization, actual TrackerService collector execution, Android callbacks, a physical Steps listener baseline, durable radio budget preservation, physical provider failure recovery, battery use or device proof. Those remain covered by their source-specific work and final convergence/device gates. The Activity ingress writer-reload/source-purpose cases are separately inventoried existing source tests, not counted among these seven.

## Disposition

- Production files: unchanged.
- Shared working drafts and six protected files: unchanged.
- New test exists only in this selected packet, ready for root review and exact staging.
- All broad completion prerequisites remain open. Written source is not a passing check.
