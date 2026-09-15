package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedPressureMaintenanceUnverifiableReason
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

	@Test
	fun `missing retained marker is unverifiable for replay selected deletion and no-change`() =
		runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		subject(testScheduler).truncate(request()) shouldBe
			TruncateImportedPressureRetentionResult.Truncated(1, 1, 1, 1)
		importer(testScheduler).importEntry(imported) shouldBe
			ImportPortablePressureResult.Blocked(
				com.adsamcik.tracker.stats.api.repository
					.PortablePressureImportBlockedReason.RETENTION_TRUNCATED,
			)
		val deleter = RoomDeleteImportedPressureEntry(
			database,
			UnconfinedTestDispatcher(testScheduler),
			{ RETAINED_AT_MS + 1L },
			{},
		)
		deleter.delete(
			DeleteImportedPressureEntryRequest(
				ImportedPressureHistoryIdentity(imported.entry.identity.value),
				1L,
				EPOCH,
			),
		) shouldBe DeleteImportedPressureEntryResult.Unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.RETENTION_BOUNDARY,
		)
		subject(testScheduler).truncate(request(expectedRevision = 2L)) shouldBe
			TruncateImportedPressureRetentionResult.NoChange
		val missing = database.importedPressureDao().retainedIdentitiesForEntries(
			listOf(imported.entry.identity.value),
			4,
		).last()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_pressure_retained_identity WHERE protected_identity = ?",
			arrayOf(missing.protectedIdentity),
		)

		importer(testScheduler).importEntry(imported) shouldBe
			ImportPortablePressureResult.Unverifiable(
				com.adsamcik.tracker.stats.api.repository
					.PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		deleter.delete(
			DeleteImportedPressureEntryRequest(
				ImportedPressureHistoryIdentity(imported.entry.identity.value),
				1L,
				EPOCH,
			),
		) shouldBe DeleteImportedPressureEntryResult.Unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		subject(testScheduler).truncate(request(expectedRevision = 2L)) shouldBe
			TruncateImportedPressureRetentionResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		val unrelated = importRequest(
			entry("unrelated", "unrelated-run", "unrelated-window", uncertaintyMs = 0L),
			2,
		)
		importer(testScheduler).importEntry(unrelated) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}

	@Test
	fun `self-checksummed retention receipt with false deletion set is unverifiable`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		subject(testScheduler).truncate(request())
		val dao = database.importedPressureDao()
		val original = requireNotNull(dao.retentionReceipt(imported.entry.identity.value))
		val markers = dao.retainedIdentitiesForEntries(
		listOf(imported.entry.identity.value),
		original.protectedIdentityCount + 1,
		)
		val fences = dao.identityFencesForEntry(
		imported.entry.identity.value,
		original.identityFenceCountForTest() + 1,
		)
		val fakeDeletion = ImportedPressureDeletionGenerationEntity.create(
		imported.entry.runs.single().identity.value,
		EPOCH,
		1L,
		RETAINED_AT_MS,
		)
		val forged = com.adsamcik.tracker.shared.base.database.data
		.ImportedPressureRetentionReceiptEntity.create(
			entryIdentity = original.entryIdentity,
			collectedDataEpoch = original.collectedDataEpoch,
			sourceEvidenceRevision = original.sourceEvidenceRevision,
			retainedFromMs = original.retainedFromMs,
			retainedAtMs = original.retainedAtMs,
			latestImportRevision = original.latestImportRevision,
			latestContentChecksum = original.latestContentChecksum,
			startTimeMs = original.startTimeMs,
			endTimeMs = original.endTimeMs,
			receivedAtMs = original.receivedAtMs,
			recencyStartTimeMs = original.recencyStartTimeMs,
			recencyEndTimeMs = original.recencyEndTimeMs,
			recencyTieIdentity = original.recencyTieIdentity,
			revisionCount = original.revisionCount,
			importReceiptCount = original.importReceiptCount,
			runRowCount = original.runRowCount,
			windowRowCount = original.windowRowCount,
			runDeletions = listOf(fakeDeletion),
			markers = markers,
			identityFences = fences,
			lineageAuthorityChecksum = original.lineageAuthorityChecksum,
		)
		database.openHelper.writableDatabase.execSQL(
		"UPDATE imported_pressure_retention_receipt SET run_deletion_count = ?, " +
			"run_deletion_set_checksum = ?, effect_checksum = ? WHERE entry_identity = ?",
		arrayOf(
			forged.runDeletionCount,
			forged.runDeletionSetChecksum,
			forged.effectChecksum,
			forged.entryIdentity,
		),
		)

		importer(testScheduler).importEntry(imported) shouldBe
		ImportPortablePressureResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		subject(testScheduler).truncate(request(expectedRevision = 2L)) shouldBe
		TruncateImportedPressureRetentionResult.Unverifiable(
			ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `older same-owner typed fence is accepted without timestamp replacement`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		subject(testScheduler).truncate(request())
		val dao = database.importedPressureDao()
		val retained = dao.identityFencesForEntry(imported.entry.identity.value, 4)
		val entryFence = retained.single {
		it.identityKind == com.adsamcik.tracker.shared.base.database.data
			.ImportedPressureIdentityFenceEntity.ENTRY
		}
		val laterEquivalent =
		com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity.create(
			entryFence.protectedIdentity,
			entryFence.identityKind,
			entryFence.entryIdentity,
			entryFence.runIdentity,
			entryFence.originalCollectedDataEpoch,
			entryFence.fencedAtMs + 100L,
			com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
				.REASON_FULL_CLEAR,
		)

		dao.insertOrAuthenticateIdentityFences(listOf(laterEquivalent))

		dao.identityFencesForEntry(imported.entry.identity.value, 4).single {
		it.protectedIdentity == entryFence.protectedIdentity
		} shouldBe entryFence
	}

	@Test
	fun `oversized live candidate text is rejected before candidate materialization`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = " +
				"CAST(zeroblob(?) AS TEXT) WHERE identity = ?",
			arrayOf(oversized, imported.entry.identity.value),
		)

		subject(testScheduler).truncate(request()) shouldBe
			TruncateImportedPressureRetentionResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		database.importedPressureDao().identityFenceCount() shouldBe 0L
		database.importedPressureDao().retentionReceiptCount() shouldBe 0L
		database.importedPressureDao().entryRevisionsForAdmission(imported.entry.identity.value)
			.size shouldBe 1
	}

	@Test
	fun `oversized retained fence dependency is rejected before receipt materialization`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		subject(testScheduler).truncate(request())
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_identity_fence SET effect_checksum = " +
				"CAST(zeroblob(?) AS TEXT) " +
				"WHERE entry_identity = ? AND identity_kind = 'WINDOW'",
			arrayOf(oversized, imported.entry.identity.value),
		)

		subject(testScheduler).truncate(request(expectedRevision = 2L)) shouldBe
			TruncateImportedPressureRetentionResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		database.importedPressureDao().retentionReceiptCount() shouldBe 1L
		database.importedPressureDao().retainedIdentityCount() shouldBe 3L
		database.importedPressureDao().sourceErase() shouldBe null
	}

	@Test
	fun `source erase uses numeric owner preflight before oversized live candidate text`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = " +
				"CAST(zeroblob(?) AS TEXT) WHERE identity = ?",
			arrayOf(oversized, imported.entry.identity.value),
		)

		RoomErasePressureSource(
			database,
			object : com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier {
				override suspend fun establish(
					expectedCollectedDataEpoch: Long,
				) = com.adsamcik.tracker.stats.api.repository
					.PressureSourceEraseBarrierResult.NoLocalProvider(
						com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken(
							expectedCollectedDataEpoch,
							null,
							1L,
						),
					)

				override suspend fun verifySettled(
					token: com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken,
				) = com.adsamcik.tracker.stats.api.repository
					.PressureSourceEraseBarrierVerification.Verified
			},
			UnconfinedTestDispatcher(testScheduler),
			{},
		).erase(
			com.adsamcik.tracker.stats.api.repository.ErasePressureSourceRequest(
				EPOCH,
				0L,
				0L,
				1L,
				1L,
				RETAINED_AT_MS,
			),
		) shouldBe com.adsamcik.tracker.stats.api.repository.ErasePressureSourceResult.Unverifiable(
			ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedPressureDao().sourceErase() shouldBe null
		database.importedPressureDao().identityFenceCount() shouldBe 0L
	}

	@Test
	fun `selected deletion preflights oversized permanent authority for known identity`() = runTest {
		val imported = importRequest(entry(), 1)
		importer(testScheduler).importEntry(imported)
		publishFloor()
		subject(testScheduler).truncate(request())
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_identity_fence SET effect_checksum = CAST(zeroblob(?) AS TEXT) " +
				"WHERE entry_identity = ? AND identity_kind = 'WINDOW'",
			arrayOf(oversized, imported.entry.identity.value),
		)

		RoomDeleteImportedPressureEntry(
			database,
			UnconfinedTestDispatcher(testScheduler),
			{ RETAINED_AT_MS + 1L },
			{},
		).delete(
			DeleteImportedPressureEntryRequest(
				ImportedPressureHistoryIdentity(imported.entry.identity.value),
				1L,
				EPOCH,
			),
		) shouldBe DeleteImportedPressureEntryResult.Unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedPressureDao().retentionReceiptCount() shouldBe 1L
		database.importedPressureDao().retainedIdentityCount() shouldBe 3L
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

	private fun request(
		expectedEpoch: Long = EPOCH,
		expectedRevision: Long = FLOOR_REVISION,
	) =
		TruncateImportedPressureRetentionRequest(
			expectedCollectedDataEpoch = expectedEpoch,
			expectedSourceEvidenceRevision = expectedRevision,
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

private fun com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
	.identityFenceCountForTest(): Int = protectedIdentityCount
