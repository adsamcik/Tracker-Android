package com.adsamcik.tracker.tracker.source.summary

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationOutcome
import com.adsamcik.tracker.tracker.worker.materializeDailySummaryDayInTransaction
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production Room read/materialization seams; fixture writes do not expose an importer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsNumericRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomStepsNumericSummaryRepository

	@Before
	fun setUp() = runBlocking {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		repository = RoomStepsNumericSummaryRepository(database, Dispatchers.IO)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `retained positive needs no local policy consent lifecycle or provider lane`() = runBlocking {
		seed(run(27L))
		repository.read(request()) shouldBe ready(27L)
		database.trackingHistoryReadDao().serviceRuns(listOf(opaque('2').value)) shouldBe emptyList()
	}

	@Test
	fun `covered zero is complete while a missing retained fact is not zero`() = runBlocking {
		seed(run(0L))
		repository.read(request()) shouldBe ready(0L)
		mutate("DELETE FROM step_fact_revision")
		repository.read(request()) shouldBe sourceUnavailable()
		// Missing an authenticated fact is integrity failure, never a fresh complete zero.
	}

	@Test
	fun `authentic empty run remains partial not zero`() = runBlocking {
		seed(run(null))
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `baseline followed by covered delta qualifies without turning baseline into a provider gap`() = runBlocking {
		val original = run(11L)
		val baseline = PortableStepsFactV1.create(
			opaque('4'), 1L, START, START, 0L, PortableStepsFactCoverage.BASELINE, null,
		)
		seed(original.copy(facts = listOf(baseline) + original.facts))
		repository.read(request()) shouldBe ready(11L)
	}

	@Test
	fun `baseline only outside the presentation envelope stays partial`() = runBlocking {
		val baseline = PortableStepsFactV1.create(
			opaque('4'), 1L, START, START, 0L, PortableStepsFactCoverage.BASELINE, null,
		)
		seed(run(null, START + DAY_MS, END + DAY_MS).copy(facts = listOf(baseline)))
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `fact wall projection discovers a complete day outside presentation envelope`() = runBlocking {
		val original = run(19L)
		seed(original.copy(startTimeMs = START + DAY_MS, endTimeMs = END + DAY_MS))
		repository.read(request()) shouldBe ready(19L)
	}

	@Test
	fun `internal covered subset cannot certify the unobserved run boundary`() = runBlocking {
		val original = run(19L, START + 1_000L, END - 1_000L)
		seed(original.copy(startTimeMs = START, endTimeMs = END))
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `nonnumeric fact outside presentation cannot become not captured`() = runBlocking {
		val original = run(null).copy(facts = listOf(PortableStepsFactV1.create(
			opaque('3'), 1L, START, END, 0L, PortableStepsFactCoverage.RESET_GAP, null,
		)))
		seed(original.copy(startTimeMs = START + DAY_MS, endTimeMs = END + DAY_MS))
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `positive midnight straddle is not proportionally promoted to qualified daily count`() = runBlocking {
		val midnight = DAY_START + DAY_MS
		seed(run(10L, midnight - 1_000L, midnight + 1_000L))
		repository.read(request()) shouldBe partial()
		repository.read(StepsNumericSummaryRequest(DAY + 1L, DAY + 1L, "UTC")) shouldBe partial()
	}

	@Test
	fun `stored capture zone uncertainty cannot borrow a more convenient requested zone`() = runBlocking {
		// UTC 10:00 is midnight in Kiritimati. The request's UTC day itself is unambiguous.
		val start = DAY_START + 10L * HOUR_MS + 500L
		val original = run(8L, start, start + HOUR_MS)
		seed(original.copy(storedZoneId = "Pacific/Kiritimati", facts = listOf(PortableStepsFactV1.create(
			opaque('3'), 1L, start, start + HOUR_MS, 1_000L, PortableStepsFactCoverage.COVERED, 8L,
		))))
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `partial materialization preserves compatibility count and repairs independent metrics in stored zone`() = runBlocking {
		val original = run(0L)
		seed(original.copy(completeness = original.completeness.copy(stopComplete = false)))
		seedSummary(73)
		val result = materializeDailySummaryDayInTransaction(
			database, aggregator(), DAY, ZoneId.of("Pacific/Kiritimati"),
		)
		result shouldBe DailySummaryMaterializationOutcome.Ready
		val summary = requireNotNull(database.dailySummaryDao().getByDay(DAY))
		summary.totalSteps shouldBe 73
		summary.totalDistanceM shouldBe 0f
		summary.totalDurationMs shouldBe 0L
		summary.tripCount shouldBe 1
		summary.calendarZoneId shouldBe "UTC"
		repository.read(request()) shouldBe partial()
		val deletion = database.withTransaction {
			StepsDailySummaryRepairComposer(database).compose(listOf(DAY), Long.MIN_VALUE)
		}
		(deletion is StepsDayRepairPreflight.Unsupported) shouldBe true
	}

	@Test
	fun `partial materialization without compatibility row does not invent zero`() = runBlocking {
		val original = run(0L)
		seed(original.copy(completeness = original.completeness.copy(stopComplete = false)))

		materializeDailySummaryDayInTransaction(database, aggregator(), DAY, ZONE) shouldBe
			DailySummaryMaterializationOutcome.Unverifiable
		database.dailySummaryDao().getByDay(DAY) shouldBe null
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `qualified Long count does not depend on obsolete Int cache capacity`() = runBlocking {
		val count = Int.MAX_VALUE.toLong() + 42L
		seed(run(count))
		seedSummary(73)
		repository.read(request()) shouldBe ready(count)
		materializeDailySummaryDayInTransaction(database, aggregator(), DAY, ZONE) shouldBe
			DailySummaryMaterializationOutcome.Ready
		requireNotNull(database.dailySummaryDao().getByDay(DAY)).totalSteps shouldBe 73
		repository.read(request()) shouldBe ready(count)
	}

	@Test
	fun `qualified Long count without compatibility row remains queryable without fabricated cache`() = runBlocking {
		val count = Int.MAX_VALUE.toLong() + 42L
		seed(run(count))

		repository.read(request()) shouldBe ready(count)
		materializeDailySummaryDayInTransaction(database, aggregator(), DAY, ZONE) shouldBe
			DailySummaryMaterializationOutcome.Unverifiable
		database.dailySummaryDao().getByDay(DAY) shouldBe null
		repository.read(request()) shouldBe ready(count)
	}

	@Test
	fun `manifest-only invalidation retracts and restores qualified number without raw summary signal`() = runBlocking {
		val original = run(9L)
		seed(original)
		repository.observe(request()).test {
			awaitItem() shouldBe ready(9L)
			mutate("UPDATE imported_steps_manifest SET capture_consent_epoch = 99")
			awaitItem() shouldBe sourceUnavailable()
			mutate("UPDATE imported_steps_manifest SET capture_consent_epoch = 8")
			awaitItem() shouldBe ready(9L)
			database.dailySummaryDao().getByDay(DAY) shouldBe null
			cancelAndIgnoreRemainingEvents()
		}
	}

	@Test
	fun `swapped physical membership is rejected before imported owner partition`() = runBlocking {
		seed(run(9L))
		mutate("UPDATE session_segment SET service_run_id = 'wrong' WHERE id = 41")
		repository.read(request()) shouldBe sourceUnavailable()
	}

	@Test
	fun `retained boundary straddle stays partial even when remaining count is covered zero`() = runBlocking {
		seed(run(0L, DAY_START - 1_000L, START))
		database.sourceEvidenceStateDao().updateLifecycle(0L, DAY_START, DAY_START) shouldBe 1
		repository.read(request()) shouldBe sourceUnavailable()
		markFence(StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE)
		repository.read(request()) shouldBe partial()
	}

	@Test
	fun `original deletion fence invalidates retained numeric value without changing cached summary`() = runBlocking {
		seed(run(12L))
		seedSummary(73)
		repository.observe(request()).test {
			awaitItem() shouldBe ready(12L)
			markFence("SESSION_CAPTURE")
			awaitItem() shouldBe sourceUnavailable()
			requireNotNull(database.dailySummaryDao().getByDay(DAY)).totalSteps shouldBe 73
			cancelAndIgnoreRemainingEvents()
		}
	}

	@Test
	fun `33 independent entries compose across authentication batch boundary`() = runBlocking {
		for (index in 1..33) {
			val start = START + index * 10L
			val runId = numberedIdentity('b', index)
			val original = run(1L, start, start + 10L).copy(
				identity = runId,
				deletionScopeDigest = PortableStepsDeletionScopeDigest(runId.value.removePrefix("sha256:")),
				facts = listOf(PortableStepsFactV1.create(
					numberedIdentity('c', index), 1L, start, start + 10L, 0L, PortableStepsFactCoverage.COVERED, 1L,
				)),
			)
			seed(original, numberedIdentity('a', index), 1_000L + index)
		}
		repository.read(request()) shouldBe ready(33L)
	}

	private suspend fun markFence(purpose: String) = database.withTransaction {
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createForOriginalRunDigest(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS, purpose = purpose,
			scopeIdentityDigest = "2".repeat(64), fenceGeneration = 1L, collectedDataEpoch = 0L, deletedAtMs = END,
		))
	}

	private suspend fun seed(
		run: PortableStepsRunV1,
		entryId: PortableStepsOpaqueIdentity = opaque('1'),
		segmentId: Long = 41L,
	) = database.seedImportedNumericTestRun(run, entryId, segmentId, epoch = 0L)

	private suspend fun seedSummary(steps: Int) = database.dailySummaryDao().upsert(
		dateEpochDay = DAY, totalDistanceM = 999f, totalSteps = steps, totalDurationMs = 999L,
		tripCount = 99, activeTrackingMs = 999L, lastUpdatedMs = 1L, calendarZoneId = ZONE.id,
	)

	private fun aggregator() = DailySummaryAggregator(
		dailySummaryDao = database.dailySummaryDao(), sessionSegmentDao = database.sessionSegmentDao(), zoneId = ZONE,
	)
	private suspend fun mutate(sql: String) = database.withTransaction { database.openHelper.writableDatabase.execSQL(sql) }
	private fun request() = StepsNumericSummaryRequest(DAY, DAY, "UTC")
	private fun ready(count: Long) = StepsNumericSummary.Ready(listOf(StepsNumericDay(DAY, count)))
	private fun partial() = StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	private fun sourceUnavailable() = StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

	private fun run(count: Long?, start: Long = START, end: Long = END) = PortableStepsRunV1(
		identity = opaque('2'), deletionScopeDigest = PortableStepsDeletionScopeDigest("2".repeat(64)),
		startTimeMs = start, endTimeMs = end, storedZoneId = "UTC",
		manifests = listOf(PortableStepsManifestV1(1L, start, 7L, 8L)),
		completeness = PortableStepsCompletenessV1(
			PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE, true, true, false,
		),
		facts = if (count == null) { emptyList() } else { listOf(PortableStepsFactV1.create(
			opaque('3'), 1L, start, end, 0L, PortableStepsFactCoverage.COVERED, count,
		)) },
	)

	private fun opaque(id: Char) = PortableStepsOpaqueIdentity("sha256:${id.toString().repeat(64)}")
	private fun numberedIdentity(prefix: Char, index: Int) =
		PortableStepsOpaqueIdentity("sha256:$prefix${index.toString(16).padStart(63, '0')}")

	private companion object {
		val ZONE: ZoneId = ZoneId.of("UTC")
		val DAY: Long = LocalDate.of(2026, 4, 2).toEpochDay()
		val DAY_START: Long = LocalDate.ofEpochDay(DAY).atStartOfDay(ZONE).toInstant().toEpochMilli()
		const val HOUR_MS = 3_600_000L
		const val DAY_MS = 24L * HOUR_MS
		val START = DAY_START + HOUR_MS
		val END = START + HOUR_MS
	}
}

/** Shared real Room fixture for imported-only and mixed live/imported numeric composition tests. */
internal suspend fun AppDatabase.seedImportedNumericTestRun(
	run: PortableStepsRunV1,
	entryId: PortableStepsOpaqueIdentity,
	segmentId: Long,
	epoch: Long,
) = withTransaction {
	val portable = PortableStepsEntryV1.create(
		entryId, PortableStepsSessionMode.AUTOMATIC, run.startTimeMs, run.endTimeMs, listOf(run),
	)
	val entry = ImportedStepsAdmissionRows.entry(portable, epoch = epoch, ownerGeneration = 7L)
	importedStepsDao().insertEntry(entry)
	sessionSegmentDao().insert(SessionSegment(
		id = segmentId, startTimeMs = run.startTimeMs, endTimeMs = run.endTimeMs,
		distanceM = 0f, steps = null, primaryActivity = null, activityConfidence = null,
		sampleCount = 0, source = SegmentSource.PORTABLE_STEPS_IMPORT, inferenceVersion = null,
		createdAt = run.endTimeMs, logicalTrackingId = entry.identity, serviceRunId = run.identity.value,
	))
	importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(entry, run, segmentId))
	ImportedStepsAdmissionRows.manifests(run).forEach { importedStepsDao().insertManifest(it) }
	run.facts.forEach { stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(entry, run, it, run.endTimeMs)) }
}
