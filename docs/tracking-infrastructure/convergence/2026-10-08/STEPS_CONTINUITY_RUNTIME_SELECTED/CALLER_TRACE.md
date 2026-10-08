# Connected caller trace â€” static only

- DI SourcePipelineModule supplies SharedStepSourceController as the Steps runtime; StepSourceRuntime remains its physical owner. Module graph/build dependencies/manifests are pinned.
- TrackerServiceSourceSession.reconfigure â†’ existing SourcePlanResolver/SemanticAcquisitionPlanFactory â†’ AuthoritativeSessionCoordinator.reconfigure.
- Coordinator before Room â†’ SharedStepSourceController.continuingRecording â†’ StepSourceRuntime.continuingRecording.
- Coordinator buildManifestDraft within existing Room/operation lease â†’ SourceBroker.continuingStepsOriginInTransaction â†’ unchanged StepsRecordingLifetime helper and actual receipt/registration DAOs.
- GuardedSourceCallerDemandDispatcher accepts fresh latest source identities with ExactSourceCallerGuard â†’ SourceBroker.replaceSessionDemandsInTransaction retaining only original live Steps demand â†’ existing authorization rotation sees unchanged Steps member fingerprint.
- Coordinator reconcileReconfigureActions (all three actual reconfigureSource branches) â†’ SharedStepSourceController.reconfigureWithContinuity â†’ StepSourceRuntime.reuseContinuingRecording with current Room ownership recheck. No provider work inside Room.
- StepRegistrationListener â†’ existing callback gate/lane/consumeOne â†’ unchanged accumulator preview â†’ real atomic step ingress/WAL/checkpoint â†’ commit under existing callback lock â†’ StepsSessionFactProjectionLane writer. Marked windows use source-local origin bounds; ordinary null-origin data retains its original checks.
- Receipt propagation/retirement in TrackerServiceSourceSession recognizes RetainedForSteps; actual End/Off still retires all session references and demands.
- Shared quiesce â†’ existing physical cutoff/flush/barrier/retirement/completeness flow. Latest operation claim owns cleanup, original recording owns observed-time data. The old registration/authorization is not relabeled with the latest manifest.
- BackgroundTrackingApi legacy Steps CONTROL removal may inspect the still-live recording but cannot invoke a new acquisition from its old origin. Generic Shared start/reconfigure remains blocked for a current continuing owner.

Locks: shared-owner mutex â†’ physical lifecycle mutex; short callback monitor protects object publication/baseline commit. Provider preflight/apply happens outside Room; Room reads the immutable snapshot and never acquires a runtime lock. The actual apply authority check is awaited without holding the callback monitor. Original callback sink is the existing unbound shared-owner sink.

V2 recovery caller: ActiveTrackingSessionCallerAuthorityReconciler.reconcile first authenticates current Room reference and successfully replays only that fresh successor, then treats RetainedForSteps alongside Completed to settle exact descriptor debt. Its failureCode exhaustive mapping treats both as nonfailures. Original receipt is not replayed or used for provider startup.
