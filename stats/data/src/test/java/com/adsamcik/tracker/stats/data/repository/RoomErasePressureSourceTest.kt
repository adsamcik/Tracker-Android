package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.preserveImportedPressureFullClearAuthority
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseWitnessEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
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
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseRetryableReason
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
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
			legacySampleCount = 0,
			importedEntryCount = 0,
			importedRevisionCount = 0,
			importedRunCount = 0,
			importedWindowCount = 0,
			fencedLocalRunCount = 0,
		)
		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.AlreadyErased
		barrier.calls shouldBe 1
		barrier.verifications shouldBe 4
	}

	@Test
	fun `existing erase refuses AlreadyErased while persistence fence is unsettled`() = runTest {
		subject(testScheduler).erase(request())
			.shouldBeInstanceOf<ErasePressureSourceResult.Erased>()
		barrier.verificationResult = PressureSourceEraseBarrierVerification.Retryable(
			PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
		)

		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.RetryableFailure(
				PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE,
			)

		barrier.calls shouldBe 1
	}

	@Test
	fun `existing erase refuses AlreadyErased after stale lifecycle or provider reappears`() = runTest {
		subject(testScheduler).erase(request())
			.shouldBeInstanceOf<ErasePressureSourceResult.Erased>()
		val failures = listOf(
			PressureSourceEraseBarrierVerification.Blocked(
				com.adsamcik.tracker.stats.api.repository
					.PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
			) to ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			PressureSourceEraseBarrierVerification.Blocked(
				com.adsamcik.tracker.stats.api.repository
					.PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			) to ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			),
		)

		failures.forEach { (verification, expected) ->
			barrier.verificationResult = verification
			subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe expected
		}

		barrier.calls shouldBe 1
	}

	@Test
	fun `ownerless v1 erase receipt remains readable but cannot recreate lifecycle token`() = runTest {
		subject(testScheduler).erase(request())
			.shouldBeInstanceOf<ErasePressureSourceResult.Erased>()
		val current = requireNotNull(database.importedPressureDao().sourceErase())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_source_erase SET legacy_write_fence_owner = NULL, " +
				"effect_checksum = ? WHERE id = ?",
			arrayOf(ownerlessV1Checksum(current), ImportedPressureSourceEraseEntity.SINGLETON_ID),
		)

		requireNotNull(database.importedPressureDao().sourceErase()).legacyWriteFenceOwner shouldBe null
		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.Unverifiable(
				com.adsamcik.tracker.stats.api.repository
					.ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		barrier.calls shouldBe 1
	}

	@Test
	fun `source erase preflights standalone permanent owner before loading oversized text`() = runTest {
		val identity = PortablePressureOpaqueIdentity.derive(
			PortablePressureIdentityKind.LOGICAL_ENTRY,
			"oversized-source-erase-fence",
		).value
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		database.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO imported_pressure_identity_fence (
			  protected_identity, identity_kind, entry_identity, run_identity,
			  original_collected_data_epoch, fence_generation, fenced_at_ms,
			  fence_reason, effect_checksum
			) VALUES (?, 'ENTRY', ?, NULL, ?, 1, ?, 'FULL_CLEAR', CAST(zeroblob(?) AS TEXT))
			""".trimIndent(),
			arrayOf(identity, identity, EPOCH, ERASED_AT_MS, oversized),
		)

		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedPressureDao().identityFenceCount() shouldBe 1L
		database.importedPressureDao().sourceErase() shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `source erase preflights orphan permanent deletion before loading oversized identity`() =
		runTest {
			val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
				.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
			val checksum = PortablePressureOpaqueIdentity.derive(
				PortablePressureIdentityKind.LOGICAL_ENTRY,
				"small-source-erase-deletion-checksum",
			).value
			database.openHelper.writableDatabase.execSQL(
				"""
				INSERT INTO imported_pressure_entry_deletion (
				  entry_identity, collected_data_epoch, deleted_import_revision, deleted_at_ms,
				  run_deletion_count, run_deletion_set_checksum, identity_fence_count,
				  identity_fence_set_checksum, effect_checksum
				) VALUES (
				  CAST(zeroblob(?) AS TEXT), ?, 1, ?, 0, ?, 0, ?, ?
				)
				""".trimIndent(),
				arrayOf(oversized, EPOCH, ERASED_AT_MS, checksum, checksum, checksum),
			)

			subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Unverifiable(
				com.adsamcik.tracker.stats.api.repository
					.ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
			database.openHelper.writableDatabase.query(
				"SELECT COUNT(*) FROM imported_pressure_entry_deletion",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getLong(0) shouldBe 1L
			}
			database.importedPressureDao().sourceErase() shouldBe null
			database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		}

	@Test
	fun `source erase preflights local deletion fence before loading oversized digest`() = runTest {
		val oversized = com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
			.MAX_LINEAGE_TEXT_BYTES.toInt() + 1
		val checksum = PortablePressureOpaqueIdentity.derive(
			PortablePressureIdentityKind.LOGICAL_ENTRY,
			"small-local-fence-checksum",
		).value
		database.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO source_deletion_fence (
			  source_kind, purpose, scope_kind, scope_identity_digest, fence_generation,
			  collected_data_epoch, deleted_at_ms, effect_checksum
			) VALUES (
			  ?, 'SESSION_CAPTURE', 'LOGICAL_SERVICE_RUN', CAST(zeroblob(?) AS TEXT),
			  1, ?, ?, ?
			)
			""".trimIndent(),
			arrayOf(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				oversized,
				EPOCH,
				ERASED_AT_MS,
				checksum,
			),
		)

		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.pressureFactRevisionDao().sourceEraseFenceCount() shouldBe 1L
		database.importedPressureDao().sourceErase() shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `import-only erase needs no provider authority and permanently fences replay`() = runTest {
		val imported = importRequest()
		val unrelatedWal = unrelatedWal()
		val pressureControlWal = pressureControlWal()
		importer(testScheduler).importEntry(imported) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		database.sourceEventWalDao().insertIgnoringDuplicate(unrelatedWal)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureControlWal)

		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Erased(
			localFactRevisionCount = 0,
			localWalEventCount = 0,
			legacySampleCount = 0,
			importedEntryCount = 1,
			importedRevisionCount = 1,
			importedRunCount = 1,
			importedWindowCount = 1,
			fencedLocalRunCount = 0,
		)

		barrier.calls shouldBe 1
		database.importedPressureDao().latestEntryRevision(imported.entry.identity.value) shouldBe null
		database.sourceEventWalDao().getByEventId(unrelatedWal.eventId)?.sourceKind shouldBe
			SourceDestinationOwnerEntity.SOURCE_CELL
		database.sourceEventWalDao().getByEventId(pressureControlWal.eventId)?.sourceKind shouldBe
			SourceDestinationOwnerEntity.SOURCE_PRESSURE
		requireNotNull(
			database.importedPressureDao().entryDeletion(imported.entry.identity.value),
		).let {
			it.deletedImportRevision shouldBe 1L
			it.identityFenceCount shouldBe 3
			it.runDeletionCount shouldBe 1
		}
		importer(testScheduler).importEntry(
			imported.copy(
				receipt = imported.receipt.copy(jobId = "late-job", entryKey = "late-entry"),
			),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_ENTRY,
		)
		val newOrigin = importRequest(
			entryLocalId = "new-entry",
			runLocalId = "new-run",
			windowLocalId = "new-window",
			jobId = "new-job",
		)
		importer(testScheduler).importEntry(newOrigin) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)
		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.Erased(
				localFactRevisionCount = 0,
				localWalEventCount = 0,
				legacySampleCount = 0,
				importedEntryCount = 1,
				importedRevisionCount = 1,
				importedRunCount = 1,
				importedWindowCount = 1,
				fencedLocalRunCount = 0,
			)
		database.withTransaction {
			preserveImportedPressureFullClearAuthority(
				database.openHelper.writableDatabase,
				EPOCH,
				ERASED_AT_MS + 1L,
			)
			database.sourceEvidenceStateDao().updateLifecycle(
				EPOCH + 1L,
				null,
				ERASED_AT_MS + 1L,
			) shouldBe 1
			database.importedPressureDao().deleteAllReceipts()
			database.importedPressureDao().deleteAllEntries()
			database.importedPressureDao().deleteAllRetainedIdentities()
			database.importedPressureDao().deleteAllRetentionReceipts()
			database.importedPressureDao().deleteSourceEraseWitnesses()
			database.importedPressureDao().deleteSourceErase()
		}
		database.importedPressureDao().sourceErase() shouldBe null
		importer(testScheduler).importEntry(
			oldEpochReplay(imported, EPOCH + 1L),
		) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_ENTRY,
		)
		importer(testScheduler).importEntry(
			importRequest(
				entryLocalId = "post-clear-entry",
				runLocalId = "post-clear-run",
				windowLocalId = "post-clear-window",
				jobId = "post-clear",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
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
	fun `source erase converts retained shell to permanent entry run and window authority`() =
		runTest {
			val imported = importRequest()
			importer(testScheduler).importEntry(imported)
			database.sourceEvidenceStateDao().updateLifecycle(
				EPOCH,
				1_500L,
				2_500L,
			) shouldBe 1
			RoomTruncateImportedPressureRetention(
				database,
				UnconfinedTestDispatcher(testScheduler),
				{},
			).truncate(
				TruncateImportedPressureRetentionRequest(EPOCH, 1L, 1_500L, 2_500L),
			) shouldBe TruncateImportedPressureRetentionResult.Truncated(1, 1, 1, 1)

			subject(testScheduler).erase(request(expectedRevision = 2L)) shouldBe
				ErasePressureSourceResult.Erased(
					localFactRevisionCount = 0,
					localWalEventCount = 0,
					legacySampleCount = 0,
					importedEntryCount = 1,
					importedRevisionCount = 1,
					importedRunCount = 1,
					importedWindowCount = 1,
					fencedLocalRunCount = 0,
				)

			val dao = database.importedPressureDao()
			dao.retentionReceipt(imported.entry.identity.value) shouldBe null
			dao.entryDeletion(imported.entry.identity.value)?.identityFenceCount shouldBe 3
			dao.identityFencesForEntry(imported.entry.identity.value, 4).size shouldBe 3
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
	fun `zero-row active provider blocks legacy hPa erase until quiescent then preserves location altitude`() =
		runTest {
			installRevokedPressurePolicy()
			database.pressureSampleDao().insert(
				PressureSample(
					timeMs = 1_000L,
					elapsedRealtimeNanos = 1_000L,
					pressureHpa = 1_000f,
					altitudeM = 123f,
					bucketId = null,
					createdAt = 1_000L,
				),
			)
			val location = LocationSample(
				timeMs = 1_000L,
				elapsedRealtimeNanos = 1_000L,
				latE7 = 500_000_000,
				lonE7 = 140_000_000,
				altitudeM = 456f,
				rawGpsAltitudeM = 457f,
				hAccM = 5f,
				vAccM = 3f,
				speedMps = null,
				speedAccuracyMps = null,
				provider = "gps",
				quality = SampleQuality.HIGH,
				motionState = null,
				policy = null,
				bucketId = null,
				createdAt = 1_000L,
			)
			database.locationSampleDao().insert(location)
			database.sourceBrokerDao().insertRegistration(activePressureRegistration())

			subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			)
			database.importedPressureDao().sourceErase() shouldBe null
			database.pressureFactRevisionDao().legacyPressureSampleCount() shouldBe 1L

			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET status = 'RETIRED', " +
					"retired_at_ms = ?, retired_elapsed_realtime_nanos = ? " +
					"WHERE source_kind = ? AND registration_generation = 1",
				arrayOf(
					ERASED_AT_MS - 1L,
					ERASED_AT_MS - 1L,
					SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				),
			)
			subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.Erased(
				localFactRevisionCount = 0,
				localWalEventCount = 0,
				legacySampleCount = 1,
				importedEntryCount = 0,
				importedRevisionCount = 0,
				importedRunCount = 0,
				importedWindowCount = 0,
				fencedLocalRunCount = 0,
			)
			database.pressureFactRevisionDao().legacyPressureSampleCount() shouldBe 0L
			database.locationSampleDao().getAllBetween(0L, 2_000L).single().altitudeM shouldBe 456f
			barrier.calls shouldBe 2
			barrier.verifications shouldBe 3
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM imported_pressure_source_erase_witness WHERE witness_kind = 'LEGACY_SAMPLE'",
			)
			subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
				ErasePressureSourceResult.Unverifiable(
					com.adsamcik.tracker.stats.api.repository
						.ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
				)
		}

	@Test
	fun `legacy writer fence verification failure leaves hPa and source marker untouched`() = runTest {
		installRevokedPressurePolicy()
		database.pressureSampleDao().insert(
			PressureSample(
				timeMs = 1_000L,
				elapsedRealtimeNanos = 1_000L,
				pressureHpa = 1_000f,
				altitudeM = 123f,
				bucketId = null,
				createdAt = 1_000L,
			),
		)
		barrier.verificationResult = PressureSourceEraseBarrierVerification.Retryable(
			PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
		)

		subject(testScheduler).erase(request()) shouldBe ErasePressureSourceResult.RetryableFailure(
			PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE,
		)
		database.pressureFactRevisionDao().legacyPressureSampleCount() shouldBe 1L
		database.importedPressureDao().sourceErase() shouldBe null
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
	fun `missing typed witness makes idempotent source erase unverifiable`() = runTest {
		val imported = importRequest()
		importer(testScheduler).importEntry(imported)
		subject(testScheduler).erase(request())
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_pressure_identity_fence WHERE protected_identity = ?",
			arrayOf(imported.entry.runs.single().windows.single().identity.value),
		)

		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.Unverifiable(
				com.adsamcik.tracker.stats.api.repository
					.ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
			)
	}

	@Test
	fun `missing local scope fence makes api idempotence unverifiable`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, null, ERASED_AT_MS) shouldBe 1
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				ownerGeneration =
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
				updatedAtMs = ERASED_AT_MS,
			),
		)
		val missingFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			"SESSION_CAPTURE",
			"missing-local-session",
			"missing-local-run",
			1L,
			EPOCH,
			ERASED_AT_MS,
		)
		val marker = ImportedPressureSourceEraseEntity.create(
			collectedDataEpoch = EPOCH,
			sourceEvidenceRevision = 1L,
			erasedAtMs = ERASED_AT_MS,
			providerRegistrationGeneration = 1L,
			legacyWriteFenceOwner =
				SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
			legacyWriteFenceGeneration =
				SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
			localFactRevisionCount = 1,
			localWalEventCount = 0,
			legacySampleCount = 0,
			legacySampleWitnesses = emptyList(),
			importedEntryCount = 0,
			importedRevisionCount = 0,
			importedRunCount = 0,
			importedWindowCount = 0,
			localFences = listOf(missingFence),
			entryDeletions = emptyList(),
			runDeletions = emptyList(),
			identityFences = emptyList(),
		)
		database.importedPressureDao().insertSourceErase(marker)
		database.importedPressureDao().insertSourceEraseWitnesses(
			listOf(ImportedPressureSourceEraseWitnessEntity.local(missingFence)),
		)

		subject(testScheduler).erase(request(expectedRevision = 1L)) shouldBe
			ErasePressureSourceResult.Unverifiable(
				com.adsamcik.tracker.stats.api.repository
					.ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
			)
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
				RecordingBarrier(fileDatabase),
				UnconfinedTestDispatcher(testScheduler),
				{},
			).erase(request()) shouldBe ErasePressureSourceResult.Erased(
				localFactRevisionCount = 0,
				localWalEventCount = 0,
				legacySampleCount = 0,
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
			requireNotNull(fileDatabase.importedPressureDao().sourceErase()).also { marker ->
				marker.collectedDataEpoch shouldBe EPOCH
				marker.legacyWriteFenceOwner shouldBe
					SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
			}
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

	private suspend fun installRevokedPressurePolicy() {
		database.sourcePolicyDao().insertPolicies(listOf(
			SourcePolicyEntity(
				policyRevision = POLICY_REVISION,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				enabled = false,
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
				effectiveBootId = "boot-pressure-erase",
				effectiveElapsedRealtimeNanos = 100L,
				effectiveWallTimeMs = 100L,
				changeReason = "TEST_REVOKED",
			),
		))
		database.sourcePolicyDao().insertConsentEpochs(listOf(
			SourceConsentEpochEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = "SESSION_CAPTURE",
				epoch = CONSENT_EPOCH,
				eligible = false,
				persistenceEligible = false,
				policyRevision = POLICY_REVISION,
				effectiveBootId = "boot-pressure-erase",
				effectiveElapsedRealtimeNanos = 100L,
				effectiveWallTimeMs = 100L,
				changeReason = "TEST_REVOKED",
			),
		))
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 100L,
			),
		)
	}

	private fun activePressureRegistration() = ProviderRegistrationGenerationEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		registrationGeneration = 1L,
		sourceInstanceId = "pressure-instance",
		ownerScope = "pressure-owner",
		clockDomainId = "boot-pressure-erase",
		physicalConfigurationFingerprint = "pressure-config",
		collectedDataEpoch = EPOCH,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process",
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 100L,
		reservedElapsedRealtimeNanos = 100L,
		acceptedAtMs = 100L,
		acceptedElapsedRealtimeNanos = 100L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

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

	private fun importRequest(
		entryLocalId: String = "entry",
		runLocalId: String = "run",
		windowLocalId: String = "window",
		jobId: String = "job",
		expectedEpoch: Long = EPOCH,
	): ImportPortablePressureRequest {
		val window = PortablePressureWindowV1.create(
			identity(PortablePressureIdentityKind.WINDOW, windowLocalId),
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
			identity(PortablePressureIdentityKind.PHYSICAL_RUN, runLocalId),
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
				identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryLocalId),
				1_000L,
				2_000L,
				listOf(run),
			),
			PortablePressureImportReceipt(jobId, entryLocalId, "backup.trackerpressure", 2_000L),
			expectedEpoch,
		)
	}

	private fun oldEpochReplay(
		request: ImportPortablePressureRequest,
		expectedEpoch: Long,
	) = request.copy(
		receipt = request.receipt.copy(jobId = "post-clear-replay", entryKey = "post-clear-replay"),
		expectedCollectedDataEpoch = expectedEpoch,
	)

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

	private fun pressureControlWal(): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = "pressure-control-wal",
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			sourceInstanceId = "pressure-control-instance",
			registrationGeneration = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			sourceSequence = 1L,
			configRevision = null,
			planAttribution = 0,
			clockDomainId = "boot-pressure-control",
			observedElapsedNanos = 100L,
			receivedElapsedNanos = 100L,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = EPOCH,
			acquiredAtMs = 100L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = byteArrayOf(4, 5, 6),
			payloadChecksum = "pending",
			createdAtMs = 100L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun ownerlessV1Checksum(value: ImportedPressureSourceEraseEntity): String {
		val values = listOf(
			value.collectedDataEpoch,
			value.sourceEvidenceRevision,
			value.erasedAtMs,
			value.providerRegistrationGeneration ?: "NONE",
			value.legacyWriteFenceGeneration,
			value.localFactRevisionCount,
			value.localWalEventCount,
			value.legacySampleCount,
			value.legacySampleSetChecksum,
			value.importedEntryCount,
			value.importedRevisionCount,
			value.importedRunCount,
			value.importedWindowCount,
			value.fencedLocalRunCount,
			value.localScopeSetChecksum,
			value.entryDeletionCount,
			value.entryDeletionSetChecksum,
			value.runDeletionCount,
			value.runDeletionSetChecksum,
			value.identityFenceCount,
			value.identityFenceSetChecksum,
		).map { it.toString() }
		val canonical = (
			listOf("tracker-imported-pressure-source-erase-v1") + values
		).joinToString(separator = "") { "${it.length}:$it" }
		return "sha256:" + MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private inner class RecordingBarrier(
		private val targetDatabase: AppDatabase = database,
	) : PressureSourceEraseBarrier {
		var calls = 0
		var verifications = 0
		var verificationResult: PressureSourceEraseBarrierVerification =
			PressureSourceEraseBarrierVerification.Verified
		override suspend fun establish(
			expectedCollectedDataEpoch: Long,
		): PressureSourceEraseBarrierResult {
			calls++
			val generation =
				SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION + calls - 1L
			val ownerDao = targetDatabase.sourceDestinationOwnerDao()
			val current = ownerDao.get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			)
			if (current == null) {
				ownerDao.insertIfAbsent(
					SourceDestinationOwnerEntity(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
						destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
						owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
						ownerGeneration = generation,
						updatedAtMs = ERASED_AT_MS,
					),
				)
			} else if (current.ownerGeneration != generation) {
				check(ownerDao.compareAndSetOwner(
					sourceKind = current.sourceKind,
					destination = current.destination,
					expectedOwner = current.owner,
					expectedOwnerGeneration = current.ownerGeneration,
					newOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					newOwnerGeneration = generation,
					updatedAtMs = ERASED_AT_MS,
				) == 1)
			}
			return PressureSourceEraseBarrierResult.NoLocalProvider(
				PressureSourceEraseBarrierToken(
					expectedCollectedDataEpoch,
					null,
					com.adsamcik.tracker.stats.api.repository
						.PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
					generation,
				),
			)
		}

		override suspend fun verifySettled(
			token: PressureSourceEraseBarrierToken,
		): PressureSourceEraseBarrierVerification {
			verifications++
			return verificationResult
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
