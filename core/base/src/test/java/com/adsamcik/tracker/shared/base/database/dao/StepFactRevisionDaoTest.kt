package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import io.kotest.matchers.collections.shouldHaveSize
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
	fun revisionsAreIdentityAddressableAndLatestEffectSupersedesCorrectionsAndRetraction() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		dao.insert(revision(intervalId, semanticRevision = 1L, admissionOrdinal = 1L)) shouldBe 1L
		dao.insert(
			revision(
				intervalId = intervalId,
				semanticRevision = 2L,
				admissionOrdinal = 2L,
				effectiveStepCount = 12L,
			),
		) shouldBe 2L

		dao.revision(LOGICAL_FACT_ID, 1L).shouldNotBeNull()
		dao.mutation("mutation-2")?.semanticRevision shouldBe 2L
		dao.revisions(LOGICAL_FACT_ID).map { it.semanticRevision } shouldBe listOf(1L, 2L)
		dao.latest(LOGICAL_FACT_ID)?.effectiveStepCount shouldBe 12L
		dao.effectiveStepCount(LOGICAL_TRACKING_ID) shouldBe 12L
		dao.latestEffectiveBetween(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			fromMs = 0L,
			toMs = 3_000L,
		).single().semanticRevision shouldBe 2L

		dao.insert(
			revision(
				intervalId = intervalId,
				semanticRevision = 3L,
				admissionOrdinal = 3L,
				effectiveStepCount = 7L,
			),
		) shouldBe 3L
		dao.latest(LOGICAL_FACT_ID)?.effectiveStepCount shouldBe 7L

		dao.insert(
			revision(
				intervalId = intervalId,
				semanticRevision = 4L,
				admissionOrdinal = 4L,
				operation = StepFactRevisionEntity.OPERATION_RETRACT,
				effectiveStepCount = 0L,
			),
		) shouldBe 4L
		dao.latest(LOGICAL_FACT_ID)?.operation shouldBe StepFactRevisionEntity.OPERATION_RETRACT
		dao.effectiveStepCount(LOGICAL_TRACKING_ID) shouldBe 0L
	}

	@Test
	fun mutationAndLiveWriterOrdinalAreIdempotentWhileImportNullOrdinalsRemainDistinct() = runTest {
		val firstIntervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		val secondIntervalId = insertInterval(startMs = 3_000L, endMs = 4_000L)
		val live = revision(firstIntervalId, semanticRevision = 1L, admissionOrdinal = 10L)
		dao.insert(live) shouldBe 1L

		dao.insert(
			live.copy(
				logicalFactId = "other-fact",
				semanticRevision = 2L,
				stepIntervalId = secondIntervalId,
				sourceEventId = "event-other",
				mutationId = live.mutationId,
			),
		) shouldBe -1L
		dao.insert(
			live.copy(
				logicalFactId = "ordinal-collision",
				semanticRevision = 1L,
				stepIntervalId = secondIntervalId,
				sourceEventId = "event-ordinal-collision",
				mutationId = "mutation-ordinal-collision",
			),
		) shouldBe -1L

		dao.insert(portableRevision(firstIntervalId, "portable-a", "mutation-portable-a")) shouldBe 2L
		dao.insert(portableRevision(secondIntervalId, "portable-b", "mutation-portable-b")) shouldBe 3L
		dao.countAll() shouldBe 3L
	}

	@Test
	fun deletingImmutableIntervalCascadesItsFactRevisions() = runTest {
		val intervalId = insertInterval(startMs = 1_000L, endMs = 2_000L)
		dao.insert(revision(intervalId, semanticRevision = 1L, admissionOrdinal = 1L))
		dao.countAll() shouldBe 1L

		database.stepIntervalDao().deleteAll()

		dao.countAll() shouldBe 0L
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
		intervalId: Long,
		semanticRevision: Long,
		admissionOrdinal: Long,
		operation: String = StepFactRevisionEntity.OPERATION_UPSERT,
		effectiveStepCount: Long = 0L,
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
		writerProjectionVersion = 1,
		writerBindingGeneration = 1L,
		operation = operation,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = effectiveStepCount,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		effectChecksum = "checksum-$semanticRevision",
		appliedAtMs = 2_000L + semanticRevision,
	)

	private fun portableRevision(
		intervalId: Long,
		logicalFactId: String,
		mutationId: String,
	): StepFactRevisionEntity = revision(
		intervalId = intervalId,
		semanticRevision = 1L,
		admissionOrdinal = 1L,
	).copy(
		logicalFactId = logicalFactId,
		mutationId = mutationId,
		sourceEventId = null,
		sourceAdmissionOrdinal = null,
		originKind = StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT,
		originIdentity = logicalFactId,
		effectChecksum = "checksum-$logicalFactId",
	)

	private companion object {
		const val LOGICAL_FACT_ID = "steps-fact-1"
		const val LOGICAL_TRACKING_ID = "session-1"
		const val WRITER_ID = "steps-session-facts"
	}
}
