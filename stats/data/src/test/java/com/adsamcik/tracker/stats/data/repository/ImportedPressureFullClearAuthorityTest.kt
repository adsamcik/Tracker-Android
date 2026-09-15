package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.preserveImportedPressureFullClearAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ImportedPressureFullClearAuthorityTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `repeated clear blocks old entry run and window reuse while allowing new origin`() = runTest {
		val old = request("old-entry", "old-run", "old-window", "job-old")
		importer(testScheduler).importEntry(old) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)

		simulateParentFullClear(EPOCH, EPOCH + 1L, 4_000L)

		importer(testScheduler).importEntry(
			old.copy(
				receipt = old.receipt.copy(jobId = "replay", entryKey = "replay"),
				expectedCollectedDataEpoch = EPOCH + 1L,
			),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_ENTRY,
		)
		val crossKind = request(
			entryId = old.entry.runs.single().windows.single().identity.value,
			runId = "cross-run",
			windowId = "cross-window",
			jobId = "cross-kind",
			deriveEntry = false,
			expectedEpoch = EPOCH + 1L,
		)
		importer(testScheduler).importEntry(crossKind) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		val reusedRun = request(
			entryId = "reused-run-entry",
			runId = "old-run",
			windowId = "reused-run-window",
			jobId = "reused-run",
			expectedEpoch = EPOCH + 1L,
		)
		importer(testScheduler).importEntry(reusedRun) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		val reparented = request(
			"reparent-entry",
			"reparent-run",
			"old-window",
			"reparent",
			expectedEpoch = EPOCH + 1L,
		)
		importer(testScheduler).importEntry(reparented) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		val fresh = request(
			"fresh-entry",
			"fresh-run",
			"fresh-window",
			"fresh",
			expectedEpoch = EPOCH + 1L,
		)
		importer(testScheduler).importEntry(fresh) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)

		simulateParentFullClear(EPOCH + 1L, EPOCH + 2L, 5_000L)
		database.importedPressureDao().identityFenceCount() shouldBe 6L
	}

	@Test
	fun `corrupt retained zone aborts full clear preservation without a partial fence`() = runTest {
		val imported = request("corrupt-entry", "corrupt-run", "corrupt-window", "corrupt")
		importer(testScheduler).importEntry(imported)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_window SET stored_zone_id = 'Not/A_Zone' " +
				"WHERE entry_identity = ?",
			arrayOf(imported.entry.identity.value),
		)

		shouldThrow<RuntimeException> {
			database.withTransaction {
				preserveImportedPressureFullClearAuthority(
					database.openHelper.writableDatabase,
					EPOCH,
					4_000L,
				)
			}
		}

		database.importedPressureDao().identityFenceCount() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe EPOCH
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.importRevision shouldBe 1L
	}

	@Test
	fun `full clear verifies portable window content before creating permanent fences`() = runTest {
		val imported = request("value-entry", "value-run", "value-window", "value-job")
		importer(testScheduler).importEntry(imported) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_window SET mean_hectopascals = 1000.25 WHERE entry_identity = ?",
			arrayOf(imported.entry.identity.value),
		)

		val failure = shouldThrow<ImportedPressureLineageFailure> {
			database.withTransaction {
				preserveImportedPressureFullClearAuthority(
					database.openHelper.writableDatabase, EPOCH, 4_000L,
				)
			}
		}

		failure.reason shouldBe ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE
		database.importedPressureDao().identityFenceCount() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe EPOCH
		database.importedPressureDao().allWindowsForAdmission(imported.entry.identity.value)
			.single().meanHectopascals shouldBe 1000.25
	}

	@Test
	fun `matching header and receipt digests cannot replace complete entry authentication`() = runTest {
		val imported = request("checksum-entry", "checksum-run", "checksum-window", "checksum-job")
		importer(testScheduler).importEntry(imported) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		val forged = "sha256:" + "c".repeat(64)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf(forged, imported.entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_receipt SET entry_content_checksum = ? WHERE entry_identity = ?",
			arrayOf(forged, imported.entry.identity.value),
		)

		val failure = shouldThrow<ImportedPressureLineageFailure> {
			database.withTransaction {
				preserveImportedPressureFullClearAuthority(
					database.openHelper.writableDatabase, EPOCH, 4_000L,
				)
			}
		}

		failure.reason shouldBe ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE
		database.importedPressureDao().identityFenceCount() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe EPOCH
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value)
			?.contentChecksum shouldBe forged
	}

	@Test
	fun `later corrupt lineage rolls back typed fences created for an earlier valid lineage`() = runTest {
		val first = request(
			"sha256:${"1".repeat(64)}",
			"rollback-first-run",
			"rollback-first-window",
			"rollback-first",
			deriveEntry = false,
		)
		val second = request(
			"sha256:${"f".repeat(64)}",
			"rollback-second-run",
			"rollback-second-window",
			"rollback-second",
			deriveEntry = false,
		)
		importer(testScheduler).importEntry(first)
		importer(testScheduler).importEntry(second)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_window SET stored_zone_id = 'Not/A_Zone' " +
				"WHERE entry_identity = ?",
			arrayOf(second.entry.identity.value),
		)

		shouldThrow<RuntimeException> {
			database.withTransaction {
				preserveImportedPressureFullClearAuthority(
					database.openHelper.writableDatabase,
					EPOCH,
					4_000L,
				)
			}
		}

		database.importedPressureDao().identityFenceCount() shouldBe 0L
		database.importedPressureDao().latestEntryRevision(first.entry.identity.value)
			?.importRevision shouldBe 1L
		database.importedPressureDao().latestEntryRevision(second.entry.identity.value)
			?.importRevision shouldBe 1L
	}

	private suspend fun simulateParentFullClear(
		oldEpoch: Long,
		newEpoch: Long,
		clearedAtMs: Long,
	) {
		database.withTransaction {
			preserveImportedPressureFullClearAuthority(
				database.openHelper.writableDatabase,
				oldEpoch,
				clearedAtMs,
			)
			database.sourceEvidenceStateDao().updateLifecycle(newEpoch, null, clearedAtMs) shouldBe 1
			database.importedPressureDao().deleteAllReceipts()
			database.importedPressureDao().deleteAllEntries()
			database.importedPressureDao().deleteAllRetainedIdentities()
			database.importedPressureDao().deleteAllRetentionReceipts()
			database.importedPressureDao().deleteSourceEraseWitnesses()
			database.importedPressureDao().deleteSourceErase()
		}
	}

	private fun importer(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
		RoomImportPortablePressure(
		database,
		UnconfinedTestDispatcher(scheduler),
	) {}

	private fun request(
		entryId: String,
		runId: String,
		windowId: String,
		jobId: String,
		deriveEntry: Boolean = true,
		expectedEpoch: Long = EPOCH,
	): ImportPortablePressureRequest {
		val window = PortablePressureWindowV1.create(
			identity(PortablePressureIdentityKind.WINDOW, windowId),
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
			identity(PortablePressureIdentityKind.PHYSICAL_RUN, runId),
			1_000L,
			2_000L,
			true,
			PortablePressureAvailability.RETAINED,
			PortablePressureCoverage.COMPLETE,
			false,
			listOf(window),
		)
		val entryIdentity = if (deriveEntry) {
			identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryId)
		} else {
			PortablePressureOpaqueIdentity(entryId)
		}
		return ImportPortablePressureRequest(
			PortablePressureEntryV1.create(entryIdentity, 1_000L, 2_000L, listOf(run)),
			PortablePressureImportReceipt(jobId, jobId, "backup.trackerpressure", 2_000L),
			expectedEpoch,
		)
	}

	private fun identity(kind: PortablePressureIdentityKind, value: String) =
		PortablePressureOpaqueIdentity.derive(kind, value)

	private companion object {
		const val EPOCH = 7L
	}
}
