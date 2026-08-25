package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
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
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
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
	private lateinit var subject: AuthoritativeSessionCoordinator
	private lateinit var policy: RoomSourcePolicyRepository
	private lateinit var activityAutomationDrainSignal: RecordingActivityAutomationDrainSignal
	private lateinit var activityAutomationEpochAuthority: ActivityAutomationEpochAuthority
	private var activityLocked = false

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		activityLocked = false
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
		activityAutomationDrainSignal = RecordingActivityAutomationDrainSignal()
		val lockManager = mockk<LockManager>(relaxed = true)
		every { lockManager.isLocked } answers { activityLocked }
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		activityAutomationEpochAuthority = ActivityAutomationEpochAuthority(
			database,
			context,
			lockManager,
			FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000_000L),
			BootClockDomainProvider { "boot-1" },
		)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, dispatcher),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
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
				continuationAuthority = ServiceRunContinuationAuthority("run-1"),
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		restored.logicalTrackingId shouldBe "logical-1"
		restored.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().latestServiceRun("logical-1")?.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().session("logical-1")?.desiredPlanRevision shouldBe 2L
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

	private suspend fun expiredPreparedLeaseElapsedNanos(): Long =
		requireNotNull(
			database.sourceProjectionStateDao().lease("tracking-session-coordinator"),
		).expiresElapsedRealtimeNanos + 1L

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

	private fun fixedEventRolloutStore() = object : TrackingRolloutStateStore {
		override suspend fun load() = TrackingRolloutState(
			revision = 1,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.EVENT,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			productProjectionStages = SourceKind.entries.associateWith {
				ProductProjectionStage.EVENT_SHADOW
			},
			captureModeMasks = SourceKind.entries.associateWith {
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask
			},
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
	}
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
