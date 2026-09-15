package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.stats.api.repository.ErasePressureSourceRequest
import com.adsamcik.tracker.stats.api.repository.ErasePressureSourceResult
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
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
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseRetryableReason
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
class RoomErasePressureSourceTest {
	private lateinit var database: AppDatabase
	private lateinit var barrier: RecordingBarrier

	@Before
	fun setUp() = runTest {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		barrier = RecordingBarrier()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `empty source erase installs one idempotent no-resurrection fence`() = runTest {
		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Erased(
			localFactRevisionCount = 0,
			localWalEventCount = 0,
			importedEntryCount = 0,
			importedRevisionCount = 0,
			importedRunCount = 0,
			importedWindowCount = 0,
			fencedLocalRunCount = 0,
		)
		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.AlreadyErased
		barrier.calls shouldBe 0
	}

	@Test
	fun `import-only erase needs no provider authority and permanently fences replay`() = runTest {
		val imported = importRequest()
		val unrelatedWal = unrelatedWal()
		importer(testScheduler).importEntry(imported) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		database.sourceEventWalDao().insertIgnoringDuplicate(unrelatedWal)

		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Erased(
			localFactRevisionCount = 0,
			localWalEventCount = 0,
			importedEntryCount = 1,
			importedRevisionCount = 1,
			importedRunCount = 1,
			importedWindowCount = 1,
			fencedLocalRunCount = 0,
		)

		barrier.calls shouldBe 0
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value) shouldBe null
		database.sourceEventWalDao().getByEventId(unrelatedWal.eventId)?.sourceKind shouldBe
			SourceDestinationOwnerEntity.SOURCE_CELL
		database.importedPressureDao().entryDeletion(imported.entry.identity.value) shouldBe
			com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity.create(
				imported.entry.identity.value,
				EPOCH,
				1L,
				ERASED_AT_MS,
			)
		importer(testScheduler).importEntry(
			imported.copy(
				receipt = imported.receipt.copy(jobId = "late-job", entryKey = "late-entry"),
			),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.SOURCE_ERASED,
		)
		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.AlreadyErased
	}

	@Test
	fun `cancellation after source fence insertion rolls back imports and fence`() = runTest {
		val imported = importRequest()
		importer(testScheduler).importEntry(imported)
		val subject = RoomErasePressureSource(
			database,
			barrier,
			UnconfinedTestDispatcher(testScheduler),
			{ checkpoint ->
				if (checkpoint == PressureSourceEraseCheckpoint.SOURCE_FENCE_INSERTED) {
					throw CancellationException("cancel erase")
				}
			},
		)

		shouldThrow<CancellationException> { subject.erase(request()) }

		database.importedPressureDao().sourceErase() shouldBe null
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.importRevision shouldBe 1L
	}

	@Test
	fun `stale collected data epoch blocks before source fence or provider barrier`() = runTest {
		val imported = importRequest()
		importer(testScheduler).importEntry(imported)

		subject(testScheduler).erase(request(expectedEpoch = EPOCH + 1L)) shouldBe
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			)

