package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.PreviousExitSourceSessionFinalizer
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartServiceValidation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainSignal
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectConsumer
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectValidator
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.OwnedSourceShutdown
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritativeSessionCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: FakeStepsRuntime
	private lateinit var locationRuntime: FakeLocationRuntime
	private lateinit var subject: AuthoritativeSessionCoordinator
	private lateinit var policy: RoomSourcePolicyRepository
	private lateinit var activityAutomationDrainSignal: RecordingActivityAutomationDrainSignal
	private lateinit var activityAutomationEpochAuthority: ActivityAutomationEpochAuthority
	private lateinit var leaseClock: FixedClock
	private lateinit var rolloutSnapshot: TrackingRolloutState
	private var currentBootId = "boot-1"
	private var activityLocked = false

	@Before
	@Suppress("LongMethod") // Shared exact-authority fixture for the coordinator test matrix.
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		activityLocked = false
		currentBootId = "boot-1"
		rolloutSnapshot = fixedEventRollout()
		leaseClock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000_000L)
		database = AppDatabase.testDatabase(context)
		policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 1_000_000, 1_000)
		}
		runBlocking {
			policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
			database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 0L))
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
		}
		runtime = FakeStepsRuntime(database)
		locationRuntime = FakeLocationRuntime()
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.admit(any()) } returns
			AdmissionResult.Admitted(SourceEventId("test-admitted-event"), 1L)
		coEvery { ingress.committedBatch(any(), any()) } returns emptyList()
		val dispatcher = ProjectionDispatcher(database, emptySet())
		activityAutomationDrainSignal = RecordingActivityAutomationDrainSignal()
		val lockManager = mockk<LockManager>(relaxed = true)
		every { lockManager.isLocked } answers { activityLocked }
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		activityAutomationEpochAuthority = ActivityAutomationEpochAuthority(
			database,
			context,
			lockManager,
			leaseClock,
			BootClockDomainProvider { currentBootId },
		)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, dispatcher),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			rolloutStore = fixedEventRolloutStore(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `session is durable before source start and closes after source drain`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		runtime.stateObservedAtStart shouldBe SessionLifecycleState.STARTING.name
		runtime.manifestRevisionObservedAtStart shouldBe 1L
		runtime.intentDesiredStateObservedAtStart shouldBe LifecycleDesiredState.ACTIVE.name
		runtime.actionStatusObservedAtStart shouldBe LifecycleActionStatus.APPLYING.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe started.serviceRunId
		val manifest = requireNotNull(database.sourceSessionDao().manifest(started.logicalTrackingId, 1L))
		manifest.serviceRunId shouldBe started.serviceRunId
		val stepsBinding = database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L).single()
		stepsBinding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		stepsBinding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		stepsBinding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		stepsBinding.writerProjectionId shouldBe null
		database.sourcePlanStateDao().latestRevision()?.status shouldBe DesiredPlanStatus.EFFECTIVE.name
		val activeDemand = database.sourceBrokerDao()
			.currentDemands("session:${started.logicalTrackingId}")
			.single()
		activeDemand.sourceKind shouldBe SourceKind.STEPS.stableCode
		activeDemand.purpose shouldBe SourceBrokerPurpose.SESSION_CAPTURE
		activeDemand.manifestRevision shouldBe 1L
		activeDemand.status shouldBe SourceDemandEntity.STATUS_ACTIVE

		val stopped = subject.stop(
			SessionStopRequest("test-owner", "manual", 2_000, 2_000_000, "boot-1", perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.finalAdmissionOrdinal shouldBe 0L
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe null
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.appDrainComplete shouldBe true
		database.sourceBrokerDao().currentDemands("session:${started.logicalTrackingId}") shouldBe emptyList()
		database.sourceBrokerDao().demandHistory("session:${started.logicalTrackingId}")
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		runtime.closed shouldBe true
	}

	@Test
	fun `manual Pressure manifest records the exact legacy destination owner`() = runTest {
		val prepared = subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "legacy-pressure-logical",
				serviceRunId = "legacy-pressure-run",
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("legacy-pressure-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		binding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
		binding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
		binding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		binding.writerProjectionId shouldBe null
	}

	@Test
	fun `manual Pressure candidate manifest records only the exact official writer binding`() = runTest {
		val expected = installPressureCandidateRollout()
		val prepared = subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "candidate-pressure-logical",
				serviceRunId = "candidate-pressure-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("candidate-pressure-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		binding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
		binding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
		binding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		binding.writerProjectionId shouldBe expected.projectionId
		binding.writerProjectionVersion shouldBe expected.projectionVersion
		binding.writerBindingGeneration shouldBe expected.bindingGeneration
	}

	@Test
	fun `Pressure generation 1 candidate cannot be attributed to an automatic session`() = runTest {
		installPressureCandidateRollout()
		val trigger = automaticTrigger()

		subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "automatic-pressure-rejected",
				serviceRunId = "automatic-pressure-rejected-run",
				rolloutRevision = rolloutSnapshot.revision,
			).copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-pressure-rejected-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("EVENT_CAPTURE_MODE_NOT_REACHABLE")

		database.sourceSessionDao().session("automatic-pressure-rejected") shouldBe null
		database.sourceBrokerDao().currentDemands("session:automatic-pressure-rejected") shouldBe
			emptyList()
	}

	@Test
	fun `stale rollout loaded before lease cannot create a new service run`() = runTest {
		database.trackingRolloutStateDao().save(
			fixedEventRollout().copy(revision = 2L).toEntity(updatedAtMs = 2L),
		)

		val rejected = subject.start(startRequest())
			.shouldBeInstanceOf<SessionStartResult.InvalidRollout>()

		rejected.code shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		runtime.startCount shouldBe 0
	}

	@Test
	fun `fresh Android preparation revalidates rollout before creating its service run`() = runTest {
		persistRolloutRevision(2L)

		val rejected = subject.prepareAndroidStart(
			startRequest(),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("stale-fresh-preparation"),
				1L,
				true,
				false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Rejected>()

		rejected.failureCode shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		runtime.startCount shouldBe 0
	}

	@Test
	fun `direct recovery revalidates rollout before creating its replacement run`() = runTest {
		subject.start(
			startRequest().copy(logicalTrackingId = "stale-direct-logical", serviceRunId = "stale-direct-run-1"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.suspendForRestart(
			SessionSuspendRequest(
				"stale-direct-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()
		persistRolloutRevision(2L)
		val recoveryPlan = startRequest().plan.copy(
			revision = 2L,
			planId = "stale-direct-recovery-plan",
			createdAtMs = 3_000L,
			plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
		)

		val rejected = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = recoveryPlan,
				logicalTrackingId = "stale-direct-logical",
				serviceRunId = "stale-direct-run-2",
				continuationAuthority = ServiceRunContinuationAuthority("stale-direct-run-1"),
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 3_000_000L,
			),
		).shouldBeInstanceOf<SessionStartResult.InvalidRollout>()

		rejected.code shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().serviceRun("stale-direct-run-2") shouldBe null
	}

	@Test
	fun `Android recovery preparation revalidates rollout before creating its replacement run`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "stale-prepared-old-token",
			commandGeneration = 41L,
			logicalTrackingId = "stale-prepared-logical",
			serviceRunId = "stale-prepared-run-1",
		)
		persistRolloutRevision(2L)
		val recoveryPlan = startRequest().plan.copy(
			revision = 2L,
			planId = "stale-prepared-recovery-plan",
			createdAtMs = 3_000L,
			plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
		)

		val rejected = subject.prepareAndroidStart(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = recoveryPlan,
				logicalTrackingId = old.logicalTrackingId,
				serviceRunId = "stale-prepared-run-2",
				continuationAuthority = ServiceRunContinuationAuthority(
					old.serviceRunId,
					old.token,
					41L,
				),
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = expiredPreparedLeaseElapsedNanos(),
			),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("stale-prepared-recovery-token"),
				42L,
				true,
				false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Rejected>()

		rejected.failureCode shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().serviceRun("stale-prepared-run-2") shouldBe null
	}

	@Test
	fun `failed automatic provider attempt remains nonterminal until its exact cleanup obligation is retired`() = runTest {
		runtime.startReturnsRetryableFailure = true
		runtime.stopStatus = SourceStopStatus.PROVIDER_FAILED
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.FAILED
		runtime.retainProviderOnIncompleteShutdown = true
		runtime.closeFailure = IllegalStateException("provider still resident")
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			logicalTrackingId = "cleanup-required-logical",
			serviceRunId = "cleanup-required-run",
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
		)

		val failed = subject.start(request).shouldBeInstanceOf<SessionStartResult.Failed>()

		failed.code shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		val startAction = database.sourceSessionDao().lifecycleActions(failed.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		startAction.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		startAction.attemptCount shouldBe 1
		startAction.retryTrigger shouldBe "RUNTIME_CLEANUP_RETRY"
		database.sourceSessionDao().session(failed.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		database.sourceSessionDao().serviceRun(failed.serviceRunId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		runtime.isActive shouldBe true
		runtime.shutdownClaims.single().attemptCount shouldBe 1

		subject.start(
			request.copy(serviceRunId = "replacement-run"),
		).shouldBeInstanceOf<SessionStartResult.AlreadyActive>()
		runtime.startCount shouldBe 1
		database.sourceSessionDao().lifecycleAction(startAction.actionId)?.attemptCount shouldBe 1
		database.sourceSessionDao().session(failed.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.STARTING.name
			session.currentServiceRunId shouldBe failed.serviceRunId
		}
		database.sourceSessionDao().serviceRun(failed.serviceRunId)?.completedAtMs shouldBe null
		database.sourceSessionDao().serviceRun("replacement-run") shouldBe null

		runtime.stopStatus = SourceStopStatus.COMPLETE
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE
		runtime.closeFailure = null
		subject.stop(
			SessionStopRequest(
				ownerToken = "cleanup-stop-owner",
				reason = "failed-start-cleanup",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.isActive shouldBe false
		database.sourceSessionDao().session(failed.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().lifecycleAction(startAction.actionId)?.status shouldBe
			LifecycleActionStatus.SUPERSEDED.name
	}

	@Test
	fun `failed provider cleanup rejects acknowledgement from another service run`() = runTest {
		runtime.startReturnsRetryableFailure = true
		runtime.acknowledgementLogicalTrackingId = "other-logical"
		runtime.acknowledgementServiceRunId = "other-run"
		val request = startRequest().copy(
			logicalTrackingId = "membership-logical",
			serviceRunId = "membership-run",
		)

		val failed = subject.start(request).shouldBeInstanceOf<SessionStartResult.Failed>()

		failed.code shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		database.sourceSessionDao().completenessForServiceRun(
			failed.logicalTrackingId,
			failed.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(failed.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `stop without acknowledgement membership remains cleanup pending and cannot write completeness`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "missing-ack-membership", serviceRunId = "missing-ack-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.omitAcknowledgementMembership = true

		subject.stop(
			SessionStopRequest(
				"missing-ack-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `suspend rejects provider acknowledgement from another service run`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "suspend-membership", serviceRunId = "suspend-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.acknowledgementLogicalTrackingId = "other-logical"
		runtime.acknowledgementServiceRunId = "other-run"

		subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-membership-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.CleanupPending>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe
			started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `unobservable provider removal cannot finalize a stopping run`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "unobservable-stop", serviceRunId = "unobservable-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE
		runtime.retainProviderOnIncompleteShutdown = true

		subject.stop(
			SessionStopRequest(
				"unobservable-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		runtime.isActive shouldBe true
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `stopping a steps run leaves an unrelated location provider untouched`() = runTest {
		locationRuntime.start(
			SourceRuntimeClaim(
				source = SourceKind.LOCATION,
				actionId = "unrelated-location-action",
				attemptCount = 1,
				leaseGeneration = 1L,
				logicalTrackingId = "unrelated-location-logical",
				serviceRunId = "unrelated-location-run",
			),
			LocationPlan(
				revision = 1L,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 2_000L,
				minimumUpdateIntervalMs = 2_000L,
				minimumDisplacementMeters = 10f,
				maximumBatchDelayMs = 10_000L,
				preciseLocationAvailable = true,
			),
			mockk(relaxed = true),
		)
		locationRuntime.failRetirement = true
		subject.start(
			startRequest().copy(logicalTrackingId = "steps-only-logical", serviceRunId = "steps-only-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.stop(
			SessionStopRequest(
				"steps-only-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		locationRuntime.quiesceCount shouldBe 0
		locationRuntime.closeCount shouldBe 0
		locationRuntime.isActive shouldBe true
	}

	@Test
	fun `stopping retry persists a new action generation before accepting improved completeness`() = runTest {
		val eventCoordinator = mockk<TrackingCoordinator>()
		coEvery { eventCoordinator.drainAvailable(any(), any()) } returnsMany listOf(
			CoordinatorDrainResult.LeaseUnavailable,
			CoordinatorDrainResult.Complete(0L, 0),
		)
		val ingress = mockk<DurableSourceIngress>(relaxed = true)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			eventCoordinator,
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			rolloutStore = fixedEventRolloutStore(),
		)
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "retry-stop-logical", serviceRunId = "retry-stop-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.stopStatus = SourceStopStatus.TIMED_OUT
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED

		subject.stop(
			SessionStopRequest("retry-stop-owner", "manual", 2_000L, 2_000_000L, "boot-1", perSourceTimeoutMs = 100L),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()
		runtime.stopStatus = SourceStopStatus.COMPLETE
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
		subject.stop(
			SessionStopRequest("retry-stop-owner", "manual", 2_500L, 2_500_000L, "boot-1", perSourceTimeoutMs = 100L),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val stopActions = database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.filter { action -> action.desiredState == "STOPPED" }
		stopActions.size shouldBe 2
		stopActions.map { action -> action.leaseGeneration }.distinct().size shouldBe 2
		stopActions.first().status shouldBe LifecycleActionStatus.SUPERSEDED.name
		stopActions.last().status shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		val run = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		run.runtimeAcknowledgement shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		run.runtimeFailureCode shouldBe null
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.stopStatus shouldBe SourceStopStatus.COMPLETE.name
	}

	@Test
	fun `shared manifest integrity detects run and writer provenance tampering`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
		val manifest = database.sourceSessionDao().manifests(started.logicalTrackingId).single()
		val sources = database.sourceSessionDao()
			.manifestSources(started.logicalTrackingId, manifest.manifestRevision)

		SessionManifestIntegrity.verify(manifest, sources) shouldBe true
		SessionManifestIntegrity.verify(manifest.copy(serviceRunId = "tampered-run"), sources) shouldBe false
		SessionManifestIntegrity.verify(
			manifest,
			sources.map { source -> source.copy(writerOwnerGeneration = source.writerOwnerGeneration?.plus(1L)) },
		) shouldBe false
		SessionManifestIntegrity.verify(manifest, emptyList()) shouldBe false
		SessionManifestIntegrity.verify(manifest, sources + sources.single()) shouldBe false
		SessionManifestIntegrity.verify(
			manifest,
			sources.map { source -> source.copy(logicalTrackingId = "foreign-logical") },
		) shouldBe false
		SessionManifestIntegrity.compute(manifest, sources.reversed()) shouldBe manifest.manifestChecksum
	}

	@Test
	fun `prepared claim rejects raw binding tampering before delivery acceptance`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "tampered-claim-token",
			commandGeneration = 16L,
			logicalTrackingId = "tampered-claim-logical",
			serviceRunId = "tampered-claim-run",
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any>(prepared.logicalTrackingId, prepared.manifestRevision),
		)

		subject.claimAndroidStart(
			prepared.token,
			16L,
			"boot-1",
			1_100_000L,
			1_100L,
		) shouldBe PreparedSessionClaimResult.Rejected("PREPARED_START_ROOM_ENVELOPE_STALE")
		runtime.startCount shouldBe 0
	}

	@Test
	fun `prepared apply rejects persisted writer provenance tampering before provider start`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "tampered-apply-token",
			commandGeneration = 17L,
			logicalTrackingId = "tampered-apply-logical",
			serviceRunId = "tampered-apply-run",
		)
		subject.claimAndroidStart(
			prepared.token,
			17L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			17L,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET writer_owner_generation = writer_owner_generation + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any>(prepared.logicalTrackingId, prepared.manifestRevision),
		)

		subject.applyPreparedAndroidStart(
			prepared.token,
			17L,
			"boot-1",
			1_300_000L,
			1_300L,
		) shouldBe SessionStartResult.InvalidIntent("PREPARED_START_MANIFEST_INTEGRITY_FAILED")
		runtime.startCount shouldBe 0
	}

	@Test
	fun `reserved legacy completeness run id cannot be admitted as a live run`() = runTest {
		subject.start(startRequest().copy(serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID)) shouldBe
			SessionStartResult.InvalidIntent("SERVICE_RUN_ID_RESERVED")
	}

	@Test
	fun `blank service run id is rejected with a typed result before any durable row`() = runTest {
		subject.start(startRequest().copy(serviceRunId = "   ")) shouldBe
			SessionStartResult.InvalidIntent("SERVICE_RUN_ID_INVALID")
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		runtime.startCount shouldBe 0
	}

	@Test
	fun `old boot cleanup allows exactly one fresh manual logical session`() = runTest {
		val oldRequest = startRequest().copy(
			logicalTrackingId = "old-boot-manual",
			serviceRunId = "old-boot-run",
		)
		subject.start(oldRequest).shouldBeInstanceOf<SessionStartResult.Started>()

		PreviousExitSourceSessionFinalizer(
			Provider { database },
			FixedClock(2_000L, 2_000_000L),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-2"
			},
		).finalizeStaleSessions()
		currentBootId = "boot-2"

		val freshRequest = startRequest().copy(
			ownerToken = "fresh-owner",
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "fresh-manual-plan",
				createdAtMs = 2_000L,
				plans = mapOf(
					SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false),
				),
			),
			clockDomainId = "boot-2",
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			logicalTrackingId = "fresh-manual",
			serviceRunId = "fresh-run",
		)
		subject.start(freshRequest).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.start(
			freshRequest.copy(
				logicalTrackingId = "duplicate-fresh-manual",
				serviceRunId = "duplicate-fresh-run",
			),
		) shouldBe SessionStartResult.AlreadyActive
		database.sourceSessionDao().session("old-boot-manual")?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().incompleteSessions().map { it.logicalTrackingId } shouldBe
			listOf("fresh-manual")
		database.sourceSessionDao().session("duplicate-fresh-manual") shouldBe null
	}

	@Test
	fun `source start cancellation propagates without terminal cleanup`() = runTest {
		runtime.startFailure = CancellationException("test cancellation")
		var propagated = false

		try {
			subject.start(startRequest())
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.closed shouldBe false
		database.sourceSessionDao().activeSession()?.state shouldBe SessionLifecycleState.STARTING.name
		database.sourceSessionDao().activeSession()?.let { session ->
			database.sourceSessionDao().lifecycleActions(session.logicalTrackingId).last().status
		} shouldBe LifecycleActionStatus.APPLYING.name
	}

	@Test
	fun `cancelled start after provider publication retires applying claim before finalization`() = runTest {
		runtime.startFailureAfterSideEffect = CancellationException("cancel after provider publication")
		runtime.stopStatus = SourceStopStatus.PROVIDER_FAILED
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.FAILED
		runtime.retainProviderOnIncompleteShutdown = true
		val request = startRequest().copy(
			logicalTrackingId = "cancelled-active-start",
			serviceRunId = "cancelled-active-run",
		)

		var propagated = false
		try {
			subject.start(request)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		val applyingStart = database.sourceSessionDao()
			.lifecycleActions("cancelled-active-start")
			.single { action -> action.desiredState == "STARTED" }
		applyingStart.status shouldBe LifecycleActionStatus.APPLYING.name
		applyingStart.attemptCount shouldBe 1

		val pending = subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-active-stop",
				reason = "SERVICE_DESTROYED",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		pending.logicalTrackingId shouldBe "cancelled-active-start"
		runtime.isActive shouldBe true
		runtime.shutdownClaims.single().let { claim ->
			claim.actionId shouldBe applyingStart.actionId
			claim.attemptCount shouldBe applyingStart.attemptCount
			claim.leaseGeneration shouldBe applyingStart.leaseGeneration
			claim.logicalTrackingId shouldBe applyingStart.logicalTrackingId
			claim.serviceRunId shouldBe applyingStart.serviceRunId
		}
		database.sourceSessionDao().session("cancelled-active-start")?.let { session ->
			session.state shouldBe SessionLifecycleState.STOPPING.name
			session.currentServiceRunId shouldBe "cancelled-active-run"
		}
		database.sourceSessionDao().lifecycleAction(applyingStart.actionId)?.status shouldBe
			LifecycleActionStatus.APPLYING.name
		database.sourceSessionDao().lifecycleActions("cancelled-active-start")
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name

		runtime.startFailureAfterSideEffect = null
		runtime.stopStatus = SourceStopStatus.COMPLETE
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE

		subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-active-stop-retry",
				reason = "SERVICE_DESTROYED_RETRY",
				wallTimeMs = 2_500L,
				elapsedRealtimeNanos = 2_500_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.isActive shouldBe false
		runtime.shutdownClaims.map(SourceRuntimeClaim::actionId) shouldBe
			listOf(applyingStart.actionId, applyingStart.actionId)
		database.sourceSessionDao().session("cancelled-active-start")?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.currentServiceRunId shouldBe null
		}
		database.sourceSessionDao().lifecycleAction(applyingStart.actionId)?.let { action ->
			action.status shouldBe LifecycleActionStatus.SUPERSEDED.name
			action.failureCode shouldBe "RUNTIME_CLEANUP_CONFIRMED_BY_STOP"
		}
	}

	@Test
	fun `stale automatic replacement cannot finalize an applying provider attempt`() = runTest {
		runtime.startFailureAfterSideEffect = CancellationException("cancel automatic start after publication")
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val original = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-applying-logical",
			serviceRunId = "automatic-applying-run",
		)

		var propagated = false
		try {
			subject.start(original)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		val applying = database.sourceSessionDao()
			.lifecycleActions("automatic-applying-logical")
			.single { action -> action.desiredState == "STARTED" }
		applying.status shouldBe LifecycleActionStatus.APPLYING.name
		applying.attemptCount shouldBe 1
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED

		subject.start(
			original.copy(
				ownerToken = "automatic-replacement-owner",
				serviceRunId = "automatic-replacement-run",
				wallTimeMs = 1_100L,
				elapsedRealtimeNanos = 1_100_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		runtime.startCount shouldBe 1
		runtime.isActive shouldBe true
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session("automatic-applying-logical")?.let { session ->
			session.state shouldBe SessionLifecycleState.STARTING.name
			session.currentServiceRunId shouldBe "automatic-applying-run"
			session.completedAtMs shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-applying-run")?.let { run ->
			run.completedAtMs shouldBe null
			run.completionReason shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-replacement-run") shouldBe null
		database.sourceSessionDao().lifecycleAction(applying.actionId)?.status shouldBe
			LifecycleActionStatus.APPLYING.name
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.let { action ->
			action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
			action.acceptedLogicalTrackingId shouldBe "automatic-applying-logical"
		}
	}

	@Test
	fun `stale automatic replacement cannot finalize a start accepted provider`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val original = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-accepted-logical",
			serviceRunId = "automatic-accepted-run",
		)

		val started = subject.start(original).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.isActive shouldBe true
		val acceptedStart = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		acceptedStart.status shouldBe LifecycleActionStatus.START_ACCEPTED.name

		subject.start(
			original.copy(
				ownerToken = "automatic-accepted-replay",
				serviceRunId = "automatic-accepted-replacement-run",
				wallTimeMs = 1_100L,
				elapsedRealtimeNanos = 1_100_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		runtime.startCount shouldBe 1
		runtime.isActive shouldBe true
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session("automatic-accepted-logical")?.let { session ->
			session.state shouldBe SessionLifecycleState.ACTIVE.name
			session.currentServiceRunId shouldBe "automatic-accepted-run"
			session.completedAtMs shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-accepted-run")?.completedAtMs shouldBe null
		database.sourceSessionDao().serviceRun("automatic-accepted-replacement-run") shouldBe null
		database.sourceSessionDao().lifecycleAction(acceptedStart.actionId)?.status shouldBe
			LifecycleActionStatus.START_ACCEPTED.name
	}

	@Test
	fun `reconfiguration appends an immutable manifest and leaves the prior revision intact`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "logical-manifest", serviceRunId = "run-manifest"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val firstManifest = database.sourceSessionDao().manifests(started.logicalTrackingId).single()
		val firstBindings = database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L)

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "balanced-reconfigured",
					createdAtMs = 2_000L,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000, 15_000, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Applied>()

		result.revision shouldBe 2L
		val manifests = database.sourceSessionDao().manifests(started.logicalTrackingId)
		manifests.map { it.manifestRevision } shouldBe listOf(1L, 2L)
		manifests.first() shouldBe firstManifest
		database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L) shouldBe firstBindings
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentManifestRevision shouldBe 2L
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).map { it.intentRevision } shouldBe
			listOf(1L, 2L)
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId).map { it.actionRevision } shouldBe
			listOf(1L, 2L)
		val demandHistory = database.sourceBrokerDao().demandHistory("session:${started.logicalTrackingId}")
		demandHistory.map { it.manifestRevision } shouldBe listOf(1L, 2L)
		demandHistory.map { it.status } shouldBe listOf(
			SourceDemandEntity.STATUS_RETIRED,
			SourceDemandEntity.STATUS_ACTIVE,
		)
	}

	@Test
	fun `reconfiguration rejects persisted manifest tampering before runtime action`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "tampered-reconfigure-logical", serviceRunId = "tampered-reconfigure-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET writer_owner_generation = writer_owner_generation + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1",
			arrayOf(started.logicalTrackingId),
		)
		val reconfigureCountBefore = runtime.reconfigureCount

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "tampered-reconfigure-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "tampered-reconfigure-plan",
					createdAtMs = 2_000L,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		)

		result shouldBe SessionReconfigureResult.InvalidIntent("CURRENT_MANIFEST_INTEGRITY_FAILED")
		runtime.reconfigureCount shouldBe reconfigureCountBefore
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `cancelled reconfiguration retires newest applying claim instead of predecessor`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "cancelled-reconfigure-logical",
				serviceRunId = "cancelled-reconfigure-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val acceptedStart = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		acceptedStart.status shouldBe LifecycleActionStatus.START_ACCEPTED.name
		runtime.reconfigureFailure = CancellationException("test cancellation")
		var propagated = false

		try {
			subject.reconfigure(
				SessionReconfigureRequest(
					ownerToken = "test-owner",
					plan = startRequest().plan.copy(
						revision = 2L,
						planId = "cancelled-reconfigure",
						createdAtMs = 2_000L,
						plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
					),
					wallTimeMs = 2_000L,
					elapsedRealtimeNanos = 2_000_000L,
					clockDomainId = "boot-1",
					zoneId = "Europe/Prague",
					foregroundCapabilityFlags = 0L,
				),
			)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		val applyingReconfigure = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.last()
		applyingReconfigure.status shouldBe LifecycleActionStatus.APPLYING.name
		applyingReconfigure.actionRevision shouldBe 2L

		runtime.reconfigureFailure = null
		subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-reconfigure-stop",
				reason = "SERVICE_DESTROYED",
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 3_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.shutdownClaims.single().let { claim ->
			claim.actionId shouldBe applyingReconfigure.actionId
			claim.attemptCount shouldBe applyingReconfigure.attemptCount
			claim.leaseGeneration shouldBe applyingReconfigure.leaseGeneration
			claim.logicalTrackingId shouldBe started.logicalTrackingId
			claim.serviceRunId shouldBe started.serviceRunId
			(claim.actionId == acceptedStart.actionId) shouldBe false
		}
		runtime.isActive shouldBe false
		database.sourceSessionDao().lifecycleAction(acceptedStart.actionId)?.status shouldBe
			LifecycleActionStatus.START_ACCEPTED.name
		database.sourceSessionDao().lifecycleAction(applyingReconfigure.actionId)?.let { action ->
			action.status shouldBe LifecycleActionStatus.SUPERSEDED.name
			action.failureCode shouldBe "RUNTIME_CLEANUP_CONFIRMED_BY_STOP"
		}
		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.currentServiceRunId shouldBe null
		}
	}

	@Test
	fun `reconfiguration cannot move effective time backwards`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "regressed",
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 999_999L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		)

		result shouldBe SessionReconfigureResult.InvalidIntent("RECONFIGURE_EFFECTIVE_TIME_REGRESSED")
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `disabling the last source cannot leave the session active`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "all-disabled",
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, false, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Failed>()

		result.failureCode shouldBe "NO_SOURCE_ACTIVE_AFTER_RECONFIGURE"
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FAILED.name
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single().registrationGeneration shouldBe 1L
	}

	@Test
	fun `Steps disable and reenable receipts each physical generation under the same run`() = runTest {
		fun plan(revision: Long, stepsEnabled: Boolean) = AcquisitionPlanRevision(
			revision = revision,
			planId = "steps-cycle-$revision",
			createdAtMs = revision * 1_000L,
			plans = mapOf(
				SourceKind.STEPS to StepsPlan(revision, stepsEnabled, 60_000L, 15_000L, false),
				SourceKind.LOCATION to LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.BALANCED,
					requestedIntervalMs = 2_000L,
					minimumUpdateIntervalMs = 2_000L,
					minimumDisplacementMeters = 10f,
					maximumBatchDelayMs = 10_000L,
					probeDurationMs = null,
					preciseLocationAvailable = true,
				),
			),
			sourcePolicyRevision = 1L,
		)
		val started = subject.start(startRequest().copy(plan = plan(1L, true)))
			.shouldBeInstanceOf<SessionStartResult.Started>()
		fun request(revision: Long, enabled: Boolean) = SessionReconfigureRequest(
			ownerToken = "test-owner",
			plan = plan(revision, enabled),
			wallTimeMs = revision * 1_000L,
			elapsedRealtimeNanos = revision * 1_000_000L,
			clockDomainId = "boot-1",
			zoneId = "Europe/Prague",
			foregroundCapabilityFlags = 0L,
		)

		subject.reconfigure(request(2L, false)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe
			started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.map { it.registrationGeneration } shouldBe listOf(1L)
		subject.reconfigure(request(3L, true)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		subject.reconfigure(request(4L, false)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.map { it.registrationGeneration }.sorted() shouldBe listOf(1L, 2L)
	}

	@Test
	fun `failed source disable blocks active and final states until predecessor provider retires`() = runTest {
		fun plan(revision: Long, locationEnabled: Boolean) = AcquisitionPlanRevision(
			revision = revision,
			planId = "location-retirement-$revision",
			createdAtMs = revision * 1_000L,
			plans = mapOf(
				SourceKind.STEPS to StepsPlan(revision, true, 60_000L, 15_000L, false),
				SourceKind.LOCATION to (LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.BALANCED,
					requestedIntervalMs = 2_000L,
					minimumUpdateIntervalMs = 2_000L,
					minimumDisplacementMeters = 10f,
					maximumBatchDelayMs = 10_000L,
					probeDurationMs = null,
					preciseLocationAvailable = true,
				).takeIf { locationEnabled } ?: LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.DISABLED,
					requestedIntervalMs = 60_000L,
					minimumUpdateIntervalMs = 60_000L,
					minimumDisplacementMeters = 0f,
					maximumBatchDelayMs = 0L,
					probeDurationMs = null,
					preciseLocationAvailable = false,
				)),
			),
			sourcePolicyRevision = 1L,
		)
		val started = subject.start(startRequest().copy(plan = plan(1L, locationEnabled = true)))
			.shouldBeInstanceOf<SessionStartResult.Started>()
		locationRuntime.failRetirement = true

		val reconfigured = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "location-disable-owner",
				plan = plan(2L, locationEnabled = false),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Failed>()

		reconfigured.failureCode shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.sourceKind == SourceKind.LOCATION.stableCode }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		locationRuntime.isActive shouldBe true

		subject.stop(
			SessionStopRequest(
				"location-cleanup-owner",
				"provider-retirement",
				3_000L,
				3_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		locationRuntime.quiesceCount shouldBe 1
		locationRuntime.isActive shouldBe true

		locationRuntime.failRetirement = false
		subject.stop(
			SessionStopRequest(
				"location-cleanup-owner",
				"provider-retirement-retry",
				4_000L,
				4_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		locationRuntime.quiesceCount shouldBe 2
		locationRuntime.isActive shouldBe false
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
	}

	@Test
	fun `released lease reacquisition increments generation and fences the old token`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = "test-lease",
				ownerToken = "owner",
				acquiredAtMs = 1,
				expiresAtMs = 2,
				bootId = "boot",
				generation = 1,
				acquiredElapsedRealtimeNanos = 100,
				expiresElapsedRealtimeNanos = 200,
			),
		) shouldBe 1L
		dao.releaseLease("test-lease", "owner", "boot", 1L, 1, 150L) shouldBe 1

		dao.acquireOrRenewLease("test-lease", "owner", "boot", 2, 3, 151L, 300L) shouldBe 1

		dao.lease("test-lease")?.generation shouldBe 2L
		dao.releaseLease("test-lease", "owner", "boot", 1L, 2, 160L) shouldBe 0
	}

	@Test
	fun `stale boot direct start is rejected before any durable row or lease`() = runTest {
		val request = startRequest().copy(
			ownerToken = "stale-boot-owner",
			logicalTrackingId = "stale-boot-logical",
			serviceRunId = "stale-boot-run",
			clockDomainId = "boot-2",
		)

		subject.start(request) shouldBe SessionStartResult.InvalidIntent("BOOT_ID_STALE")

		val dao = database.sourceSessionDao()
		dao.session("stale-boot-logical") shouldBe null
		dao.serviceRun("stale-boot-run") shouldBe null
		dao.manifests("stale-boot-logical") shouldBe emptyList()
		dao.lifecycleIntents("stale-boot-logical") shouldBe emptyList()
		dao.lifecycleActions("stale-boot-logical") shouldBe emptyList()
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe null
		runtime.startCount shouldBe 0
	}

	@Test
	fun `stale boot prepared start is rejected before any durable row or lease`() = runTest {
		val token = PreparedTrackingStartToken("stale-boot-prepared-token")
		val request = startRequest().copy(
			ownerToken = "stale-boot-prepared-owner",
			logicalTrackingId = "stale-boot-prepared-logical",
			serviceRunId = "stale-boot-prepared-run",
			clockDomainId = "boot-2",
		)

		subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = token,
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("BOOT_ID_STALE")

		val dao = database.sourceSessionDao()
		dao.session("stale-boot-prepared-logical") shouldBe null
		dao.serviceRun("stale-boot-prepared-run") shouldBe null
		dao.manifests("stale-boot-prepared-logical") shouldBe emptyList()
		dao.lifecycleActions("stale-boot-prepared-logical") shouldBe emptyList()
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe null
	}

	@Test
	fun `stale boot cannot mutate an active session through reconfigure stop or suspend`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "stale-mutation-logical",
				serviceRunId = "stale-mutation-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val dao = database.sourceSessionDao()
		val sessionBefore = requireNotNull(dao.session(started.logicalTrackingId))
		val runBefore = requireNotNull(dao.serviceRun(started.serviceRunId))
		val manifestsBefore = dao.manifests(started.logicalTrackingId)
		val intentsBefore = dao.lifecycleIntents(started.logicalTrackingId)
		val actionsBefore = dao.lifecycleActions(started.logicalTrackingId)
		val leaseBefore = database.sourceProjectionStateDao().lease("tracking-session-coordinator")
		val reconfigureCountBefore = runtime.reconfigureCount
		val shutdownCountBefore = runtime.shutdownClaims.size

		val reconfigure = SessionReconfigureRequest(
			ownerToken = "stale-reconfigure-owner",
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "stale-boot-reconfigure",
				createdAtMs = 2_000L,
				plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
			),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			clockDomainId = "boot-2",
			zoneId = "Europe/Prague",
			foregroundCapabilityFlags = 0L,
		)
		subject.reconfigure(reconfigure) shouldBe
			SessionReconfigureResult.InvalidIntent("BOOT_ID_STALE")
		subject.stop(
			SessionStopRequest(
				ownerToken = "stale-stop-owner",
				reason = "STALE_BOOT_TEST",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-2",
			),
		) shouldBe SessionStopResult.InvalidIntent("BOOT_ID_STALE")
		subject.suspendForRestart(
			SessionSuspendRequest(
				ownerToken = "stale-suspend-owner",
				reason = "STALE_BOOT_TEST",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-2",
			),
		) shouldBe SessionSuspendResult.InvalidIntent("BOOT_ID_STALE")

		dao.session(started.logicalTrackingId) shouldBe sessionBefore
		dao.serviceRun(started.serviceRunId) shouldBe runBefore
		dao.manifests(started.logicalTrackingId) shouldBe manifestsBefore
		dao.lifecycleIntents(started.logicalTrackingId) shouldBe intentsBefore
		dao.lifecycleActions(started.logicalTrackingId) shouldBe actionsBefore
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe leaseBefore
		runtime.reconfigureCount shouldBe reconfigureCountBefore
		runtime.shutdownClaims.size shouldBe shutdownCountBefore
	}

	@Test
	fun `extreme request elapsed metadata cannot poison the canonical session lease`() = runTest {
		val incumbent = incumbentSessionLease()
		database.sourceProjectionStateDao().insertLeaseIfAbsent(incumbent) shouldBe 1L

		subject.start(
			startRequest().copy(
				ownerToken = "challenger",
				elapsedRealtimeNanos = Long.MAX_VALUE,
			),
		) shouldBe SessionStartResult.Busy

		database.sourceProjectionStateDao().lease(incumbent.leaseName) shouldBe incumbent
	}

	@Test
	fun `prepared demand is not callback authority until foreground acceptance`() = runTest {
		seedActiveStepsRegistration()
		val prepared = prepareAndroidStart(
			tokenValue = "blocked-demand-token",
			commandGeneration = 11L,
			logicalTrackingId = "blocked-demand-logical",
			serviceRunId = "blocked-demand-run",
		)
		val consumerId = "session:${prepared.logicalTrackingId}"
		val brokerDao = database.sourceBrokerDao()

		brokerDao.currentDemands(consumerId) shouldBe emptyList()
		brokerDao.demandHistory(consumerId).single().status shouldBe SourceDemandEntity.STATUS_BLOCKED
		brokerDao.authorizationDemands(SourceKind.STEPS.stableCode) shouldBe emptyList()
		brokerDao.latestAuthorization(SourceKind.STEPS.stableCode, 1L)
			.any { authorization ->
				authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			} shouldBe false
		database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
			.map { action -> action.status }.distinct() shouldBe
			listOf(LifecycleActionStatus.AWAITING_FOREGROUND.name)

		subject.claimAndroidStart(
			prepared.token,
			11L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			11L,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true

		brokerDao.currentDemands(consumerId).single().status shouldBe SourceDemandEntity.STATUS_ACTIVE
		val captureAuthorization = brokerDao.latestAuthorization(SourceKind.STEPS.stableCode, 1L)
			.single { authorization ->
				authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			}
		captureAuthorization.logicalTrackingId shouldBe prepared.logicalTrackingId
		captureAuthorization.serviceRunId shouldBe prepared.serviceRunId
		captureAuthorization.manifestRevision shouldBe prepared.manifestRevision
		database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
			.map { action -> action.status }.distinct() shouldBe
			listOf(LifecycleActionStatus.PENDING.name)
	}

	@Test
	fun `active manual redelivery prepares one distinct recovery run after old lease expiry`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "active-redelivery-old-token",
			commandGeneration = 31L,
			logicalTrackingId = "active-redelivery-logical",
			serviceRunId = "active-redelivery-old-run",
		)
		val recoveryToken = PreparedTrackingStartToken("active-redelivery-recovery-token")
		val recoveryElapsed = expiredPreparedLeaseElapsedNanos()
		val recoveryRequest = startRequest().copy(
			origin = SessionStartOrigin.RECOVERY,
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "active-redelivery-recovery-plan",
				createdAtMs = 2_000L,
				plans = mapOf(
					SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false),
				),
			),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = recoveryElapsed,
			logicalTrackingId = old.logicalTrackingId,
			serviceRunId = "active-redelivery-recovery-run",
			continuationAuthority = ServiceRunContinuationAuthority(
				previousServiceRunId = old.serviceRunId,
				previousDeliveryToken = old.token,
				previousCommandGeneration = 31L,
			),
		)

		val recovery = subject.prepareAndroidStart(
			recoveryRequest,
			AndroidStartDeliveryMetadata(recoveryToken, 32L, true, false),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		recovery.logicalTrackingId shouldBe old.logicalTrackingId
		recovery.serviceRunId shouldBe "active-redelivery-recovery-run"
		recovery.token shouldBe recoveryToken
		database.sourceSessionDao().serviceRun(old.serviceRunId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().serviceRun(old.serviceRunId)?.completionReason shouldBe
			"PROCESS_DEATH_RECOVERY"
		database.sourceSessionDao().incompleteServiceRuns(old.logicalTrackingId)
			.map { it.serviceRunId } shouldBe listOf(recovery.serviceRunId)
		database.sourceSessionDao().session(old.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		database.sourceSessionDao().manifests(old.logicalTrackingId).size shouldBe 2
	}

	@Test
	fun `different boot terminalizes exact active redelivery without creating a run`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "old-boot-redelivery-token",
			commandGeneration = 41L,
			logicalTrackingId = "old-boot-redelivery-logical",
			serviceRunId = "old-boot-redelivery-run",
		)

		subject.finalizeUnrecoverableContinuation(
			logicalTrackingId = old.logicalTrackingId,
			authority = ServiceRunContinuationAuthority(
				old.serviceRunId,
				old.token,
				41L,
			),
			currentBootId = "boot-2",
			elapsedRealtimeNanos = 10_000_000L,
			wallTimeMs = 2_000L,
			failureCode = "REDELIVERY_RECOVERY_OLD_BOOT",
		) shouldBe true

		database.sourceSessionDao().session(old.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().session(old.logicalTrackingId)?.failureCode shouldBe
			"REDELIVERY_RECOVERY_OLD_BOOT"
		database.sourceSessionDao().incompleteServiceRuns(old.logicalTrackingId) shouldBe emptyList()
		database.sourceSessionDao().manifests(old.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `expired prepared delivery terminalizes only the untouched envelope`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "expired-prepared-token",
			commandGeneration = 21L,
			logicalTrackingId = "expired-prepared-logical",
			serviceRunId = "expired-prepared-run",
		)

		val result = subject.claimAndroidStart(
			prepared.token,
			21L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()

		result.failureCode shouldBe "PREPARED_START_LEASE_EXPIRED"
		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
		database.sourceSessionDao().hasLifecycleBoundaryBlocker() shouldBe false
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		database.sourceSessionDao().hasNonterminalLatestLifecycleAction() shouldBe false
	}

	@Test
	fun `expired enqueued automatic delivery terminalizes its accepted action`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val prepared = prepareAndroidStart(
			tokenValue = "expired-enqueued-token",
			commandGeneration = 22L,
			logicalTrackingId = "expired-enqueued-logical",
			serviceRunId = "expired-enqueued-run",
			request = startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
		)
		subject.markAndroidStartEnqueued(prepared.token, 22L, 1_100L) shouldBe true
		database.sourceSessionDao().serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.ENQUEUED.name
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED

		val result = subject.claimAndroidStart(
			prepared.token,
			22L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()

		result.failureCode shouldBe "PREPARED_START_LEASE_EXPIRED"
		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
		val action = requireNotNull(database.activityAutomaticStartActionDao().action(trigger.triggerId))
		action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_TERMINAL
		action.terminalReason shouldBe "PREPARED_START_LEASE_EXPIRED"
		action.acceptedLogicalTrackingId shouldBe prepared.logicalTrackingId
		action.acceptedIntentRevision shouldBe prepared.intentRevision
	}

	@Test
	fun `lease expiry after claim but before foreground terminalizes delivered envelope`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "expired-delivered-token",
			commandGeneration = 23L,
			logicalTrackingId = "expired-delivered-logical",
			serviceRunId = "expired-delivered-run",
		)
		subject.markAndroidStartEnqueued(prepared.token, 23L, 1_050L) shouldBe true
		subject.claimAndroidStart(
			prepared.token,
			23L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		database.sourceSessionDao().serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.DELIVERED.name

		subject.markPreparedForegroundAccepted(
			prepared.token,
			23L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		) shouldBe false

		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
	}

	@Test
	fun `advanced envelope refuses old claim and compensation without touching newer rows`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "advanced-old-token",
			commandGeneration = 24L,
			logicalTrackingId = "advanced-logical",
			serviceRunId = "advanced-old-run",
		)
		subject.markAndroidStartEnqueued(prepared.token, 24L, 1_050L) shouldBe true
		advancePreparedEnvelope(prepared)
		val sessionDao = database.sourceSessionDao()
		val newerSessionBefore = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val newerRunBefore = requireNotNull(sessionDao.serviceRun("advanced-new-run"))
		val newerActionBefore = sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == newerRunBefore.serviceRunId }
		val demandHistoryBefore = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")

		val claim = subject.claimAndroidStart(
			prepared.token,
			24L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()
		claim.failureCode shouldBe "PREPARED_START_ROOM_ENVELOPE_STALE"
		subject.compensatePreparedAndroidStart(
			prepared.token,
			24L,
			"OLD_ENQUEUE_FAILED",
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe false

		sessionDao.session(prepared.logicalTrackingId) shouldBe newerSessionBefore
		sessionDao.serviceRun("advanced-new-run") shouldBe newerRunBefore
		sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == newerRunBefore.serviceRunId } shouldBe newerActionBefore
		database.sourceBrokerDao().demandHistory("session:${prepared.logicalTrackingId}") shouldBe
			demandHistoryBefore
		sessionDao.serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.ENQUEUED.name
	}

	@Test
	fun `zero-source and stale automatic starts fail before durable intent`() = runTest {
		val zeroSource = startRequest().copy(
			plan = startRequest().plan.copy(
				plans = mapOf(SourceKind.STEPS to StepsPlan(1L, false, 60_000, 15_000, false)),
			),
		)
		subject.start(zeroSource) shouldBe SessionStartResult.InvalidIntent("ZERO_CAPTURE_SOURCES")

		val staleAutomatic = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = AutomaticTrackingStartTrigger(
				triggerId = "motion-1",
				kind = "ON_FOOT",
				bootId = "old-boot",
				observedElapsedRealtimeNanos = 500_000L,
				receivedElapsedRealtimeNanos = 600_000L,
				expiresElapsedRealtimeNanos = 2_000_000L,
				automationEpoch = 9L,
				startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
				sourcePolicyRevision = 1L,
				requestedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
				intendedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
				intendedForegroundServiceTypeMask = 0L,
				collectedDataEpoch = 0L,
			),
		)
		subject.start(staleAutomatic) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_TRIGGER_BOOT_STALE")

		val oldPolicyRevision = staleAutomatic.copy(
			automaticTrigger = requireNotNull(staleAutomatic.automaticTrigger).copy(
				bootId = "boot-1",
				sourcePolicyRevision = 2L,
			),
		)
		subject.start(oldPolicyRevision) shouldBe
			SessionStartResult.InvalidIntent("AUTOMATIC_TRIGGER_EPOCH_STALE")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `automatic action and lifecycle intent are accepted atomically with distinct epochs`() = runTest {
		val trigger = automaticTrigger(automationEpoch = 9L, sourcePolicyRevision = 1L)
		seedAutomaticStartAction(trigger)

		val started = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "automatic-logical",
				serviceRunId = "automatic-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		started.logicalTrackingId shouldBe "automatic-logical"
		val action = requireNotNull(database.activityAutomaticStartActionDao().current())
		action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
		action.acceptedLogicalTrackingId shouldBe started.logicalTrackingId
		val intent = requireNotNull(
			database.sourceSessionDao().lifecycleIntent(started.logicalTrackingId, 1L),
		)
		intent.automationEpoch shouldBe 9L
		intent.triggerCollectedDataEpoch shouldBe trigger.collectedDataEpoch
		activityAutomationDrainSignal.requestCount shouldBe 1
	}

	@Test
	fun `coordinator synchronously reconciles lock before automatic lifecycle acceptance`() = runTest {
		val trigger = automaticTrigger(automationEpoch = 17L, sourcePolicyRevision = 1L)
		seedAutomaticStartAction(trigger)
		activityLocked = true

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "locked-automatic-logical",
				serviceRunId = "locked-automatic-run",
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_ACTION_TERMINAL")

		database.activityAutomationEpochDao().current()?.let { it.epoch to it.lockSuppressed } shouldBe
			(18L to true)
		database.sourceSessionDao().session("locked-automatic-logical") shouldBe null
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_TERMINAL
	}

	@Test
	fun `automatic action foreground envelope mismatch creates no lifecycle intent`() = runTest {
		val trigger = automaticTrigger(intendedForegroundServiceTypeMask = 256L)
		seedAutomaticStartAction(trigger)

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				foregroundCapabilityFlags = 0L,
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_FGS_MASK_MISMATCH")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
		database.activityAutomaticStartActionDao().current()?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_TERMINAL
		database.activityAutomaticStartActionDao().current()?.terminalReason shouldBe
			"AUTOMATIC_START_FGS_MASK_MISMATCH"
	}

	@Test
	fun `automatic start without the exact requested action creates no lifecycle intent`() = runTest {
		val trigger = automaticTrigger()
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = trigger.collectedDataEpoch),
		)

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_ACTION_MISSING")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `accepted automatic action cannot recreate its finalized logical session`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-logical",
			serviceRunId = "automatic-run",
		)
		subject.start(request).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest(
				ownerToken = "test-owner",
				reason = "test",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 1_100_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		subject.start(
			request.copy(
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 1_200_000L,
			),
		) shouldBe SessionStartResult.InvalidIntent(
			"AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL",
		)
		database.sourceSessionDao().manifests("automatic-logical").size shouldBe 1
	}

	@Test
	fun `crash after coordinator acceptance then finalize still acknowledges the durable outbox`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "activity-automatic-effect",
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				admissionOrdinal = 1L,
				effectKind = ActivityAutomationProjection.OUTBOX_KIND,
				payloadVersion = ActivityAutomationProjection.PAYLOAD_VERSION,
				payload = automaticOutboxPayload(trigger),
				createdAtMs = 500L,
				deliveredAtMs = null,
			),
		) shouldBe 1L
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-logical",
			serviceRunId = "automatic-run",
		)

		// Model a process crash after the coordinator transaction commits but before the
		// process-local drain hint is serviced, followed by terminal session recovery.
		subject.start(request).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest(
				ownerToken = "test-owner",
				reason = "process-exit-recovery",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 1_100_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val actionRepository = ActivityAutomaticStartActionRepository(
			database,
			ReadyTrackingStartupGate,
		)
		val consumer = mockk<ActivityAutomationEffectConsumer>(relaxed = true)
		val dispatcher = ActivityAutomationOutboxDispatcher(
			database,
			ActivityAutomationEffectValidator(
				database,
				object : BootClockDomainProvider {
					override fun current(): String = "boot-1"
				},
				actionRepository,
				mockk<ActivityAutomationEpochAuthority>(relaxed = true),
			),
			consumer,
		)

		actionRepository.validateForService(trigger)
			.shouldBeInstanceOf<ActivityAutomaticStartServiceValidation.Rejected>()
			.reason shouldBe "AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL"
		dispatcher.drain() shouldBe 1
		val outbox = requireNotNull(
			database.sourceProjectionStateDao().outbox("activity-automatic-effect"),
		)
		(outbox.deliveredAtMs != null) shouldBe true
		outbox.terminalDisposition shouldBe null
		coVerify(exactly = 0) { consumer.deliver(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `a finalized logical identity cannot be recovered or recreated`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "terminal-logical", serviceRunId = "terminal-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest("test-owner", "manual", 2_000, 2_000_000, "boot-1", perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val recovery = startRequest().copy(
			origin = SessionStartOrigin.RECOVERY,
			logicalTrackingId = started.logicalTrackingId,
			serviceRunId = "terminal-run-2",
			continuationAuthority = ServiceRunContinuationAuthority(started.serviceRunId),
			wallTimeMs = 3_000L,
			elapsedRealtimeNanos = 3_000_000L,
		)
		subject.start(recovery) shouldBe SessionStartResult.InvalidIntent("RECOVERY_SESSION_MISSING")
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `service run suspension preserves logical session and recovery resumes exact identity`() = runTest {
		val first = subject.start(
			startRequest().copy(logicalTrackingId = "logical-1", serviceRunId = "run-1"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		first.logicalTrackingId shouldBe "logical-1"
		first.serviceRunId shouldBe "run-1"
		val lifecycleRevisionBeforeSuspend = requireNotNull(
			database.sourceSessionDao().session("logical-1"),
		).lifecycleRevision

		subject.suspendForRestart(
			SessionSuspendRequest(
				"test-owner",
				"ANDROID_RESTART",
				2_000,
				2_000_000,
				"boot-1",
				perSourceTimeoutMs = 100,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()

		database.sourceSessionDao().session("logical-1")?.state shouldBe SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().session("logical-1")?.currentServiceRunId shouldBe null
		database.sourceSessionDao().session("logical-1")?.lifecycleRevision shouldBe
			lifecycleRevisionBeforeSuspend + 1L
		database.sourceSessionDao().serviceRun("run-1")?.state shouldBe SessionLifecycleState.FINALIZED.name

		val restored = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = AcquisitionPlanRevision(
					revision = 2,
					planId = "balanced-restore",
					createdAtMs = 3_000,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2, true, 60_000, 15_000, false)),
					sourcePolicyRevision = 1,
				),
				wallTimeMs = 3_000,
				elapsedRealtimeNanos = 3_000_000,
				logicalTrackingId = "logical-1",
				serviceRunId = "run-2",
				continuationAuthority = ServiceRunContinuationAuthority("run-1"),
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		restored.logicalTrackingId shouldBe "logical-1"
		restored.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().serviceRun("run-2")?.logicalTrackingId shouldBe "logical-1"
		database.sourceSessionDao().session("logical-1")?.currentServiceRunId shouldBe "run-2"
		database.sourceSessionDao().session("logical-1")?.desiredPlanRevision shouldBe 2L
	}

	@Test
	fun `service run suspension stays nonterminal until provider flush retry succeeds`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "suspend-incomplete-logical", serviceRunId = "suspend-incomplete-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.FAILED

		val pending = subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-incomplete-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.CleanupPending>()

		pending.requiredOrdinal shouldBe 0L
		val pendingRun = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		pendingRun.state shouldBe SessionLifecycleState.STOPPING.name
		pendingRun.runtimeAcknowledgement shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		pendingRun.runtimeFailureCode shouldBe "STOP_INCOMPLETE"
		pendingRun.completionReason shouldBe null
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.stopStatus shouldBe SourceStopStatus.COMPLETE.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId).last().status shouldBe
			LifecycleActionStatus.CLEANUP_REQUIRED.name

		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE
		subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-incomplete-owner",
				"ANDROID_RESTART",
				2_500L,
				2_500_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()
		val completedRun = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		completedRun.state shouldBe SessionLifecycleState.FINALIZED.name
		completedRun.runtimeAcknowledgement shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		completedRun.runtimeFailureCode shouldBe null
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe null
	}

	@Test
	fun `ordinary duplicate start cannot replace a live manual session`() = runTest {
		subject.start(
			startRequest().copy(
				logicalTrackingId = "active-manual",
				serviceRunId = "active-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.start(
			startRequest().copy(
				logicalTrackingId = "duplicate-manual",
				serviceRunId = "duplicate-run",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		database.sourceSessionDao().activeSession()?.logicalTrackingId shouldBe "active-manual"
		database.sourceSessionDao().session("active-manual")?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun("active-run")?.completionReason shouldBe null
		database.sourceSessionDao().session("duplicate-manual") shouldBe null
	}

	@Test
	fun `plan from a revoked policy revision is rejected before provider start`() = runTest {
		val initialSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val initial = policy.bootstrapFromLegacy(initialSettings)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = initial.revision,
			settings = initialSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = initialSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_REVOKE",
		)

		val result = subject.start(
			startRequest().copy(
				plan = startRequest().plan.copy(sourcePolicyRevision = initial.revision),
			),
		)

		result shouldBe SessionStartResult.InvalidPolicy("SOURCE_POLICY_REVISION_STALE")
		runtime.stateObservedAtStart shouldBe null
		(database.sourcePolicyDao().authority()?.currentPolicyRevision) shouldBe 2L
	}

	@Test
	fun `unbound new plan is rejected without durable lifecycle intent`() = runTest {
		val result = subject.start(startRequest().copy(plan = startRequest().plan.copy(sourcePolicyRevision = null)))

		result shouldBe SessionStartResult.InvalidPolicy("SOURCE_POLICY_BINDING_MISSING")
		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `plan stronger than current policy QoS is rejected`() = runTest {
		val forged = startRequest().plan.copy(
			plans = mapOf(SourceKind.STEPS to StepsPlan(1, true, 5_000, 2_000, true)),
		)

		subject.start(startRequest().copy(plan = forged)) shouldBe
			SessionStartResult.InvalidPolicy("SOURCE_PLAN_EXCEEDS_POLICY")
		runtime.stateObservedAtStart shouldBe null
	}

	@Test
	fun `manual location capture keeps its consent epoch after unrelated wifi policy change`() = runTest {
		val unchanged = advancePolicyForUnrelatedWifiQos()
		val request = startRequest().copy(
			plan = locationOnlyPlan(unchanged.policyRevision),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			logicalTrackingId = "location-only-manual",
			serviceRunId = "location-only-manual-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("location-only-manual-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.LOCATION.stableCode
		binding.purpose shouldBe SessionManifestPurpose.SESSION_CAPTURE.name
		binding.consentEpoch shouldBe unchanged.locationCaptureEpoch
	}

	@Test
	fun `automatic location start keeps capture and activity control epochs after wifi change`() = runTest {
		val unchanged = advancePolicyForUnrelatedWifiQos()
		val locationMask = 1L shl (SourceKind.LOCATION.stableCode - 1)
		val trigger = automaticTrigger(sourcePolicyRevision = unchanged.policyRevision).copy(
			requestedCaptureSourceMask = locationMask,
			intendedCaptureSourceMask = locationMask,
		)
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			plan = locationOnlyPlan(unchanged.policyRevision),
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			wallTimeMs = 1_000L,
			elapsedRealtimeNanos = 1_000_000L,
			logicalTrackingId = "location-only-automatic",
			serviceRunId = "location-only-automatic-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("location-only-automatic-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val bindings = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
		bindings.size shouldBe 2
		val capture = bindings.single { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
		capture.sourceKind shouldBe SourceKind.LOCATION.stableCode
		capture.consentEpoch shouldBe unchanged.locationCaptureEpoch
		val control = bindings.single { it.purpose == SessionManifestPurpose.CONTROL.name }
		control.sourceKind shouldBe SourceKind.ACTIVITY.stableCode
		control.consentEpoch shouldBe unchanged.activityControlEpoch
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
	}

	@Test
	fun `automatic generation 2 Steps registers only Steps capture and Activity control`() = runTest {
		val binding = installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2)
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			rolloutRevision = rolloutSnapshot.revision,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-steps-v2",
			serviceRunId = "automatic-steps-v2-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-steps-v2-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val manifestSources = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
		manifestSources.filter { source ->
			source.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
		}.let { capture ->
			capture.map(SessionManifestSourceEntity::sourceKind).toSet() shouldBe
				setOf(SourceKind.STEPS.stableCode)
			capture.single().writerProjectionId shouldBe binding.projectionId
			capture.single().writerProjectionVersion shouldBe binding.projectionVersion
			capture.single().writerBindingGeneration shouldBe binding.bindingGeneration
		}
		manifestSources.filter { source ->
			source.purpose == SessionManifestPurpose.CONTROL.name
		}.let { control ->
			control.map(SessionManifestSourceEntity::sourceKind).toSet() shouldBe
				setOf(SourceKind.ACTIVITY.stableCode)
			control.single().persistenceEligible shouldBe false
			control.single().writerOwner shouldBe null
		}

		val demands = database.sourceBrokerDao().demandHistory("session:${prepared.logicalTrackingId}")
		demands.map { demand -> demand.sourceKind to demand.purpose }.toSet() shouldBe setOf(
			SourceKind.STEPS.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
			SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION,
		)
		demands.all { demand -> demand.status == SourceDemandEntity.STATUS_BLOCKED } shouldBe true
		database.sourceBrokerDao().currentDemands("session:${prepared.logicalTrackingId}") shouldBe
			emptyList()
		demands.single { demand -> demand.sourceKind == SourceKind.ACTIVITY.stableCode }
			.persistenceEligible shouldBe false
		setOf(
			SourceKind.LOCATION,
			SourceKind.PRESSURE,
			SourceKind.WIFI,
			SourceKind.CELL,
		).all { source -> demands.none { demand -> demand.sourceKind == source.stableCode } } shouldBe true
	}

	@Test
	fun `generation 1 candidate remains manual only at the lifecycle boundary`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)

		subject.prepareAndroidStart(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				rolloutRevision = rolloutSnapshot.revision,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "automatic-steps-v1-rejected",
				serviceRunId = "automatic-steps-v1-rejected-run",
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-steps-v1-rejected-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("EVENT_CAPTURE_MODE_NOT_REACHABLE")

		database.sourceSessionDao().session("automatic-steps-v1-rejected") shouldBe null
		database.sourceBrokerDao().currentDemands("session:automatic-steps-v1-rejected") shouldBe
			emptyList()
	}

	@Test
	fun `revocation committed during provider start rolls registration back`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(logicalTrackingId = "logical-session", serviceRunId = "service-run"),
			)
		}
		runtime.startEntered.await()
		val currentSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = 1,
			settings = currentSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = currentSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_CONCURRENT_REVOKE",
		)
		// The candidate predates the revocation boundary and remains a durable observation.
		// Provider ownership is still rolled back after start because the current policy changed.
		val durable = requireNotNull(runtime.sinkAtStart).admit(stepsCandidate())
			.shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()
		durable.admissionOrdinal shouldBe 1L
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe true
		database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe null
		val failed = requireNotNull(database.sourceSessionDao().session("logical-session"))
		failed.currentServiceRunId shouldBe null
		database.sourceSessionDao().completenessForServiceRun("logical-session", "service-run")
			.single().registrationGeneration shouldBe 1L
		database.sourceSessionDao().lifecycleIntents(failed.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
	}

	@Test
	fun `manifest tampering committed during provider start rolls exact runtime claim back`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(
					logicalTrackingId = "manifest-race-logical",
					serviceRunId = "manifest-race-run",
				),
			)
		}
		runtime.startEntered.await()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1",
			arrayOf("manifest-race-logical"),
		)
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe true
		database.sourceSessionDao().session("manifest-race-logical")?.state shouldBe
			SessionLifecycleState.FAILED.name
		database.sourceSessionDao().session("manifest-race-logical")?.currentServiceRunId shouldBe null
		database.sourceSessionDao().serviceRun("manifest-race-run")?.runtimeAcknowledgement shouldBe
			LifecycleActionStatus.TERMINAL_FAILURE.name
	}

	@Test
	fun `stale start compensation cannot close a successor runtime claim`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(
					logicalTrackingId = "stale-cleanup-logical",
					serviceRunId = "stale-cleanup-run",
				),
			)
		}
		runtime.startEntered.await()
		val currentSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = 1,
			settings = currentSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = currentSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_SUCCESSOR_RUNTIME_CLAIM",
		)
		runtime.supersedeRuntimeClaim(
			SourceRuntimeClaim(
				source = SourceKind.STEPS,
				actionId = "successor-action",
				attemptCount = 1,
				leaseGeneration = 2L,
				logicalTrackingId = "foreign-logical",
				serviceRunId = "foreign-run",
			),
		)
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe false
		database.sourceSessionDao().completenessForServiceRun(
			"stale-cleanup-logical",
			"stale-cleanup-run",
		) shouldBe emptyList()
	}

	private fun stepsCandidate() = SourceEvidenceCandidate(
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		sourceSequence = 1,
		configRevision = 1,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot-1",
		observedElapsedRealtimeNanos = 1,
		receivedElapsedRealtimeNanos = 2,
		wallTimeMs = 1,
		wallTimeUncertaintyMs = 0,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = 1,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = StepCounterWindowPayload("boot-1", 100, 101, 1, 1, 2, 1, 1, false),
	)

	private suspend fun prepareAndroidStart(
		tokenValue: String,
		commandGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		request: SessionStartRequest = startRequest(),
	): PreparedSessionStart {
		val token = PreparedTrackingStartToken(tokenValue)
		return subject.prepareAndroidStart(
			request.copy(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			),
			AndroidStartDeliveryMetadata(
				token = token,
				commandGeneration = commandGeneration,
				isUserInitiated = request.origin == SessionStartOrigin.MANUAL_FOREGROUND_START,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start
	}

	private suspend fun activatePreparedAndroidRun(
		tokenValue: String,
		commandGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
	): PreparedSessionStart {
		val prepared = prepareAndroidStart(
			tokenValue = tokenValue,
			commandGeneration = commandGeneration,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		subject.markAndroidStartEnqueued(prepared.token, commandGeneration, 1_050L) shouldBe true
		subject.claimAndroidStart(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true
		subject.applyPreparedAndroidStart(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_300_000L,
			1_300L,
		).shouldBeInstanceOf<SessionStartResult.Started>()
		return prepared
	}

	private suspend fun expiredPreparedLeaseElapsedNanos(): Long {
		val lease = requireNotNull(
			database.sourceProjectionStateDao().lease("tracking-session-coordinator"),
		)
		val expiredElapsedNanos = lease.expiresElapsedRealtimeNanos + 1L
		leaseClock.setTime(lease.expiresAtMs + 1L, expiredElapsedNanos)
		return expiredElapsedNanos
	}

	private fun incumbentSessionLease() = SourceCoordinatorLeaseEntity(
		leaseName = "tracking-session-coordinator",
		ownerToken = "incumbent",
		acquiredAtMs = 900L,
		expiresAtMs = 2_000L,
		bootId = "boot-1",
		generation = 7L,
		acquiredElapsedRealtimeNanos = 900_000L,
		expiresElapsedRealtimeNanos = 2_000_000L,
	)

	private suspend fun assertPreparedStartTerminalized(
		prepared: PreparedSessionStart,
		failureCode: String,
	) {
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val run = requireNotNull(sessionDao.serviceRun(prepared.serviceRunId))
		val exactActions = sessionDao.lifecycleActions(prepared.logicalTrackingId).filter { action ->
			action.serviceRunId == prepared.serviceRunId &&
				action.manifestRevision == prepared.manifestRevision
		}
		val demandHistory = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")
		session.state shouldBe SessionLifecycleState.FAILED.name
		session.failureCode shouldBe failureCode
		run.state shouldBe SessionLifecycleState.FAILED.name
		run.androidDeliveryState shouldBe AndroidStartDeliveryState.TERMINAL_FAILURE.name
		run.runtimeFailureCode shouldBe failureCode
		run.completionReason shouldBe failureCode
		exactActions.isNotEmpty() shouldBe true
		exactActions.all { action ->
			action.status == LifecycleActionStatus.SUPERSEDED.name &&
				action.failureCode == failureCode
		} shouldBe true
		demandHistory.isNotEmpty() shouldBe true
		demandHistory.all { demand -> demand.status == SourceDemandEntity.STATUS_RETIRED } shouldBe true
	}

	private suspend fun seedActiveStepsRegistration() {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "prepared-steps-instance",
				ownerScope = "shared:steps",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "prepared-steps-physical",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 500L,
				reservedElapsedRealtimeNanos = 500_000L,
				acceptedAtMs = 600L,
				acceptedElapsedRealtimeNanos = 600_000L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
	}

	private suspend fun advancePreparedEnvelope(prepared: PreparedSessionStart) {
		val sessionDao = database.sourceSessionDao()
		val oldSession = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val oldManifest = requireNotNull(
			sessionDao.manifest(prepared.logicalTrackingId, prepared.manifestRevision),
		)
		val oldIntent = requireNotNull(
			sessionDao.lifecycleIntent(prepared.logicalTrackingId, prepared.intentRevision),
		)
		val oldRun = requireNotNull(sessionDao.serviceRun(prepared.serviceRunId))
		val oldAction = sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == prepared.serviceRunId }
		val oldDemands = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")
		val newManifestRevision = prepared.manifestRevision + 1L
		val newIntentRevision = prepared.intentRevision + 1L
		val newServiceRunId = "advanced-new-run"

		sessionDao.insertManifest(
			oldManifest.copy(
				manifestRevision = newManifestRevision,
				serviceRunId = newServiceRunId,
				effectiveElapsedRealtimeNanos = oldManifest.effectiveElapsedRealtimeNanos + 1L,
				effectiveWallTimeMs = oldManifest.effectiveWallTimeMs + 1L,
				changeReason = "TEST_ENVELOPE_ADVANCED",
				manifestChecksum = "test-advanced-manifest-checksum",
			),
		)
		sessionDao.insertManifestSources(
			sessionDao.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
				.map { binding -> binding.copy(manifestRevision = newManifestRevision) },
		)
		sessionDao.insertLifecycleIntent(
			oldIntent.copy(
				intentRevision = newIntentRevision,
				manifestRevision = newManifestRevision,
				requestedElapsedRealtimeNanos = oldIntent.requestedElapsedRealtimeNanos + 1L,
				requestedWallTimeMs = oldIntent.requestedWallTimeMs + 1L,
				intentChecksum = "test-advanced-intent-checksum",
			),
		)
		sessionDao.insertLifecycleActions(
			listOf(
				oldAction.copy(
					actionId = "advanced-new-action",
					serviceRunId = newServiceRunId,
					manifestRevision = newManifestRevision,
					actionRevision = oldAction.actionRevision + 1L,
					requestedAtMs = oldAction.requestedAtMs + 1L,
					requestedElapsedRealtimeNanos = oldAction.requestedElapsedRealtimeNanos + 1L,
				),
			),
		)
		sessionDao.insertServiceRun(
			oldRun.copy(
				serviceRunId = newServiceRunId,
				startedAtMs = oldRun.startedAtMs + 1L,
				startedElapsedNanos = oldRun.startedElapsedNanos + 1L,
				runRevision = 1L,
				startDeliveryToken = "advanced-new-token",
				startCommandGeneration = oldRun.startCommandGeneration + 1L,
				preparedManifestRevision = newManifestRevision,
				preparedIntentRevision = newIntentRevision,
			),
		)
		if (oldDemands.isNotEmpty()) {
			database.sourceBrokerDao().insertDemands(
				oldDemands.mapIndexed { index, demand ->
					demand.copy(
						demandId = "advanced-new-demand-$index",
						serviceRunId = newServiceRunId,
						manifestRevision = newManifestRevision,
						requestedElapsedRealtimeNanos = demand.requestedElapsedRealtimeNanos + 1L,
						requestedAtMs = demand.requestedAtMs + 1L,
					)
				},
			)
		}
		sessionDao.updateSession(
			oldSession.copy(
				lifecycleRevision = oldSession.lifecycleRevision + 1L,
				currentManifestRevision = newManifestRevision,
				currentIntentRevision = newIntentRevision,
				currentServiceRunId = newServiceRunId,
			),
		) shouldBe 1
	}

	private fun startRequest() = SessionStartRequest(
		ownerToken = "test-owner",
		origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
		plan = AcquisitionPlanRevision(
			revision = 1,
			planId = "balanced",
			createdAtMs = 1_000,
			plans = mapOf(SourceKind.STEPS to StepsPlan(1, true, 60_000, 15_000, false)),
			sourcePolicyRevision = 1,
		),
		rolloutRevision = 1,
		clockDomainId = "boot-1",
		foregroundCapabilityFlags = 0,
		wallTimeMs = 1_000,
		elapsedRealtimeNanos = 1_000_000,
		zoneId = "Europe/Prague",
	)

	private fun pressureStartRequest(
		logicalTrackingId: String,
		serviceRunId: String,
		rolloutRevision: Long = 1L,
	) = startRequest().copy(
		plan = AcquisitionPlanRevision(
			revision = 1L,
			planId = "pressure-only",
			createdAtMs = 1_000L,
			plans = mapOf(
				SourceKind.PRESSURE to PressurePlan(
					revision = 1L,
					enabled = true,
					hardwareSamplePeriodMicros = 200_000,
					maximumReportLatencyMicros = 10_000_000,
					aggregationWindowMs = 10_000L,
					movementGatedBurst = false,
				),
			),
			sourcePolicyRevision = 1L,
		),
		rolloutRevision = rolloutRevision,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private fun locationOnlyPlan(policyRevision: Long) = AcquisitionPlanRevision(
		revision = 2L,
		planId = "location-only-after-wifi-policy-change",
		createdAtMs = 2_000L,
		plans = mapOf(
			SourceKind.LOCATION to LocationPlan(
				revision = 2L,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 2_000L,
				minimumUpdateIntervalMs = 2_000L,
				minimumDisplacementMeters = 10f,
				maximumBatchDelayMs = 10_000L,
				preciseLocationAvailable = true,
			),
		),
		sourcePolicyRevision = policyRevision,
	)

	private suspend fun advancePolicyForUnrelatedWifiQos(): UnchangedConsentEpochs {
		val dao = database.sourcePolicyDao()
		val revisionOneLocation = requireNotNull(
			dao.policyAtRevision(1L, SourceKind.LOCATION.stableCode),
		)
		val revisionOneActivity = requireNotNull(
			dao.policyAtRevision(1L, SourceKind.ACTIVITY.stableCode),
		)
		val settings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val revisionTwo = policy.replaceCaptureSettings(
			expectedPolicyRevision = 1L,
			settings = settings.copy(
				wifiEnabled = true,
				sourceCollectionSettings = settings.sourceCollectionSettings.copy(
					wifi = SourceCollectionFrequency.BATTERY_SAVER,
				),
			),
			reason = "TEST_UNRELATED_WIFI_QOS",
		)
		val revisionTwoLocation = requireNotNull(
			dao.policyAtRevision(revisionTwo.revision, SourceKind.LOCATION.stableCode),
		)
		val revisionTwoActivity = requireNotNull(
			dao.policyAtRevision(revisionTwo.revision, SourceKind.ACTIVITY.stableCode),
		)
		revisionTwo.revision shouldBe 2L
		revisionTwoLocation.captureConsentEpoch shouldBe revisionOneLocation.captureConsentEpoch
		revisionTwoActivity.controlConsentEpoch shouldBe revisionOneActivity.controlConsentEpoch
		dao.consentEpoch(
			SourceKind.LOCATION.stableCode,
			"SESSION_CAPTURE",
			requireNotNull(revisionTwoLocation.captureConsentEpoch),
		)?.policyRevision shouldBe 1L
		dao.consentEpoch(
			SourceKind.ACTIVITY.stableCode,
			"CONTROL",
			requireNotNull(revisionTwoActivity.controlConsentEpoch),
		)?.policyRevision shouldBe 1L
		dao.latestConsentEpoch(SourceKind.LOCATION.stableCode, "SESSION_CAPTURE")?.epoch shouldBe
			revisionOneLocation.captureConsentEpoch
		dao.latestConsentEpoch(SourceKind.ACTIVITY.stableCode, "CONTROL")?.epoch shouldBe
			revisionOneActivity.controlConsentEpoch
		return UnchangedConsentEpochs(
			policyRevision = revisionTwo.revision,
			locationCaptureEpoch = requireNotNull(revisionOneLocation.captureConsentEpoch),
			activityControlEpoch = requireNotNull(revisionOneActivity.controlConsentEpoch),
		)
	}

	private fun automaticTrigger(
		automationEpoch: Long = 9L,
		sourcePolicyRevision: Long = 1L,
		intendedForegroundServiceTypeMask: Long = 0L,
	): AutomaticTrackingStartTrigger {
		val stepsMask = 1L shl (SourceKind.STEPS.stableCode - 1)
		return AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:boot-1:1",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = "boot-1",
			observedElapsedRealtimeNanos = 500_000L,
			receivedElapsedRealtimeNanos = 600_000L,
			expiresElapsedRealtimeNanos = 2_000_000L,
			automationEpoch = automationEpoch,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = sourcePolicyRevision,
			requestedCaptureSourceMask = stepsMask,
			intendedCaptureSourceMask = stepsMask,
			intendedForegroundServiceTypeMask = intendedForegroundServiceTypeMask,
			collectedDataEpoch = 0L,
		)
	}

	private suspend fun seedAutomaticStartAction(trigger: AutomaticTrackingStartTrigger) {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = trigger.automationEpoch,
				automaticControlEnabled = true,
				bootClockDomainId = trigger.bootId,
				effectiveElapsedRealtimeNanos = 0L,
				lastRotationReason = "TEST_SEED",
			),
		)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = trigger.collectedDataEpoch),
		)
		val controlConsentEpoch = requireNotNull(
			database.sourcePolicyDao()
				.policyAtRevision(trigger.sourcePolicyRevision, SourceKind.ACTIVITY.stableCode)
				?.controlConsentEpoch,
		)
		val authorizationFingerprint = "activity-control-authorization"
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-automatic-provider",
				ownerScope = "activity-automatic-control",
				clockDomainId = trigger.bootId,
				physicalConfigurationFingerprint = "activity-automatic-physical",
				collectedDataEpoch = trigger.collectedDataEpoch,
				providerResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 1L,
				reservedElapsedRealtimeNanos = 1L,
				acceptedAtMs = 1L,
				acceptedElapsedRealtimeNanos = 1L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:activity-control",
					authorizationFingerprint = authorizationFingerprint,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
					demandId = "activity-control",
					consumerId = "automatic-control",
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					sourcePolicyRevision = trigger.sourcePolicyRevision,
					consentEpoch = controlConsentEpoch,
					persistenceEligible = false,
					effectiveBootId = trigger.bootId,
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
		database.activityAutomaticStartActionDao().insertIfSlotFree(
			ActivityAutomaticStartActionEntity(
				triggerId = trigger.triggerId,
				effectStableId = "activity-automatic-effect",
				admissionOrdinal = 1L,
				triggerKind = trigger.kind,
				bootId = trigger.bootId,
				observedElapsedRealtimeNanos = trigger.observedElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
				expiresElapsedRealtimeNanos = trigger.expiresElapsedRealtimeNanos,
				automationEpoch = trigger.automationEpoch,
				sourcePolicyRevision = trigger.sourcePolicyRevision,
				controlConsentEpoch = controlConsentEpoch,
				collectedDataEpoch = trigger.collectedDataEpoch,
				requestedCaptureSourceMask = trigger.requestedCaptureSourceMask,
				intendedCaptureSourceMask = trigger.intendedCaptureSourceMask,
				intendedForegroundServiceTypeMask = trigger.intendedForegroundServiceTypeMask,
				registrationGeneration = 1L,
				authorizationRevision = 1L,
				authorizationFingerprint = authorizationFingerprint,
				startOrigin = trigger.startContext.name,
				status = ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
				reservedAtMs = 500L,
				startRequestedAtMs = 900L,
				lifecycleIntentAcceptedAtMs = null,
				acceptedLogicalTrackingId = null,
				acceptedIntentRevision = null,
				terminalAtMs = null,
				terminalReason = null,
			),
		) shouldBe 1L
	}

	private fun automaticOutboxPayload(trigger: AutomaticTrackingStartTrigger): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(ActivityAutomationProjection.KIND_TRANSITION)
				output.writeInt(1) // WALKING stable code
				output.writeInt(100)
				output.writeInt(ActivityTransitionType.ENTER.value)
				output.writeUTF(trigger.bootId)
				output.writeLong(trigger.observedElapsedRealtimeNanos)
				output.writeLong(trigger.receivedElapsedRealtimeNanos)
				output.writeLong(1L)
				output.writeLong(1L)
				output.writeUTF("activity-control-authorization")
				output.writeLong(trigger.collectedDataEpoch)
				output.writeLong(trigger.automationEpoch)
			}
			bytes.toByteArray()
		}

	private suspend fun installStepsCandidateRollout(
		binding: ExecutableSourceLaneBinding,
	): ExecutableSourceLaneBinding {
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		))
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = owner.sourceKind,
			destination = owner.destination,
			expectedOwner = owner.owner,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		) == 1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS),
			controlSources = setOf(SourceKind.ACTIVITY),
			revision = 2L,
			captureModes = mapOf(SourceKind.STEPS to binding.captureModes),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = rolloutSnapshot.revision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 1L))
		return binding
	}

	private suspend fun installPressureCandidateRollout(): ExecutableSourceLaneBinding {
		val binding = ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		))
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = owner.sourceKind,
			destination = owner.destination,
			expectedOwner = owner.owner,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		) == 1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.PRESSURE),
			revision = 2L,
			captureModes = mapOf(SourceKind.PRESSURE to binding.captureModes),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = rolloutSnapshot.revision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 1L))
		return binding
	}

	private fun fixedEventRolloutStore() = object : TrackingRolloutStateStore {
		override suspend fun load() = rolloutSnapshot

		override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
	}

	private suspend fun persistRolloutRevision(revision: Long) {
		database.trackingRolloutStateDao().save(
			fixedEventRollout().copy(revision = revision).toEntity(updatedAtMs = revision),
		)
	}

	private fun fixedEventRollout() = TrackingRolloutState(
			revision = 1,
			schemaVersion = TrackingRolloutState.CURRENT_SCHEMA_VERSION,
			coordinatorMode = CoordinatorMode.EVENT,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			productProjectionStages = SourceKind.entries.associateWith {
				ProductProjectionStage.EVENT_CANONICAL
			},
			captureModeMasks = SourceKind.entries.associateWith {
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask or
					CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE.mask
			},
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)
}

