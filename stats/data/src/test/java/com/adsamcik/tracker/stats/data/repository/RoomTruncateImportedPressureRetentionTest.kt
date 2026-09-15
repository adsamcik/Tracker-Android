package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
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
class RoomTruncateImportedPressureRetentionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `empty import store is an exact no change`() = runTest {
		publishFloor()

		subject(testScheduler).truncate(request()) shouldBe
			TruncateImportedPressureRetentionResult.NoChange
		database.sourceEvidenceStateDao().get()?.revision shouldBe FLOOR_REVISION
	}

	@Test
	fun `uncertainty crossing correction lineage compacts immediately and suppresses reexport`() =
		runTest {
			val importer = importer(testScheduler)
			val first = importRequest(entry("affected", "run-a", "window-a", uncertaintyMs = 1L), 1)
			val correction = importRequest(
				entry("affected", "run-b", "window-b", uncertaintyMs = 2L),
				2,
			)
			val exactBoundary = importRequest(
				entry("boundary", "run-c", "window-c", uncertaintyMs = 0L),
				3,
			)
			importer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
			importer.importEntry(correction) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
			importer.importEntry(exactBoundary) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
			publishFloor()

			subject(testScheduler).truncate(request()) shouldBe
				TruncateImportedPressureRetentionResult.Truncated(1, 2, 2, 2)

			val dao = database.importedPressureDao()
			dao.entryRevisionsForAdmission(first.entry.identity.value) shouldBe emptyList()
			dao.retentionReceipt(first.entry.identity.value)?.revisionCount shouldBe 2
			dao.retainedIdentitiesForEntries(listOf(first.entry.identity.value), 6).size shouldBe 5
			dao.latestEntryRevision(exactBoundary.entry.identity.value)?.importRevision shouldBe 1L
			val evaluation = database.withTransaction {
				ImportedPressureHistoryEvaluator(database).selectRecentInTransaction(10)
			}.first { it.candidate.identity == first.entry.identity.value }
			evaluation::class shouldBe ImportedPressureHistoryEvaluation.Retained::class
			evaluation.toPublicPressureOnlyEntry().pressure.let {
				it.evidence shouldBe HistoryEvidence.NONE
				it.productState shouldBe HistoryProductState.PARTIAL
				it.windows shouldBe emptyList()
			}
			exporter(testScheduler).export(
				ExportPortablePressureRequest(0L, 4_000L),
				PortablePressureEntrySink {
					require(it.identity == exactBoundary.entry.identity)
				},
			) shouldBe ExportPortablePressureResult.Exported(1)
		}

	@Test
	fun `cancellation after retention authority insertion rolls back marker and hierarchy`() =
		runTest {
			val imported = importRequest(entry(), 1)
			importer(testScheduler).importEntry(imported)
			publishFloor()
			val subject = RoomTruncateImportedPressureRetention(
				database,
				UnconfinedTestDispatcher(testScheduler),
				{ checkpoint ->
					if (checkpoint == ImportedPressureRetentionCheckpoint.RETENTION_AUTHORITY_INSERTED) {
						throw CancellationException("cancel retention")
					}
				},
			)

			shouldThrow<CancellationException> { subject.truncate(request()) }

			database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
				?.importRevision shouldBe 1L
			database.importedPressureDao().retentionReceipt(imported.entry.identity.value) shouldBe null
			database.sourceEvidenceStateDao().get()?.revision shouldBe FLOOR_REVISION
		}

	@Test
	fun `storage failure rolls back and stale epoch is typed without mutation`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		val failing = RoomTruncateImportedPressureRetention(
			database,
			UnconfinedTestDispatcher(testScheduler),
			{ checkpoint ->
				if (checkpoint == ImportedPressureRetentionCheckpoint.PAYLOAD_REMOVED) {
					throw SQLiteException("disk unavailable")
				}
			},
		)

		failing.truncate(request()) shouldBe TruncateImportedPressureRetentionResult.RetryableFailure(
			com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
				.STORAGE_UNAVAILABLE,
		)
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.importRevision shouldBe 1L

		subject(testScheduler).truncate(
			request(expectedEpoch = EPOCH + 1L),
		) shouldBe TruncateImportedPressureRetentionResult.Blocked(
			com.adsamcik.tracker.stats.api.repository.ImportedPressureRetentionBlockedReason
				.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
		)
	}

	private suspend fun publishFloor() {
		database.sourceEvidenceStateDao().updateLifecycle(
			EPOCH,
			RETENTION_FLOOR_MS,
			RETAINED_AT_MS,
		) shouldBe 1
	}

	private fun subject(scheduler: TestCoroutineScheduler) =
		RoomTruncateImportedPressureRetention(
			database,
			UnconfinedTestDispatcher(scheduler),
			{},
		)

	private fun importer(scheduler: TestCoroutineScheduler) =
		RoomImportPortablePressure(database, UnconfinedTestDispatcher(scheduler)) {}

	private fun exporter(scheduler: TestCoroutineScheduler): RoomExportPortablePressure {
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

	private fun request(expectedEpoch: Long = EPOCH) =
		TruncateImportedPressureRetentionRequest(
			expectedCollectedDataEpoch = expectedEpoch,
			expectedSourceEvidenceRevision = FLOOR_REVISION,
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = RETAINED_AT_MS,
		)

	private fun importRequest(
		entry: PortablePressureEntryV1,
		index: Int,
	) = ImportPortablePressureRequest(
		entry,
		PortablePressureImportReceipt(
			jobId = "job-$index",
			entryKey = "entry-$index",
			sourceName = "backup.trackerpressure",
			receivedAtMs = 100L + index,
		),
		EPOCH,
	)

	private fun entry(
		entryId: String = "entry",
		runId: String = "run",
		windowId: String = "window",
		uncertaintyMs: Long = 1L,
	): PortablePressureEntryV1 {
		val window = PortablePressureWindowV1.create(
			identity = identity(PortablePressureIdentityKind.WINDOW, windowId),
			intervalStartTimeMs = RETENTION_FLOOR_MS,
			intervalEndTimeMs = RETENTION_FLOOR_MS + 1_000L,
			wallTimeUncertaintyMs = uncertaintyMs,
			observedDurationNanos = 1_000_000_000L,
			sampleCount = 2,
			expectedSampleCount = 2,
			meanHectopascals = 1_000.5,
			sumSquaredDeviations = 0.5,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_001f,
			firstHectopascals = 1_000f,
			latestHectopascals = 1_001f,
			slopeHectopascalsPerSecond = 1.0,
			rSquared = 1.0,
			sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
			effectiveSamplePeriodMicros = 1_000_000,
			effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 2_000_000_000L,
			maximumInterSampleGapNanos = 1_000_000_000L,
			closure = PortablePressureWindowClosure.TARGET_ELAPSED,
			qualification = PortablePressureWindowQualification.COMPLETE,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
			zoneId = "Europe/Prague",
		)
		val run = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, runId),
			startTimeMs = window.intervalStartTimeMs,
			endTimeMs = window.intervalEndTimeMs,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.RETAINED,
			coverage = PortablePressureCoverage.COMPLETE,
			retentionLoss = false,
			windows = listOf(window),
		)
		return PortablePressureEntryV1.create(
			identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryId),
			run.startTimeMs,
			run.endTimeMs,
			listOf(run),
		)
	}

	private fun identity(kind: PortablePressureIdentityKind, value: String) =
		PortablePressureOpaqueIdentity.derive(kind, value)

	private fun newDatabase() = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private companion object {
		const val EPOCH = 7L
		const val FLOOR_REVISION = 1L
		const val RETENTION_FLOOR_MS = 1_500L
		const val RETAINED_AT_MS = 3_000L
	}
}
