package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomImportPortableCapturedCellTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imports complete replacement membership without creating live Cell authority`() = runTest {
		seedEvidence()
		val entry = entry(
			runDefinitions = listOf(
				RunDefinition("retained", 10L, 20L, listOf(observation("one"))),
				RunDefinition(
					"gap", 20L, 30L, emptyList(),
					availability = PortableCellRunAvailability.NO_RETAINED_OBSERVATION,
					completeness = PortableCellAcquisitionCompleteness.PARTIAL,
				),
				RunDefinition(
					"not-captured", 30L, 40L, emptyList(),
					coverage = PortableCellCaptureCoverage.NOT_CAPTURED,
					availability = PortableCellRunAvailability.NOT_CAPTURED,
					completeness = PortableCellAcquisitionCompleteness.UNKNOWN,
				),
			),
		)

		importer().importEntry(request(entry)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 3, 1)

		val lineage = database.importedCellDao()
		lineage.boundedEntryRevisions(entry.identity.value).single().contentChecksum shouldBe
			entry.contentChecksum.value
		lineage.allRunsForAdmission(entry.identity.value).map { it.availability } shouldBe
			listOf("RETAINED", "NO_RETAINED_OBSERVATION", "NOT_CAPTURED")
		lineage.allObservationsForAdmission(entry.identity.value).single().storedZoneId shouldBe "UTC"
		listOf(
			"source_event_wal", "source_demand", "provider_registration_generation",
			"source_authorization", "session_manifest_version", "session_manifest_source",
			"cell_captured_fact_revision", "cell_captured_fact_cursor",
		).forEach { table -> rowCount(table) shouldBe 0L }
	}

	@Test
	fun `exact replay and alternate receipt are deterministic while receipt reuse conflicts`() = runTest {
		seedEvidence()
		val entry = entry()
		val original = request(entry)
		importer().importEntry(original) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		importer().importEntry(original) shouldBe ImportPortableCapturedCellResult.Duplicate(1L)
		importer().importEntry(
			original.copy(receipt = original.receipt.copy(jobId = "alternate", entryKey = "entry-2")),
		) shouldBe ImportPortableCapturedCellResult.Duplicate(1L)
		importer().importEntry(
			original.copy(receipt = original.receipt.copy(sourceName = "changed.trackercell")),
		) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.RECEIPT_CONFLICT,
		)
		database.importedCellDao().receiptsForAdmission(entry.identity.value).size shouldBe 2
	}

	@Test
	fun `immediate semantic correction appends one local revision`() = runTest {
		seedEvidence()
		val first = entry(observation = observation("one"))
		val correctedObservation = observation("one", semanticRevision = 2L, qualityFlags = 7L)
		val corrected = entry(observation = correctedObservation)
		importer().importEntry(request(first)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)

		importer().importEntry(
			request(corrected, jobId = "correction", entryKey = "entry-2"),
		) shouldBe ImportPortableCapturedCellResult.Applied(2L, 1, 1)

		database.importedCellDao().boundedEntryRevisions(first.identity.value)
			.map { it.importRevision } shouldBe listOf(1L, 2L)
	}

	@Test
	fun `correction cannot add an observation or resurrect a retained-away identity`() = runTest {
		seedEvidence()
		val first = entry(observation = observation("one"))
		importer().importEntry(request(first)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val added = entry(
			runDefinitions = listOf(
				RunDefinition(
					"run", 10L, 20L,
					listOf(observation("one", 2L), observation("two")),
				),
			),
		)

		importer().importEntry(request(added, "correction", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Blocked(PortableCellImportBlockedReason.CORRECTION_CONFLICT)
	}

	@Test
	fun `retention correction can remove an aggregate dependent and replay cannot resurrect it`() = runTest {
		seedEvidence()
		val owner = observation("owner")
		val dependent = observation(
			"dependent",
			aggregateOwnerIdentity = owner.identity,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
		)
		val original = entry(
			runDefinitions = listOf(RunDefinition("run", 10L, 20L, listOf(owner, dependent))),
		)
		val retained = entry(
			runDefinitions = listOf(
				RunDefinition("run", 10L, 20L, listOf(owner), retentionLoss = true),
			),
		)
		importer().importEntry(request(original)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 2)

		importer().importEntry(request(retained, "correction", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Applied(2L, 1, 1)
		importer().importEntry(request(original, "replay", "entry-3")) shouldBe
			ImportPortableCapturedCellResult.Duplicate(1L)
		database.importedCellDao().allObservationsForAdmission(original.identity.value)
			.filter { it.entryImportRevision == 2L }
			.map { it.identity } shouldBe listOf(owner.identity.value)
	}

	@Test
	fun `correction cannot retarget immutable aggregate ownership`() = runTest {
		seedEvidence()
		val firstOwner = observation("first-owner")
		val secondOwner = observation("second-owner")
		val dependent = observation(
			"dependent",
			aggregateOwnerIdentity = firstOwner.identity,
			aggregateOwnerSemanticRevision = firstOwner.semanticRevision,
		)
		val original = entry(
			runDefinitions = listOf(
				RunDefinition("run", 10L, 20L, listOf(firstOwner, secondOwner, dependent)),
			),
		)
		val retargeted = entry(
			runDefinitions = listOf(
				RunDefinition(
					"run",
					10L,
					20L,
					listOf(
						firstOwner,
						secondOwner,
						observation(
							"dependent",
							semanticRevision = 2L,
							aggregateOwnerIdentity = secondOwner.identity,
							aggregateOwnerSemanticRevision = secondOwner.semanticRevision,
						),
					),
				),
			),
		)
		importer().importEntry(request(original)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 3)

		importer().importEntry(request(retargeted, "correction", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Blocked(PortableCellImportBlockedReason.CORRECTION_CONFLICT)
	}

	@Test
	fun `epoch and uncertainty-aware retention are rechecked in the transaction`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, retainedFromMs = 101L),
		)
		val oldCoverage = entry(observation = observation("one", coverageStartTimeMs = 100L))
		importer().importEntry(request(oldCoverage, expectedEpoch = 3L)) shouldBe
			ImportPortableCapturedCellResult.Blocked(
				PortableCellImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		importer().importEntry(request(oldCoverage, expectedEpoch = 4L)) shouldBe
			ImportPortableCapturedCellResult.Blocked(PortableCellImportBlockedReason.RETENTION_BOUNDARY)
	}

	@Test
	fun `retention ignores old structural zero and not-captured replacement members`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = EPOCH, retainedFromMs = 100L),
		)
		val value = entry(
			observation = observation("one", coverageStartTimeMs = 100L),
			runDefinitions = listOf(
				RunDefinition("retained", 100L, 120L, listOf(observation("one", coverageStartTimeMs = 100L))),
				RunDefinition(
					"old-gap", 1L, 9L, emptyList(),
					availability = PortableCellRunAvailability.NO_RETAINED_OBSERVATION,
					completeness = PortableCellAcquisitionCompleteness.PARTIAL,
				),
				RunDefinition(
					"old-not-captured", 9L, 10L, emptyList(),
					coverage = PortableCellCaptureCoverage.NOT_CAPTURED,
					availability = PortableCellRunAvailability.NOT_CAPTURED,
					completeness = PortableCellAcquisitionCompleteness.UNKNOWN,
				),
			),
		)

		importer().importEntry(request(value)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 3, 1)
	}

	@Test
	fun `current source deletion fence rejects the exact imported run scope`() = runTest {
		seedEvidence()
		val entry = entry()
		val run = entry.runs.single()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = run.deletionScopeDigest.value,
				fenceGeneration = 1L,
				collectedDataEpoch = EPOCH,
				deletedAtMs = 500L,
				effectChecksum = SourceDeletionFenceEntity.createLogicalServiceRun(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					LOGICAL_LOCAL,
					"run",
					1L,
					EPOCH,
					500L,
				).effectChecksum,
			),
		)
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_LOCAL,
				serviceRunId = "run",
				collectedDataEpoch = EPOCH,
				generation = 1L,
				updatedAtMs = 500L,
			),
		)

		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.DELETED_SCOPE,
		)
	}

	@Test
	fun `source fence and Cell generation disagreement fails closed`() = runTest {
		seedEvidence()
		val entry = entry()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				LOGICAL_LOCAL,
				"run",
				2L,
				EPOCH,
				500L,
			),
		)
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(LOGICAL_LOCAL, "run", EPOCH, 1L, 500L),
		)

		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Unverifiable(
			PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `capture scope reused by another purpose is an opaque identity conflict`() = runTest {
		seedEvidence()
		val entry = entry()
		val scope = entry.runs.single().deletionScopeDigest.value
		val purpose = SessionManifestPurposeCode.CONTROL
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				purpose = purpose,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = scope,
				fenceGeneration = 1L,
				collectedDataEpoch = EPOCH,
				deletedAtMs = 500L,
				effectChecksum = sourceFenceChecksum(purpose, scope),
			),
		)

		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
	}

	@Test
	fun `imported run generation prevents resurrection before any hierarchy exists`() = runTest {
		seedEvidence()
		val entry = entry()
		val run = entry.runs.single()
		database.importedCellDao().insertDeletionGeneration(
			ImportedCellDeletionGenerationEntity.create(
				run.identity.value,
				entry.identity.value,
				run.deletionScopeDigest.value,
				EPOCH,
				1L,
				500L,
			),
		)
		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.DELETED_RUN,
		)
	}

	@Test
	fun `imported entry tombstone prevents resurrection before any hierarchy exists`() = runTest {
		seedEvidence()
		val entry = entry()
		database.importedCellDao().insertEntryDeletion(
			ImportedCellEntryDeletionEntity.create(
				entry.identity.value,
				EPOCH,
				1L,
				500L,
			),
		)

		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.DELETED_ENTRY,
		)
	}

	@Test
	fun `opaque identity and deletion scope namespaces cannot alias`() = runTest {
		seedEvidence()
		val entry = entry()
		val scope = entry.runs.single().deletionScopeDigest.value
		database.importedCellDao().insertEntryRevision(
			ImportedCellEntryRevisionEntity(
				identity = scope,
				importRevision = 1L,
				supersedesImportRevision = null,
				contentChecksum = "a".repeat(64),
				sourceFormat = CellCapturedPortableFormatV1.FORMAT,
				sourceSchemaVersion = 1,
				sessionMode = "MANUAL",
				startTimeMs = 1L,
				endTimeMs = 2L,
				subscriptionGrouping = "UNKNOWN",
				collectedDataEpoch = EPOCH,
				importJobId = "collision",
				importEntryKey = "entry",
				importSourceName = "collision.trackercell",
				receivedAtMs = 3L,
			),
		)

		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
	}

	@Test
	fun `missing aggregate owner and noncanonical latest time fail before Room writes`() = runTest {
		seedEvidence()
		val missingOwner = observation(
			"dependent",
			aggregateOwnerIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION,
				"absent",
			),
			aggregateOwnerSemanticRevision = 1L,
		)
		importer().importEntry(request(entry(observation = missingOwner))) shouldBe
			ImportPortableCapturedCellResult.Unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)

		val noncanonical = observation("one", latestPossibleTimeMs = 200L)
		importer().importEntry(request(entry(observation = noncanonical))) shouldBe
			ImportPortableCapturedCellResult.Unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)
		rowCount("imported_cell_entry_revision") shouldBe 0L
	}

	@Test
	fun `aggregate owner must be a same-run latest root with the same identity-free aggregate`() = runTest {
		seedEvidence()
		val owner = observation("owner")
		val dependent = observation(
			"dependent",
			aggregateOwnerIdentity = owner.identity,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
		)
		val valid = entry(
			runDefinitions = listOf(RunDefinition("run", 10L, 20L, listOf(owner, dependent))),
		)
		importer().importEntry(request(valid)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 2)

		val crossRun = entry(
			logicalLocal = "other",
			runDefinitions = listOf(
				RunDefinition("owner-run", 10L, 20L, listOf(owner)),
				RunDefinition("dependent-run", 20L, 30L, listOf(dependent)),
			),
		)
		importer().importEntry(request(crossRun, "other", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)
	}

	@Test
	fun `stored hierarchy checksum corruption fails before receipt replay`() = runTest {
		seedEvidence()
		val entry = entry()
		val request = request(entry)
		importer().importEntry(request) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_observation SET quality_flags = quality_flags + 1",
		)

		importer().importEntry(request) shouldBe ImportPortableCapturedCellResult.Unverifiable(
			PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `mutable decoded collection exceeding the run cap is typed before Room`() = runTest {
		seedEvidence()
		val original = entry()
		val mutableRuns = original.runs.toMutableList()
		val mutableEntry = original.copy(runs = mutableRuns)
		repeat(CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) { mutableRuns += original.runs.first() }

		importer().importEntry(request(mutableEntry)) shouldBe ImportPortableCapturedCellResult.Unverifiable(
			PortableCellImportUnverifiableReason.RUN_OVERFLOW,
		)
		rowCount("imported_cell_entry_revision") shouldBe 0L
	}

	@Test
	fun `cap plus one live owner snapshot fails closed instead of admitting an incomplete relation`() = runTest {
		seedEvidence()
		repeat(ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1) { index ->
			database.sourceSessionDao().insertSession(localSession("local-$index"))
		}

		importer().importEntry(request(entry())) shouldBe ImportPortableCapturedCellResult.Unverifiable(
			PortableCellImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		rowCount("imported_cell_entry_revision") shouldBe 0L
		rowCount("imported_cell_receipt") shouldBe 0L
	}

	@Test
	fun `same local Cell origin is compatible while a cross-kind live identity is rejected`() = runTest {
		seedEvidence()
		insertLocalSession(LOGICAL_LOCAL, "run")
		val exact = entry()
		importer().importEntry(request(exact)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)

		val foreignEntry = entry(
			logicalLocal = "foreign",
			runIdentityOverride = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				LOGICAL_LOCAL,
			),
		)
		importer().importEntry(request(foreignEntry, "other", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Blocked(
				PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
	}

	@Test
	fun `cancellation between child writes rolls back the whole admission`() = runTest {
		seedEvidence()
		val cancelling = RoomImportPortableCapturedCell(
			database,
			Dispatchers.Unconfined,
		) { checkpoint ->
			if (checkpoint == PortableCellImportWriteCheckpoint.OBSERVATION_INSERTED) {
				throw CancellationException("cancel import")
			}
		}

		shouldThrow<CancellationException> { cancelling.importEntry(request(entry())) }
		rowCount("imported_cell_entry_revision") shouldBe 0L
		rowCount("imported_cell_run") shouldBe 0L
		rowCount("imported_cell_observation") shouldBe 0L
		rowCount("imported_cell_receipt") shouldBe 0L
	}

	@Test
	fun `closed database is a typed retryable storage failure`() = runTest {
		seedEvidence()
		database.close()
		importer().importEntry(request(entry())) shouldBe ImportPortableCapturedCellResult.RetryableFailure(
			PortableCellImportRetryableReason.STORAGE_UNAVAILABLE,
		)
	}

	@Test
	fun `full collected-data clear removes imported payload and imported privacy markers`() = runTest {
		seedEvidence()
		val entry = entry()
		importer().importEntry(request(entry)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val otherRun = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.PHYSICAL_RUN, "deleted")
		database.importedCellDao().insertDeletionGeneration(
			ImportedCellDeletionGenerationEntity.create(
				otherRun.value,
				PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, "deleted").value,
				PortableCellDeletionScopeDigest.derive("deleted", "deleted").value,
				EPOCH,
				1L,
				500L,
			),
		)
		database.importedCellDao().insertEntryDeletion(
			ImportedCellEntryDeletionEntity.create(
				PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.LOGICAL_ENTRY,
					"entry-deleted",
				).value,
				EPOCH,
				1L,
				500L,
			),
		)

		AppDatabase.deleteAllCollectedData(database, EPOCH + 1L, null, 600L)

		listOf(
			"imported_cell_entry_revision", "imported_cell_receipt", "imported_cell_run",
			"imported_cell_observation", "imported_cell_entry_deletion",
			"imported_cell_deletion_generation",
		).forEach { rowCount(it) shouldBe 0L }
	}

	private suspend fun seedEvidence() {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	private fun importer() = RoomImportPortableCapturedCell(database, Dispatchers.Unconfined)

	private fun request(
		entry: PortableCapturedCellEntryV1,
		jobId: String = "job",
		entryKey: String = "entry",
		expectedEpoch: Long = EPOCH,
	) = ImportPortableCapturedCellRequest(
		entry,
		PortableCellImportReceipt(jobId, entryKey, "backup.trackercell", 400L),
		expectedEpoch,
	)

	private suspend fun insertLocalSession(logicalTrackingId: String, serviceRunId: String) {
		database.sourceSessionDao().insertSession(localSession(logicalTrackingId))
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 10L,
				startedElapsedNanos = 10L,
				completedAtMs = 20L,
				completionReason = "STOPPED",
			),
		)
	}

	private fun localSession(logicalTrackingId: String) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalTrackingId,
		state = "FINALIZED",
		lifecycleRevision = 1L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL",
		clockDomainId = "boot",
		startedAtMs = 10L,
		startedElapsedNanos = 10L,
		cutoffAtMs = 20L,
		cutoffElapsedNanos = 20L,
		completedAtMs = 20L,
		finalAdmissionOrdinal = 0L,
		failureCode = null,
	)

	private fun rowCount(table: String): Long = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun sourceFenceChecksum(purpose: String, scope: String): String {
		val values = listOf(
			"tracker-source-deletion-fence-v1",
			SourceDestinationOwnerEntity.SOURCE_CELL.toString(),
			purpose,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scope,
			"1",
			EPOCH.toString(),
			"500",
		)
		val canonical = values.joinToString(separator = "") { "${it.length}:$it" }
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private companion object {
		const val EPOCH = 3L
		const val LOGICAL_LOCAL = "logical"
	}
}

private data class RunDefinition(
	val localId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val observations: List<PortableCapturedCellObservationV1>,
	val coverage: PortableCellCaptureCoverage = PortableCellCaptureCoverage.WHOLE_RUN,
	val availability: PortableCellRunAvailability = PortableCellRunAvailability.RETAINED,
	val completeness: PortableCellAcquisitionCompleteness = PortableCellAcquisitionCompleteness.COMPLETE,
	val retentionLoss: Boolean = false,
)

private fun entry(
	logicalLocal: String = "logical",
	observation: PortableCapturedCellObservationV1 = observation("one"),
	runDefinitions: List<RunDefinition> = listOf(
		RunDefinition("run", 10L, 20L, listOf(observation)),
	),
	runIdentityOverride: PortableCellOpaqueIdentity? = null,
): PortableCapturedCellEntryV1 {
	val runs = runDefinitions.mapIndexed { index, definition ->
		val identity = runIdentityOverride?.takeIf { index == 0 }
			?: PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.PHYSICAL_RUN, definition.localId)
		val orderedObservations = definition.observations.sortedWith(compareBy(
			PortableCapturedCellObservationV1::coverageStartTimeMs,
			PortableCapturedCellObservationV1::observedTimeMs,
			{ it.identity.value },
		))
		val payload = PortableCellRunPayload(
			identity = identity,
			deletionScopeDigest = PortableCellDeletionScopeDigest.derive(logicalLocal, definition.localId),
			startTimeMs = definition.startTimeMs,
			endTimeMs = definition.endTimeMs,
			captureCoverage = definition.coverage,
			availability = definition.availability,
			acquisitionCompleteness = definition.completeness,
			retentionLoss = definition.retentionLoss,
			subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
			observations = orderedObservations,
		)
		PortableCapturedCellRunV1(
			payload.identity,
			payload.deletionScopeDigest,
			CellCapturedPortableIntegrity.runChecksum(payload),
			payload.startTimeMs,
			payload.endTimeMs,
			payload.captureCoverage,
			payload.availability,
			payload.acquisitionCompleteness,
			payload.retentionLoss,
			payload.subscriptionGrouping,
			payload.observations,
		)
	}.sortedWith(compareBy(PortableCapturedCellRunV1::startTimeMs, { it.identity.value }))
	val payload = PortableCellEntryPayload(
		identity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, logicalLocal),
		sessionMode = PortableCellSessionMode.MANUAL,
		startTimeMs = runs.minOf { it.startTimeMs },
		endTimeMs = runs.maxOf { it.endTimeMs },
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		runs = runs,
	)
	return PortableCapturedCellEntryV1(
		identity = payload.identity,
		contentChecksum = CellCapturedPortableIntegrity.entryChecksum(payload),
		sessionMode = payload.sessionMode,
		startTimeMs = payload.startTimeMs,
		endTimeMs = payload.endTimeMs,
		subscriptionGrouping = payload.subscriptionGrouping,
		runs = payload.runs,
	)
}

@Suppress("LongParameterList")
private fun observation(
	localId: String,
	semanticRevision: Long = 1L,
	coverageStartTimeMs: Long = 100L,
	latestPossibleTimeMs: Long = 112L,
	qualityFlags: Long = 0L,
	aggregateOwnerIdentity: PortableCellOpaqueIdentity? = null,
	aggregateOwnerSemanticRevision: Long? = null,
): PortableCapturedCellObservationV1 {
	val payload = PortableCellObservationPayload(
		identity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, localId),
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = semanticRevision.takeIf { it > 1L }?.minus(1L),
		aggregateOwnerIdentity = aggregateOwnerIdentity,
		aggregateOwnerSemanticRevision = aggregateOwnerSemanticRevision,
		coverageStartTimeMs = coverageStartTimeMs,
		observedTimeMs = 110L,
		latestPossibleTimeMs = latestPossibleTimeMs,
		wallTimeUncertaintyMs = 2L,
		storedZoneId = "UTC",
		childCompleteness = PortableCellChildCompleteness.COMPLETE,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		submittedChildCount = 1,
		acceptedChildCount = 1,
		staleChildCount = 0,
		futureTimeChildCount = 0,
		missingTimeChildCount = 0,
		clockUnverifiableChildCount = 0,
		authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0,
		observationCount = 1,
		registeredObservationCount = 1,
		gsmCount = 0,
		cdmaCount = 0,
		wcdmaCount = 0,
		tdscdmaCount = 0,
		lteCount = 1,
		nrCount = 0,
		qualityUnknownCount = 0,
		qualityNoneOrUnknownCount = 0,
		qualityPoorCount = 0,
		qualityModerateCount = 0,
		qualityGoodCount = 1,
		qualityGreatCount = 0,
		weakObservationCount = 0,
		knownQualityObservationCount = 1,
		allKnownQualityIsWeak = false,
		qualityFlags = qualityFlags,
		qualityConfidence = 1.0,
	)
	return PortableCapturedCellObservationV1(
		payload.identity, payload.semanticRevision, payload.supersedesSemanticRevision,
		payload.aggregateOwnerIdentity, payload.aggregateOwnerSemanticRevision,
		CellCapturedPortableIntegrity.observationChecksum(payload), payload.coverageStartTimeMs,
		payload.observedTimeMs, payload.latestPossibleTimeMs, payload.wallTimeUncertaintyMs,
		payload.storedZoneId, payload.childCompleteness, payload.subscriptionGrouping,
		payload.submittedChildCount, payload.acceptedChildCount, payload.staleChildCount,
		payload.futureTimeChildCount, payload.missingTimeChildCount,
		payload.clockUnverifiableChildCount, payload.authorityMismatchChildCount,
		payload.unsupportedTechnologyChildCount, payload.observationCount,
		payload.registeredObservationCount, payload.gsmCount, payload.cdmaCount,
		payload.wcdmaCount, payload.tdscdmaCount, payload.lteCount, payload.nrCount,
		payload.qualityUnknownCount, payload.qualityNoneOrUnknownCount, payload.qualityPoorCount,
		payload.qualityModerateCount, payload.qualityGoodCount, payload.qualityGreatCount,
		payload.weakObservationCount, payload.knownQualityObservationCount,
		payload.allKnownQualityIsWeak, payload.qualityFlags, payload.qualityConfidence,
	)
}
