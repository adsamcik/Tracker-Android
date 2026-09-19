package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionRunMarker
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class TrackingSourceFullClearAssemblyTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
		database = openDatabase()
	}

	@After
	fun tearDown() {
		if (::database.isInitialized) database.close()
		if (::context.isInitialized) context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun `fresh AppDatabase installs exact Steps count-domain schema and DAO`() = runTest {
		StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.ValidV2
		database.stepsCountDomainReceiptDao().receipt(opaque('f')) shouldBe null
		rowCount(StepsCountDomainSchema.SCHEMA_MARKER_TABLE) shouldBe 1L
	}

	@Test
	fun `shared test builder authenticates Steps schema before full clear`() = runTest {
		val testDatabase = AppDatabase.testDatabase(context)
		try {
			StepsCountDomainSchema.inspect(testDatabase.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
			testDatabase.sourceEvidenceStateDao().ensure(
				SourceEvidenceState(collectedDataEpoch = EPOCH),
			)

			AppDatabase.deleteAllCollectedData(
				database = testDatabase,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = 1L,
			)

			StepsCountDomainSchema.inspect(testDatabase.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
		} finally {
			testDatabase.close()
		}
	}

	@Test
	fun `repository direct builder convention installs final Steps callback`() {
		val direct = AppDatabase.inMemoryBuilder(context)
			.allowMainThreadQueries()
			.build()
		try {
			StepsCountDomainSchema.inspect(direct.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
		} finally {
			direct.close()
		}
	}

	@Test
	@Suppress("LongMethod")
	fun `file backed full clear publishes once and preserves source authority across repeated reopen`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(
				SourceEvidenceState(revision = 5L, collectedDataEpoch = EPOCH),
			)
			val wifi = seedWifiDeletionAuthority()
			val localWifi = seedLocalWifiDeletionAuthority()
			val importedCell = seedImportedCellDeletionAuthority()
			val capturedCell = seedCapturedCellDeletionAuthority()
			val ambientArchive = seedAmbientWifiPayload()
			val stepsScope = seedStepsFence()
			val countDomain = seedStepsCountDomainEvidence()
			seedRuntimeSettlementRows()

			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = RETAINED_FROM_MS,
				updatedAtMs = 1_000L,
			)

			reopen()
			assertEvidenceState(EPOCH + 1L, 6L, RETAINED_FROM_MS)
			assertWifiAuthority(wifi, EPOCH + 1L)
			assertWifiAuthority(localWifi, EPOCH + 1L)
			assertImportedCellAuthority(importedCell, EPOCH + 1L)
			assertCapturedCellAuthority(capturedCell, EPOCH + 1L)
			assertStepsFence(stepsScope, EPOCH + 1L)
			assertStepsCountDomainEvidence(countDomain)
			database.ambientWifiFactDao().importedFactCount() shouldBe 0L
			database.ambientWifiFactDao().importTombstone(ambientArchive) shouldNotBe null
			database.ambientWifiFactDao().replayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
				ambientArchive,
				0L,
			) shouldNotBe null
			rowCount("source_capture_admission_barrier") shouldBe 0L
			rowCount("source_run_retirement") shouldBe 0L
			rowCount("source_authorization") shouldBe 0L

			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 2L,
				retainedFromMs = null,
				updatedAtMs = 2_000L,
			)

			reopen()
			assertEvidenceState(EPOCH + 2L, 7L, RETAINED_FROM_MS)
			assertWifiAuthority(wifi, EPOCH + 2L)
			assertWifiAuthority(localWifi, EPOCH + 2L)
			assertImportedCellAuthority(importedCell, EPOCH + 2L)
			assertCapturedCellAuthority(capturedCell, EPOCH + 2L)
			assertStepsFence(stepsScope, EPOCH + 2L)
			assertStepsCountDomainEvidence(countDomain)
			database.ambientWifiFactDao().importedFactCount() shouldBe 0L
			database.ambientWifiFactDao().importTombstone(ambientArchive) shouldNotBe null
			database.ambientWifiFactDao().replayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
				ambientArchive,
				0L,
			) shouldNotBe null
			rowCount("ambient_wifi_deletion_marker") shouldBe 2L
			rowCount("ambient_cell_deletion_marker") shouldBe 2L
		}

	private suspend fun seedStepsCountDomainEvidence(): StepsCountDomainFixture {
		val boundScope = opaque('8')
		val boundOwnerIdentity = opaque('9')
		val boundEffect = digest('a')
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = opaque('b'),
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity = boundScope,
			ownerIdentity = boundOwnerIdentity,
			ownerRevision = 1L,
			registrationGeneration = 1L,
			collectedDataEpoch = EPOCH,
			authorityRevision = 1L,
			authorityFingerprint = digest('c'),
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
			coverageVersion = 1,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = boundEffect,
			completionEvidenceChecksum = null,
		)
		val receipt = StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = opaque('b'),
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity = boundScope,
			ownerIdentity = boundOwnerIdentity,
			ownerRevision = 1L,
			registrationGeneration = 1L,
			collectedDataEpoch = EPOCH,
			authorityRevision = 1L,
			authorityFingerprint = digest('c'),
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
			coverageVersion = 1,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = boundEffect,
			completionEvidenceChecksum = null,
		)
		val boundOwner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity = boundScope,
			ownerIdentity = boundOwnerIdentity,
			ownerRevision = 1L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
			receiptIdentity = receiptIdentity,
			ownerEffectChecksum = boundEffect,
			linkedAtMs = 100L,
		)
		val terminalOwner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = opaque('d'),
			ownerIdentity = opaque('e'),
			ownerRevision = 1L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
			receiptIdentity = null,
			ownerEffectChecksum = digest('f'),
			linkedAtMs = 101L,
		)
		database.stepsCountDomainReceiptDao().append(receipt, boundOwner) shouldBe
			StepsCountDomainAppendResult.INSERTED
		database.stepsCountDomainReceiptDao().append(null, terminalOwner) shouldBe
			StepsCountDomainAppendResult.INSERTED
		return StepsCountDomainFixture(receipt, boundOwner, terminalOwner)
	}

	private suspend fun assertStepsCountDomainEvidence(fixture: StepsCountDomainFixture) {
		val dao = database.stepsCountDomainReceiptDao()
		dao.receipt(fixture.receipt.receiptIdentity) shouldBe null
		dao.owner(
			fixture.boundOwner.ownerKind,
			fixture.boundOwner.ownerIdentity,
			fixture.boundOwner.ownerRevision,
		) shouldBe null
		dao.owner(
			fixture.terminalOwner.ownerKind,
			fixture.terminalOwner.ownerIdentity,
			fixture.terminalOwner.ownerRevision,
		) shouldBe fixture.terminalOwner
		rowCount(StepsCountDomainSchema.SCHEMA_MARKER_TABLE) shouldBe 1L
	}

	@Test
	fun `full clear rejects a radio receipt whose retained floor is not authenticated`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(revision = 5L, collectedDataEpoch = EPOCH),
		)
		val wifi = seedWifiDeletionAuthority()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_selected_deletion_receipt SET retained_from_ms = ? " +
				"WHERE selection_identity = ? AND origin = ?",
			arrayOf(777L, wifi.entry, wifi.origin),
		)

		shouldThrow<IllegalArgumentException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = RETAINED_FROM_MS,
				updatedAtMs = 1_000L,
			)
		}

		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe EPOCH
		database.openHelper.writableDatabase.query(
			"SELECT collected_data_epoch, retained_from_ms " +
				"FROM wifi_selected_deletion_receipt WHERE selection_identity = ? AND origin = ?",
			arrayOf(wifi.entry, wifi.origin),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe EPOCH
			cursor.getLong(1) shouldBe 777L
		}
	}

	private suspend fun seedWifiDeletionAuthority(): WifiAuthorityFixture {
		val entry = digest('1')
		val run = digest('2')
		val observation = digest('3')
		val scope = digest('4')
		val deletedAtMs = 100L
		val runDeletion = ImportedWifiDeletionGenerationEntity.create(
			run,
			entry,
			scope,
			EPOCH,
			1L,
			deletedAtMs,
		)
		val entryDeletion = ImportedWifiEntryDeletionEntity.create(
			entry,
			EPOCH,
			1L,
			deletedAtMs,
		)
		val sourceFence = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			scope,
			1L,
			EPOCH,
			deletedAtMs,
		)
		val protected = listOf(
			wifiProtected(entry, entry, WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY),
			wifiProtected(
				entry,
				run,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN,
				run,
				scope,
			),
			wifiProtected(
				entry,
				observation,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION,
				run,
			),
			wifiProtected(
				entry,
				scope,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE,
				run,
				scope,
			),
		)
		val receipt = WifiSelectedDeletionReceiptEntity.create(
			selectionIdentity = entry,
			origin = WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			collectedDataEpoch = EPOCH,
			selectedImportRevision = 1L,
			selectedContentChecksum = digest('5'),
			startTimeMs = 10L,
			endTimeMs = 20L,
			protectedIdentities = protected,
			runDeletionRows = listOf(
				WifiSelectedDeletionRunMarker(
					run,
					entry,
					scope,
					EPOCH,
					1L,
					deletedAtMs,
				),
			),
			sourceFences = listOf(sourceFence),
			retainedFromMs = null,
			deletedAtMs = deletedAtMs,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(sourceFence) shouldNotBe -1L
		database.importedWifiDao().insertDeletionGeneration(runDeletion)
		database.importedWifiDao().insertEntryDeletion(entryDeletion)
		database.importedWifiDao().insertSelectedDeletionReceipt(receipt)
		database.importedWifiDao().insertSelectedDeletionProtectedIdentities(protected)
		return WifiAuthorityFixture(
			entry,
			run,
			scope,
			protected.size,
			WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
		)
	}

	private suspend fun seedLocalWifiDeletionAuthority(): WifiAuthorityFixture {
		val entry = digest('a')
		val run = digest('b')
		val observation = digest('c')
		val scope = digest('d')
		val deletedAtMs = 101L
		val origin = WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL
		val sourceFence = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			scope,
			1L,
			EPOCH,
			deletedAtMs,
		)
		val protected = listOf(
			wifiProtected(
				entry,
				entry,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY,
				origin = origin,
			),
			wifiProtected(
				entry,
				run,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN,
				run,
				scope,
				origin,
			),
			wifiProtected(
				entry,
				observation,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION,
				run,
				origin = origin,
			),
			wifiProtected(
				entry,
				scope,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE,
				run,
				scope,
				origin,
			),
		)
		val receipt = WifiSelectedDeletionReceiptEntity.create(
			selectionIdentity = entry,
			origin = origin,
			collectedDataEpoch = EPOCH,
			selectedImportRevision = null,
			selectedContentChecksum = null,
			startTimeMs = 11L,
			endTimeMs = 21L,
			protectedIdentities = protected,
			runDeletionRows = listOf(
				WifiSelectedDeletionRunMarker(
					run,
					entry,
					scope,
					EPOCH,
					1L,
					deletedAtMs,
				),
			),
			sourceFences = listOf(sourceFence),
			retainedFromMs = null,
			deletedAtMs = deletedAtMs,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(sourceFence) shouldNotBe -1L
		database.importedWifiDao().insertSelectedDeletionReceipt(receipt)
		database.importedWifiDao().insertSelectedDeletionProtectedIdentities(protected)
		return WifiAuthorityFixture(entry, run, scope, protected.size, origin)
	}

	private fun wifiProtected(
		entry: String,
		protected: String,
		kind: String,
		run: String? = null,
		scope: String? = null,
		origin: String = WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
	) = WifiSelectedDeletionProtectedIdentityEntity.create(
		selectionIdentity = entry,
		receiptOrigin = origin,
		identityKind = kind,
		protectedIdentity = protected,
		ownerEntryIdentity = entry,
		ownerRunIdentity = run,
		deletionScopeDigest = scope,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		revisionCount = 1,
		revisionSetChecksum = digest('6'),
		collectedDataEpoch = EPOCH,
	)

	private suspend fun seedImportedCellDeletionAuthority(): ImportedCellAuthorityFixture {
		val entry = digest('7')
		val run = digest('8')
		val observation = digest('9')
		val scope = digest('a')
		val content = digest('b')
		val deletedAtMs = 200L
		val deletion = ImportedCellEntryDeletionEntity.create(entry, EPOCH, 1L, deletedAtMs)
		val runDeletion = ImportedCellDeletionGenerationEntity.create(
			run,
			entry,
			scope,
			EPOCH,
			1L,
			deletedAtMs,
		)
		val protected = listOf(
			ImportedCellDeletedIdentityEntity.create(
				entry,
				entry,
				ImportedCellDeletedIdentityEntity.ENTRY,
				contentChecksum = content,
			),
			ImportedCellDeletedIdentityEntity.create(
				run,
				entry,
				ImportedCellDeletedIdentityEntity.RUN,
				runIdentity = run,
				contentChecksum = content,
				deletionScopeDigest = scope,
				runStartTimeMs = 30L,
				runEndTimeMs = 40L,
			),
			ImportedCellDeletedIdentityEntity.create(
				observation,
				entry,
				ImportedCellDeletedIdentityEntity.OBSERVATION,
				runIdentity = run,
				contentChecksum = content,
				observationOrdinal = 0,
			),
			ImportedCellDeletedIdentityEntity.create(
				scope,
				entry,
				ImportedCellDeletedIdentityEntity.DELETION_SCOPE,
				runIdentity = run,
				deletionScopeDigest = scope,
			),
		)
		val receipt = ImportedCellEntryDeletionReceiptEntity.create(
			entryDeletion = deletion,
			deletedContentChecksum = content,
			sessionMode = "MANUAL",
			subscriptionGrouping = "UNKNOWN",
			startTimeMs = 30L,
			endTimeMs = 40L,
			receivedAtMs = 50L,
			retainedFromMs = null,
			revisionCount = 1,
			receiptCount = 1,
			runCount = 1,
			observationCount = 1,
			protectedIdentities = protected,
		)
		val sourceFence = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			scope,
			1L,
			EPOCH,
			deletedAtMs,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(sourceFence) shouldNotBe -1L
		database.importedCellDao().insertDeletionGeneration(runDeletion)
		database.importedCellDao().insertEntryDeletion(deletion)
		database.importedCellDao().insertEntryDeletionReceipt(receipt)
		database.importedCellDao().insertDeletedIdentities(protected)
		return ImportedCellAuthorityFixture(entry, run, scope, protected.size)
	}

	private suspend fun seedCapturedCellDeletionAuthority(): CapturedCellAuthorityFixture {
		val logicalTrackingId = "captured-cell-entry"
		val serviceRunId = "captured-cell-run"
		val deletedAtMs = 300L
		val run = CellCapturedDeletedRunEntity.create(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sessionSegmentId = 31L,
			startTimeMs = 60L,
			endTimeMs = 70L,
			collectedDataEpoch = EPOCH,
			deletedAtMs = deletedAtMs,
		)
		val receipt = CellCapturedEntryDeletionReceiptEntity.create(
			logicalTrackingId = logicalTrackingId,
			entryIdentity = digest('c'),
			collectedDataEpoch = EPOCH,
			runFootprints = listOf(run),
			deletedAtMs = deletedAtMs,
		)
		val sourceFence = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			run.scopeIdentityDigest,
			1L,
			EPOCH,
			deletedAtMs,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(sourceFence) shouldNotBe -1L
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId,
				serviceRunId,
				EPOCH,
				1L,
				deletedAtMs,
			),
		)
		database.cellCapturedFactDao().insertEntryDeletionReceipt(receipt)
		database.cellCapturedFactDao().insertDeletedRuns(listOf(run))
		return CapturedCellAuthorityFixture(logicalTrackingId, serviceRunId, run.scopeIdentityDigest)
	}

	private suspend fun seedAmbientWifiPayload(): String {
		val archiveId = AmbientWifiAuthorityIntegrity.digest("archive", "full-clear")
		val factId = AmbientWifiAuthorityIntegrity.digest("fact", "full-clear")
		val draft = ImportedAmbientWifiFactEntity(
			archiveId = archiveId,
			factId = factId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			contentChecksum = "0".repeat(64),
			portableEffectChecksum = "0".repeat(64),
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = 1L,
			observedTimeMs = 2L,
			latestPossibleTimeMs = 3L,
			storedZoneId = "UTC",
			structuralEpochDay = 0L,
			coverageCompleteness = "UNVERIFIABLE",
			observationCount = 1,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 0,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -50,
			weakestSignalDbm = -50,
			meanSignalDbm = -50.0,
			retentionPolicyId = "privacy:wifi:import:v1",
			retentionApprovalRevision = 1L,
			collectedDataEpoch = EPOCH,
			importDeletionGeneration = 0L,
			receivedAtMs = 4L,
		)
		val withEffect = draft.copy(
			portableEffectChecksum = AmbientWifiFactIntegrity.importedFactEffectChecksum(draft),
		)
		database.ambientWifiFactDao().insertImportedFact(
			withEffect.copy(
				contentChecksum = AmbientWifiFactIntegrity.importedFactContentChecksum(withEffect),
			),
		)
		return archiveId
	}

	private fun seedRuntimeSettlementRows() {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO provider_registration_generation(" +
				"source_kind, registration_generation, source_instance_id, owner_scope, " +
				"clock_domain_id, physical_configuration_fingerprint, collected_data_epoch, " +
				"provider_residency, provider_process_incarnation_id, status, reserved_at_ms, " +
				"reserved_elapsed_realtime_nanos, accepted_at_ms, accepted_elapsed_realtime_nanos, " +
				"retired_at_ms, retired_elapsed_realtime_nanos, failure_code, " +
				"capture_callback_barrier_authorization_revision) VALUES (" +
				"5, 1, 'wifi', 'source-broker:5', 'boot', 'fingerprint', $EPOCH, " +
				"'SYSTEM_REARMABLE', NULL, 'RETIRED', 1, 1, NULL, NULL, 1, 1, NULL, 1)",
		)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO source_capture_admission_barrier(" +
				"source_kind, registration_generation, source_instance_id, " +
				"through_authorization_revision, last_admission_ordinal, last_source_sequence, " +
				"sealed_elapsed_realtime_nanos, sealed_at_ms) VALUES (5, 1, 'wifi', 1, 0, 0, 1, 1)",
		)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO source_run_retirement(" +
				"logical_tracking_id, service_run_id, source_kind, source_instance_id, " +
				"registration_generation, action_id, attempt_count, lease_generation, " +
				"cutoff_elapsed_realtime_nanos, cutoff_wall_time_ms, state, updated_at_ms) " +
				"VALUES ('entry', 'run', 5, 'wifi', 1, 'action', 1, 1, 1, 1, 'REQUESTED', 1)",
		)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO source_authorization(" +
				"source_kind, registration_generation, authorization_revision, member_id, " +
				"authorization_fingerprint, purpose_eligibility_mask, demand_id, consumer_id, " +
				"purpose, source_policy_revision, consent_epoch, persistence_eligible, " +
				"effective_boot_id, effective_elapsed_realtime_nanos, effective_wall_time_ms, " +
				"logical_tracking_id, service_run_id, manifest_revision, " +
				"lifecycle_lease_generation) VALUES (" +
				"5, 1, 1, '__DENY_ALL__', 'deny', 0, NULL, NULL, NULL, NULL, NULL, 0, " +
				"'boot', 1, 1, NULL, NULL, NULL, NULL)",
		)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER require_runtime_dependents_deleted_before_authorization " +
				"BEFORE DELETE ON source_authorization " +
				"WHEN EXISTS(SELECT 1 FROM source_capture_admission_barrier) " +
				"OR EXISTS(SELECT 1 FROM source_run_retirement) " +
				"BEGIN SELECT RAISE(ABORT, " +
				"'runtime dependents survived authorization delete'); END",
		)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER require_runtime_dependents_deleted_before_registration " +
				"BEFORE DELETE ON provider_registration_generation " +
				"WHEN EXISTS(SELECT 1 FROM source_capture_admission_barrier " +
				"WHERE source_kind = OLD.source_kind " +
				"AND registration_generation = OLD.registration_generation) " +
				"OR EXISTS(SELECT 1 FROM source_run_retirement " +
				"WHERE source_kind = OLD.source_kind " +
				"AND source_instance_id = OLD.source_instance_id " +
				"AND registration_generation = OLD.registration_generation) " +
				"BEGIN SELECT RAISE(ABORT, 'runtime dependents survived registration delete'); END",
		)
	}

	private suspend fun seedStepsFence(): String {
		val scope = digest('d')
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createForOriginalRunDigest(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				scope,
				2L,
				EPOCH,
				50L,
			),
		) shouldNotBe -1L
		return scope
	}

	private suspend fun assertStepsFence(scope: String, epoch: Long) {
		requireNotNull(database.sourceDeletionFenceDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scope,
		)).also { fence ->
			fence.collectedDataEpoch shouldBe epoch
			fence.fenceGeneration shouldBe 2L
			fence.deletedAtMs shouldBe 50L
		}
	}

	private suspend fun assertWifiAuthority(
		fixture: WifiAuthorityFixture,
		epoch: Long,
	) {
		if (fixture.origin == WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED) {
			database.importedWifiDao().entryDeletion(fixture.entry)?.collectedDataEpoch shouldBe epoch
			database.importedWifiDao().deletionGenerationsByRun(
				listOf(fixture.run),
			).single().collectedDataEpoch shouldBe epoch
		}
		database.importedWifiDao().selectedDeletionReceipt(
			fixture.entry,
			fixture.origin,
		).also { receipt ->
			receipt?.collectedDataEpoch shouldBe epoch
			receipt?.retainedFromMs shouldBe RETAINED_FROM_MS
		}
		database.importedWifiDao().selectedDeletionProtectedIdentities(
			fixture.entry,
			fixture.origin,
			fixture.protectedCount + 1,
		).all { it.collectedDataEpoch == epoch } shouldBe true
		database.sourceDeletionFenceDao().get(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			fixture.scope,
		)?.collectedDataEpoch shouldBe epoch
	}

	private suspend fun assertImportedCellAuthority(
		fixture: ImportedCellAuthorityFixture,
		epoch: Long,
	) {
		database.importedCellDao().entryDeletion(fixture.entry)?.collectedDataEpoch shouldBe epoch
		database.importedCellDao().deletionGenerationOwners(
			listOf(fixture.run),
			2,
		).single().collectedDataEpoch shouldBe epoch
		database.importedCellDao().entryDeletionReceipt(
			fixture.entry,
		).also { receipt ->
			receipt?.collectedDataEpoch shouldBe epoch
			receipt?.retainedFromMs shouldBe RETAINED_FROM_MS
		}
		database.importedCellDao().deletedIdentitiesForEntry(
			fixture.entry,
			fixture.protectedCount + 1,
		).size shouldBe fixture.protectedCount
		database.sourceDeletionFenceDao().get(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			fixture.scope,
		)?.collectedDataEpoch shouldBe epoch
	}

	private suspend fun assertCapturedCellAuthority(
		fixture: CapturedCellAuthorityFixture,
		epoch: Long,
	) {
		database.cellCapturedFactDao().entryDeletionReceipt(
			fixture.logicalTrackingId,
		).also { receipt ->
			receipt?.collectedDataEpoch shouldBe epoch
			receipt?.retainedFromMs shouldBe RETAINED_FROM_MS
		}
		database.cellCapturedFactDao().deletedRuns(
			fixture.logicalTrackingId,
			2,
		).single().collectedDataEpoch shouldBe epoch
		database.cellCapturedFactDao().deletionGeneration(
			fixture.logicalTrackingId,
			fixture.serviceRunId,
		)?.collectedDataEpoch shouldBe epoch
		database.sourceDeletionFenceDao().get(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			fixture.scope,
		)?.collectedDataEpoch shouldBe epoch
	}

	private suspend fun assertEvidenceState(
		epoch: Long,
		revision: Long,
		retainedFromMs: Long?,
	) {
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe epoch
			state.revision shouldBe revision
			state.retainedFromMs shouldBe retainedFromMs
		}
	}

	private fun rowCount(table: String): Long =
		database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun openDatabase(): AppDatabase = AppDatabase.fileBuilder(
		context,
		DATABASE_NAME,
	)
		.allowMainThreadQueries()
		.build()

	private fun reopen() {
		database.close()
		database = openDatabase()
	}

	private fun digest(character: Char): String = character.toString().repeat(64)

	private fun opaque(character: Char): String = "sha256:${digest(character)}"

	private data class WifiAuthorityFixture(
		val entry: String,
		val run: String,
		val scope: String,
		val protectedCount: Int,
		val origin: String,
	)

	private data class ImportedCellAuthorityFixture(
		val entry: String,
		val run: String,
		val scope: String,
		val protectedCount: Int,
	)

	private data class CapturedCellAuthorityFixture(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val scope: String,
	)

	private data class StepsCountDomainFixture(
		val receipt: StepsCountDomainReceiptEntity,
		val boundOwner: StepsCountDomainOwnerRevisionEntity,
		val terminalOwner: StepsCountDomainOwnerRevisionEntity,
	)

	private companion object {
		const val DATABASE_NAME = "tracking-source-full-clear-assembly"
		const val EPOCH = 4L
		const val RETAINED_FROM_MS = 900L
	}
}
