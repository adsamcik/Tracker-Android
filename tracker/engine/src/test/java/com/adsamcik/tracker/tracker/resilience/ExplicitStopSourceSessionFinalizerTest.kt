package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import io.kotest.matchers.shouldBe
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
class ExplicitStopSourceSessionFinalizerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `exact stop retires authority and terminalizes the named lifecycle`() = runTest {
		seedAutomationEpoch()
		insertActiveSession(LOGICAL_ID, clockDomainId = "old-boot")
		insertRun(LOGICAL_ID)
		insertPendingAction(LOGICAL_ID, bootId = "old-boot")
		database.sourceBrokerDao().insertDemands(listOf(demand(LOGICAL_ID)))

		finalizer().finalize(
			logicalTrackingId = LOGICAL_ID,
			expectedServiceRunId = runId(LOGICAL_ID),
			requestedAtMs = 5_000L,
			requestedBootId = "old-boot",
			requestedElapsedRealtimeNanos = 5_000_000L,
			reconciliationAtMs = 9_000L,
			reconciliationElapsedRealtimeNanos = 9_000_000L,
			reconciliationBootId = "new-boot",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.state shouldBe SessionLifecycleState.FINALIZED.name
		session?.completedAtMs shouldBe 5_000L
		session?.cutoffElapsedNanos shouldBe null
		database.sourceSessionDao().serviceRun(runId(LOGICAL_ID))?.runtimeAcknowledgement shouldBe
			LifecycleActionStatus.TERMINAL_FAILURE.name
		database.sourceSessionDao().lifecycleAction(actionId(LOGICAL_ID))?.status shouldBe
			LifecycleActionStatus.SUPERSEDED.name
		val retiredDemand =
			database.sourceBrokerDao().demandHistory(consumerId(LOGICAL_ID)).single()
		retiredDemand.status shouldBe SourceDemandEntity.STATUS_RETIRED
		retiredDemand.retiredAtMs shouldBe 9_000L
		retiredDemand.retireBootId shouldBe "new-boot"
		retiredDemand.retireElapsedRealtimeNanos shouldBe 9_000_000L
		database.activityAutomationEpochDao().current()?.epoch shouldBe 18L
	}

