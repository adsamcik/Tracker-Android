package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
	fun rawRetentionRemovesExpiredUpsertPayloadAndPreservesRedactedRetraction() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		val upsert = revision(
			intervalId = intervalId,
			admissionOrdinal = 1L,
			effectiveStepCount = 12L,
		)
		val tombstone = redactedRetraction(semanticRevision = 2L)
		dao.insert(upsert) shouldBe 1L
		dao.insert(tombstone) shouldBe 2L

		dao.deleteUpsertsEndingBefore(2_000L) shouldBe 0
		dao.deleteUpsertsEndingBefore(2_001L) shouldBe 1

		dao.countAll() shouldBe 1L
		dao.latest(WRITER_ID, WRITER_VERSION, LOGICAL_FACT_ID) shouldBe tombstone
		dao.latestEffectiveBetween(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			fromMs = 0L,
			toMs = 3_000L,
		).shouldBeEmpty()
	}

	@Test
	fun retentionCannotRevealAnOlderScopeWhenTheLatestCorrectionExpires() = runTest {
		val retainedOldScope = revision(
			intervalId = null,
			admissionOrdinal = 11L,
			effectiveStepCount = 0L,
		).copy(
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			intervalStartTimeMs = 2_000L,
			intervalEndTimeMs = 3_000L,
		)
		val expiredCurrentScope = retainedOldScope.copy(
			semanticRevision = 2L,
			mutationId = "mutation-expired-run-2",
			sourceEventId = "event-expired-run-2",
			sourceAdmissionOrdinal = 22L,
			originIdentity = "event-expired-run-2",
			writerBindingGeneration = 2L,
			intervalStartTimeMs = 500L,
			intervalEndTimeMs = 1_000L,
			serviceRunId = "run-2",
			manifestRevision = 2L,
			effectChecksum = "checksum-expired-run-2",
		)
		dao.insert(retainedOldScope) shouldBe 1L
		dao.insert(expiredCurrentScope) shouldBe 2L

		dao.deleteUpsertsEndingBefore(1_500L) shouldBe 2
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
		).shouldBeEmpty()

		val redactedDeletion = redactedRetraction(semanticRevision = 3L)
		(dao.insert(redactedDeletion) != -1L) shouldBe true
		dao.countAll() shouldBe 1L
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
		).shouldBeEmpty()
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

	private companion object {
		const val LOGICAL_FACT_ID = "steps-fact-1"
		const val LOGICAL_TRACKING_ID = "session-1"
		const val SERVICE_RUN_ID = "run-1"
		const val WRITER_ID = "steps-session-facts"
		const val SHADOW_WRITER_ID = "steps-session-facts-shadow"
		const val WRITER_VERSION = 1
	}
}
