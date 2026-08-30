package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSessionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: SourceSessionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sourceSessionDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `manifest reads are scoped to the exact service run`() = runTest {
		dao.insertManifest(manifest(revision = 1L, serviceRunId = "run-a"))
		dao.insertManifest(manifest(revision = 2L, serviceRunId = "run-b"))
		dao.insertManifest(manifest(revision = 3L, serviceRunId = "run-a"))

		dao.manifestsForServiceRun("run-a").map { it.manifestRevision } shouldContainExactly
			listOf(1L, 3L)
		dao.manifestByServiceRunRevision("run-a", 3L)?.serviceRunId shouldBe "run-a"
		dao.manifestByServiceRunRevision("run-b", 3L) shouldBe null
		dao.manifests(LOGICAL_ID).map { it.manifestRevision } shouldContainExactly listOf(1L, 2L, 3L)
	}

	@Test
	fun `completeness rows for two runs and two generations do not overwrite`() = runTest {
		val runAGeneration1 = completeness(serviceRunId = "run-a", registrationGeneration = 1L)
		val runBGeneration1 = completeness(serviceRunId = "run-b", registrationGeneration = 1L)
		val runAGeneration2 = completeness(serviceRunId = "run-a", registrationGeneration = 2L)
		dao.saveCompleteness(runAGeneration1)
		dao.saveCompleteness(runBGeneration1)
		dao.saveCompleteness(runAGeneration2)

		dao.completenessForServiceRun(LOGICAL_ID, "run-a") shouldContainExactly
			listOf(runAGeneration1, runAGeneration2)
		dao.completenessForServiceRun(LOGICAL_ID, "run-b") shouldContainExactly
			listOf(runBGeneration1)

		val corrected = runAGeneration1.copy(
			lastAdmissionOrdinal = 99L,
			updatedAtMs = 2_000L,
		)
		dao.saveCompleteness(corrected)

		dao.completeness(LOGICAL_ID) shouldContainExactly
			listOf(corrected, runAGeneration2, runBGeneration1)
	}

	@Test
	fun `legacy unattributed completeness is excluded from exact physical run reads`() = runTest {
		val legacy = completeness(
			serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID,
			registrationGeneration = 4L,
		)
		dao.saveCompleteness(legacy)

		dao.legacyUnattributedCompleteness(LOGICAL_ID) shouldContainExactly listOf(legacy)
		dao.completenessForServiceRun(LOGICAL_ID, "run-a") shouldContainExactly emptyList()
	}

	@Test
	fun `legacy completeness sentinel cannot identify a service run`() {
		shouldThrow<IllegalArgumentException> { serviceRun(LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID) }
		shouldThrow<IllegalArgumentException> { serviceRun(" ") }
		shouldThrow<IllegalArgumentException> {
			manifest(revision = 1L, serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID)
		}
		shouldThrow<IllegalArgumentException> {
			completeness(serviceRunId = " ", registrationGeneration = 1L)
		}
		serviceRun("run-a").serviceRunId shouldBe "run-a"
	}

	@Test
	fun `steps capture writer provenance groups fail closed`() {
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource()
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			)
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 2L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 2L,
				writerProjectionId = "steps-session",
			)
		}

		stepsManifestSource(
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			writerOwnerGeneration = 1L,
		).writerProjectionId shouldBe null
		stepsManifestSource(
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			writerOwnerGeneration = 2L,
			writerProjectionId = "steps-session",
			writerProjectionVersion = 1,
			writerBindingGeneration = 3L,
		).writerBindingGeneration shouldBe 3L
	}

	@Test
	fun `writer transition boundary rejects every nonterminal durable lifecycle shape`() = runTest {
		dao.hasLifecycleBoundaryBlocker() shouldBe false
		dao.hasIncompleteServiceRun() shouldBe false
		dao.hasNonterminalLatestLifecycleAction() shouldBe false

		dao.insertSession(terminalSession(currentIntentRevision = 1L))
		dao.hasLifecycleBoundaryBlocker() shouldBe true
		dao.insertLifecycleIntent(finalizedIntent())
		dao.hasLifecycleBoundaryBlocker() shouldBe false
		val terminalFailure = terminalSession(currentIntentRevision = 1L).copy(
			state = "FAILED",
			failureCode = "START_EXPIRED",
		)
		dao.updateSession(terminalFailure) shouldBe 1
		dao.hasLifecycleBoundaryBlocker() shouldBe false
		dao.updateSession(terminalFailure.copy(currentServiceRunId = "orphan-run")) shouldBe 1
		dao.hasLifecycleBoundaryBlocker() shouldBe true
		dao.updateSession(terminalFailure) shouldBe 1

		val completedRun = serviceRun("run-a").copy(
			state = "FINALIZED",
			completedAtMs = 2_000L,
			completionReason = "DONE",
		)
		dao.insertServiceRun(completedRun)
		dao.hasIncompleteServiceRun() shouldBe false
		dao.updateServiceRun(completedRun.copy(completedAtMs = null)) shouldBe 1
		dao.hasIncompleteServiceRun() shouldBe true
		dao.updateServiceRun(completedRun.copy(state = "ACTIVE")) shouldBe 1
		dao.hasIncompleteServiceRun() shouldBe true
		dao.updateServiceRun(completedRun) shouldBe 1
		dao.hasIncompleteServiceRun() shouldBe false

		val acceptedStart = lifecycleAction(
			actionId = "start-action",
			actionRevision = 1L,
			status = "START_ACCEPTED",
		)
		val acceptedStop = lifecycleAction(
			actionId = "stop-action",
			actionRevision = 2L,
			status = "STOP_ACCEPTED",
		)
		dao.insertLifecycleActions(listOf(acceptedStart, acceptedStop))
		dao.hasNonterminalLatestLifecycleAction() shouldBe false
		dao.insertLifecycleActions(
			listOf(
				lifecycleAction(
					actionId = "new-start-action",
					actionRevision = 3L,
					status = "START_ACCEPTED",
				),
			),
		)
		dao.hasNonterminalLatestLifecycleAction() shouldBe true
	}

	@Test
	fun `another logical session cannot hide a nonterminal action with a reused run id`() = runTest {
		dao.insertLifecycleActions(
			listOf(
				lifecycleAction(
					actionId = "session-a-start",
					actionRevision = 1L,
					status = "START_ACCEPTED",
				).copy(
					logicalTrackingId = "session-a",
					serviceRunId = "shared-malformed-run",
					desiredState = "ACTIVE",
				),
				lifecycleAction(
					actionId = "session-b-stop",
					actionRevision = 2L,
					status = "STOP_ACCEPTED",
				).copy(
					logicalTrackingId = "session-b",
					serviceRunId = "shared-malformed-run",
				),
			),
		)

		dao.hasNonterminalLatestLifecycleAction() shouldBe true
	}

	@Test
	fun `presentation binding and acknowledgement preserve lifecycle revision`() = runTest {
		val active = serviceRun("presentation-run").copy(
			state = "ACTIVE",
			runRevision = 4L,
		)
		dao.insertServiceRun(active)

		dao.bindSessionSegmentExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = active.serviceRunId,
			sessionSegmentId = 42L,
		) shouldBe 1
		val bound = requireNotNull(dao.serviceRun(active.serviceRunId))
		bound.sessionSegmentId shouldBe 42L
		bound.runRevision shouldBe 4L

		dao.updateServiceRun(
			bound.copy(
				state = "FINALIZED",
				completedAtMs = 2_000L,
				completionReason = "STOPPED",
				runRevision = 5L,
			),
		) shouldBe 1
		dao.acknowledgePresentationQuiescedExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = active.serviceRunId,
			sessionSegmentId = 42L,
			acknowledgedAtMs = 2_100L,
		) shouldBe 1

		val acknowledged = requireNotNull(dao.serviceRun(active.serviceRunId))
		acknowledged.presentationAcknowledgement shouldBe
			SourceServiceRunEntity.PRESENTATION_QUIESCED
		acknowledged.presentationAcknowledgedAtMs shouldBe 2_100L
		acknowledged.runRevision shouldBe 5L
	}

	private fun manifest(revision: Long, serviceRunId: String) = SessionManifestVersionEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = revision,
		serviceRunId = serviceRunId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = 1L,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL",
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = revision,
		effectiveWallTimeMs = revision,
		zoneId = "Europe/Prague",
		automationEpoch = null,
		changeReason = "test",
		manifestChecksum = "checksum-$revision",
	)

	private fun completeness(
		serviceRunId: String,
		registrationGeneration: Long,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = serviceRunId,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = "step-counter",
		registrationGeneration = registrationGeneration,
		lastAdmissionOrdinal = registrationGeneration,
		lastSourceSequence = registrationGeneration,
		appDrainComplete = false,
		providerCoverage = "UNKNOWN",
		stopStatus = "INCOMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 1_000L,
	)

	private fun serviceRun(serviceRunId: String) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = LOGICAL_ID,
		state = "PREPARED",
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		completedAtMs = null,
		completionReason = null,
	)

	private fun terminalSession(currentIntentRevision: Long?) = LogicalTrackingSessionEntity(
		logicalTrackingId = LOGICAL_ID,
		state = "FINALIZED",
		lifecycleRevision = 1L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = "boot",
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		cutoffAtMs = 2_000L,
		cutoffElapsedNanos = 2_000L,
		completedAtMs = 2_000L,
		finalAdmissionOrdinal = 0L,
		failureCode = null,
		currentIntentRevision = currentIntentRevision,
	)

	private fun finalizedIntent() = SessionLifecycleIntentVersionEntity(
		logicalTrackingId = LOGICAL_ID,
		intentRevision = 1L,
		manifestRevision = 1L,
		desiredState = "FINALIZED",
		startOrigin = "MANUAL_FOREGROUND_START",
		requestBootId = "boot",
		requestedElapsedRealtimeNanos = 2_000L,
		requestedWallTimeMs = 2_000L,
		automationEpoch = null,
		triggerId = null,
		triggerKind = null,
		triggerBootId = null,
		triggerObservedElapsedRealtimeNanos = null,
		triggerReceivedElapsedRealtimeNanos = null,
		triggerExpiresElapsedRealtimeNanos = null,
		stopReason = "DONE",
		stopDeadlineBootId = null,
		stopDeadlineElapsedRealtimeNanos = null,
		intentChecksum = "intent-checksum",
	)

	private fun lifecycleAction(
		actionId: String,
		actionRevision: Long,
		status: String,
	) = LifecycleDesiredActionEntity(
		actionId = actionId,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = "run-a",
		manifestRevision = 1L,
		actionRevision = actionRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		desiredState = "FINALIZED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		bootId = "boot",
		leaseGeneration = 1L,
		requestedAtMs = 2_000L,
		requestedElapsedRealtimeNanos = 2_000L,
		status = status,
		attemptCount = 1,
		acknowledgedAtMs = 2_000L,
		acknowledgedElapsedRealtimeNanos = 2_000L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "steps-instance",
		registrationGeneration = 1L,
	)

	private fun stepsManifestSource(
		outputDestination: String? = null,
		writerOwner: String? = null,
		writerOwnerGeneration: Long? = null,
		writerProjectionId: String? = null,
		writerProjectionVersion: Int? = null,
		writerBindingGeneration: Long? = null,
	) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = outputDestination,
		writerOwner = writerOwner,
		writerOwnerGeneration = writerOwnerGeneration,
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
	)

	private companion object {
		const val LOGICAL_ID = "logical-session"
	}
}
