package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.markAuthenticatedStepsRunsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
class StepFactRevisionDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: StepFactRevisionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.stepFactRevisionDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun exactWriterAdmissionLookupDistinguishesReplayConflictAndIndependentShadowReceipt() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		val live = revision(intervalId = intervalId, admissionOrdinal = 10L)
		dao.insert(live) shouldBe 1L

		val exact = dao.writerAdmission(WRITER_ID, WRITER_VERSION, 10L).shouldNotBeNull()
		exact.mutationId shouldBe live.mutationId
		exact.effectChecksum shouldBe live.effectChecksum
		dao.insert(live) shouldBe -1L
		dao.insert(
			live.copy(
				logicalFactId = "conflicting-fact",
				mutationId = "conflicting-mutation",
				sourceEventId = "conflicting-event",
				effectChecksum = "conflicting-checksum",
			),
		) shouldBe -1L

		val shadow = live.copy(writerProjectionId = SHADOW_WRITER_ID)
		dao.insert(shadow) shouldBe 2L
		dao.writerAdmission(SHADOW_WRITER_ID, WRITER_VERSION, 10L) shouldBe shadow
		dao.mutation(WRITER_ID, WRITER_VERSION, live.mutationId) shouldBe live
		dao.mutation(SHADOW_WRITER_ID, WRITER_VERSION, live.mutationId) shouldBe shadow
	}

	@Test
	fun latestAndProductQueriesAreQualifiedByActiveWriterIdentity() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		val canonical = revision(
			intervalId = intervalId,
			admissionOrdinal = 1L,
			effectiveStepCount = 12L,
		)
		val shadow = canonical.copy(
			writerProjectionId = SHADOW_WRITER_ID,
			effectiveStepCount = 7L,
			effectChecksum = "shadow-checksum",
		)
		dao.insert(canonical) shouldBe 1L
		dao.insert(shadow) shouldBe 2L

		dao.revision(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID, 1L) shouldBe canonical
		dao.latest(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID) shouldBe canonical
		dao.latest(SHADOW_WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID) shouldBe shadow
		dao.revisions(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID) shouldBe listOf(canonical)
		dao.latestEffectiveBetween(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			fromMs = 0L,
			toMs = 3_000L,
		).single() shouldBe canonical
		dao.effectiveStepCount(WRITER_ID, WRITER_VERSION, LOGICAL_TRACKING_ID) shouldBe 12L
		dao.effectiveStepCount(
			SHADOW_WRITER_ID,
			WRITER_VERSION,
			LOGICAL_TRACKING_ID,
		) shouldBe 7L
	}

	@Test
	fun redactedLocalDeleteSurvivesLegacyIntervalDeletionAndSuppressesOlderRevision() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		val upsert = revision(
			intervalId = intervalId,
			admissionOrdinal = 1L,
			effectiveStepCount = 12L,
		)
		val tombstone = redactedRetraction(semanticRevision = 2L)
		dao.insert(upsert) shouldBe 1L
		dao.insert(tombstone) shouldBe 2L

		database.stepIntervalDao().deleteAll()

		dao.countAll() shouldBe 2L
		dao.latest(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID) shouldBe tombstone
		dao.latestEffectiveBetween(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			fromMs = 0L,
			toMs = 3_000L,
		).shouldBeEmpty()
		dao.effectiveStepCount(
			WRITER_ID,
			WRITER_VERSION,
			LOGICAL_TRACKING_ID,
		) shouldBe null
	}

	@Test
	fun serviceRunQueryKeepsHistoricalBindingExactAndHonorsRedactedRetraction() = runTest {
		val runOne = revision(
			intervalId = null,
			admissionOrdinal = 11L,
			effectiveStepCount = 4L,
		)
		val runTwo = runOne.copy(
			logicalFactId = "steps-fact-run-2",
			mutationId = "mutation-run-2",
			sourceEventId = "event-run-2",
			sourceAdmissionOrdinal = 22L,
			originIdentity = "event-run-2",
			writerBindingGeneration = 2L,
			serviceRunId = "run-2",
			manifestRevision = 2L,
			effectiveStepCount = 9L,
			effectChecksum = "checksum-run-2",
		)
		dao.insert(runOne) shouldBe 1L
		dao.insert(runTwo) shouldBe 2L

		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			manifestRevisions = listOf(1L),
		) shouldBe listOf(runOne)
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 2L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = "run-2",
			manifestRevisions = listOf(2L),
		) shouldBe listOf(runTwo)

		val deletedRunOne = redactedRetraction(semanticRevision = 2L)
		dao.insert(deletedRunOne) shouldBe 3L
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			manifestRevisions = listOf(1L),
		) shouldBe listOf(deletedRunOne)
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 2L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = "run-2",
			manifestRevisions = listOf(2L),
		) shouldBe listOf(runTwo)
	}

	@Test
	fun serviceRunQueryAssignsCorrectionAndRetractionOnlyToTheLatestUpsertScope() = runTest {
		val runOne = revision(
			intervalId = null,
			admissionOrdinal = 11L,
			effectiveStepCount = 4L,
		)
		val movedToRunTwo = runOne.copy(
			semanticRevision = 2L,
			mutationId = "mutation-moved-to-run-2",
			sourceEventId = "event-run-2",
			sourceAdmissionOrdinal = 22L,
			originIdentity = "event-run-2",
			writerBindingGeneration = 2L,
			serviceRunId = "run-2",
			manifestRevision = 2L,
			effectiveStepCount = 9L,
			effectChecksum = "checksum-moved-to-run-2",
		)
		dao.insert(runOne) shouldBe 1L
		dao.insert(movedToRunTwo) shouldBe 2L

		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			manifestRevisions = listOf(1L),
		).shouldBeEmpty()
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 2L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = "run-2",
			manifestRevisions = listOf(2L),
		) shouldBe listOf(movedToRunTwo)

		val deletedAfterMove = redactedRetraction(semanticRevision = 3L)
		dao.insert(deletedAfterMove) shouldBe 3L
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			manifestRevisions = listOf(1L),
		).shouldBeEmpty()
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 2L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = "run-2",
			manifestRevisions = listOf(2L),
		) shouldBe listOf(deletedAfterMove)
	}

	@Test
	fun rawRetentionRemovesExactAuthenticatedUpsertAndPreservesRedactedRetraction() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val upsert = canonicalRetentionFact(
			factSuffix = "retracted",
			admissionOrdinal = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			startElapsedNanos = 1_000_000_000L,
			endElapsedNanos = 2_000_000_000L,
			cumulativeStart = 100L,
			cumulativeEnd = 112L,
			collectedDataEpoch = 7L,
		)
		val tombstone = canonicalRetraction(upsert)
		dao.insert(upsert) shouldBe 1L
		dao.insert(tombstone) shouldBe 2L

		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(
			beforeMs = 2_001L,
			collectedDataEpoch = 7L,
			markedAtMs = 9_000L,
		) shouldBe 0
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
			beforeMs = 2_001L,
			collectedDataEpoch = 7L,
			markedAtMs = 9_000L,
		) shouldBe 1

		dao.countAll() shouldBe 1L
		dao.latest(WRITER_ID, WRITER_VERSION, upsert.logicalFactId) shouldBe tombstone
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	@Suppress("LongMethod")
	fun retentionMarksClockRegressedRunAndPrunesOnlyExpiredAuthenticatedFacts() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		insertServiceRun("unrelated-run", "unrelated-session")
		val retainedPrefix = canonicalRetentionFact(
			"prefix", 1L, 3_000L, 4_000L, 0L, 1_000_000_000L, 100L, 110L, 7L,
		)
		val clockRegressedCurrent = canonicalRetentionFact(
			"regressed", 2L, 500L, 1_000L, 1_000_000_000L, 1_500_000_000L,
			110L, 120L, 7L,
		)
		val laterRetractedUpsert = canonicalRetentionFact(
			"retracted", 3L, 750L, 1_250L, 1_500_000_000L, 2_000_000_000L,
			120L, 125L, 7L,
		)
		val retainedRetraction = canonicalRetraction(laterRetractedUpsert)
		val unrelated = canonicalRetentionFact(
			"unrelated", 4L, 2_500L, 3_500L, 0L, 1_000_000_000L, 20L, 25L, 7L,
			logicalTrackingId = "unrelated-session",
			serviceRunId = "unrelated-run",
		)
		listOf(retainedPrefix, clockRegressedCurrent, laterRetractedUpsert, retainedRetraction, unrelated)
			.forEach { row -> (dao.insert(row) != -1L) shouldBe true }

		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(
			beforeMs = 1_500L,
			collectedDataEpoch = 7L,
			markedAtMs = 9_000L,
		) shouldBe 1
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(
			beforeMs = 1_500L,
			collectedDataEpoch = 7L,
			markedAtMs = 9_500L,
		) shouldBe 0
		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(
				beforeMs = 1_500L,
				collectedDataEpoch = 8L,
				markedAtMs = 9_500L,
			)
		}
		val markerIdentity = StepFactRevisionIntegrity.retentionTruncationIdentity(
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)
		val marker = database.sourceDeletionFenceDao().get(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = markerIdentity,
		).shouldNotBeNull()
		marker shouldBe StepFactRevisionIntegrity.retentionTruncationFence(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			collectedDataEpoch = 7L,
			markedAtMs = 9_000L,
		)
		marker.collectedDataEpoch shouldBe
			requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
		marker.effectChecksum.length shouldBe 64
		StepFactRevisionIntegrity.isRetentionTruncationFence(
			marker,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			7L,
		) shouldBe true
		StepFactRevisionIntegrity.isRetentionTruncationFence(
			marker,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			8L,
		) shouldBe false
		database.sourceDeletionFenceDao().get(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
				LOGICAL_TRACKING_ID,
				"unrelated-run",
			),
		) shouldBe null

		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
			beforeMs = 1_500L,
			collectedDataEpoch = 7L,
			markedAtMs = 9_500L,
		) shouldBe 2
		dao.revisions(WRITER_ID, WRITER_VERSION, retainedPrefix.logicalFactId) shouldBe
			listOf(retainedPrefix)
		dao.revisions(WRITER_ID, WRITER_VERSION, clockRegressedCurrent.logicalFactId)
			.shouldBeEmpty()
		dao.revisions(WRITER_ID, WRITER_VERSION, laterRetractedUpsert.logicalFactId) shouldBe
			listOf(retainedRetraction)
		dao.revisions(WRITER_ID, WRITER_VERSION, unrelated.logicalFactId) shouldBe listOf(unrelated)
		database.sourceDeletionFenceDao().countAll() shouldBe 1L

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = 8L,
			retainedFromMs = 1_500L,
			updatedAtMs = 10_000L,
		)
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		dao.countAll() shouldBe 0L
	}

	@Test
	fun retentionRejectsChecksumValidUnsupportedCorrectionEvenWithoutAFloorCandidate() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val original = canonicalRetentionFact(
			"future-corrected", 1L, 2_000L, 2_500L, 0L, 500_000_000L,
			10L, 11L, 7L,
		)
		val unsignedCorrection = original.copy(
			semanticRevision = 2L,
			mutationId = "${original.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = "future-correction",
			sourceAdmissionOrdinal = 2L,
			originIdentity = "future-correction",
			intervalStartTimeMs = 2_500L,
			intervalEndTimeMs = 3_000L,
			intervalStartElapsedRealtimeNanos = 500_000_000L,
			intervalEndElapsedRealtimeNanos = 1_000_000_000L,
			cumulativeStepCountStart = 11L,
			cumulativeStepCountEnd = 12L,
			effectiveStepCount = 1L,
			effectChecksum = "unsigned",
			appliedAtMs = 3_000L,
		)
		val correction = unsignedCorrection.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedCorrection),
		)
		dao.insert(original)
		dao.insert(correction)

		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		dao.revisions(WRITER_ID, WRITER_VERSION, original.logicalFactId) shouldBe
			listOf(original, correction)
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun retentionRejectsCorruptAndStaleCandidatesBeforeMarkerOrDeletion() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val affected = canonicalRetentionFact(
			"affected", 1L, 500L, 1_000L, 0L, 500_000_000L, 100L, 105L, 7L,
		)
		val corrupt = canonicalRetentionFact(
			"corrupt", 2L, 2_000L, 2_500L, 500_000_000L, 1_000_000_000L,
			105L, 110L, 7L,
		).copy(effectChecksum = "checksum-invalid")
		val stale = canonicalRetentionFact(
			"stale", 3L, 2_500L, 3_000L, 1_000_000_000L, 1_500_000_000L,
			110L, 115L, 6L,
		)
		val nonCanonical = revision(intervalId = null, admissionOrdinal = 4L).copy(
			logicalFactId = "noncanonical-fact",
			mutationId = "noncanonical-mutation",
			sourceEventId = "noncanonical-event",
			originIdentity = "noncanonical-event",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			intervalStartTimeMs = 3_000L,
			intervalEndTimeMs = 3_500L,
			effectChecksum = "noncanonical-checksum",
		)
		listOf(affected, corrupt, stale, nonCanonical).forEach { dao.insert(it) }

		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		dao.countAll() shouldBe 4L
		dao.revision(WRITER_ID, WRITER_VERSION, affected.logicalFactId, 1L) shouldBe affected
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun unauthenticatedCandidatesAloneCannotAuthorizeRetention() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val checksumInvalid = canonicalRetentionFact(
			"checksum-invalid", 1L, 500L, 1_000L, 0L, 500_000_000L, 10L, 11L, 7L,
		).copy(effectChecksum = "invalid")
		val stale = canonicalRetentionFact(
			"stale-only", 2L, 600L, 1_100L, 500_000_000L, 1_000_000_000L,
			11L, 12L, 6L,
		)
		val nonCanonical = revision(intervalId = null, admissionOrdinal = 3L).copy(
			logicalFactId = "noncanonical-only",
			mutationId = "noncanonical-only-mutation",
			sourceEventId = "noncanonical-only-event",
			originIdentity = "noncanonical-only-event",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			intervalStartTimeMs = 700L,
			intervalEndTimeMs = 1_200L,
			effectChecksum = "noncanonical-only-checksum",
		)
		listOf(checksumInvalid, stale, nonCanonical).forEach { dao.insert(it) }

		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		dao.countAll() shouldBe 3L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun retentionFailsClosedWhenCandidateHasNoAuthoritativeServiceRun() = runTest {
		initializeRetentionEpoch(7L)
		val orphaned = canonicalRetentionFact(
			"orphaned", 1L, 500L, 1_000L, 0L, 500_000_000L, 10L, 11L, 7L,
		)
		dao.insert(orphaned)

		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		dao.revision(WRITER_ID, WRITER_VERSION, orphaned.logicalFactId, 1L) shouldBe orphaned
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun supersededExpiredRevisionCannotBeDeletedOrResurrectedWithoutAuthenticatedLineage() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val expired = canonicalRetentionFact(
			"corrected", 1L, 500L, 1_000L, 0L, 500_000_000L, 10L, 11L, 7L,
		)
		val unsignedCorrection = expired.copy(
			semanticRevision = 2L,
			mutationId = "${expired.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = "retention-correction",
			sourceAdmissionOrdinal = 2L,
			originIdentity = "retention-correction",
			intervalStartTimeMs = 2_000L,
			intervalEndTimeMs = 2_500L,
			intervalStartElapsedRealtimeNanos = 500_000_000L,
			intervalEndElapsedRealtimeNanos = 1_000_000_000L,
			cumulativeStepCountStart = 11L,
			cumulativeStepCountEnd = 12L,
			effectiveStepCount = 1L,
			effectChecksum = "unsigned",
			appliedAtMs = 2_500L,
		)
		val correction = unsignedCorrection.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedCorrection),
		)
		dao.insert(expired)
		dao.insert(correction)

		shouldThrow<IllegalStateException> {
			database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
		}
		dao.revisions(WRITER_ID, WRITER_VERSION, expired.logicalFactId) shouldBe
			listOf(expired, correction)
		dao.latest(WRITER_ID, WRITER_VERSION, expired.logicalFactId) shouldBe correction
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun retentionAuditsMutatedDiscoveryFieldsBeforeMarkerOrDeletion() = runTest {
		initializeRetentionEpoch(7L)
		insertServiceRun(SERVICE_RUN_ID, LOGICAL_TRACKING_ID)
		val mutations = listOf(
			"UPDATE step_fact_revision SET logical_tracking_id = NULL, service_run_id = NULL, " +
				"purpose = 'CONTROL' WHERE logical_fact_id = ?",
			"UPDATE step_fact_revision SET operation = 'RETRACT' WHERE logical_fact_id = ?",
			"UPDATE step_fact_revision SET interval_end_time_ms = 2500 " +
				"WHERE logical_fact_id = ?",
		)
		mutations.forEachIndexed { index, sql ->
			val malformed = canonicalRetentionFact(
				"malformed-$index", index + 1L, 500L, 1_000L,
				0L, 500_000_000L, 1L, 2L, 7L,
			)
			(dao.insert(malformed) != -1L) shouldBe true
			database.openHelper.writableDatabase.execSQL(
				sql,
				arrayOf<Any>(malformed.logicalFactId),
			)

			shouldThrow<IllegalStateException> {
				database.markAuthenticatedStepsRunsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
			}
			shouldThrow<IllegalStateException> {
				database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(1_500L, 7L, 9_000L)
			}
			dao.countAll() shouldBe 1L
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			dao.deleteAll()
		}
	}

	@Test
	fun aggregateKeepsNoDataDistinctFromVerifiedZeroAndRowsExposeCoverage() = runTest {
		dao.effectiveStepCount(
			WRITER_ID,
			WRITER_VERSION,
			LOGICAL_TRACKING_ID,
		) shouldBe null

		val intervalId = insertInterval(startMs = 1_000L, endMs = 1_000L)
		val baseline = revision(
			intervalId = intervalId,
			admissionOrdinal = 1L,
			coverageKind = StepFactRevisionEntity.COVERAGE_BASELINE,
			effectiveStepCount = 0L,
			cumulativeStepCountStart = 100L,
			cumulativeStepCountEnd = 100L,
		)
		dao.insert(baseline) shouldBe 1L

		dao.effectiveStepCount(
			WRITER_ID,
			WRITER_VERSION,
			LOGICAL_TRACKING_ID,
		) shouldBe 0L
		val row = dao.latestEffectiveBetween(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			fromMs = 0L,
			toMs = 2_000L,
		).single()
		row.effectiveStepCount shouldBe 0L
		row.coverageKind shouldBe StepFactRevisionEntity.COVERAGE_BASELINE
	}

	@Test
	fun `exact run payload read spans every upsert revision and excludes unrelated scope`() = runTest {
		val first = revision(intervalId = null, admissionOrdinal = 1L, effectiveStepCount = 4L)
		val correction = revision(
			intervalId = null,
			semanticRevision = 2L,
			admissionOrdinal = 2L,
			effectiveStepCount = 5L,
		)
		val unrelated = revision(intervalId = null, admissionOrdinal = 3L).copy(
			logicalFactId = "unrelated-fact",
			mutationId = "unrelated-mutation",
			sourceEventId = "unrelated-event",
			originIdentity = "unrelated-event",
			logicalTrackingId = "unrelated-logical",
			serviceRunId = "unrelated-run",
			effectChecksum = "unrelated-checksum",
		)
		val otherSelected = revision(
			intervalId = null,
			admissionOrdinal = 4L,
			effectiveStepCount = 6L,
		).copy(
			logicalFactId = "zz-selected-fact",
			mutationId = "zz-selected-mutation",
			sourceEventId = "zz-selected-event",
			originIdentity = "zz-selected-event",
			intervalStartTimeMs = 3_000L,
			intervalEndTimeMs = 4_000L,
			effectChecksum = "zz-selected-checksum",
		)
		dao.insert(first) shouldBe 1L
		dao.insert(correction) shouldBe 2L
		dao.insert(unrelated) shouldBe 3L
		dao.insert(otherSelected) shouldBe 4L

		dao.upsertsForServiceRun(LOGICAL_TRACKING_ID, SERVICE_RUN_ID) shouldBe
			listOf(first, correction, otherSelected)
		dao.upsertsForServiceRun(LOGICAL_TRACKING_ID, SERVICE_RUN_ID, limit = 2) shouldBe
			listOf(first, correction)
		dao.latestStatesForServiceRun(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			manifestRevisions = listOf(1L),
			limit = 1,
		) shouldBe listOf(correction)
		database.trackingHistoryReadDao().stepFactStates(
			serviceRunIds = listOf(SERVICE_RUN_ID),
			limit = 1,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		).map { scoped -> scoped.state.logicalFactId } shouldBe listOf(first.logicalFactId)
	}

	@Test
	fun `candidate paging keeps out of Int projection version typed and advancing`() = runTest {
		val row = revision(intervalId = null, admissionOrdinal = 1L)
		dao.insert(row) shouldBe 1L
		val rawVersion = Int.MAX_VALUE.toLong() + 1L
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET writer_projection_version = ? " +
				"WHERE writer_projection_id = ? AND logical_fact_id = ?",
			arrayOf<Any>(rawVersion, row.writerProjectionId, row.logicalFactId),
		)
		val pageSizes = mutableListOf<Int>()
		val candidates = database.trackingHistoryReadDao().loadStepsFactCandidateStatesByRun(
			serviceRuns = listOf(
				SourceServiceRunEntity(
					serviceRunId = SERVICE_RUN_ID,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					state = "PREPARED",
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					foregroundCapabilityFlags = 0L,
					startedAtMs = 1_000L,
					startedElapsedNanos = 1_000L,
					completedAtMs = null,
					completionReason = null,
				),
			),
			onPageLoaded = pageSizes::add,
		)

		pageSizes shouldContainExactly listOf(1, 0)
		val candidate = candidates.getValue(SERVICE_RUN_ID).single()
		candidate.scopeCarrier shouldBe null
		candidate.state shouldBe null
	}

	@Test
	fun `history latest fact state pages preserve run interval and fact ties exactly once`() = runTest {
		fun fact(
			runId: String,
			factId: String,
			startMs: Long,
			admissionOrdinal: Long,
		) = revision(intervalId = null, admissionOrdinal = admissionOrdinal).copy(
			logicalFactId = factId,
			mutationId = "mutation-$factId",
			sourceEventId = "event-$factId",
			originIdentity = "event-$factId",
			intervalStartTimeMs = startMs,
			intervalEndTimeMs = startMs + 100L,
			logicalTrackingId = "logical-$runId",
			serviceRunId = runId,
			effectChecksum = "checksum-$factId",
			appliedAtMs = startMs + 100L,
		)

		val runAFactB = fact("run-a", "fact-b", 1_000L, 2L)
		val runBFactE = fact("run-b", "fact-e", 500L, 5L)
		val runAFactA = fact("run-a", "fact-a", 1_000L, 1L)
		val runBFactD = fact("run-b", "fact-d", 500L, 4L)
		val runAFactC = fact("run-a", "fact-c", 2_000L, 3L)
		listOf(runAFactB, runBFactE, runAFactA, runBFactD, runAFactC).forEach { row ->
			dao.insert(row)
		}
		val retractedRunAFactA = redactedRetraction(semanticRevision = 2L).copy(
			logicalFactId = runAFactA.logicalFactId,
			mutationId = "delete-${runAFactA.logicalFactId}",
			originIdentity = "delete-${runAFactA.logicalFactId}",
			writerBindingGeneration = runAFactA.writerBindingGeneration,
			collectedDataEpoch = runAFactA.collectedDataEpoch,
			effectChecksum = "delete-checksum-${runAFactA.logicalFactId}",
		)
		dao.insert(retractedRunAFactA)

		val history = database.trackingHistoryReadDao()
		val seen = mutableListOf<ScopedStepFactState>()
		var afterServiceRunId: String? = null
		var afterFirstIntervalStartTimeMs: Long? = null
		var afterLogicalFactId: String? = null
		var afterWriterProjectionId: String? = null
		var afterWriterProjectionVersion: Int? = null
		while (true) {
			val page = history.stepFactStatePage(
				serviceRunIds = listOf("run-b", "run-a"),
				limit = 2,
				afterServiceRunId = afterServiceRunId,
				afterFirstIntervalStartTimeMs = afterFirstIntervalStartTimeMs,
				afterLogicalFactId = afterLogicalFactId,
				afterWriterProjectionId = afterWriterProjectionId,
				afterWriterProjectionVersion = afterWriterProjectionVersion,
			)
			seen += page
			val last = page.lastOrNull() ?: break
			afterServiceRunId = last.serviceRunId
			afterFirstIntervalStartTimeMs = last.firstIntervalStartTimeMs
			afterLogicalFactId = last.state.logicalFactId
			afterWriterProjectionId = last.state.writerProjectionId
			afterWriterProjectionVersion = last.state.writerProjectionVersion
			if (page.size < 2) {
				break
			}
		}

		seen.map { scoped ->
			Triple(scoped.serviceRunId, scoped.firstIntervalStartTimeMs, scoped.state.logicalFactId)
		} shouldBe listOf(
			Triple("run-a", 1_000L, "fact-a"),
			Triple("run-a", 1_000L, "fact-b"),
			Triple("run-a", 2_000L, "fact-c"),
			Triple("run-b", 500L, "fact-d"),
			Triple("run-b", 500L, "fact-e"),
		)
		seen.map { scoped -> scoped.state.logicalFactId to scoped.serviceRunId }.distinct().size shouldBe
			seen.size
		seen.first().state.operation shouldBe StepFactRevisionEntity.OPERATION_RETRACT
	}

	@Test
	fun `history latest fact state pages cross a full tie boundary exactly once`() = runTest {
		val sharedFactId = "shared-fact"
		val sharedStartMs = 1_000L
		val facts = (1..257).map { writerVersion ->
			revision(intervalId = null, admissionOrdinal = 1L).copy(
				logicalFactId = sharedFactId,
				mutationId = "mutation-$writerVersion",
				sourceEventId = "event-$writerVersion",
				originIdentity = "event-$writerVersion",
				writerProjectionVersion = writerVersion,
				intervalStartTimeMs = sharedStartMs,
				intervalEndTimeMs = sharedStartMs + 100L,
				effectChecksum = "checksum-$writerVersion",
				appliedAtMs = sharedStartMs + 100L,
			)
		}
		facts.forEach { fact -> dao.insert(fact) }

		val history = database.trackingHistoryReadDao()
		val seenVersions = mutableListOf<Int>()
		var afterServiceRunId: String? = null
		var afterFirstIntervalStartTimeMs: Long? = null
		var afterLogicalFactId: String? = null
		var afterWriterProjectionId: String? = null
		var afterWriterProjectionVersion: Int? = null
		var pageSize: Int
		do {
			val page = history.stepFactStatePage(
				serviceRunIds = listOf(SERVICE_RUN_ID),
				limit = 256,
				afterServiceRunId = afterServiceRunId,
				afterFirstIntervalStartTimeMs = afterFirstIntervalStartTimeMs,
				afterLogicalFactId = afterLogicalFactId,
				afterWriterProjectionId = afterWriterProjectionId,
				afterWriterProjectionVersion = afterWriterProjectionVersion,
			)
			seenVersions += page.map { scoped -> scoped.state.writerProjectionVersion }
			val last = page.lastOrNull()
			afterServiceRunId = last?.serviceRunId
			afterFirstIntervalStartTimeMs = last?.firstIntervalStartTimeMs
			afterLogicalFactId = last?.state?.logicalFactId
			afterWriterProjectionId = last?.state?.writerProjectionId
			afterWriterProjectionVersion = last?.state?.writerProjectionVersion
			pageSize = page.size
		} while (pageSize == 256)

		seenVersions shouldBe (1..257).toList()
		seenVersions.distinct().size shouldBe facts.size
	}

	@Test
	@Suppress("LongMethod")
	fun `history upsert revision pages retain corrections and exclude retractions`() = runTest {
		val rows = listOf(
			revision(intervalId = null, admissionOrdinal = 1L).copy(
				logicalFactId = "fact-b",
				serviceRunId = "run-a",
				writerProjectionId = "writer-z",
				mutationId = "a-z-b-1",
				sourceEventId = "a-z-b-event-1",
				originIdentity = "a-z-b-event-1",
			),
			revision(intervalId = null, semanticRevision = 2L, admissionOrdinal = 2L).copy(
				logicalFactId = "fact-b",
				serviceRunId = "run-a",
				writerProjectionId = "writer-z",
				mutationId = "a-z-b-2",
				sourceEventId = "a-z-b-event-2",
				originIdentity = "a-z-b-event-2",
			),
			revision(intervalId = null, admissionOrdinal = 3L).copy(
				logicalFactId = "fact-z",
				serviceRunId = "run-a",
				writerProjectionId = "writer-a",
				mutationId = "a-a-z-1",
				sourceEventId = "a-a-z-event-1",
				originIdentity = "a-a-z-event-1",
			),
			revision(intervalId = null, admissionOrdinal = 4L).copy(
				logicalFactId = "fact-a",
				serviceRunId = "run-b",
				writerProjectionId = "writer-a",
				mutationId = "b-a-a-1",
				sourceEventId = "b-a-a-event-1",
				originIdentity = "b-a-a-event-1",
			),
		)
		rows.forEach { row -> dao.insert(row) }
		dao.insert(redactedRetraction(semanticRevision = 2L))

		val history = database.trackingHistoryReadDao()
		val seen = mutableListOf<StepFactRevisionEntity>()
		var afterServiceRunId: String? = null
		var afterWriterProjectionId: String? = null
		var afterWriterProjectionVersion: Int? = null
		var afterLogicalFactId: String? = null
		var afterSemanticRevision: Long? = null
		var pageSize: Int
		do {
			val page = history.stepFactUpsertRevisionPage(
				serviceRunIds = listOf("run-b", "run-a"),
				capturePurpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				limit = 2,
				afterServiceRunId = afterServiceRunId,
				afterWriterProjectionId = afterWriterProjectionId,
				afterWriterProjectionVersion = afterWriterProjectionVersion,
				afterLogicalFactId = afterLogicalFactId,
				afterSemanticRevision = afterSemanticRevision,
			)
			seen += page
			val last = page.lastOrNull()
			afterServiceRunId = last?.serviceRunId
			afterWriterProjectionId = last?.writerProjectionId
			afterWriterProjectionVersion = last?.writerProjectionVersion
			afterLogicalFactId = last?.logicalFactId
			afterSemanticRevision = last?.semanticRevision
			pageSize = page.size
		} while (pageSize == 2)

		seen.map { row ->
			listOf(
				requireNotNull(row.serviceRunId),
				row.writerProjectionId,
				row.logicalFactId,
				row.semanticRevision.toString(),
			)
		} shouldContainExactly listOf(
			listOf("run-a", "writer-a", "fact-z", "1"),
			listOf("run-a", "writer-z", "fact-b", "1"),
			listOf("run-a", "writer-z", "fact-b", "2"),
			listOf("run-b", "writer-a", "fact-a", "1"),
		)
		history.stepFactUpsertRevisionPage(
			serviceRunIds = listOf("run-a", "run-b"),
			capturePurpose = SessionManifestPurposeCode.CONTROL,
			limit = 10,
			afterServiceRunId = null,
			afterWriterProjectionId = null,
			afterWriterProjectionVersion = null,
			afterLogicalFactId = null,
			afterSemanticRevision = null,
		) shouldBe emptyList()
	}

	@Test
	fun `portable fact lineage pages retain moved corrections and retractions`() = runTest {
		val initial = revision(intervalId = null, admissionOrdinal = 1L)
		val movedCorrection = revision(
			intervalId = null,
			semanticRevision = 2L,
			admissionOrdinal = 2L,
		).copy(
			serviceRunId = "replacement-run",
			mutationId = "moved-correction",
			sourceEventId = "moved-event",
			originIdentity = "moved-event",
		)
		val retraction = redactedRetraction(semanticRevision = 3L)
		val unrelated = revision(intervalId = null, admissionOrdinal = 3L).copy(
			logicalFactId = "unrelated-fact",
			mutationId = "unrelated-mutation",
			sourceEventId = "unrelated-event",
			originIdentity = "unrelated-event",
		)
		listOf(initial, movedCorrection, retraction, unrelated).forEach { row ->
			dao.insert(row)
		}

		val history = database.trackingHistoryReadDao()
		val seen = mutableListOf<StepFactRevisionEntity>()
		var afterLogicalFactId: String? = null
		var afterSemanticRevision: Long? = null
		var pageSize: Int
		do {
			val page = history.portableStepFactLineagePage(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalFactIds = listOf(LOGICAL_FACT_ID),
				limit = 2,
				afterLogicalFactId = afterLogicalFactId,
				afterSemanticRevision = afterSemanticRevision,
			)
			seen += page
			afterLogicalFactId = page.lastOrNull()?.logicalFactId
			afterSemanticRevision = page.lastOrNull()?.semanticRevision
			pageSize = page.size
		} while (pageSize == 2)

		seen shouldContainExactly listOf(initial, movedCorrection, retraction)
	}

	@Test
	fun entityRejectsIncompleteUpsertAndNonRedactedRetraction() {
		shouldThrow<IllegalArgumentException> {
			revision(intervalId = 1L, admissionOrdinal = 1L).copy(intervalStartTimeMs = null)
		}
		shouldThrow<IllegalArgumentException> {
			redactedRetraction(semanticRevision = 2L).copy(logicalTrackingId = LOGICAL_TRACKING_ID)
		}
		shouldThrow<IllegalArgumentException> {
			redactedRetraction(semanticRevision = 2L).copy(
				originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			)
		}
		shouldThrow<IllegalArgumentException> {
			redactedRetraction(semanticRevision = 2L).copy(scopeDeletionGeneration = 0L)
		}
	}

	private suspend fun insertInterval(startMs: Long, endMs: Long): Long =
		database.stepIntervalDao().insert(
			StepInterval(
				startTimeMs = startMs,
				endTimeMs = endMs,
				stepCount = 12,
				sensorValueStart = 100,
				sensorValueEnd = 112,
				sensorReset = false,
				createdAt = endMs,
				sourceSignalId = "signal-$startMs",
			),
		)

	private suspend fun initializeRetentionEpoch(epoch: Long) {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = epoch))
	}

	private suspend fun insertServiceRun(serviceRunId: String, logicalTrackingId: String) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = "PREPARED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 0L,
				startedElapsedNanos = 0L,
				completedAtMs = null,
				completionReason = null,
			),
		)
	}

	@Suppress("LongParameterList")
	private fun canonicalRetentionFact(
		factSuffix: String,
		admissionOrdinal: Long,
		startMs: Long,
		endMs: Long,
		startElapsedNanos: Long,
		endElapsedNanos: Long,
		cumulativeStart: Long,
		cumulativeEnd: Long,
		collectedDataEpoch: Long,
		logicalTrackingId: String = LOGICAL_TRACKING_ID,
		serviceRunId: String = SERVICE_RUN_ID,
	): StepFactRevisionEntity {
		val sourceEventId = "retention-$factSuffix"
		val logicalFactId = "$WRITER_ID:$sourceEventId"
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = sourceEventId,
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = startMs,
			intervalEndTimeMs = endMs,
			intervalStartElapsedRealtimeNanos = startElapsedNanos,
			intervalEndElapsedRealtimeNanos = endElapsedNanos,
			clockDomainId = "retention-boot",
			bootClockDomainId = "retention-boot",
			cumulativeStepCountStart = cumulativeStart,
			cumulativeStepCountEnd = cumulativeEnd,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = cumulativeEnd - cumulativeStart,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = 0L,
			effectChecksum = "unsigned",
			appliedAtMs = endMs,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private fun canonicalRetraction(scope: StepFactRevisionEntity): StepFactRevisionEntity {
		val scopeIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = requireNotNull(scope.purpose),
			logicalTrackingId = requireNotNull(scope.logicalTrackingId),
			serviceRunId = requireNotNull(scope.serviceRunId),
		)
		val semanticRevision = scope.semanticRevision + 1L
		val deletionGeneration = 1L
		val unsigned = StepFactRevisionEntity(
			logicalFactId = scope.logicalFactId,
			semanticRevision = semanticRevision,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentityDigest = scopeIdentity,
				logicalFactId = scope.logicalFactId,
				semanticRevision = semanticRevision,
				scopeDeletionGeneration = deletionGeneration,
			),
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentity,
			writerProjectionId = scope.writerProjectionId,
			writerProjectionVersion = scope.writerProjectionVersion,
			writerBindingGeneration = scope.writerBindingGeneration,
			operation = StepFactRevisionEntity.OPERATION_RETRACT,
			intervalStartTimeMs = null,
			intervalEndTimeMs = null,
			intervalStartElapsedRealtimeNanos = null,
			intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null,
			bootClockDomainId = null,
			cumulativeStepCountStart = null,
			cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = null,
			coverageKind = null,
			effectiveStepCount = null,
			logicalTrackingId = null,
			serviceRunId = null,
			purpose = scope.purpose,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			collectedDataEpoch = scope.collectedDataEpoch,
			scopeDeletionGeneration = deletionGeneration,
			effectChecksum = "unsigned",
			appliedAtMs = requireNotNull(scope.intervalEndTimeMs) + 1L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

	private fun revision(
		intervalId: Long?,
		semanticRevision: Long = 1L,
		admissionOrdinal: Long,
		coverageKind: String = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount: Long = 0L,
		cumulativeStepCountStart: Long = 100L,
		cumulativeStepCountEnd: Long = 112L,
	): StepFactRevisionEntity = StepFactRevisionEntity(
		logicalFactId = LOGICAL_FACT_ID,
		semanticRevision = semanticRevision,
		mutationId = "mutation-$semanticRevision",
		stepIntervalId = intervalId,
		sourceEventId = "event-$admissionOrdinal",
		sourceAdmissionOrdinal = admissionOrdinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "event-$admissionOrdinal",
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = 1L,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 2_000L,
		intervalStartElapsedRealtimeNanos = 10_000L,
		intervalEndElapsedRealtimeNanos = 20_000L,
		clockDomainId = "elapsed-domain-1",
		bootClockDomainId = "boot-1",
		cumulativeStepCountStart = cumulativeStepCountStart,
		cumulativeStepCountEnd = cumulativeStepCountEnd,
		wallTimeUncertaintyMs = 25L,
		coverageKind = coverageKind,
		effectiveStepCount = effectiveStepCount,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		scopeDeletionGeneration = 0L,
		effectChecksum = "checksum-$semanticRevision",
		appliedAtMs = 2_000L + semanticRevision,
	)

	private fun redactedRetraction(semanticRevision: Long): StepFactRevisionEntity =
		StepFactRevisionEntity(
			logicalFactId = LOGICAL_FACT_ID,
			semanticRevision = semanticRevision,
			mutationId = "delete-mutation-$semanticRevision",
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = "delete-request-opaque",
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 2L,
			operation = StepFactRevisionEntity.OPERATION_RETRACT,
			intervalStartTimeMs = null,
			intervalEndTimeMs = null,
			intervalStartElapsedRealtimeNanos = null,
			intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null,
			bootClockDomainId = null,
			cumulativeStepCountStart = null,
			cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = null,
			coverageKind = null,
			effectiveStepCount = null,
			logicalTrackingId = null,
			serviceRunId = null,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			collectedDataEpoch = 2L,
			scopeDeletionGeneration = 1L,
			effectChecksum = "redacted-delete-checksum",
			appliedAtMs = 3_000L,
		)

	@Test
	fun exactServiceRunPayloadDeletionRetainsRetractionAndUnrelatedRun() = runTest {
		val selected = revision(
			intervalId = null,
			admissionOrdinal = 1L,
			effectiveStepCount = 12L,
		)
		val retraction = redactedRetraction(semanticRevision = 2L).copy(
			writerBindingGeneration = selected.writerBindingGeneration,
			collectedDataEpoch = selected.collectedDataEpoch,
		)
		val unrelated = revision(
			intervalId = null,
			admissionOrdinal = 2L,
			effectiveStepCount = 8L,
		).copy(
			logicalFactId = "steps-fact-unrelated",
			mutationId = "mutation-unrelated",
			sourceEventId = "event-unrelated",
			originIdentity = "event-unrelated",
			logicalTrackingId = "session-unrelated",
			serviceRunId = "run-unrelated",
			effectChecksum = "checksum-unrelated",
		)
		val priorWriterSelected = unrelated.copy(
			logicalFactId = "steps-fact-prior-writer",
			mutationId = "mutation-prior-writer",
			sourceEventId = "event-prior-writer",
			originIdentity = "event-prior-writer",
			writerProjectionId = "steps-session-facts-v0",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			effectChecksum = "checksum-prior-writer",
		)
		dao.insert(selected) shouldBe 1L
		dao.insert(retraction) shouldBe 2L
		dao.insert(unrelated) shouldBe 3L
		dao.insert(priorWriterSelected) shouldBe 4L

		dao.deleteUpsertsForServiceRun(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
		) shouldBe 2

		dao.revision(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID, 1L) shouldBe null
		dao.revision(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID, 2L) shouldBe retraction
		dao.revision(WRITER_ID, WRITER_VERSION, unrelated.logicalFactId, 1L) shouldBe unrelated
		dao.revision(
			priorWriterSelected.writerProjectionId,
			priorWriterSelected.writerProjectionVersion,
			priorWriterSelected.logicalFactId,
			1L,
		) shouldBe null
	}

	private companion object {
		const val LOGICAL_FACT_ID = "steps-fact-1"
		const val LOGICAL_TRACKING_ID = "session-1"
		const val SERVICE_RUN_ID = "run-1"
		const val WRITER_ID = "steps-session-facts"
		const val SHADOW_WRITER_ID = "steps-session-facts-shadow"
		const val WRITER_VERSION = 1
	}
}
