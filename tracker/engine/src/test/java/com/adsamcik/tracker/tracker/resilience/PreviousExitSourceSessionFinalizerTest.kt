package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.dao.PriorProcessRegistrationReconciliationResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.source.coordinator.AndroidStartDeliveryState
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleDesiredState
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.stableLifecycleChecksum
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
			mockk {
				coEvery {
					reconcilePriorProcessRegistrations(any(), any())
				} returns PriorProcessRegistrationReconciliationResult(0, 0, 0)
			},
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
		insertRecoveryAuthorityEnvelope(MANUAL_ID)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "manual-demand", CURRENT_BOOT_ID)),
		)

		val result = finalizer().finalizeStaleSessions(
			recoveryDescriptor = manualDescriptor(CURRENT_BOOT_ID),
		)

		result.finalizedLogicalTrackingIds shouldBe emptySet()
		result.descriptorDisposition shouldBe
			PreviousExitRecoveryDescriptorDisposition.Preserved(
				manualDescriptor(CURRENT_BOOT_ID),
			)
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
	fun `same boot reconfiguration crash before descriptor update remains recoverable`() = runTest {
		seedAutomationEpoch()
		insertSession(
			MANUAL_ID,
			SessionMode.MANUAL,
			CURRENT_BOOT_ID,
			state = SessionLifecycleState.RECONFIGURING,
		)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		insertRecoveryAuthorityEnvelope(MANUAL_ID)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "reconfiguring-manual-demand", CURRENT_BOOT_ID)),
		)

		val result = finalizer().finalizeStaleSessions(
			recoveryDescriptor = manualDescriptor(CURRENT_BOOT_ID),
		)

		result.finalizedLogicalTrackingIds shouldBe emptySet()
		database.sourceSessionDao().session(MANUAL_ID)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceBrokerDao().demandHistory(sessionConsumerId(MANUAL_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_ACTIVE
		database.activityAutomationEpochDao().current()?.epoch shouldBe 17L
	}

	@Test
	fun `same boot automatic descriptor cannot revive an interrupted session`() = runTest {
		seedAutomationEpoch()
		insertSession(AUTOMATIC_ID, SessionMode.AUTOMATIC, CURRENT_BOOT_ID)
		insertRun(AUTOMATIC_ID, CURRENT_BOOT_ID)
		insertPendingAction(AUTOMATIC_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(AUTOMATIC_ID, "automatic-demand", CURRENT_BOOT_ID)),
		)
		val automaticDescriptor = manualDescriptor(CURRENT_BOOT_ID).copy(
			isUserInitiated = false,
			logicalTrackingId = AUTOMATIC_ID,
			serviceRunId = runId(AUTOMATIC_ID),
		)

		val result = finalizer().finalizeStaleSessions(
			recoveryDescriptor = automaticDescriptor,
		)

		result.finalizedLogicalTrackingIds shouldContainExactly setOf(AUTOMATIC_ID)
		database.sourceSessionDao().session(AUTOMATIC_ID)?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.currentServiceRunId shouldBe null
			session.failureCode shouldBe PreviousExitSourceSessionFinalizer.COMPLETION_REASON
		}
		database.sourceSessionDao().serviceRun(runId(AUTOMATIC_ID))?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().lifecycleAction(actionId(AUTOMATIC_ID))?.status shouldBe
			LifecycleActionStatus.TERMINAL_FAILURE.name
		database.sourceBrokerDao().demandHistory(sessionConsumerId(AUTOMATIC_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		database.activityAutomationEpochDao().current()?.epoch shouldBe 18L
	}

	@Test
	fun `recoverable current run is preserved while an older incomplete run is terminalized`() =
		runTest {
			insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
			insertRun(
				logicalTrackingId = MANUAL_ID,
				bootId = CURRENT_BOOT_ID,
				serviceRunId = STALE_RUN_ID,
			)
			insertRun(MANUAL_ID, CURRENT_BOOT_ID)
			insertRecoveryAuthorityEnvelope(MANUAL_ID)
			insertAction(
				logicalTrackingId = MANUAL_ID,
				bootId = CURRENT_BOOT_ID,
				serviceRunId = STALE_RUN_ID,
				actionId = STALE_ACTION_ID,
				actionRevision = 2L,
				status = LifecycleActionStatus.AWAITING_FOREGROUND,
			)
			insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
			database.sourceBrokerDao().insertDemands(
				listOf(demand(MANUAL_ID, "manual-demand", CURRENT_BOOT_ID)),
			)

			val result = finalizer().finalizeStaleSessions(
				recoveryDescriptor = manualDescriptor(CURRENT_BOOT_ID),
			)

			result.finalizedLogicalTrackingIds shouldBe emptySet()
			val session = database.sourceSessionDao().session(MANUAL_ID)
			session?.state shouldBe SessionLifecycleState.ACTIVE.name
			session?.currentServiceRunId shouldBe runId(MANUAL_ID)
			database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.state shouldBe
				SessionLifecycleState.ACTIVE.name
			database.sourceSessionDao().lifecycleAction(actionId(MANUAL_ID))?.status shouldBe
				LifecycleActionStatus.PENDING.name
			database.sourceSessionDao().serviceRun(STALE_RUN_ID)?.state shouldBe
				SessionLifecycleState.FINALIZED.name
			database.sourceSessionDao().serviceRun(STALE_RUN_ID)?.completionReason shouldBe
				PreviousExitSourceSessionFinalizer.COMPLETION_REASON
			database.sourceSessionDao().lifecycleAction(STALE_ACTION_ID)?.status shouldBe
				LifecycleActionStatus.TERMINAL_FAILURE.name
			database.sourceBrokerDao().demandHistory(sessionConsumerId(MANUAL_ID)).single().status shouldBe
				SourceDemandEntity.STATUS_ACTIVE
		}

	@Test
	fun `completed restart suspension survives process crash for exact recovery`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(
			logicalTrackingId = MANUAL_ID,
			bootId = CURRENT_BOOT_ID,
			state = SessionLifecycleState.FINALIZED,
			completedAtMs = SUSPENDED_AT_MS,
		)
		insertRecoveryAuthorityEnvelope(
			logicalTrackingId = MANUAL_ID,
			completedSuspensionReason = RESTART_REASON,
		)
		val staleDescriptor = manualDescriptor(
			CURRENT_BOOT_ID,
			callerReference = SourceCallerReplayReference("caller-before-suspend"),
		)
		val repairedDescriptor = staleDescriptor.copy(
			sourceCallerAuthorityReference = SourceCallerReplayReference(CALLER_REFERENCE),
		)

		val result = finalizer { descriptor ->
			descriptor shouldBe staleDescriptor
			ActiveTrackingCallerAuthorityReconciliation.Ready(repairedDescriptor)
		}.finalizeStaleSessions(
			recoveryDescriptor = staleDescriptor,
		)

		result.finalizedLogicalTrackingIds shouldBe emptySet()
		result.descriptorDisposition shouldBe
			PreviousExitRecoveryDescriptorDisposition.Preserved(repairedDescriptor)
		database.sourceSessionDao().session(MANUAL_ID)?.let { session ->
			session.state shouldBe SessionLifecycleState.ACTIVE.name
			session.currentServiceRunId shouldBe null
			session.completedAtMs shouldBe null
		}

		database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.let { run ->
			run.state shouldBe SessionLifecycleState.FINALIZED.name
			run.completedAtMs shouldBe SUSPENDED_AT_MS
			run.completionReason shouldBe RESTART_REASON
			run.runtimeAcknowledgement shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		}
	}

	@Test
	fun `already terminal Room session clears the exact stale descriptor`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		val terminal = requireNotNull(database.sourceSessionDao().session(MANUAL_ID)).copy(
			state = SessionLifecycleState.FINALIZED.name,
			completedAtMs = SUSPENDED_AT_MS,
			failureCode = "ALREADY_TERMINAL",
			currentServiceRunId = null,
		)
		database.sourceSessionDao().updateSession(terminal) shouldBe 1
		val descriptor = manualDescriptor(CURRENT_BOOT_ID)
		val store = RecordingStore(descriptor)
		val registrationRepository =
			mockk<com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository>()
		coEvery {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		} returns PriorProcessRegistrationReconciliationResult(0, 0, 0)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			mockk(relaxed = true),
			finalizer(),
			registrationRepository,
		)

		val result = coordinator.reconcileStaleSessions()

		result.finalizedLogicalTrackingIds shouldBe emptySet()
		result.descriptorDisposition shouldBe
			PreviousExitRecoveryDescriptorDisposition.Clear(descriptor)
		store.descriptor shouldBe null
		store.clearCount shouldBe 1
		database.sourceSessionDao().session(MANUAL_ID) shouldBe terminal
	}

	@Test
	fun `completed suspension lookalike with mismatched authority is finalized`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(
			logicalTrackingId = MANUAL_ID,
			bootId = CURRENT_BOOT_ID,
			state = SessionLifecycleState.FINALIZED,
			completedAtMs = SUSPENDED_AT_MS,
		)
		insertRecoveryAuthorityEnvelope(
			logicalTrackingId = MANUAL_ID,
			completedSuspensionReason = RESTART_REASON,
			runCompletionReason = "UNRELATED_STOP",
		)

		val descriptor = manualDescriptor(CURRENT_BOOT_ID)
		val result = finalizer().finalizeStaleSessions(recoveryDescriptor = descriptor)

		result.finalizedLogicalTrackingIds shouldContainExactly setOf(MANUAL_ID)
		result.descriptorDisposition shouldBe
			PreviousExitRecoveryDescriptorDisposition.Clear(descriptor)
		database.sourceSessionDao().session(MANUAL_ID)?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.failureCode shouldBe PreviousExitSourceSessionFinalizer.COMPLETION_REASON
		}
	}

	@Test
	fun `transient caller authority reconciliation preserves Room state for retry`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		val descriptor = manualDescriptor(CURRENT_BOOT_ID)

		val failure = shouldThrow<PreviousExitCallerAuthorityUnavailableException> {
			finalizer { candidate ->
				ActiveTrackingCallerAuthorityReconciliation.Blocked(
					failureCode = "RECOVERY_SOURCE_CALLER_AUTHORITY_STORAGE_UNAVAILABLE",
					disposition = TrackingStartFailureDisposition.RETRYABLE,
					descriptor = candidate,
				)
			}.finalizeStaleSessions(recoveryDescriptor = descriptor)
		}

		failure.failureCode shouldBe "RECOVERY_SOURCE_CALLER_AUTHORITY_STORAGE_UNAVAILABLE"
		database.sourceSessionDao().session(MANUAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun(runId(MANUAL_ID))?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `terminal caller retirement rejection finalizes Room and clears descriptor`() = runTest {
		insertSession(MANUAL_ID, SessionMode.MANUAL, CURRENT_BOOT_ID)
		insertRun(MANUAL_ID, CURRENT_BOOT_ID)
		insertPendingAction(MANUAL_ID, CURRENT_BOOT_ID)
		database.sourceBrokerDao().insertDemands(
			listOf(demand(MANUAL_ID, "terminal-caller-demand", CURRENT_BOOT_ID)),
		)
		val descriptor = manualDescriptor(CURRENT_BOOT_ID)
		val store = RecordingStore(descriptor)
		val terminalFinalizer = finalizer { candidate ->
			ActiveTrackingCallerAuthorityReconciliation.Blocked(
				failureCode = "RECOVERY_SOURCE_CALLER_RETIREMENT_CORRUPT",
				disposition = TrackingStartFailureDisposition.TERMINAL,
				descriptor = candidate,
			)
		}
		val registrationRepository =
			mockk<com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository>()
		coEvery {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		} returns PriorProcessRegistrationReconciliationResult(0, 0, 0)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			mockk(relaxed = true),
			terminalFinalizer,
			registrationRepository,
		)

		val result = coordinator.reconcileStaleSessions()

		result.finalizedLogicalTrackingIds shouldContainExactly setOf(MANUAL_ID)
		result.descriptorDisposition shouldBe
			PreviousExitRecoveryDescriptorDisposition.Clear(descriptor)
		store.descriptor shouldBe null
		database.sourceSessionDao().session(MANUAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
	}

	@Test
	fun `stale session terminalizes every incomplete run and prepared action`() = runTest {
		insertSession(AUTOMATIC_ID, SessionMode.AUTOMATIC, OLD_BOOT_ID)
		insertRun(
			logicalTrackingId = AUTOMATIC_ID,
			bootId = OLD_BOOT_ID,
			serviceRunId = STALE_RUN_ID,
		)
		insertRun(AUTOMATIC_ID, OLD_BOOT_ID)
		insertAction(
			logicalTrackingId = AUTOMATIC_ID,
			bootId = OLD_BOOT_ID,
			serviceRunId = STALE_RUN_ID,
			actionId = STALE_ACTION_ID,
			actionRevision = 2L,
			status = LifecycleActionStatus.AWAITING_FOREGROUND,
		)
		insertPendingAction(AUTOMATIC_ID, OLD_BOOT_ID)

		finalizer().finalizeStaleSessions(factualCompletedAtMs = EXIT_AT_MS)

		database.sourceSessionDao().incompleteServiceRuns(AUTOMATIC_ID) shouldBe emptyList()
		listOf(runId(AUTOMATIC_ID), STALE_RUN_ID).forEach { serviceRunId ->
			database.sourceSessionDao().serviceRun(serviceRunId)?.state shouldBe
				SessionLifecycleState.FINALIZED.name
		}
		listOf(actionId(AUTOMATIC_ID), STALE_ACTION_ID).forEach { lifecycleActionId ->
			database.sourceSessionDao().lifecycleAction(lifecycleActionId)?.status shouldBe
				LifecycleActionStatus.TERMINAL_FAILURE.name
		}
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

	private fun finalizer(
		reconcile: (ActiveTrackingSessionDescriptor) ->
			ActiveTrackingCallerAuthorityReconciliation = { descriptor ->
				ActiveTrackingCallerAuthorityReconciliation.Ready(descriptor)
			},
	): PreviousExitSourceSessionFinalizer {
		val reconciler = mockk<ActiveTrackingSessionCallerAuthorityReconciler>()
		coEvery { reconciler.reconcile(any()) } answers {
			reconcile(firstArg())
		}
		return PreviousExitSourceSessionFinalizer(
			Provider { database },
			FixedClock(RECOVERY_AT_MS, RECOVERY_ELAPSED_NANOS),
			CurrentBootClockDomainProvider,
			reconciler,
		)
	}

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
		if (completedAtMs == null) {
			val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
			database.sourceSessionDao().updateSession(session.copy(currentServiceRunId = serviceRunId)) shouldBe 1
		}
	}

	private suspend fun insertRecoveryAuthorityEnvelope(
		logicalTrackingId: String,
		serviceRunId: String = runId(logicalTrackingId),
		completedSuspensionReason: String? = null,
		runCompletionReason: String? = completedSuspensionReason,
	) {
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(logicalTrackingId))
		val run = requireNotNull(dao.serviceRun(serviceRunId))
		val bindings = listOf(
			SessionManifestSourceEntity(
				logicalTrackingId = logicalTrackingId,
				manifestRevision = MANIFEST_REVISION,
				sourceKind = SOURCE_KIND,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 1,
			),
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalTrackingId,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = serviceRunId,
			sessionMode = SessionMode.MANUAL.name,
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
			effectiveBootId = CURRENT_BOOT_ID,
			effectiveElapsedRealtimeNanos = 1_000L,
			effectiveWallTimeMs = STARTED_AT_MS,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "SESSION_START",
			manifestChecksum = "",
		)
		dao.insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, bindings),
			),
		)
		dao.insertManifestSources(bindings)
		val startOrigin = if (completedSuspensionReason == null) {
			SessionStartOrigin.MANUAL_FOREGROUND_START
		} else {
			SessionStartOrigin.RECOVERY
		}
		val intentChecksum = if (completedSuspensionReason == null) {
			stableLifecycleChecksum(
				logicalTrackingId,
				INTENT_REVISION,
				MANIFEST_REVISION,
				LifecycleDesiredState.ACTIVE,
				startOrigin,
				CURRENT_BOOT_ID,
				1_000L,
				STARTED_AT_MS,
				null,
				null,
				null,
				CALLER_REFERENCE,
			)
		} else {
			stableLifecycleChecksum(
				logicalTrackingId,
				INTENT_REVISION,
				MANIFEST_REVISION,
				LifecycleDesiredState.ACTIVE,
				completedSuspensionReason,
				CURRENT_BOOT_ID,
				1_000L,
				CALLER_REFERENCE,
			)
		}
		dao.insertLifecycleIntent(
			SessionLifecycleIntentVersionEntity(
				logicalTrackingId = logicalTrackingId,
				intentRevision = INTENT_REVISION,
				manifestRevision = MANIFEST_REVISION,
				desiredState = LifecycleDesiredState.ACTIVE.name,
				startOrigin = startOrigin.name,
				requestBootId = CURRENT_BOOT_ID,
				requestedElapsedRealtimeNanos = 1_000L,
				requestedWallTimeMs = STARTED_AT_MS,
				automationEpoch = null,
				triggerId = null,
				triggerKind = null,
				triggerBootId = null,
				triggerObservedElapsedRealtimeNanos = null,
				triggerReceivedElapsedRealtimeNanos = null,
				triggerExpiresElapsedRealtimeNanos = null,
				stopReason = completedSuspensionReason,
				stopDeadlineBootId = null,
				stopDeadlineElapsedRealtimeNanos = null,
				intentChecksum = intentChecksum,
				sourceCallerAuthorityReference = CALLER_REFERENCE,
			),
		)
		dao.updateSession(
			session.copy(
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = INTENT_REVISION,
				currentServiceRunId = serviceRunId.takeIf { completedSuspensionReason == null },
			),
		) shouldBe 1
		dao.updateServiceRun(
			run.copy(
				state = if (completedSuspensionReason == null) {
					run.state
				} else {
					SessionLifecycleState.FINALIZED.name
				},
				completedAtMs = completedSuspensionReason?.let { SUSPENDED_AT_MS },
				completionReason = runCompletionReason,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (completedSuspensionReason == null) {
					LifecycleActionStatus.START_ACCEPTED.name
				} else {
					LifecycleActionStatus.STOP_ACCEPTED.name
				},
				runRevision = 2L,
				startDeliveryToken = "delivery-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = INTENT_REVISION,
				androidDeliveryState = AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
				startIsUserInitiated = true,
			),
		) shouldBe 1
	}

	private suspend fun insertPendingAction(logicalTrackingId: String, bootId: String) =
		insertAction(
			logicalTrackingId = logicalTrackingId,
			bootId = bootId,
			serviceRunId = runId(logicalTrackingId),
			actionId = actionId(logicalTrackingId),
			status = LifecycleActionStatus.PENDING,
		)

	private suspend fun insertAction(
		logicalTrackingId: String,
		bootId: String,
		serviceRunId: String,
		actionId: String,
		actionRevision: Long = 1L,
		status: LifecycleActionStatus,
	) {
		database.sourceSessionDao().insertLifecycleActions(
			listOf(
				LifecycleDesiredActionEntity(
					actionId = actionId,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					manifestRevision = 1L,
					actionRevision = actionRevision,
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
					status = status.name,
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
		callerReference: SourceCallerReplayReference =
			SourceCallerReplayReference(CALLER_REFERENCE),
	) = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = MANUAL_ID,
		serviceRunId = serviceRunId,
		restartBootId = bootId,
		restartToken = "restart-token",
		sourceCallerAuthorityReference = callerReference,
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
		const val STALE_ACTION_ID = "stale-manual-action"
		const val OLD_BOOT_ID = "boot-before-restart"
		const val CURRENT_BOOT_ID = "current-boot"
		val SOURCE_KIND = SourceKind.ACTIVITY.stableCode
		const val REGISTRATION_GENERATION = 1L
		const val STARTED_AT_MS = 1_000L
		const val EXIT_AT_MS = 2_500L
		const val RECOVERY_AT_MS = 9_000L
		const val RECOVERY_ELAPSED_NANOS = 9_000_000L
		const val SUSPENDED_AT_MS = 2_000L
		const val RESTART_REASON = "ANDROID_RESTART"
		const val MANIFEST_REVISION = 1L
		const val INTENT_REVISION = 1L
		const val CALLER_REFERENCE = "caller-authority"
	}
}