	@Test
	fun `mismatched delayed stop cannot close the current active lifecycle`() = runTest {
		insertActiveSession("stale-logical-id", clockDomainId = "boot-1", startedAtMs = 1_000L)
		insertActiveSession(NEW_LOGICAL_ID, clockDomainId = "boot-1", startedAtMs = 2_000L)
		database.sourceBrokerDao().insertDemands(listOf(demand(NEW_LOGICAL_ID)))

		finalizer().finalize(
			logicalTrackingId = "stale-logical-id",
			expectedServiceRunId = "stale-service-run",
			requestedAtMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 5_000_000L,
			reconciliationAtMs = 6_000L,
			reconciliationElapsedRealtimeNanos = 6_000_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.SESSION_MISMATCH

		database.sourceSessionDao().session(NEW_LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceBrokerDao().demandHistory(consumerId(NEW_LOGICAL_ID)).single().status shouldBe
			SourceDemandEntity.STATUS_ACTIVE
	}

	@Test
	fun `missing requested Room session is distinct from a genuine mismatch`() = runTest {
		insertActiveSession(NEW_LOGICAL_ID, clockDomainId = "boot-1")

		finalizer().finalize(
			logicalTrackingId = "deleted-logical-id",
			expectedServiceRunId = "deleted-service-run",
			requestedAtMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 5_000_000L,
			reconciliationAtMs = 6_000L,
			reconciliationElapsedRealtimeNanos = 6_000_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.NO_ROOM_SESSION

		database.sourceSessionDao().session(NEW_LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `same boot uses factual stop cutoff while retiring authority at reconciliation time`() = runTest {
		insertActiveSession(LOGICAL_ID, clockDomainId = "boot-1")
		insertRun(LOGICAL_ID)
		insertPendingAction(LOGICAL_ID, bootId = "boot-1")
		database.sourceBrokerDao().insertDemands(listOf(demand(LOGICAL_ID)))

		finalizer().finalize(
			logicalTrackingId = LOGICAL_ID,
			expectedServiceRunId = runId(LOGICAL_ID),
			requestedAtMs = 2_500L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 2_500_000L,
			reconciliationAtMs = 9_000L,
			reconciliationElapsedRealtimeNanos = 9_000_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.completedAtMs shouldBe 2_500L
		session?.cutoffAtMs shouldBe 2_500L
		session?.cutoffElapsedNanos shouldBe 2_500_000L
		val action = database.sourceSessionDao().lifecycleAction(actionId(LOGICAL_ID))
		action?.acknowledgedAtMs shouldBe 9_000L
		action?.acknowledgedElapsedRealtimeNanos shouldBe 9_000_000L
		val demand = database.sourceBrokerDao().demandHistory(consumerId(LOGICAL_ID)).single()
		demand.retiredAtMs shouldBe 9_000L
		demand.retireElapsedRealtimeNanos shouldBe 9_000_000L
	}

	@Test
	fun `repeating an exact stop is idempotent`() = runTest {
		insertActiveSession(LOGICAL_ID, clockDomainId = "boot-1")
		insertRun(LOGICAL_ID)
		val finalizer = finalizer()

		finalizer.finalize(
			logicalTrackingId = LOGICAL_ID,
			expectedServiceRunId = runId(LOGICAL_ID),
			requestedAtMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 5_000_000L,
			reconciliationAtMs = 5_500L,
			reconciliationElapsedRealtimeNanos = 5_500_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.FINALIZED
		finalizer.finalize(
			logicalTrackingId = LOGICAL_ID,
			expectedServiceRunId = runId(LOGICAL_ID),
			requestedAtMs = 6_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 6_000_000L,
			reconciliationAtMs = 6_500L,
			reconciliationElapsedRealtimeNanos = 6_500_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.ALREADY_FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.lifecycleRevision shouldBe 2L
		session?.completedAtMs shouldBe 5_000L
	}

	@Test
	fun `delayed stop for old run cannot close a replacement run with the same logical id`() = runTest {
		insertActiveSession(LOGICAL_ID, clockDomainId = "boot-1")
		insertRun(LOGICAL_ID, serviceRunId = "run:old", startedAtMs = 1_000L)
		insertRun(LOGICAL_ID, serviceRunId = "run:new", startedAtMs = 2_000L)

		finalizer().finalize(
			logicalTrackingId = LOGICAL_ID,
			expectedServiceRunId = "run:old",
			requestedAtMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 5_000_000L,
			reconciliationAtMs = 6_000L,
			reconciliationElapsedRealtimeNanos = 6_000_000L,
			reconciliationBootId = "boot-1",
			reason = "EXPLICIT_REQUEST",
		) shouldBe ExplicitStopSourceSessionFinalization.SESSION_MISMATCH

		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun("run:new")?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	private fun finalizer() = ExplicitStopSourceSessionFinalizer(Provider { database })

	private suspend fun seedAutomationEpoch() {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = 17,
				automaticControlEnabled = true,
				lastRotationReason = "TEST_SEED",
			),
		)
	}

	private suspend fun insertActiveSession(
		logicalTrackingId: String,
		clockDomainId: String,
		startedAtMs: Long = 1_000L,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = clockDomainId,
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
			),
		)
	}

	private suspend fun insertRun(
		logicalTrackingId: String,
		serviceRunId: String = runId(logicalTrackingId),
		startedAtMs: Long = 1_000L,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1_000L,
				completedAtMs = null,
				completionReason = null,
				bootId = "old-boot",
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
					actionFamily = "SOURCE_RUNTIME",
					sourceKind = 3,
					desiredState = SessionLifecycleState.ACTIVE.name,
					desiredPlanRevision = 1L,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					startOrigin = "MANUAL_FOREGROUND_START",
					bootId = bootId,
					leaseGeneration = 1L,
					requestedAtMs = 1_000L,
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

	private fun demand(logicalTrackingId: String) = SourceDemandEntity(
		demandId = "demand:$logicalTrackingId",
		consumerId = consumerId(logicalTrackingId),
		sourceKind = 3,
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
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1_000L,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun runId(logicalTrackingId: String) = "run:$logicalTrackingId"
	private fun actionId(logicalTrackingId: String) = "action:$logicalTrackingId"
	private fun consumerId(logicalTrackingId: String) = "session:$logicalTrackingId"

	private companion object {
		const val LOGICAL_ID = "explicit-stop-session"
		const val NEW_LOGICAL_ID = "new-active-session"
	}
}
