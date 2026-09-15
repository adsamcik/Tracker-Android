package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwner
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerKind
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentResetRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentResetResult
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadata
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionResult
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomImportedAmbientStepsTransferTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		seedEvidence(database)
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) database.close()
	}

	@Test
	fun `two database import read and reexport roundtrip preserves DST gaps and covered zero`() = runTest {
		val archive = archive(
			completeDay(LocalDate.of(2026, 10, 25), count = 0L, zoneId = "Europe/Prague"),
			partialDay(LocalDate.of(2026, 10, 26), count = 12L, zoneId = "Europe/Prague"),
		)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 2)
		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
		val first = readReady(database, archive)
		first.products.flatMap { it.origins }.toSet() shouldBe
			setOf(QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT)
		first.products.first().total shouldBe AmbientStepsNumericValue.Exact(0L)
		first.products.last().total shouldBe AmbientStepsNumericValue.Partial(
			12L,
			setOf(
				AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL,
				AmbientStepsDayCause.AMBIENT_GAP,
			),
		)
		val exported = reexport(database, archive)
		exported shouldBe archive

		val secondDatabase = newDatabase()
		try {
			seedEvidence(secondDatabase)
			importer(secondDatabase).importArchive(
				request(exported, jobId = "job-2", archiveKey = "archive-2"),
			) shouldBe applied(exported, 2)
			readReady(secondDatabase, exported).archive shouldBe archive
			reexport(secondDatabase, exported) shouldBe archive
		} finally {
			secondDatabase.close()
		}
	}

	@Test
	fun `duplicate receipt alternate receipt and stale archive replay never append or revert`() = runTest {
		val initial = archive(completeDay(LocalDate.of(2026, 1, 1), 10L))
		val initialRequest = request(initial)
		importer(database).importArchive(initialRequest) shouldBe applied(initial, 1)
		importer(database).importArchive(initialRequest) shouldBe
			ImportPortableAmbientStepsResult.Duplicate(initial.identity, 1)
		val conflictingReceipt = archive(completeDay(LocalDate.of(2026, 1, 2), 3L))
		importer(database).importArchive(
			request(conflictingReceipt),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT,
		)
		importer(database).importArchive(
			request(initial, jobId = "alternate", archiveKey = "alternate"),
		) shouldBe ImportPortableAmbientStepsResult.Duplicate(initial.identity, 1)

		val correction = archive(completeDay(LocalDate.of(2026, 1, 1), 12L))
		importer(database).importArchive(
			request(correction, jobId = "correction", archiveKey = "correction"),
		) shouldBe applied(correction, 1)
		readReady(database, correction).products.single().total shouldBe
			AmbientStepsNumericValue.Exact(12L)

		importer(database).importArchive(
			request(initial, jobId = "late-copy", archiveKey = "late-copy"),
		) shouldBe ImportPortableAmbientStepsResult.Duplicate(initial.identity, 1)
		readReady(database, correction).archive shouldBe correction
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 2L
	}

	@Test
	fun `exact native portable fact identity blocks import before any payload mutation`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 2, 1), 10L))
		val fact = archive.days.single().facts.single()
		val subject = RoomImportPortableAmbientSteps(
			database = database,
			dao = database.importedAmbientStepsDao(),
			ioDispatcher = Dispatchers.Unconfined,
			localOriginSource = {
				listOf(
					AmbientStepsPortableLocalOwner(
						fact.identity.value,
						AmbientStepsPortableLocalOwnerKind.FACT,
						fact.contentChecksum.value,
					),
				)
			},
		)

		subject.importArchive(request(archive)) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP,
		)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	@Test
	fun `metadata mismatch and opaque collision fail closed without complete zero`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 2, 2), 0L))
		val badMetadata = metadata(archive).copy(factCount = 2)
		importer(database).importArchive(
			request(archive).copy(metadata = badMetadata),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.METADATA_MISMATCH,
		)

		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val otherDay = completeDay(LocalDate.of(2026, 2, 3), 4L)
		val collidingFact = PortableAmbientStepsFactV1.create(
			archive.days.single().facts.single().identity,
			otherDay.structuralDayStartTimeMs,
			otherDay.structuralDayEndTimeMs,
			4L,
		)
		val other = archive(
			PortableAmbientStepsDayV1.create(
				identity = otherDay.identity,
				structuralEpochDay = otherDay.structuralEpochDay,
				storedZoneId = otherDay.storedZoneId,
				structuralDayStartTimeMs = otherDay.structuralDayStartTimeMs,
				structuralDayEndTimeMs = otherDay.structuralDayEndTimeMs,
				retainedFromTimeMs = null,
				coverage = PortableAmbientStepsCoverage.COMPLETE,
				partialCauses = emptyList(),
				retainedStepCount = 4L,
				facts = listOf(collidingFact),
				gaps = emptyList(),
			),
		)
		importer(database).importArchive(
			request(other, jobId = "collision", archiveKey = "collision"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		val originalDay = archive.days.single()
		val parallelFact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "parallel-fact"),
			originalDay.structuralDayStartTimeMs,
			originalDay.structuralDayEndTimeMs,
			0L,
		)
		val parallelDay = PortableAmbientStepsDayV1.create(
			identity = identity(AmbientStepsPortableIdentityKind.DAY, "parallel-day"),
			structuralEpochDay = originalDay.structuralEpochDay,
			storedZoneId = originalDay.storedZoneId,
			structuralDayStartTimeMs = originalDay.structuralDayStartTimeMs,
			structuralDayEndTimeMs = originalDay.structuralDayEndTimeMs,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = 0L,
			facts = listOf(parallelFact),
			gaps = emptyList(),
		)
		val parallel = archive(parallelDay)
		importer(database).importArchive(
			request(parallel, jobId = "parallel", archiveKey = "parallel"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT,
		)
	}

	@Test
	fun `current epoch and retention floor are checked before duplicate shortcut`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 3, 1), 8L))
		val original = request(archive)
		importer(database).importArchive(original) shouldBe applied(archive, 1)
		val floor = archive.days.single().structuralDayEndTimeMs
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, floor, floor) shouldBe 1

		importer(database).importArchive(original) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.RETENTION_BOUNDARY,
		)

		val fresh = newDatabase()
		try {
			seedEvidence(fresh)
			importer(fresh).importArchive(original) shouldBe applied(archive, 1)
			fresh.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, floor) shouldBe 1
			importer(fresh).importArchive(original) shouldBe ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		} finally {
			fresh.close()
		}
	}

	@Test
	fun `retention compacts one lineage and permanently suppresses replay`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 4, 1), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val floor = archive.days.single().structuralDayEndTimeMs
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, floor, floor) shouldBe 1
		val result = RoomTruncateImportedAmbientStepsRetention(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).truncateNext(
			TruncateImportedAmbientStepsRetentionRequest(floor, EPOCH, floor + 1L),
		)
		result shouldBe TruncateImportedAmbientStepsRetentionResult.Retained(1)
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 0L
		database.importedAmbientStepsDao().fence(
			archive.days.single().identity.value,
		)?.fenceKind shouldBe
			com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION

		importer(database).importArchive(
			request(archive, jobId = "replay", archiveKey = "replay"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.RETAINED_DAY,
		)
		reexportResult(database, archive) shouldBe ExportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_RETAINED,
		)
	}

	@Test
	fun `portable retention boundary equal to the local floor remains readable and is not repruned`() = runTest {
		val day = retainedDay(LocalDate.of(2026, 4, 2), 6L)
		val archive = archive(day)
		val floor = requireNotNull(day.retainedFromTimeMs)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, floor, floor) shouldBe 1

		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		RoomTruncateImportedAmbientStepsRetention(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).truncateNext(
			TruncateImportedAmbientStepsRetentionRequest(floor, EPOCH, floor + 1L),
		) shouldBe TruncateImportedAmbientStepsRetentionResult.Complete
		readReady(database, archive).products.single().total shouldBe
			AmbientStepsNumericValue.Partial(
				6L,
				setOf(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL),
			)
	}

	@Test
	fun `selected deletion fences before cascade and replay stays deleted`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 5, 1), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val day = archive.days.single()
		val deleted = RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteDay(DeleteImportedAmbientStepsDayRequest(day.identity, EPOCH, day.structuralDayEndTimeMs))
		deleted shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)

		importer(database).importArchive(
			request(archive, jobId = "replay", archiveKey = "replay"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
		reexportResult(database, archive) shouldBe ExportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_DELETED,
		)
		val unrelated = archive(completeDay(LocalDate.of(2026, 5, 4), 2L))
		importer(database).importArchive(request(unrelated)) shouldBe
			ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT,
			)
	}

	@Test
	fun `missing protected identity makes every later admission fail closed`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 5, 2), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteDay(
			DeleteImportedAmbientStepsDayRequest(
				archive.days.single().identity,
				EPOCH,
				archive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_ambient_steps_protected_identity " +
				"WHERE protected_identity = ?",
			arrayOf(archive.days.single().facts.single().identity.value),
		)

		val unrelated = archive(completeDay(LocalDate.of(2026, 5, 3), 2L))
		importer(database).importArchive(
			request(unrelated, jobId = "unrelated", archiveKey = "unrelated"),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `consent reset primitive requires revoked authority and deletes one lineage`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 6, 1), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		seedRevokedConsent(database)
		val deleter = RoomDeleteImportedAmbientStepsAfterConsentReset(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		)
		val result = deleter.deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				REVOKED_CONSENT_EPOCH,
				archive.days.single().structuralDayEndTimeMs,
			),
		)
		result shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Deleted(1)
		deleter.deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				REVOKED_CONSENT_EPOCH,
				archive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Complete
		val later = archive(completeDay(LocalDate.of(2026, 6, 2), 3L))
		importer(database).importArchive(
			request(later, jobId = "after-revoke", archiveKey = "after-revoke"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.SOURCE_DELETED,
		)
		seedEligibleConsent(database)
		importer(database).importArchive(
			request(later, jobId = "after-reset", archiveKey = "after-reset"),
		) shouldBe applied(later, 1)
		importer(database).importArchive(
			request(archive, jobId = "old-replay", archiveKey = "old-replay"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
	}

	@Test
	fun `import and deletion cancellation roll back receipt fence and payload atomically`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 7, 1), 8L))
		val cancellingImporter = RoomImportPortableAmbientSteps(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			checkpoint = {
				if (it == ImportedAmbientStepsWriteCheckpoint.DAY_INSERTED) {
					throw CancellationException("cancel import")
				}
			},
		)
		assertFailsWith<CancellationException> {
			cancellingImporter.importArchive(request(archive))
		}
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 0L

		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val cancellingDeletion = RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			checkpoint = {
				if (it == ImportedAmbientStepsMaintenanceCheckpoint.PROTECTED_IDENTITIES_INSERTED) {
					throw CancellationException("cancel deletion")
				}
			},
		)
		assertFailsWith<CancellationException> {
			cancellingDeletion.deleteDay(
				DeleteImportedAmbientStepsDayRequest(
					archive.days.single().identity,
					EPOCH,
					archive.days.single().structuralDayEndTimeMs,
				),
			)
		}
		database.importedAmbientStepsDao().fence(archive.days.single().identity.value) shouldBe null
		readReady(database, archive).archive shouldBe archive
	}

	@Test
	fun `corrupt retained hierarchy and closed storage return typed failures`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 8, 1), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_revision SET retained_step_count = 9 " +
				"WHERE day_identity = ?",
			arrayOf(archive.days.single().identity.value),
		)
		ImportedAmbientStepsRoomReader(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).read(range(archive)) shouldBe ImportedAmbientStepsSnapshot.Unverifiable(
			ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
		)

		database.close()
		importer(database).importArchive(request(archive)) shouldBe
			ImportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
	}

	@Test
	fun `named database reopen retains authenticated imported lineage`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		context.deleteDatabase(REOPEN_DATABASE)
		val archive = archive(completeDay(LocalDate.of(2026, 9, 1), 8L))
		openNamed(context).let { first ->
			try {
				seedEvidence(first)
				importer(first).importArchive(request(archive)) shouldBe applied(archive, 1)
			} finally {
				first.close()
			}
		}

		openNamed(context).let { reopened ->
			try {
				readReady(reopened, archive).archive shouldBe archive
			} finally {
				reopened.close()
				context.deleteDatabase(REOPEN_DATABASE)
			}
		}
	}

	@Test
	fun `full collected data epoch clear removes source fences before same archive can return`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 2), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteDay(
			DeleteImportedAmbientStepsDayRequest(
				archive.days.single().identity,
				EPOCH,
				archive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)
		val dao = database.importedAmbientStepsDao()
		dao.deleteAllDays()
		dao.deleteAllReceipts()
		dao.deleteAllArchives()
		dao.deleteAllProtectedIdentities()
		dao.deleteAllFences()
		dao.deleteSourceFence()
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 1L) shouldBe 1

		importer(database).importArchive(
			request(
				archive,
				jobId = "new-epoch",
				archiveKey = "new-epoch",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe applied(archive, 1)
	}

	private fun importer(database: AppDatabase) = RoomImportPortableAmbientSteps(
		database,
		database.importedAmbientStepsDao(),
		Dispatchers.Unconfined,
	)

	private suspend fun readReady(
		database: AppDatabase,
		archive: PortableAmbientStepsArchiveV1,
	): ImportedAmbientStepsSnapshot.Ready =
		ImportedAmbientStepsRoomReader(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).read(range(archive)) as ImportedAmbientStepsSnapshot.Ready

	private suspend fun reexport(
		database: AppDatabase,
		archive: PortableAmbientStepsArchiveV1,
	): PortableAmbientStepsArchiveV1 {
		var emitted: PortableAmbientStepsArchiveV1? = null
		RoomReexportImportedAmbientSteps(
			ImportedAmbientStepsRoomReader(
				database,
				database.importedAmbientStepsDao(),
				Dispatchers.Unconfined,
			),
			Dispatchers.Unconfined,
		).export(range(archive)) { emitted = it } shouldBe
			ExportPortableAmbientStepsResult.Exported(
				archive.days.size,
				archive.days.sumOf { it.facts.size },
				archive.days.sumOf { it.gaps.size },
			)
		return requireNotNull(emitted)
	}

	private suspend fun reexportResult(
		database: AppDatabase,
		archive: PortableAmbientStepsArchiveV1,
	): ExportPortableAmbientStepsResult = RoomReexportImportedAmbientSteps(
		ImportedAmbientStepsRoomReader(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		),
		Dispatchers.Unconfined,
	).export(range(archive)) { error("Unavailable export must not emit") }

	private fun request(
		archive: PortableAmbientStepsArchiveV1,
		jobId: String = "job-1",
		archiveKey: String = "archive-1",
		expectedEpoch: Long = EPOCH,
	) = ImportPortableAmbientStepsRequest(
		archive = archive,
		receipt = PortableAmbientStepsImportReceipt(
			jobId,
			archiveKey,
			"backup.trackerambientsteps",
			archive.days.maxOf { it.structuralDayEndTimeMs },
		),
		metadata = metadata(archive),
		expectedCollectedDataEpoch = expectedEpoch,
	)

	private fun metadata(archive: PortableAmbientStepsArchiveV1) =
		PortableAmbientStepsImportMetadata(
			encodedByteCount = 1_024L,
			archiveContentChecksum = archive.contentChecksum,
			dayCount = archive.days.size,
			factCount = archive.days.sumOf { it.facts.size },
			gapCount = archive.days.sumOf { it.gaps.size },
		)

	private fun applied(
		archive: PortableAmbientStepsArchiveV1,
		appended: Int,
	) = ImportPortableAmbientStepsResult.Applied(
		archive.identity,
		appended,
		archive.days.size,
		archive.days.sumOf { it.facts.size },
		archive.days.sumOf { it.gaps.size },
	)

	private fun range(archive: PortableAmbientStepsArchiveV1) =
		ExportPortableAmbientStepsRequest(
			archive.days.minOf { it.structuralDayStartTimeMs },
			archive.days.maxOf { it.structuralDayEndTimeMs },
		)

	private fun archive(vararg days: PortableAmbientStepsDayV1) =
		PortableAmbientStepsArchiveV1.create(days.sortedBy { it.structuralDayStartTimeMs })

	private fun completeDay(
		date: LocalDate,
		count: Long,
		zoneId: String = "UTC",
	): PortableAmbientStepsDayV1 {
		val (start, end) = dayBounds(date, zoneId)
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "fact-${date.toEpochDay()}"),
			start,
			end,
			count,
		)
		return portableDay(date, zoneId, listOf(fact), emptyList(), emptyList())
	}

	private fun partialDay(
		date: LocalDate,
		count: Long,
		zoneId: String,
	): PortableAmbientStepsDayV1 {
		val (start, end) = dayBounds(date, zoneId)
		val boundary = start + 60L * 60L * 1_000L
		val gap = PortableAmbientStepsGapV1.create(
			identity(AmbientStepsPortableIdentityKind.GAP, "gap-${date.toEpochDay()}"),
			start,
			boundary,
			PortableAmbientStepsGapReason.PROCESS_ABSENCE,
		)
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "fact-${date.toEpochDay()}"),
			boundary,
			end,
			count,
		)
		return portableDay(
			date,
			zoneId,
			listOf(fact),
			listOf(gap),
			listOf(PortableAmbientStepsPartialCause.EXPLICIT_GAP),
		)
	}

	private fun retainedDay(
		date: LocalDate,
		count: Long,
		zoneId: String = "UTC",
	): PortableAmbientStepsDayV1 {
		val (start, end) = dayBounds(date, zoneId)
		val retainedFrom = start + 60L * 60L * 1_000L
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "retained-fact-${date.toEpochDay()}"),
			retainedFrom,
			end,
			count,
		)
		return PortableAmbientStepsDayV1.create(
			identity = identity(
				AmbientStepsPortableIdentityKind.DAY,
				"${date.toEpochDay()}|$zoneId|$start|$end",
			),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = zoneId,
			structuralDayStartTimeMs = start,
			structuralDayEndTimeMs = end,
			retainedFromTimeMs = retainedFrom,
			coverage = PortableAmbientStepsCoverage.PARTIAL,
			partialCauses = listOf(PortableAmbientStepsPartialCause.RETENTION),
			retainedStepCount = count,
			facts = listOf(fact),
			gaps = emptyList(),
		)
	}

	private fun portableDay(
		date: LocalDate,
		zoneId: String,
		facts: List<PortableAmbientStepsFactV1>,
		gaps: List<PortableAmbientStepsGapV1>,
		causes: List<PortableAmbientStepsPartialCause>,
	): PortableAmbientStepsDayV1 {
		val (start, end) = dayBounds(date, zoneId)
		return PortableAmbientStepsDayV1.create(
			identity = identity(
				AmbientStepsPortableIdentityKind.DAY,
				"${date.toEpochDay()}|$zoneId|$start|$end",
			),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = zoneId,
			structuralDayStartTimeMs = start,
			structuralDayEndTimeMs = end,
			retainedFromTimeMs = null,
			coverage = if (causes.isEmpty()) {
				PortableAmbientStepsCoverage.COMPLETE
			} else {
				PortableAmbientStepsCoverage.PARTIAL
			},
			partialCauses = causes,
			retainedStepCount = facts.sumOf { it.stepCount },
			facts = facts,
			gaps = gaps,
		)
	}

	private fun dayBounds(date: LocalDate, zoneId: String): Pair<Long, Long> {
		val zone = ZoneId.of(zoneId)
		return date.atStartOfDay(zone).toInstant().toEpochMilli() to
			date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
	}

	private fun identity(kind: AmbientStepsPortableIdentityKind, local: String) =
		AmbientStepsPortableOpaqueIdentity.derive(kind, local)

	private suspend fun seedEvidence(database: AppDatabase) {
		val dao = database.sourceEvidenceStateDao()
		dao.ensure(SourceEvidenceState())
		if (dao.get()?.collectedDataEpoch != EPOCH) {
			dao.updateLifecycle(EPOCH, null, 0L) shouldBe 1
		}
	}

	private suspend fun seedRevokedConsent(database: AppDatabase) {
		val policy = SourcePolicyEntity(
			policyRevision = 2L,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			enabled = true,
			qosCode = 1,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = false,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = null,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 2L,
			effectiveWallTimeMs = 2L,
			changeReason = "test revoke",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			epoch = REVOKED_CONSENT_EPOCH,
			eligible = false,
			persistenceEligible = false,
			policyRevision = policy.policyRevision,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 2L,
			effectiveWallTimeMs = 2L,
			changeReason = "test revoke",
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy))
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent))
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = policy.policyRevision,
				legacySettingsFingerprint = null,
				updatedAtMs = 2L,
			),
		)
	}

	private suspend fun seedEligibleConsent(database: AppDatabase) {
		val policy = SourcePolicyEntity(
			policyRevision = 3L,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			enabled = true,
			qosCode = 1,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = false,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = true,
			captureConsentEpoch = null,
			controlConsentEpoch = null,
			ambientConsentEpoch = ELIGIBLE_CONSENT_EPOCH,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 3L,
			effectiveWallTimeMs = 3L,
			changeReason = "test reset",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			epoch = ELIGIBLE_CONSENT_EPOCH,
			eligible = true,
			persistenceEligible = true,
			policyRevision = policy.policyRevision,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 3L,
			effectiveWallTimeMs = 3L,
			changeReason = "test reset",
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy))
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent))
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 2L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = policy.policyRevision,
			legacySettingsFingerprint = null,
			updatedAtMs = 3L,
		) shouldBe 1
	}

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private fun openNamed(context: Application): AppDatabase = Room.databaseBuilder(
		context,
		AppDatabase::class.java,
		REOPEN_DATABASE,
	).openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
		.allowMainThreadQueries()
		.build()

	private companion object {
		const val EPOCH = 7L
		const val REVOKED_CONSENT_EPOCH = 2L
		const val ELIGIBLE_CONSENT_EPOCH = 3L
		const val REOPEN_DATABASE = "imported-ambient-steps-reopen"
	}
}