private data class UnchangedConsentEpochs(
	val policyRevision: Long,
	val locationCaptureEpoch: Long,
	val activityControlEpoch: Long,
)

private class RecordingActivityAutomationDrainSignal : ActivityAutomationDrainSignal {
	var requestCount: Int = 0
		private set

	override fun requestDrain() {
		requestCount++
	}
}

private class FakeStepsRuntime(private val database: AppDatabase) : ClaimedSourceRuntime<StepsPlan> {
	override val source = SourceKind.STEPS
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var stateObservedAtStart: String? = null
	var manifestRevisionObservedAtStart: Long? = null
	var intentDesiredStateObservedAtStart: String? = null
	var actionStatusObservedAtStart: String? = null
	var closed = false
	var blockStart = false
	var startFailure: Throwable? = null
	var startFailureAfterSideEffect: Throwable? = null
	var startReturnsRetryableFailure = false
	var reconfigureFailure: Throwable? = null
	var sinkAtStart: SourceEventSink? = null
	var startCount = 0
	var reconfigureCount = 0
	var stopStatus = SourceStopStatus.COMPLETE
	var registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
	var stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE
	var retainProviderOnIncompleteShutdown = false
	var closeFailure: Throwable? = null
	var acknowledgementLogicalTrackingId: String? = null
	var acknowledgementServiceRunId: String? = null
	var omitAcknowledgementMembership = false
	val shutdownClaims = mutableListOf<SourceRuntimeClaim>()
	val startEntered = CompletableDeferred<Unit>()
	val releaseStart = CompletableDeferred<Unit>()
	private var active = false
	val isActive: Boolean get() = active
	private var registrationGeneration = 0L
	private var ownedClaim: SourceRuntimeClaim? = null

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		ownedClaim = claim
		return start(plan, sink)
	}

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
		startCount += 1
		startFailure?.let { throw it }
		val session = database.sourceSessionDao().activeSession()
		stateObservedAtStart = session?.state
		manifestRevisionObservedAtStart = session?.currentManifestRevision
		intentDesiredStateObservedAtStart = session?.currentIntentRevision?.let { revision ->
			database.sourceSessionDao().lifecycleIntent(requireNotNull(session).logicalTrackingId, revision)?.desiredState
		}
		actionStatusObservedAtStart = session?.let { current ->
			database.sourceSessionDao().lifecycleActions(current.logicalTrackingId).lastOrNull()?.status
		}
		sinkAtStart = sink
		if (blockStart) {
			startEntered.complete(Unit)
			releaseStart.await()
		}
		if (plan.enabled && !active) registrationGeneration += 1L
		active = plan.enabled
		startFailureAfterSideEffect?.let { throw it }
		val applied = applied(plan)
		return if (startReturnsRetryableFailure) {
			SourceStartResult.Failed(applied, retryable = true)
		} else {
			SourceStartResult.Started(applied)
		}
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult {
		reconfigureCount += 1
		reconfigureFailure?.let { throw it }
		val stopAck = if (active && !plan.enabled) stopAck() else null
		if (plan.enabled && !active) registrationGeneration += 1L
		active = plan.enabled
		return SourceApplyResult.Applied(applied(plan), stopAck)
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		ownedClaim = claim
		return reconfigure(plan, sink).also {
			if (!plan.enabled) ownedClaim = null
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		val acknowledgement = stopAck()
		if (isComplete(acknowledgement) || !retainProviderOnIncompleteShutdown) active = false
		return acknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		if (ownedClaim != claim) return OwnedSourceShutdown.NotOwned
		shutdownClaims += claim
		val acknowledgement = stopAck()
		val complete = isComplete(acknowledgement)
		return if (complete) {
			active = false
			closed = true
			ownedClaim = null
			OwnedSourceShutdown.Released(
				provider = null,
				stopAck = acknowledgement,
			)
		} else {
			if (!retainProviderOnIncompleteShutdown) active = false
			OwnedSourceShutdown.Incomplete(
				provider = null,
				stopAck = acknowledgement,
			)
		}
	}

	fun supersedeRuntimeClaim(claim: SourceRuntimeClaim) {
		ownedClaim = claim
	}

	private fun isComplete(acknowledgement: SourceStopAck): Boolean =
		acknowledgement.status == SourceStopStatus.COMPLETE &&
			acknowledgement.registrationRemovalOutcome == RegistrationRemovalOutcome.REMOVED &&
			acknowledgement.providerFlushOutcome !in setOf(
				ProviderFlushOutcome.FAILED,
				ProviderFlushOutcome.TIMED_OUT,
			) && acknowledgement.appDrainComplete

	private fun stopAck() = SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = registrationGeneration,
		appliedRevision = 1,
		callbackEntryBarrierSequence = 4,
		lastDurablyAdmittedSequence = 4,
		lastAdmissionOrdinal = null,
		failedAdmissionCount = 0,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = registrationRemovalOutcome,
		providerFlushOutcome = stopProviderFlushOutcome,
		providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
		appDrainComplete = true,
		status = stopStatus,
		logicalTrackingId = if (omitAcknowledgementMembership) {
			null
		} else {
			acknowledgementLogicalTrackingId ?: ownedClaim?.logicalTrackingId
		},
		serviceRunId = if (omitAcknowledgementMembership) {
			null
		} else {
			acknowledgementServiceRunId ?: ownedClaim?.serviceRunId
		},
	)

	override suspend fun close() {
		closeFailure?.let { throw it }
		closed = true
		active = false
		ownedClaim = null
	}

	private fun applied(plan: SourcePlan) = AppliedSourcePlan(
		desiredRevision = plan.revision,
		appliedRevision = plan.revision,
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance").takeIf { plan.enabled },
		registrationGeneration = registrationGeneration.takeIf { plan.enabled },
		appliedAtElapsedRealtimeNanos = 1_000_000,
		status = SourceApplyStatus.APPLIED,
	)
}

