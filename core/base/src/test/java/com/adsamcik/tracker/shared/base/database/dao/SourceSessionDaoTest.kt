package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
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
@Suppress("LargeClass")
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
	fun `logical session batch returns only exact requested identities`() = runTest {
		val first = terminalSession(currentIntentRevision = null)
		val second = first.copy(logicalTrackingId = "logical-other", startedAtMs = 2_000L)
		val excluded = first.copy(logicalTrackingId = "logical-excluded", startedAtMs = 3_000L)
		dao.insertSession(first)
		dao.insertSession(second)
		dao.insertSession(excluded)

		dao.sessions(listOf(second.logicalTrackingId, first.logicalTrackingId))
			.associateBy(LogicalTrackingSessionEntity::logicalTrackingId) shouldBe mapOf(
			first.logicalTrackingId to first,
			second.logicalTrackingId to second,
		)
	}

	@Test
	fun `manifest reads are scoped to the exact service run`() = runTest {
		dao.insertManifest(manifest(revision = 1L, serviceRunId = "run-a"))
		dao.insertManifest(manifest(revision = 2L, serviceRunId = "run-b"))
		dao.insertManifest(manifest(revision = 3L, serviceRunId = "run-a"))

		dao.manifestsForServiceRun("run-a").map { it.manifestRevision } shouldContainExactly
			listOf(1L, 3L)
		dao.manifestsForServiceRun("run-a", limit = 1).map { it.manifestRevision } shouldContainExactly
			listOf(1L)
		dao.manifestByServiceRunRevision("run-a", 3L)?.serviceRunId shouldBe "run-a"
		dao.manifestByServiceRunRevision("run-b", 3L) shouldBe null
		dao.manifests(LOGICAL_ID).map { it.manifestRevision } shouldContainExactly listOf(1L, 2L, 3L)
	}

	@Test
	fun `history manifest batches apply deterministic SQL limits before materialization`() = runTest {
		dao.insertManifest(manifest(revision = 1L, serviceRunId = "run-a"))
		dao.insertManifest(manifest(revision = 2L, serviceRunId = "run-b"))
		dao.insertManifest(manifest(revision = 3L, serviceRunId = "run-a"))
		dao.insertManifestSources((1L..3L).map(::controlManifestSource))
		val history = database.trackingHistoryReadDao()

		history.manifests(listOf("run-b", "run-a"), limit = 2)
			.map { it.serviceRunId to it.manifestRevision } shouldContainExactly
			listOf("run-a" to 1L, "run-a" to 3L)
		history.manifestSources(listOf("run-b", "run-a"), limit = 2)
			.map(SessionManifestSourceEntity::manifestRevision) shouldContainExactly listOf(1L, 2L)
	}

	@Test
	fun `history service run pages include bound and unbound ties exactly once`() = runTest {
		val runs = listOf(
			serviceRun("ended-at-from").copy(startedAtMs = 500L, completedAtMs = 1_000L),
			serviceRun("terminal-overlap").copy(
				startedAtMs = 500L,
				completedAtMs = 1_001L,
				sessionSegmentId = 41L,
			),
			serviceRun("a-tie-unbound").copy(startedAtMs = 1_000L),
			serviceRun("b-tie-bound").copy(startedAtMs = 1_000L, sessionSegmentId = 42L),
			serviceRun("later-bound").copy(startedAtMs = 1_500L, sessionSegmentId = 43L),
			serviceRun("started-at-to").copy(startedAtMs = 2_000L),
		)
		runs.forEach { run -> dao.insertServiceRun(run) }
		val history = database.trackingHistoryReadDao()

		val seen = mutableListOf<SourceServiceRunEntity>()
		var afterStartedAtMs: Long? = null
		var afterServiceRunId: String? = null
		while (true) {
			val page = history.serviceRunCandidatePage(
				fromMs = 1_000L,
				toMs = 2_000L,
				limit = 2,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterServiceRunId,
			)
			seen += page
			val last = page.lastOrNull() ?: break
			afterStartedAtMs = last.startedAtMs
			afterServiceRunId = last.serviceRunId
			if (page.size < 2) {
				break
			}
		}

		seen.map(SourceServiceRunEntity::serviceRunId) shouldContainExactly listOf(
			"terminal-overlap",
			"a-tie-unbound",
			"b-tie-bound",
			"later-bound",
		)
		seen.map(SourceServiceRunEntity::serviceRunId).distinct().size shouldBe seen.size
		seen.map { run -> run.sessionSegmentId != null } shouldContainExactly
			listOf(true, false, true, true)
	}

	@Test
	fun `history service run pages surface in-range starts with regressed completion walls`() = runTest {
		val runs = listOf(
			serviceRun("ended-at-from").copy(startedAtMs = 500L, completedAtMs = 1_000L),
			serviceRun("regressed-outside").copy(startedAtMs = 500L, completedAtMs = 900L),
			serviceRun("ordinary-inside").copy(startedAtMs = 1_000L),
			serviceRun("regressed-inside").copy(startedAtMs = 1_500L, completedAtMs = 900L),
			serviceRun("started-at-to").copy(startedAtMs = 2_000L),
		)
		runs.forEach { run -> dao.insertServiceRun(run) }
		val history = database.trackingHistoryReadDao()

		val firstPage = history.serviceRunCandidatePage(
			fromMs = 1_000L,
			toMs = 2_000L,
			limit = 1,
			afterStartedAtMs = null,
			afterServiceRunId = null,
		)
		firstPage.map(SourceServiceRunEntity::serviceRunId) shouldContainExactly
			listOf("ordinary-inside")
		val firstCursor = firstPage.single()

		val secondPage = history.serviceRunCandidatePage(
			fromMs = 1_000L,
			toMs = 2_000L,
			limit = 1,
			afterStartedAtMs = firstCursor.startedAtMs,
			afterServiceRunId = firstCursor.serviceRunId,
		)
		secondPage.map(SourceServiceRunEntity::serviceRunId) shouldContainExactly
			listOf("regressed-inside")
		val secondCursor = secondPage.single()

		history.serviceRunCandidatePage(
			fromMs = 1_000L,
			toMs = 2_000L,
			limit = 1,
			afterStartedAtMs = secondCursor.startedAtMs,
			afterServiceRunId = secondCursor.serviceRunId,
		) shouldBe emptyList()
	}

	@Test
	fun `history terminal failures apply deterministic tie order and cap in SQL`() = runTest {
		dao.insertManifest(manifest(revision = 1L, serviceRunId = "run-a"))
		dao.insertManifest(manifest(revision = 2L, serviceRunId = "run-a"))
		dao.insertManifestSources(
			listOf(
				stepsManifestSource(
					manifestRevision = 1L,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
					writerOwnerGeneration = 2L,
					writerProjectionId = "z-writer",
					writerProjectionVersion = 2,
					writerBindingGeneration = 3L,
				),
				stepsManifestSource(
					manifestRevision = 2L,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
					writerOwnerGeneration = 2L,
					writerProjectionId = "a-writer",
					writerProjectionVersion = 3,
					writerBindingGeneration = 4L,
				),
			),
		)
		val failures = listOf(
			SourceProjectionFailureEntity("z-writer", 2, 5L, 1, "z-five", true, 5L),
			SourceProjectionFailureEntity("a-writer", 3, 5L, 1, "a-five", true, 5L),
			SourceProjectionFailureEntity("a-writer", 3, 4L, 1, "a-four", true, 4L),
		)
		failures.forEach { database.sourceProjectionStateDao().saveFailure(it) }

		val history = database.trackingHistoryReadDao()
		suspend fun read(limit: Int) = history.terminalFailuresForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			serviceRunIds = listOf("run-a"),
			afterOrdinal = 0L,
			throughOrdinal = 5L,
			limit = limit,
		)

		read(limit = 3).map {
			Triple(it.admissionOrdinal, it.projectionId, it.projectionVersion)
		} shouldContainExactly listOf(
			Triple(4L, "a-writer", 3),
			Triple(5L, "a-writer", 3),
			Triple(5L, "z-writer", 2),
		)
		read(limit = 2).map {
			Triple(it.admissionOrdinal, it.projectionId, it.projectionVersion)
		} shouldContainExactly
			listOf(
				Triple(4L, "a-writer", 3),
				Triple(5L, "a-writer", 3),
			)
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
	fun `exact run latest action predicate spans every terminal and nonterminal status`() = runTest {
		val expectedByStatus = linkedMapOf(
			"AWAITING_FOREGROUND" to true,
			"PENDING" to true,
			"APPLYING" to true,
			"CLEANUP_REQUIRED" to true,
			"START_ACCEPTED" to true,
			"TEMPORARILY_ILLEGAL" to true,
			"TERMINAL_FAILURE" to false,
			"STOP_ACCEPTED" to false,
			"SUPERSEDED" to false,
		)
		expectedByStatus.entries.forEachIndexed { index, (status, expected) ->
			val logicalId = "matrix-logical-$index"
			val runId = "matrix-run-$index"
			dao.insertLifecycleActions(
				listOf(
					lifecycleAction("matrix-$index", 1L, status).copy(
						logicalTrackingId = logicalId,
						serviceRunId = runId,
					),
				),
			)

			dao.hasNonterminalLatestLifecycleAction(logicalId, runId) shouldBe expected
		}
	}

	@Test
	fun `exact run predicate selects latest before considering attempts desired state or status`() =
		runTest {
			val olderAcceptedStart = lifecycleAction("exact-start", 1L, "START_ACCEPTED")
			val newerZeroAttemptStop = lifecycleAction("exact-stop", 2L, "STOP_ACCEPTED").copy(
				desiredState = "UNRECOGNIZED_SETTLEMENT",
				attemptCount = 0,
			)
			dao.insertLifecycleActions(listOf(olderAcceptedStart, newerZeroAttemptStop))

			dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe false

			dao.insertLifecycleActions(
				listOf(
					lifecycleAction("exact-new-pending", 3L, "PENDING").copy(
						desiredState = "UNRECOGNIZED_PENDING",
						attemptCount = 0,
					),
				),
			)
			dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe true
		}

	@Test
	fun `exact run predicate settles each action family and nullable source independently`() = runTest {
		dao.insertLifecycleActions(
			listOf(
				lifecycleAction("steps-start", 1L, "START_ACCEPTED"),
				lifecycleAction("steps-stop", 2L, "STOP_ACCEPTED"),
				lifecycleAction("control-settled", 3L, "SUPERSEDED").copy(
					actionFamily = "ANDROID_SERVICE",
					sourceKind = null,
				),
			),
		)
		dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe false

		dao.insertLifecycleActions(
			listOf(
				lifecycleAction("control-pending", 4L, "PENDING").copy(
					actionFamily = "ANDROID_SERVICE",
					sourceKind = null,
				),
			),
		)
		dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe true

		dao.insertLifecycleActions(
			listOf(
				lifecycleAction("control-failed", 5L, "TERMINAL_FAILURE").copy(
					actionFamily = "ANDROID_SERVICE",
					sourceKind = null,
				),
				lifecycleAction("other-source-pending", 6L, "CLEANUP_REQUIRED").copy(
					sourceKind = 99,
				),
			),
		)
		dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe true

		dao.insertLifecycleActions(
			listOf(
				lifecycleAction("other-source-settled", 7L, "SUPERSEDED").copy(
					sourceKind = 99,
				),
			),
		)
		dao.hasNonterminalLatestLifecycleAction(LOGICAL_ID, "run-a") shouldBe false
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

	private fun controlManifestSource(revision: Long) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = revision,
		sourceKind = 100 + revision.toInt(),
		purpose = SessionManifestPurposeCode.CONTROL,
		consentEpoch = 1L,
		persistenceEligible = false,
		qosCode = 0,
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
		manifestRevision: Long = 1L,
		outputDestination: String? = null,
		writerOwner: String? = null,
		writerOwnerGeneration: Long? = null,
		writerProjectionId: String? = null,
		writerProjectionVersion: Int? = null,
		writerBindingGeneration: Long? = null,
	) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = manifestRevision,
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
