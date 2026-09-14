package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
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
class RoomImportPortablePressureTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission persists only immutable portable origin hierarchy`() = runTest {
		val request = request()

		importer(testScheduler).importEntry(request) shouldBe ImportPortablePressureResult.Applied(
			importRevision = 1L,
			physicalRunCount = 1,
			windowCount = 1,
		)

		val dao = database.importedPressureDao()
		val stored = requireNotNull(dao.entryRevision(request.entry.identity.value, 1L))
		stored.contentChecksum shouldBe request.entry.contentChecksum.value
		stored.collectedDataEpoch shouldBe EPOCH
		dao.runs(request.entry.identity.value, 1L).single().scopeDeletionGeneration shouldBe 0L
		dao.windows(request.entry.identity.value, 1L, request.entry.runs.single().identity.value)
			.single().contentChecksum shouldBe request.entry.runs.single().windows.single().contentChecksum.value
		database.pressureFactRevisionDao().count() shouldBe 0L
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceSessionDao().session(request.entry.identity.value) shouldBe null
		database.sourceSessionDao().serviceRun(request.entry.runs.single().identity.value) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `exact receipt replay is idempotent but conflicting receipt is blocked`() = runTest {
		val request = request()
		val importer = importer(testScheduler)
		importer.importEntry(request)

		importer.importEntry(request) shouldBe ImportPortablePressureResult.Duplicate(1L)
		importer.importEntry(
			request.copy(receipt = request.receipt.copy(sourceName = "other.trackerpressure")),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.RECEIPT_CONFLICT,
		)
		database.importedPressureDao().entryRevision(request.entry.identity.value, 2L) shouldBe null
	}

	@Test
	fun `authenticated correction appends an exact successor without replacing revision one`() = runTest {
		val first = request()
		val corrected = request(
			portableEntry = entry(wallTimeUncertaintyMs = 26L),
			receiptMetadata = receipt(jobId = "job-2", receivedAtMs = 40L),
		)
		val importer = importer(testScheduler)
		importer.importEntry(first)

		importer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)

		val dao = database.importedPressureDao()
		dao.entryRevision(first.entry.identity.value, 1L)?.contentChecksum shouldBe
			first.entry.contentChecksum.value
		val revisionTwo = requireNotNull(dao.entryRevision(corrected.entry.identity.value, 2L))
		revisionTwo.supersedesImportRevision shouldBe 1L
		revisionTwo.contentChecksum shouldBe corrected.entry.contentChecksum.value
	}

	@Test
	fun `opaque run identity cannot be retargeted to another entry`() = runTest {
		val importer = importer(testScheduler)
		importer.importEntry(request())
		val retargeted = request(
			portableEntry = entry(entryLocalId = "entry-2", windowLocalId = "window-2"),
			receiptMetadata = receipt(jobId = "job-2", receivedAtMs = 40L),
		)

		importer.importEntry(retargeted) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		database.importedPressureDao().latestEntryRevision(retargeted.entry.identity.value) shouldBe null
	}

	@Test
	fun `retained run tombstone blocks even an otherwise exact duplicate`() = runTest {
		val request = request()
		val importer = importer(testScheduler)
		importer.importEntry(request)
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = request.entry.runs.single().identity.value,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 50L,
			),
		)

		importer.importEntry(request) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_RUN,
		)
	}

	@Test
	fun `missing or changed collected data epoch fails before mutation`() = runTest {
		val missingStateDatabase = newDatabase()
		try {
			importer(testScheduler, missingStateDatabase).importEntry(request()) shouldBe
				ImportPortablePressureResult.Unverifiable(
					PortablePressureImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
				)
			missingStateDatabase.importedPressureDao().latestEntryRevision(entry().identity.value) shouldBe null
		} finally {
			missingStateDatabase.close()
		}

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 50L) shouldBe 1
		importer(testScheduler).importEntry(request()) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
		database.importedPressureDao().latestEntryRevision(entry().identity.value) shouldBe null
	}

	@Test
	fun `post-construction hierarchy mutation is reauthenticated and rejected`() = runTest {
		val originalRun = run()
		val callerOwnedRuns = mutableListOf(originalRun)
		val entry = PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "entry"),
			startTimeMs = originalRun.startTimeMs,
			endTimeMs = originalRun.endTimeMs,
			runs = callerOwnedRuns,
		)
		callerOwnedRuns[0] = run(wallTimeUncertaintyMs = 26L)

		importer(testScheduler).importEntry(request(portableEntry = entry)) shouldBe
			ImportPortablePressureResult.Unverifiable(
				PortablePressureImportUnverifiableReason.ENTRY_INVALID,
			)
		database.importedPressureDao().latestEntryRevision(entry.identity.value) shouldBe null
	}

	@Test
	fun `mid-write failure and cancellation roll back the complete hierarchy`() = runTest {
		val request = request()
		val failing = importer(testScheduler) { checkpoint ->
			if (checkpoint == PressureImportWriteCheckpoint.WINDOW_INSERTED) error("injected")
		}
		failing.importEntry(request) shouldBe ImportPortablePressureResult.RetryableFailure(
			PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
		)
		database.importedPressureDao().latestEntryRevision(request.entry.identity.value) shouldBe null

		val cancelling = importer(testScheduler) { checkpoint ->
			if (checkpoint == PressureImportWriteCheckpoint.RUN_INSERTED) {
				throw CancellationException("cancelled")
			}
		}
		shouldThrow<CancellationException> { cancelling.importEntry(request) }
		database.importedPressureDao().latestEntryRevision(request.entry.identity.value) shouldBe null
	}

	private fun importer(
		scheduler: TestCoroutineScheduler,
		database: AppDatabase = this.database,
		checkpoint: suspend (PressureImportWriteCheckpoint) -> Unit = {},
	) = RoomImportPortablePressure(database, UnconfinedTestDispatcher(scheduler), checkpoint)

	private fun request(
		portableEntry: PortablePressureEntryV1 = entry(),
		receiptMetadata: PortablePressureImportReceipt = receipt(),
	) = ImportPortablePressureRequest(portableEntry, receiptMetadata, EPOCH)

	private fun receipt(
		jobId: String = "job-1",
		receivedAtMs: Long = 30L,
	) = PortablePressureImportReceipt(
		jobId = jobId,
		entryKey = "entry-1",
		sourceName = "backup.trackerpressure",
		receivedAtMs = receivedAtMs,
	)

	private fun entry(
		entryLocalId: String = "entry",
		runLocalId: String = "run",
		windowLocalId: String = "window",
		wallTimeUncertaintyMs: Long = 25L,
	): PortablePressureEntryV1 {
		val run = run(runLocalId, windowLocalId, wallTimeUncertaintyMs)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryLocalId),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun run(
		runLocalId: String = "run",
		windowLocalId: String = "window",
		wallTimeUncertaintyMs: Long = 25L,
	) = PortablePressureRunV1(
		identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, runLocalId),
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		capturedForWholeRun = true,
		availability = PortablePressureAvailability.RETAINED,
		coverage = PortablePressureCoverage.COMPLETE,
		retentionLoss = false,
		windows = listOf(window(windowLocalId, wallTimeUncertaintyMs)),
	)

	@Suppress("LongMethod")
	private fun window(
		windowLocalId: String,
		wallTimeUncertaintyMs: Long,
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