private class FakeLocationRuntime : ClaimedSourceRuntime<LocationPlan> {
	override val source = SourceKind.LOCATION
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var failRetirement = false
	var quiesceCount = 0
	var closeCount = 0
	private var active = false
	private var ownedClaim: SourceRuntimeClaim? = null
	val isActive: Boolean get() = active

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		ownedClaim = claim
		return start(plan, sink)
	}

	override suspend fun start(plan: LocationPlan, sink: SourceEventSink): SourceStartResult {
		active = plan.enabled
		return SourceStartResult.Started(applied(plan))
	}

	override suspend fun reconfigure(plan: LocationPlan, sink: SourceEventSink): SourceApplyResult {
		if (!plan.enabled && failRetirement) {
			return SourceApplyResult.Failed(
				state = applied(plan).copy(status = SourceApplyStatus.FAILED),
				retryable = true,
				stopAck = stopAck(),
			)
		}
		active = plan.enabled
		return SourceApplyResult.Applied(applied(plan))
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		if (plan.enabled || !failRetirement) ownedClaim = claim
		return reconfigure(plan, sink).also {
			if (!plan.enabled && !failRetirement) ownedClaim = null
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		quiesceCount += 1
		val acknowledgement = stopAck()
		if (!acknowledgement.hasIncompleteTerminalRetirementForTest()) {
			active = false
			ownedClaim = null
		}
		return acknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		if (ownedClaim != claim) return OwnedSourceShutdown.NotOwned
		val acknowledgement = quiesce(cutoff)
		return if (acknowledgement.hasIncompleteTerminalRetirementForTest()) {
			OwnedSourceShutdown.Incomplete(provider = null, stopAck = acknowledgement)
		} else {
			OwnedSourceShutdown.Released(provider = null, stopAck = acknowledgement)
		}
	}

	private fun stopAck() = SourceStopAck(
			source = source,
			sourceInstanceId = SourceInstanceId("location-instance"),
			registrationGeneration = 1L,
			appliedRevision = 1L,
			callbackEntryBarrierSequence = 0L, lastDurablyAdmittedSequence = null,
			lastAdmissionOrdinal = null, failedAdmissionCount = 0L,
			unresolvedSequenceStart = null, unresolvedSequenceEndInclusive = null,
			registrationRemovalOutcome = if (failRetirement) {
				RegistrationRemovalOutcome.FAILED
			} else {
				RegistrationRemovalOutcome.REMOVED
			},
			providerFlushOutcome = if (failRetirement) {
				ProviderFlushOutcome.FAILED
			} else {
				ProviderFlushOutcome.COMPLETE
			},
			providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
			appDrainComplete = !failRetirement,
			status = if (failRetirement) SourceStopStatus.PROVIDER_FAILED else SourceStopStatus.COMPLETE,
			logicalTrackingId = ownedClaim?.logicalTrackingId,
			serviceRunId = ownedClaim?.serviceRunId,
		)

	override suspend fun close() {
		closeCount += 1
		if (failRetirement) error("location provider still resident")
		active = false
		ownedClaim = null
	}

	private fun applied(plan: LocationPlan) = AppliedSourcePlan(
		plan.revision, plan.revision, source,
		SourceInstanceId("location-instance").takeIf { active }, 1L.takeIf { active },
		1_000_000L, SourceApplyStatus.APPLIED,
	)
}

private fun SourceStopAck.hasIncompleteTerminalRetirementForTest(): Boolean =
	status != SourceStopStatus.COMPLETE ||
		registrationRemovalOutcome == RegistrationRemovalOutcome.FAILED ||
		providerFlushOutcome in setOf(ProviderFlushOutcome.FAILED, ProviderFlushOutcome.TIMED_OUT) ||
		!appDrainComplete
