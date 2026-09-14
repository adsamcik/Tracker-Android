package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryResult
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionStaleReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
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
@Suppress("LargeClass", "TooManyFunctions")
class RoomDeleteImportedPressureEntryTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `exact selected lineage is fenced removed and cannot resurrect under any receipt`() = runTest {
		val importer = importer(testScheduler)
		val first = request(entry(), receipt("job-1", "entry-1", 30L))
		val correction = request(
			entry(runLocalId = "replacement-run", windowLocalId = "replacement-window"),
			receipt("job-2", "entry-2", 40L),
		)
		val unrelated = request(
			entry("other-entry", "other-run", "other-window"),
			receipt("job-other", "entry-other", 45L),
		)
		importer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		importer.importEntry(correction) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
		importer.importEntry(unrelated) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)

		deleter(testScheduler).delete(deleteRequest(correction.entry, revision = 2L)) shouldBe
			DeleteImportedPressureEntryResult.Deleted

		val dao = database.importedPressureDao()
		dao.entryRevisionsForAdmission(correction.entry.identity.value) shouldBe emptyList()
		dao.receiptsForAdmission(correction.entry.identity.value) shouldBe emptyList()
		dao.allRunsForAdmission(correction.entry.identity.value) shouldBe emptyList()
		dao.allWindowsForAdmission(correction.entry.identity.value) shouldBe emptyList()
		val deletion = requireNotNull(dao.entryDeletion(correction.entry.identity.value))
		deletion shouldBe ImportedPressureEntryDeletionEntity.create(
			entryIdentity = correction.entry.identity.value,
			collectedDataEpoch = EPOCH,
			deletedImportRevision = 2L,
			deletedAtMs = DELETED_AT_MS,
		)
		(first.entry.runs + correction.entry.runs).map { run ->
			requireNotNull(dao.deletionGeneration(run.identity.value)).generation
		} shouldContainExactly listOf(1L, 1L)
		dao.latestEntryRevision(unrelated.entry.identity.value)?.importRevision shouldBe 1L
		database.pressureFactRevisionDao().count() shouldBe 0L
		database.sourceEventWalDao().countAll() shouldBe 0L

		importer.importEntry(first) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_ENTRY,
		)
		importer.importEntry(
			first.copy(receipt = receipt("job-alternate", "entry-alternate", 50L)),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_ENTRY,
		)
		deleter(testScheduler).delete(deleteRequest(correction.entry, revision = 2L)) shouldBe
			DeleteImportedPressureEntryResult.AlreadyDeleted
		deleter(testScheduler).delete(deleteRequest(correction.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.StaleSelection(
				ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED,
			)

		val history = database.withTransaction {
			ImportedPressureHistoryEvaluator(database).selectRecentInTransaction(10)
		}
		history.map { it.candidate.identity } shouldContainExactly
			listOf(unrelated.entry.identity.value)
	}

	@Test
	fun `stale revision and stale epoch leave the complete lineage untouched`() = runTest {
		val imported = request()
		importer(testScheduler).importEntry(imported)

		deleter(testScheduler).delete(deleteRequest(imported.entry, revision = 2L)) shouldBe
			DeleteImportedPressureEntryResult.StaleSelection(
				ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED,
			)
		val dao = database.importedPressureDao()
		dao.entryDeletion(imported.entry.identity.value) shouldBe null
		dao.latestEntryRevision(imported.entry.identity.value)?.importRevision shouldBe 1L

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 60L) shouldBe 1
		deleter(testScheduler).delete(deleteRequest(imported.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.StaleSelection(
				ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		dao.entryDeletion(imported.entry.identity.value) shouldBe null
		dao.latestEntryRevision(imported.entry.identity.value)?.importRevision shouldBe 1L
	}

	@Test
	fun `entry tombstone beside retained hierarchy fails deletion and product read closed`() = runTest {
		val imported = request()
		importer(testScheduler).importEntry(imported)
		database.importedPressureDao().insertEntryDeletion(
			ImportedPressureEntryDeletionEntity.create(
				imported.entry.identity.value,
				EPOCH,
				1L,
				DELETED_AT_MS,
			),
		)

		deleter(testScheduler).delete(deleteRequest(imported.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE,
			)
		val history = database.withTransaction {
			ImportedPressureHistoryEvaluator(database).selectRecentInTransaction(1).single()
		}
		history shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			history.candidate,
			ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)
		val public = history.toPublicPressureOnlyEntry()
		public.pressure.availability shouldBe HistoryAvailability.UNAVAILABLE
		public.pressure.windows shouldBe emptyList()
	}

	@Test
	fun `cross-owner run identity blocks before any selected or unrelated mutation`() = runTest {
		val selected = request()
		val unrelated = request(
			entry("other-entry", "other-run", "other-window"),
			receipt("job-other", "entry-other", 40L),
		)
		val importer = importer(testScheduler)
		importer.importEntry(selected) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		importer.importEntry(unrelated) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		val dao = database.importedPressureDao()
		val selectedRun = dao.allRunsForAdmission(selected.entry.identity.value).single()
		dao.insertRun(
			selectedRun.copy(
				entryIdentity = unrelated.entry.identity.value,
				entryImportRevision = 1L,
			),
		)

		deleter(testScheduler).delete(deleteRequest(selected.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)

		dao.latestEntryRevision(selected.entry.identity.value)?.importRevision shouldBe 1L
		dao.latestEntryRevision(unrelated.entry.identity.value)?.importRevision shouldBe 1L
		dao.existingRunIdentityOwners(listOf(selectedRun.identity), 3)
			.map { owner -> owner.entryIdentity }
			.toSet() shouldBe setOf(selected.entry.identity.value, unrelated.entry.identity.value)
		dao.entryDeletion(selected.entry.identity.value) shouldBe null
		dao.deletionGeneration(selectedRun.identity) shouldBe null
	}

	@Test
	fun `cross-kind global identity blocks before any privacy fence`() = runTest {
		val selected = request()
		importer(testScheduler).importEntry(selected) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		val dao = database.importedPressureDao()
		val selectedHeader = requireNotNull(dao.latestEntryRevision(selected.entry.identity.value))
		val selectedRun = dao.allRunsForAdmission(selected.entry.identity.value).single()
		val crossKindHeader = selectedHeader.copy(
			identity = selectedRun.identity,
			importJobId = "cross-kind-job",
			importEntryKey = "cross-kind-entry",
			receivedAtMs = selectedHeader.receivedAtMs + 1L,
		)
		dao.insertEntryRevision(crossKindHeader)

		deleter(testScheduler).delete(deleteRequest(selected.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)

		dao.latestEntryRevision(selected.entry.identity.value)?.importRevision shouldBe 1L
		dao.entryRevision(selectedRun.identity, 1L) shouldBe crossKindHeader
		dao.entryDeletion(selected.entry.identity.value) shouldBe null
		dao.deletionGeneration(selectedRun.identity) shouldBe null
	}

	@Test
	fun `global owner overflow is typed and leaves the selected lineage unfenced`() = runTest {
		val selected = request()
		importer(testScheduler).importEntry(selected) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		val dao = database.importedPressureDao()
		val selectedHeader = requireNotNull(dao.latestEntryRevision(selected.entry.identity.value))
		val selectedRun = dao.allRunsForAdmission(selected.entry.identity.value).single()
		repeat(3) { index ->
			val ownerIdentity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "owner-$index").value
			dao.insertEntryRevision(
				selectedHeader.copy(
					identity = ownerIdentity,
					importJobId = "owner-job-$index",
					importEntryKey = "owner-entry-$index",
					receivedAtMs = selectedHeader.receivedAtMs + index + 1L,
				),
			)
			dao.insertRun(selectedRun.copy(entryIdentity = ownerIdentity))
		}

		deleter(testScheduler).delete(deleteRequest(selected.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
			)

		dao.latestEntryRevision(selected.entry.identity.value)?.importRevision shouldBe 1L
		dao.entryDeletion(selected.entry.identity.value) shouldBe null
		dao.deletionGeneration(selectedRun.identity) shouldBe null
	}

	@Test
	fun `over-limit lineage is unverifiable without installing fences`() = runTest {
		val imported = request()
		importer(testScheduler).importEntry(imported)
		val dao = database.importedPressureDao()
		val firstHeader = requireNotNull(dao.entryRevision(imported.entry.identity.value, 1L))
		for (revision in 2L..(ImportedPressureDao.MAX_REVISIONS_PER_ENTRY + 1L)) {
			dao.insertEntryRevision(
				firstHeader.copy(
					importRevision = revision,
					supersedesImportRevision = revision - 1L,
					importJobId = "overflow-job-$revision",
					importEntryKey = "overflow-entry-$revision",
					receivedAtMs = firstHeader.receivedAtMs + revision,
				),
			)
		}

		deleter(testScheduler).delete(deleteRequest(imported.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		dao.entryDeletion(imported.entry.identity.value) shouldBe null
		dao.deletionGeneration(imported.entry.runs.single().identity.value) shouldBe null
		dao.entryRevisionsForAdmission(imported.entry.identity.value).size shouldBe
			ImportedPressureDao.MAX_REVISIONS_PER_ENTRY + 1
	}

	@Test
	fun `corrupt stored checksum fails closed and rolls back without a tombstone`() = runTest {
		val imported = request()
		importer(testScheduler).importEntry(imported)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf("sha256:${"f".repeat(64)}", imported.entry.identity.value),
		)

		deleter(testScheduler).delete(deleteRequest(imported.entry, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.Unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		val dao = database.importedPressureDao()
		dao.entryDeletion(imported.entry.identity.value) shouldBe null
		dao.deletionGeneration(imported.entry.runs.single().identity.value) shouldBe null
		dao.latestEntryRevision(imported.entry.identity.value) shouldBe requireNotNull(
			dao.entryRevision(imported.entry.identity.value, 1L),
		)
	}

	@Test
	fun `cancellation after both tombstone kinds rolls back every mutation`() = runTest {
		val imported = request()
		importer(testScheduler).importEntry(imported)
		val cancelling = deleter(testScheduler) { checkpoint ->
			if (checkpoint == ImportedPressureDeletionWriteCheckpoint.ENTRY_TOMBSTONE_INSERTED) {
				throw CancellationException("cancel deletion")
			}
		}

		shouldThrow<CancellationException> {
			cancelling.delete(deleteRequest(imported.entry, revision = 1L))
		}

		val dao = database.importedPressureDao()
		dao.entryDeletion(imported.entry.identity.value) shouldBe null
		dao.deletionGeneration(imported.entry.runs.single().identity.value) shouldBe null
		dao.latestEntryRevision(imported.entry.identity.value)?.importRevision shouldBe 1L
		dao.receipt(imported.receipt.jobId, imported.receipt.entryKey) shouldBe
			dao.receiptsForAdmission(imported.entry.identity.value).single()
	}

	@Test
	fun `unknown identity is typed not found without storage mutation`() = runTest {
		val missing = entry("missing-entry", "missing-run", "missing-window")

		deleter(testScheduler).delete(deleteRequest(missing, revision = 1L)) shouldBe
			DeleteImportedPressureEntryResult.NotFound
		database.importedPressureDao().entryDeletion(missing.identity.value) shouldBe null
	}

	private fun importer(scheduler: TestCoroutineScheduler) = RoomImportPortablePressure(
		database,
		UnconfinedTestDispatcher(scheduler),
		{},
	)

	private fun deleter(
		scheduler: TestCoroutineScheduler,
		checkpoint: suspend (ImportedPressureDeletionWriteCheckpoint) -> Unit = {},
	) = RoomDeleteImportedPressureEntry(
		database,
		UnconfinedTestDispatcher(scheduler),
		{ DELETED_AT_MS },
		checkpoint,
	)

	private fun request(
		portableEntry: PortablePressureEntryV1 = entry(),
		receiptMetadata: PortablePressureImportReceipt = receipt("job-1", "entry-1", 30L),
	) = ImportPortablePressureRequest(portableEntry, receiptMetadata, EPOCH)

	private fun deleteRequest(
		entry: PortablePressureEntryV1,
		revision: Long,
	) = DeleteImportedPressureEntryRequest(
		identity = ImportedPressureHistoryIdentity(entry.identity.value),
		expectedImportRevision = revision,
		expectedCollectedDataEpoch = EPOCH,
	)

	private fun receipt(
		jobId: String,
		entryKey: String,
		receivedAtMs: Long,
	) = PortablePressureImportReceipt(
		jobId = jobId,
		entryKey = entryKey,
		sourceName = "backup.trackerpressure",
		receivedAtMs = receivedAtMs,
	)

	private fun entry(
		entryLocalId: String = "entry",
		runLocalId: String = "run",
		windowLocalId: String = "window",
	): PortablePressureEntryV1 {
		val run = run(runLocalId, windowLocalId)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryLocalId),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun run(runLocalId: String, windowLocalId: String) = PortablePressureRunV1(
		identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, runLocalId),
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		capturedForWholeRun = true,
		availability = PortablePressureAvailability.RETAINED,
		coverage = PortablePressureCoverage.COMPLETE,
		retentionLoss = false,
		windows = listOf(window(windowLocalId)),
	)

	@Suppress("LongMethod")
	private fun window(windowLocalId: String) = PortablePressureWindowV1.create(
		identity = identity(PortablePressureIdentityKind.WINDOW, windowLocalId),
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		wallTimeUncertaintyMs = 25L,
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
		const val DELETED_AT_MS = 70L
	}
}
