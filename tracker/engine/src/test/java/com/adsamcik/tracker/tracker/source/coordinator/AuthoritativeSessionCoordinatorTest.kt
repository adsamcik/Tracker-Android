package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
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
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritativeSessionCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: FakeStepsRuntime
	private lateinit var subject: AuthoritativeSessionCoordinator
	private lateinit var policy: RoomSourcePolicyRepository

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 1_000_000, 1_000)
		}
		runBlocking {
			policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		}
		runtime = FakeStepsRuntime(database)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.admit(any()) } returns
			AdmissionResult.Admitted(SourceEventId("test-admitted-event"), 1L)
		coEvery { ingress.committedBatch(any(), any()) } returns emptyList()
		val dispatcher = ProjectionDispatcher(database, emptySet())
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, dispatcher),
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
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
		database.sourceSessionDao().completeness(started.logicalTrackingId).single().appDrainComplete shouldBe true
		database.sourceBrokerDao().currentDemands("session:${started.logicalTrackingId}") shouldBe emptyList()
		database.sourceBrokerDao().demandHistory("session:${started.logicalTrackingId}")
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		runtime.closed shouldBe true
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
	fun `source reconfiguration cancellation propagates without closing provider`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
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
		runtime.closed shouldBe false
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId).last().status shouldBe
			LifecycleActionStatus.APPLYING.name
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
			automaticTrigger = AutomaticTriggerEvidence(
				triggerId = "motion-1",
				kind = "ON_FOOT",
				bootId = "old-boot",
				observedElapsedRealtimeNanos = 500_000L,
				receivedElapsedRealtimeNanos = 600_000L,
				expiresElapsedRealtimeNanos = 2_000_000L,
				automationEpoch = 1L,
			),
		)
		subject.start(staleAutomatic) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_TRIGGER_BOOT_STALE")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
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
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		restored.logicalTrackingId shouldBe "logical-1"
		restored.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().latestServiceRun("logical-1")?.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().session("logical-1")?.desiredPlanRevision shouldBe 2L
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
		database.sourceSessionDao().lifecycleIntents(failed.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
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

	private fun fixedEventRolloutStore() = object : TrackingRolloutStateStore {
		override suspend fun load() = TrackingRolloutState(
			revision = 1,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.EVENT,
			projectionMode = ProjectionMode.EVENT_CANONICAL,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
	}
}

private class FakeStepsRuntime(private val database: AppDatabase) : SourceRuntime<StepsPlan> {
	override val source = SourceKind.STEPS
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var stateObservedAtStart: String? = null
	var manifestRevisionObservedAtStart: Long? = null
	var intentDesiredStateObservedAtStart: String? = null
	var actionStatusObservedAtStart: String? = null
	var closed = false
	var blockStart = false
	var startFailure: Throwable? = null
	var reconfigureFailure: Throwable? = null
	var sinkAtStart: SourceEventSink? = null
	val startEntered = CompletableDeferred<Unit>()
	val releaseStart = CompletableDeferred<Unit>()

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
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
		return SourceStartResult.Started(applied(plan))
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult {
		reconfigureFailure?.let { throw it }
		return SourceApplyResult.Applied(applied(plan))
	}

	override suspend fun quiesce(cutoff: SessionCutoff) = SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		appliedRevision = 1,
		callbackEntryBarrierSequence = 4,
		lastDurablyAdmittedSequence = 4,
		lastAdmissionOrdinal = null,
		failedAdmissionCount = 0,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED,
		providerFlushOutcome = ProviderFlushOutcome.COMPLETE,
		providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
		appDrainComplete = true,
		status = SourceStopStatus.COMPLETE,
	)

	override suspend fun close() {
		closed = true
	}

	private fun applied(plan: SourcePlan) = AppliedSourcePlan(
		desiredRevision = plan.revision,
		appliedRevision = plan.revision,
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		appliedAtElapsedRealtimeNanos = 1_000_000,
		status = SourceApplyStatus.APPLIED,
	)
}
