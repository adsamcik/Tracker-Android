package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
class PreviousExitSourceSessionFinalizerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `startup finalizes old boot manual authority and clears its exact descriptor`() = runTest {
		seedAutomationEpoch()
		insertSession(AUTOMATIC_ID, SessionMode.AUTOMATIC, OLD_BOOT_ID)
		insertSession(MANUAL_ID, SessionMode.MANUAL, OLD_BOOT_ID)
		insertRun(AUTOMATIC_ID, OLD_BOOT_ID)
		insertRun(MANUAL_ID, OLD_BOOT_ID)
		insertPendingAction(AUTOMATIC_ID, OLD_BOOT_ID)
		insertPendingAction(MANUAL_ID, OLD_BOOT_ID)
		val automaticDemand = demand(AUTOMATIC_ID, "auto-demand")
		val manualDemand = demand(MANUAL_ID, "manual-demand")
		val brokerDao = database.sourceBrokerDao()
		brokerDao.insertDemands(listOf(automaticDemand, manualDemand))
		brokerDao.insertRegistration(activeRegistration())
		brokerDao.insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = SOURCE_KIND,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = 1L,
				demands = listOf(automaticDemand, manualDemand),
				effectiveBootId = CURRENT_BOOT_ID,
				effectiveElapsedRealtimeNanos = 1_000L,
				effectiveWallTimeMs = 1_000L,
			),
		)

		val descriptor = manualDescriptor(OLD_BOOT_ID)
		val store = RecordingStore(descriptor)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			mockk(relaxed = true),
			finalizer(),
			mockk(relaxed = true),
		)

		val result = coordinator.reconcileStaleSessions(completedAtMs = EXIT_AT_MS)

		result.finalizedLogicalTrackingIds shouldContainExactly setOf(AUTOMATIC_ID, MANUAL_ID)
		val automatic = database.sourceSessionDao().session(AUTOMATIC_ID)
		automatic?.state shouldBe SessionLifecycleState.FINALIZED.name
		automatic?.cutoffAtMs shouldBe EXIT_AT_MS
		automatic?.completedAtMs shouldBe EXIT_AT_MS
		// elapsedRealtime from a new boot cannot be assigned to an old-boot session.
		automatic?.cutoffElapsedNanos shouldBe null
		val manual = database.sourceSessionDao().session(MANUAL_ID)
		manual?.state shouldBe SessionLifecycleState.FINALIZED.name
		manual?.cutoffAtMs shouldBe EXIT_AT_MS
		manual?.cutoffElapsedNanos shouldBe null
		listOf(AUTOMATIC_ID, MANUAL_ID).forEach { logicalTrackingId ->
			val run = database.sourceSessionDao().serviceRun(runId(logicalTrackingId))
			run?.state shouldBe SessionLifecycleState.FINALIZED.name
			run?.completedAtMs shouldBe EXIT_AT_MS
			run?.completionReason shouldBe PreviousExitSourceSessionFinalizer.COMPLETION_REASON
			val action = database.sourceSessionDao().lifecycleAction(actionId(logicalTrackingId))
			action?.status shouldBe LifecycleActionStatus.TERMINAL_FAILURE.name
			action?.acknowledgedElapsedRealtimeNanos shouldBe null
		}

		brokerDao.demandHistory(sessionConsumerId(AUTOMATIC_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		brokerDao.demandHistory(sessionConsumerId(MANUAL_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		brokerDao.latestAuthorization(SOURCE_KIND, REGISTRATION_GENERATION)
			.mapNotNull { it.consumerId } shouldBe emptyList()
		store.descriptor shouldBe null
		store.clearCount shouldBe 1
		database.activityAutomationEpochDao().current()?.epoch shouldBe 18L
	}

	@Test
	fun `same boot restart eligible manual authority is preserved`() = runTest {
		seedAutomationEpoch()
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "manual-demand", CURRENT_BOOT_ID)),
		)

		val result = finalizer().finalizeStaleSessions(
			recoveryDescriptor = manualDescriptor(CURRENT_BOOT_ID),
		)

		result.finalizedLogicalTrackingIds shouldBe emptySet()
		database.sourceSessionDao().session(MANUAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().lifecycleAction(actionId(MANUAL_ID))?.status shouldBe
			LifecycleActionStatus.PENDING.name
		database.sourceBrokerDao().demandHistory(sessionConsumerId(MANUAL_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_ACTIVE
		database.activityAutomationEpochDao().current()?.epoch shouldBe 17L
	}

	@Test
	fun `same boot stopping manual authority is finalized despite active descriptor`() = runTest {
		insertSession(
			MANUAL_ID,
			SessionMode.MANUAL,
			CURRENT_BOOT_ID,
			state = SessionLifecycleState.STOPPING,
		)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "stopping-manual-demand", CURRENT_BOOT_ID)),
		)

		finalizer().finalizeStaleSessions(
			recoveryDescriptor = manualDescriptor(CURRENT_BOOT_ID),
		)

		assertManualAuthorityFinalized("stopping-manual-demand")
	}

	@Test
	fun `descriptor naming a completed prior run finalizes the mismatched active manual run`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		insertRun(
			logicalTrackingId = MANUAL_ID,
			bootId = CURRENT_BOOT_ID,
			serviceRunId = STALE_RUN_ID,
			state = SessionLifecycleState.FINALIZED,
			completedAtMs = 2_000L,
		)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "mismatched-run-demand", CURRENT_BOOT_ID)),
		)

		finalizer().finalizeStaleSessions(
			recoveryDescriptor = manualDescriptor(
				bootId = CURRENT_BOOT_ID,
				serviceRunId = STALE_RUN_ID,
			),
		)

		assertManualAuthorityFinalized("mismatched-run-demand")
		database.sourceSessionDao().serviceRun(STALE_RUN_ID)?.completedAtMs shouldBe 2_000L
	}

	@Test
	fun `same boot delayed relaunch never uses current elapsed time as historical cutoff`() = runTest {
		insertSession(AUTOMATIC_ID, SessionMode.LEGACY_UNKNOWN, CURRENT_BOOT_ID)

		finalizer().finalizeStaleSessions(factualCompletedAtMs = 500L)

		val session = database.sourceSessionDao().session(AUTOMATIC_ID)
		session?.cutoffAtMs shouldBe STARTED_AT_MS
		session?.completedAtMs shouldBe STARTED_AT_MS
		session?.cutoffElapsedNanos shouldBe null
	}

	@Test
	fun `missing exit info falls back to the last durable start boundary not next launch`() = runTest {
		insertSession(AUTOMATIC_ID, SessionMode.AUTOMATIC, CURRENT_BOOT_ID)

		finalizer().finalizeStaleSessions(factualCompletedAtMs = null)

		val session = database.sourceSessionDao().session(AUTOMATIC_ID)
		session?.cutoffAtMs shouldBe STARTED_AT_MS
		session?.cutoffElapsedNanos shouldBe 1_000L
		session?.completedAtMs shouldBe RECOVERY_AT_MS
		session?.failureCode shouldBe PreviousExitSourceSessionFinalizer.COMPLETION_REASON
	}

	private fun finalizer() = PreviousExitSourceSessionFinalizer(
		Provider { database },
		FixedClock(RECOVERY_AT_MS, RECOVERY_ELAPSED_NANOS),
		CurrentBootClockDomainProvider,
	)

	private suspend fun seedAutomationEpoch() {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = 17,
				automaticControlEnabled = true,
				lastRotationReason = "TEST_SEED",
			),
		)
	}

	private suspend fun insertSession(
		logicalTrackingId: String,
		mode: SessionMode,
		clockDomainId: String,
		state: SessionLifecycleState = SessionLifecycleState.ACTIVE,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalTrackingId,
				state = state.name,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "AUTOMATIC_CONTROL",
				clockDomainId = clockDomainId,
				startedAtMs = STARTED_AT_MS,
				startedElapsedNanos = 1_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = mode.name,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = clockDomainId,
			),
		)
	}

	private suspend fun insertRun(
		logicalTrackingId: String,
		bootId: String,
		serviceRunId: String = runId(logicalTrackingId),
		state: SessionLifecycleState = SessionLifecycleState.ACTIVE,
		completedAtMs: Long? = null,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = state.name,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = STARTED_AT_MS,
				startedElapsedNanos = 1_000L,
				completedAtMs = completedAtMs,
				completionReason = completedAtMs?.let { "PRIOR_RUN_COMPLETED" },
				bootId = bootId,
				leaseGeneration = 1L,
			),
		)
	}

	private suspend fun insertPendingAction(logicalTrackingId: String, bootId: String) {
		database.sourceSessionDao().insertLifecycleActions(
			listOf(
				LifecycleDesiredActionEntity(
					actionId = actionId(logicalTrackingId),
					logicalTrackingId = logicalTrackingId,
					serviceRunId = runId(logicalTrackingId),
					manifestRevision = 1L,
					actionRevision = 1L,
					actionFamily = "SERVICE",
					sourceKind = null,
					desiredState = SessionLifecycleState.ACTIVE.name,
					desiredPlanRevision = 1L,
					sourcePolicyRevision = 1L,
					consentEpoch = null,
					startOrigin = "AUTOMATIC_CONTROL",
					bootId = bootId,
					leaseGeneration = 1L,
					requestedAtMs = STARTED_AT_MS,
					requestedElapsedRealtimeNanos = 1_000L,
					status = LifecycleActionStatus.PENDING.name,
					attemptCount = 0,
					acknowledgedAtMs = null,
					acknowledgedElapsedRealtimeNanos = null,
					failureCode = null,
					retryTrigger = null,
					sourceInstanceId = null,
					registrationGeneration = null,
				),
			),
		)
	}

	private fun demand(
		logicalTrackingId: String,
		demandId: String,
		requestedBootId: String = OLD_BOOT_ID,
	) = SourceDemandEntity(
		demandId = demandId,
		consumerId = sessionConsumerId(logicalTrackingId),
		sourceKind = SOURCE_KIND,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = runId(logicalTrackingId),
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = requestedBootId,
		requestedElapsedRealtimeNanos = 1_000L,
		requestedAtMs = STARTED_AT_MS,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun activeRegistration() = ProviderRegistrationGenerationEntity(
		sourceKind = SOURCE_KIND,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = "activity-instance",
		ownerScope = "APP",
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null,
		clockDomainId = CURRENT_BOOT_ID,
		physicalConfigurationFingerprint = "activity-config",
		collectedDataEpoch = 0L,
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 1_000L,
		reservedElapsedRealtimeNanos = 1_000L,
		acceptedAtMs = 1_000L,
		acceptedElapsedRealtimeNanos = 1_000L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

	private fun runId(logicalTrackingId: String) = "run:$logicalTrackingId"
	private fun actionId(logicalTrackingId: String) = "action:$logicalTrackingId"
	private fun sessionConsumerId(logicalTrackingId: String) = "session:$logicalTrackingId"
	private suspend fun assertManualAuthorityFinalized(demandId: String) {
		database.sourceSessionDao().session(MANUAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().lifecycleAction(actionId(MANUAL_ID))?.status shouldBe
			LifecycleActionStatus.TERMINAL_FAILURE.name
		database.sourceBrokerDao().demandHistory(sessionConsumerId(MANUAL_ID))
			.single { demand -> demand.demandId == demandId }.status shouldBe
			SourceDemandEntity.STATUS_RETIRED
	}

	private fun manualDescriptor(
		bootId: String,
		serviceRunId: String = runId(MANUAL_ID),
	) = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = MANUAL_ID,
		serviceRunId = serviceRunId,
		restartBootId = bootId,
		restartToken = "restart-token",
	)

	private class RecordingStore(
		var descriptor: ActiveTrackingSessionDescriptor?,
	) : ActiveTrackingSessionStore {
		var clearCount = 0

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(descriptor)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			this.descriptor = descriptor
			return ActiveTrackingSessionStoreResult.Success(descriptor)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			clearCount++
			descriptor = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private object NoOpDrainScheduler : PendingSignalDrainScheduler {
		override fun enqueueExpedited(startupGeneration: Long) = Unit
	}

	private object CurrentBootClockDomainProvider : BootClockDomainProvider {
		override fun current(): String = CURRENT_BOOT_ID
	}

	private companion object {
		const val AUTOMATIC_ID = "automatic-session"
		const val MANUAL_ID = "manual-session"
		const val STALE_RUN_ID = "stale-manual-run"
		const val OLD_BOOT_ID = "boot-before-restart"
		const val CURRENT_BOOT_ID = "current-boot"
		val SOURCE_KIND = SourceKind.ACTIVITY.stableCode
		const val REGISTRATION_GENERATION = 1L
		const val STARTED_AT_MS = 1_000L
		const val EXIT_AT_MS = 2_500L
		const val RECOVERY_AT_MS = 9_000L
		const val RECOVERY_ELAPSED_NANOS = 9_000_000L
	}
}
