package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class ImportedPressureHistoryEvaluatorTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported only entry is discoverable without a live run or fact`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)

		val public = pageReader(database).selectRecent(10).single()

		public.origin shouldBe PressureHistoryOrigin.Imported(
			com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity(
				request.entry.identity.value,
			),
		)
		public.state shouldBe PressureHistoryPresentationState.READY
		public.pressure.summary?.latestHectopascals shouldBe 1_003f
		public.pressure.zoneAuthorities shouldBe setOf("Europe/Prague")
		database.pressureFactRevisionDao().count() shouldBe 0L
		database.sourceSessionDao().serviceRun(request.entry.runs.single().identity.value) shouldBe null
	}

	@Test
	fun `latest authenticated correction is the only imported product revision`() = runTest {
		val first = request()
		val corrected = request(
			entry(wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-2", entryKey = "entry-2", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)

		val selected = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		selected.latest.header.importRevision shouldBe 2L
		selected.latest.entry shouldBe corrected.entry
	}

	@Test
	fun `retention-only imported evidence remains partial and never fabricates pressure`() = runTest {
		val retainedLoss = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "retained-loss"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val portable = PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "retained-entry"),
			startTimeMs = retainedLoss.startTimeMs,
			endTimeMs = retainedLoss.endTimeMs,
			runs = listOf(retainedLoss),
		)
		importer(database, testScheduler).importEntry(request(portable)) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 0)

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.PARTIAL
		public.pressure.availability shouldBe HistoryAvailability.AVAILABLE
		public.pressure.evidence shouldBe HistoryEvidence.NONE
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		public.pressure.productState shouldBe HistoryProductState.PARTIAL
	}

	@Test
	fun `local retained floor makes a crossing import partial and prevents full re-export`() = runTest {
		importer(database, testScheduler).importEntry(request())
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = EPOCH,
			retainedFromMs = 1_100L,
			updatedAtMs = 50L,
		) shouldBe 1

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.PARTIAL
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { error("Retention-limited import must not be re-exported whole") },
		) shouldBe ExportPortablePressureResult.NoEntries
	}

	@Test
	fun `exact local portable content suppresses the imported duplicate`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val imported = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		val importedPublic = imported.toPublicPressureOnlyEntry()
		val localPublic = importedPublic.copy(
			key = TrackingHistoryEntryKey("pressure:local-test"),
			origin = PressureHistoryOrigin.Local,
		)

		val composed = PressureHistoryPageComposer.compose(
			live = listOf(LocalPressurePageCandidate(localPublic, request.entry)),
			imported = listOf(imported),
			limit = 10,
		)

		composed shouldBe listOf(localPublic)
	}

	@Test
	fun `same portable identity with different proven content preserves both origins`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val imported = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		val importedPublic = imported.toPublicPressureOnlyEntry()
		val localPublic = importedPublic.copy(
			key = TrackingHistoryEntryKey("pressure:local-distinct"),
			origin = PressureHistoryOrigin.Local,
		)
		val differentContent = entry(wallTimeUncertaintyMs = 27L)

		val composed = PressureHistoryPageComposer.compose(
			live = listOf(LocalPressurePageCandidate(localPublic, differentContent)),
			imported = listOf(imported),
			limit = 10,
		)

		composed.size shouldBe 2
		composed.map { it.origin }.toSet() shouldBe setOf(
			PressureHistoryOrigin.Local,
			importedPublic.origin,
		)
	}

	@Test
	fun `retained run tombstone is deleted history and blocks old receipt replay`() = runTest {
		val request = request()
		val writer = importer(database, testScheduler)
		writer.importEntry(request) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		val runIdentity = request.entry.runs.single().identity.value
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = runIdentity,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.DELETED
		public.pressure.summary shouldBe null
		writer.importEntry(request) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_RUN,
		)
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { error("Tombstoned import must not be emitted") },
		) shouldBe ExportPortablePressureResult.NoEntries
	}

	@Test
	fun `superseded run tombstone makes corrected history nonnumeric and unexportable`() = runTest {
		val first = request(
			entry(runLocalId = "run-a", windowLocalId = "window-a"),
			receipt(jobId = "job-a", entryKey = "entry-a", receivedAtMs = 30L),
		)
		val corrected = request(
			entry(runLocalId = "run-b", windowLocalId = "window-b", wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-b", entryKey = "entry-b", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = first.entry.runs.single().identity.value,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)
		val public = selected.toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.UNVERIFIABLE
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		var emitted = false
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { emitted = true },
		) shouldBe ExportPortablePressureResult.Unverifiable(
			PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
		emitted shouldBe false
	}

	@Test
	fun `run identity reused by latest correction preserves typed deleted history`() = runTest {
		val first = request()
		val corrected = request(
			entry(wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-2", entryKey = "entry-2", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
		first.entry.runs.single().identity shouldBe corrected.entry.runs.single().identity
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = first.entry.runs.single().identity.value,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val selected = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		selected.deletedRunIdentities shouldBe setOf(first.entry.runs.single().identity.value)
		val public = selected.toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.DELETED
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
	}

	@Test
	fun `stored checksum corruption is a typed unverifiable history row`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val corrupt = identity(PortablePressureIdentityKind.WINDOW, "corrupt-checksum").value
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf(corrupt, request.entry.identity.value),
		)

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)
		selected.toPublicPressureOnlyEntry().state shouldBe
			PressureHistoryPresentationState.UNVERIFIABLE
	}

	@Test
	fun `stale imported epoch exposes no pressure value`() = runTest {
		importer(database, testScheduler).importEntry(request())
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = 70L,
		) shouldBe 1

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STALE_COLLECTED_DATA_EPOCH,
		)
		selected.toPublicPressureOnlyEntry().pressure.summary shouldBe null
	}

	@Test
	fun `revision overflow is a typed dependency overflow`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val sqlite = database.openHelper.writableDatabase
		for (revision in 2L..17L) {
			sqlite.execSQL(
				"""
				INSERT INTO imported_pressure_entry_revision (
				  identity, import_revision, supersedes_import_revision, content_checksum,
				  source_format, source_schema_version, start_time_ms, end_time_ms,
				  collected_data_epoch, import_job_id, import_entry_key, import_source_name,
				  received_at_ms
				)
				SELECT identity, ?, ?, content_checksum, source_format, source_schema_version,
				       start_time_ms, end_time_ms, collected_data_epoch, ?, ?, import_source_name,
				       received_at_ms + ?
				FROM imported_pressure_entry_revision
				WHERE identity = ? AND import_revision = 1
				""".trimIndent(),
				arrayOf(
					revision,
					revision - 1L,
					"overflow-job-$revision",
					"overflow-entry-$revision",
					revision,
					request.entry.identity.value,
				),
			)
		}

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW,
		)
	}

	@Test
	fun `import history export and second database import preserve portable v1 content`() = runTest {
		val original = request()
		importer(database, testScheduler).importEntry(original)
		val emitted = mutableListOf<PortablePressureEntryV1>()

		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { emitted += it },
		) shouldBe ExportPortablePressureResult.Exported(1)
		emitted shouldBe listOf(original.entry)

		val target = newDatabase()
		try {
			target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			val targetRequest = request(
				emitted.single(),
				receipt(jobId = "target-job", entryKey = "target-entry", receivedAtMs = 80L),
			)
			importer(target, testScheduler).importEntry(targetRequest) shouldBe
				ImportPortablePressureResult.Applied(1L, 1, 1)
			val roundTrip = evaluate(target).single() as ImportedPressureHistoryEvaluation.Readable
			roundTrip.latest.entry shouldBe original.entry
		} finally {
			target.close()
		}
	}

	private suspend fun evaluate(
		database: AppDatabase,
	): List<ImportedPressureHistoryEvaluation> = database.withTransaction {
		ImportedPressureHistoryEvaluator(database).selectRecentInTransaction(10)
	}

	private fun importer(
		database: AppDatabase,
		scheduler: TestCoroutineScheduler,
	) = RoomImportPortablePressure(database, UnconfinedTestDispatcher(scheduler)) {}

	private fun exporter(
		database: AppDatabase,
		scheduler: TestCoroutineScheduler,
	): RoomExportPortablePressure {
		val evaluator = ImportedPressureHistoryEvaluator(database)
		val selector = PressureHistorySelector(
			database,
			SourceProductLaneExecutionAuthority { false },
		)
		return RoomExportPortablePressure(
			PortablePressureRoomReader(database, selector, evaluator),
			UnconfinedTestDispatcher(scheduler),
		)
	}

	private fun pageReader(database: AppDatabase): PressureHistoryPageReader {
		val evaluator = ImportedPressureHistoryEvaluator(database)
		val selector = PressureHistorySelector(
			database,
			SourceProductLaneExecutionAuthority { false },
		)
		return PressureHistoryPageReader(
			database = database,
			liveSelector = selector,
			importedEvaluator = evaluator,
			portableReader = PortablePressureRoomReader(database, selector, evaluator),
		)
	}

	private fun request(
		portableEntry: PortablePressureEntryV1 = entry(),
		receipt: PortablePressureImportReceipt = receipt(),
	) = ImportPortablePressureRequest(portableEntry, receipt, EPOCH)

	private fun receipt(
		jobId: String = "job-1",
		entryKey: String = "entry-1",
		receivedAtMs: Long = 30L,
	) = PortablePressureImportReceipt(
		jobId = jobId,
		entryKey = entryKey,
		sourceName = "backup.trackerpressure",
		receivedAtMs = receivedAtMs,
	)

	private fun entry(
		wallTimeUncertaintyMs: Long = 25L,
		runLocalId: String = "run",
		windowLocalId: String = "window",
	): PortablePressureEntryV1 {
		val run = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, runLocalId),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.RETAINED,
			coverage = PortablePressureCoverage.COMPLETE,
			retentionLoss = false,
			windows = listOf(window(wallTimeUncertaintyMs, windowLocalId)),
		)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "entry"),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	@Suppress("LongMethod")
	private fun window(
		wallTimeUncertaintyMs: Long,
		windowLocalId: String,
	) = PortablePressureWindowV1.create(
		identity = identity(PortablePressureIdentityKind.WINDOW, windowLocalId),
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		observedDurationNanos = 150_000_000L,
		sampleCount = 4,
		expectedSampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstHectopascals = 1_000f,
		latestHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 15.0,
		rSquared = 1.0,
		sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 200_000_000L,
		maximumInterSampleGapNanos = 50_000_000L,
		closure = PortablePressureWindowClosure.TARGET_ELAPSED,
		qualification = PortablePressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)

	private fun identity(kind: PortablePressureIdentityKind, local: String) =
		PortablePressureOpaqueIdentity.derive(kind, local)

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private companion object {
		const val EPOCH = 7L
	}
}