		barrier.calls shouldBe 0
		database.importedPressureDao().sourceErase() shouldBe null
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.importRevision shouldBe 1L
	}

	@Test
	fun `storage failure after imported cascade rolls back source fence and hierarchy`() = runTest {
		val imported = importRequest()
		importer(testScheduler).importEntry(imported)
		val subject = RoomErasePressureSource(
			database,
			barrier,
			UnconfinedTestDispatcher(testScheduler),
			{ checkpoint ->
				if (checkpoint == PressureSourceEraseCheckpoint.IMPORTED_PAYLOAD_REMOVED) {
					throw SQLiteException("storage unavailable")
				}
			},
		)

		subject.erase(request()) shouldBe ErasePressureSourceResult.RetryableFailure(
			PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE,
		)
		database.importedPressureDao().sourceErase() shouldBe null
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.importRevision shouldBe 1L
		database.importedPressureDao().entryDeletion(imported.entry.identity.value) shouldBe null
	}

	@Test
	fun `source-wide fence survives close and reopen`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		context.deleteDatabase(REOPEN_DATABASE)
		var reopened: AppDatabase? = null
		try {
			var fileDatabase = Room.databaseBuilder(
				context,
				AppDatabase::class.java,
				REOPEN_DATABASE,
			).allowMainThreadQueries().build()
			fileDatabase.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			RoomImportPortablePressure(
				fileDatabase,
				UnconfinedTestDispatcher(testScheduler),
			) {}.importEntry(importRequest())
			RoomErasePressureSource(
				fileDatabase,
				RecordingBarrier(),
				UnconfinedTestDispatcher(testScheduler),
				{},
			).erase(request()) shouldBe ErasePressureSourceResult.Erased(
				localFactRevisionCount = 0,
				localWalEventCount = 0,
				importedEntryCount = 1,
				importedRevisionCount = 1,
				importedRunCount = 1,
				importedWindowCount = 1,
				fencedLocalRunCount = 0,
			)
			fileDatabase.close()

			fileDatabase = Room.databaseBuilder(
				context,
				AppDatabase::class.java,
				REOPEN_DATABASE,
			).allowMainThreadQueries().build()
			reopened = fileDatabase
			fileDatabase.importedPressureDao().sourceErase()?.collectedDataEpoch shouldBe EPOCH
			fileDatabase.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		} finally {
			reopened?.close()
			context.deleteDatabase(REOPEN_DATABASE)
		}
	}

	private fun subject(scheduler: TestCoroutineScheduler) = RoomErasePressureSource(
		database,
		barrier,
		UnconfinedTestDispatcher(scheduler),
		{},
	)

	private fun importer(scheduler: TestCoroutineScheduler) =
		RoomImportPortablePressure(database, UnconfinedTestDispatcher(scheduler)) {}

	private fun request(
		expectedEpoch: Long = EPOCH,
		expectedRevision: Long = 0L,
	) = ErasePressureSourceRequest(
		expectedCollectedDataEpoch = expectedEpoch,
		expectedSourceEvidenceRevision = expectedRevision,
		expectedDeletedSourceEventHighWaterOrdinal = 0L,
		expectedCurrentPolicyRevision = POLICY_REVISION,
		expectedRevokedConsentEpoch = CONSENT_EPOCH,
		erasedAtMs = ERASED_AT_MS,
	)

	private fun importRequest(): ImportPortablePressureRequest {
		val window = PortablePressureWindowV1.create(
			identity(PortablePressureIdentityKind.WINDOW, "window"),
			1_000L,
			2_000L,
			0L,
			1_000_000_000L,
			2,
			2,
			1_000.5,
			0.5,
			1_000f,
			1_001f,
			1_000f,
			1_001f,
			1.0,
			1.0,
			PortablePressureSensorAccuracy.HIGH,
			1_000_000,
			0,
			2_000_000_000L,
			1_000_000_000L,
			PortablePressureWindowClosure.TARGET_ELAPSED,
			PortablePressureWindowQualification.COMPLETE,
			0L,
			1f,
			"UTC",
		)
		val run = PortablePressureRunV1(
			identity(PortablePressureIdentityKind.PHYSICAL_RUN, "run"),
			1_000L,
			2_000L,
			true,
			PortablePressureAvailability.RETAINED,
			PortablePressureCoverage.COMPLETE,
			false,
			listOf(window),
		)
		return ImportPortablePressureRequest(
			PortablePressureEntryV1.create(
				identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "entry"),
				1_000L,
				2_000L,
				listOf(run),
			),
			PortablePressureImportReceipt("job", "entry", "backup.trackerpressure", 2_000L),
			EPOCH,
		)
	}

	private fun identity(kind: PortablePressureIdentityKind, value: String) =
		PortablePressureOpaqueIdentity.derive(kind, value)

	private fun unrelatedWal(): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = "unrelated-cell-wal",
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			sourceInstanceId = "cell-instance",
			registrationGeneration = 1L,
			sourceSequence = 1L,
			configRevision = null,
			planAttribution = 0,
			clockDomainId = "boot-unrelated",
			observedElapsedNanos = 100L,
			receivedElapsedNanos = 100L,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = EPOCH,
			acquiredAtMs = 100L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = byteArrayOf(1, 2, 3),
			payloadChecksum = "pending",
			createdAtMs = 100L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private class RecordingBarrier : PressureSourceEraseBarrier {
		var calls = 0
		override suspend fun establish(
			expectedCollectedDataEpoch: Long,
		): PressureSourceEraseBarrierResult {
			calls++
			return PressureSourceEraseBarrierResult.NoLocalProvider
		}
	}

	private companion object {
		const val EPOCH = 7L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 5L
		const val ERASED_AT_MS = 3_000L
		const val REOPEN_DATABASE = "pressure-source-erase-reopen"
	}
}
