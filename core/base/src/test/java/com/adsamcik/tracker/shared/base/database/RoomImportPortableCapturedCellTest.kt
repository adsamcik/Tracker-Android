package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
	fun `semantic revision bump without a material Cell value change is rejected`() = runTest {
		seedEvidence()
		val first = entry(observation = observation("one"))
		val noOp = entry(observation = observation("one", semanticRevision = 2L))
		importer().importEntry(request(first)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)

		importer().importEntry(request(noOp, "no-op", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Blocked(
				PortableCellImportBlockedReason.CORRECTION_CONFLICT,
			)
		database.importedCellDao().boundedEntryRevisions(first.identity.value).size shouldBe 1
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
	fun `canonical Cell v1 coverage cannot conceal the observation uncertainty lower bound`() {
		val payload = observationPayload("one")
		payload.copy(coverageStartTimeMs = 108L).toPortableValue().coverageStartTimeMs shouldBe 108L
		shouldThrow<IllegalArgumentException> {
			payload.copy(coverageStartTimeMs = 110L).toPortableValue()
		}
		shouldThrow<IllegalArgumentException> {
			payload.copy(coverageStartTimeMs = 0L, observedTimeMs = 1L,
				wallTimeUncertaintyMs = 2L, latestPossibleTimeMs = 3L).toPortableValue()
		}
		shouldThrow<IllegalArgumentException> {
			payload.copy(coverageStartTimeMs = 0L, observedTimeMs = 0L,
				wallTimeUncertaintyMs = Long.MAX_VALUE, latestPossibleTimeMs = Long.MAX_VALUE).toPortableValue()
		}
	}

	@Test
	fun `earliest possible Cell observation before the floor is blocked with exact boundary allowed`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH, retainedFromMs = 110L))
		val beforeFloor = observationPayload("one").copy(coverageStartTimeMs = 108L).toPortableValue()
		importer().importEntry(request(entry(observation = beforeFloor))) shouldBe
			ImportPortableCapturedCellResult.Blocked(PortableCellImportBlockedReason.RETENTION_BOUNDARY)
		rowCount("imported_cell_entry_revision") shouldBe 0L

		val atFloor = observationPayload("one").copy(coverageStartTimeMs = 110L,
			observedTimeMs = 112L, latestPossibleTimeMs = 114L).toPortableValue()
		importer().importEntry(request(entry(observation = atFloor))) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
	}

	@Test
	fun `incoming uncertainty upper overflow is typed before imported Room writes`() = runTest {
		seedEvidence()
		val overflowing = observationPayload("one").copy(observedTimeMs = Long.MAX_VALUE,
			wallTimeUncertaintyMs = 1L, latestPossibleTimeMs = Long.MAX_VALUE).toPortableValue()
		importer().importEntry(request(entry(observation = overflowing))) shouldBe
			ImportPortableCapturedCellResult.Unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)
		rowCount("imported_cell_entry_revision") shouldBe 0L
		rowCount("imported_cell_receipt") shouldBe 0L
	}

	@Test
	fun `canonical Cell v1 known and weak quality summaries equal their exact buckets`() {
		val payload = observationPayload("one")
		shouldThrow<IllegalArgumentException> {
			payload.copy(knownQualityObservationCount = 0).toPortableValue()
		}
		shouldThrow<IllegalArgumentException> {
			payload.copy(weakObservationCount = 1, allKnownQualityIsWeak = true).toPortableValue()
		}
		val allBuckets = payload.copy(submittedChildCount = 6, acceptedChildCount = 6,
			observationCount = 6, registeredObservationCount = 6, lteCount = 6,
			qualityUnknownCount = 1, qualityNoneOrUnknownCount = 1, qualityPoorCount = 1,
			qualityModerateCount = 1, qualityGoodCount = 1, qualityGreatCount = 1,
			weakObservationCount = 2, knownQualityObservationCount = 5).toPortableValue()
		allBuckets.knownQualityObservationCount shouldBe 5
		allBuckets.weakObservationCount shouldBe 2
		allBuckets.allKnownQualityIsWeak shouldBe false
		shouldThrow<ArithmeticException> {
			payload.copy(submittedChildCount = Int.MAX_VALUE, acceptedChildCount = Int.MAX_VALUE,
				observationCount = Int.MAX_VALUE, lteCount = Int.MAX_VALUE,
				qualityNoneOrUnknownCount = Int.MAX_VALUE, qualityPoorCount = 1,
				qualityGoodCount = 0, knownQualityObservationCount = Int.MAX_VALUE).toPortableValue()
		}
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
	fun `rehashed stored timing and bucket contradictions fail before replay or correction`() = runTest {
		seedEvidence()
		val validPayload = observationPayload("one")
		val invalidPayloads = listOf(
			validPayload.copy(coverageStartTimeMs = 110L),
			validPayload.copy(knownQualityObservationCount = 0),
			validPayload.copy(weakObservationCount = 1, allKnownQualityIsWeak = true),
			validPayload.copy(coverageStartTimeMs = 0L, observedTimeMs = 1L,
				wallTimeUncertaintyMs = 2L, latestPossibleTimeMs = 3L),
			validPayload.copy(observedTimeMs = Long.MAX_VALUE,
				wallTimeUncertaintyMs = 1L, latestPossibleTimeMs = Long.MAX_VALUE),
		)
		for ((index, invalid) in invalidPayloads.withIndex()) {
			val validCurrent = validPayload.copy(identity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION, "stored-observation-$index"))
			val original = entry(logicalLocal = "stored-$index", observation = validCurrent.toPortableValue(),
				runDefinitions = listOf(RunDefinition("stored-run-$index", 10L, 20L, listOf(validCurrent.toPortableValue()))))
			val originalRequest = request(original, "stored-job-$index", "entry-$index")
			importer().importEntry(originalRequest) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			// Pin the independent test encoder to the native format before rehashing corruption.
			rehashStoredObservation(original, validCurrent)
			importer().importEntry(originalRequest) shouldBe ImportPortableCapturedCellResult.Duplicate(1L)
			rehashStoredObservation(original, invalid.copy(identity = validCurrent.identity))

			importer().importEntry(originalRequest) shouldBe ImportPortableCapturedCellResult.Unverifiable(
				PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			val corrected = validCurrent.copy(semanticRevision = 2L, supersedesSemanticRevision = 1L,
				qualityFlags = 7L).toPortableValue()
			val correction = entry(logicalLocal = "stored-$index", observation = corrected,
				runDefinitions = listOf(RunDefinition("stored-run-$index", 10L, 20L, listOf(corrected))))
			importer().importEntry(request(correction, "correction-$index", "entry-$index")) shouldBe
				ImportPortableCapturedCellResult.Unverifiable(PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			database.importedCellDao().boundedEntryRevisions(original.identity.value).size shouldBe 1
			database.importedCellDao().receiptsForAdmission(original.identity.value).size shouldBe 1
		}
	}

	@Test
	fun `stored no-op second revision is impossible authority for read replay correction and reexport`() =
		runTest {
			seedEvidence()
			val value = entry()
			val originalRequest = request(value)
			importer().importEntry(originalRequest) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			val dao = database.importedCellDao()
			val firstHeader = dao.boundedEntryRevisions(value.identity.value).single()
			val firstRun = dao.allRunsForAdmission(value.identity.value).single()
			val firstObservation = dao.allObservationsForAdmission(value.identity.value).single()
			dao.insertEntryRevision(
				firstHeader.copy(
					importRevision = 2L,
					supersedesImportRevision = 1L,
					importJobId = "forged-no-op",
					importEntryKey = "entry-2",
					receivedAtMs = firstHeader.receivedAtMs + 1L,
				),
			)
			dao.insertRun(firstRun.copy(entryImportRevision = 2L))
			dao.insertObservation(firstObservation.copy(entryImportRevision = 2L))
			dao.insertReceipt(
				ImportedCellReceiptEntity(
					importJobId = "forged-no-op",
					importEntryKey = "entry-2",
					importSourceName = firstHeader.importSourceName,
					receivedAtMs = firstHeader.receivedAtMs + 1L,
					entryIdentity = firstHeader.identity,
					entryImportRevision = 2L,
					entryContentChecksum = firstHeader.contentChecksum,
					collectedDataEpoch = EPOCH,
				),
			)

			(database.withTransaction {
				ImportedCellProductReader(database).selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Unverifiable).reason shouldBe
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			importer().importEntry(originalRequest) shouldBe ImportPortableCapturedCellResult.Unverifiable(
				PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
			val correction = entry(observation = observation("one", 2L, qualityFlags = 7L))
			importer().importEntry(request(correction, "correction", "entry-3")) shouldBe
				ImportPortableCapturedCellResult.Unverifiable(
					PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			var sinkReached = false
			RoomReexportImportedPortableCapturedCell(database, Dispatchers.Unconfined).reexport(
				ReexportImportedPortableCapturedCellRequest(value.identity, 2L, value.contentChecksum),
			) { sinkReached = true } shouldBe ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
			sinkReached shouldBe false
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
		val readable = database.withTransaction {
			ImportedCellProductReader(database).selectIdentityInTransaction(exact.identity)
		} as ImportedCellProductEvaluation.Readable
		readable.localOriginHandle?.toString() shouldBe "ImportedCellLocalOriginHandle"
		readable.toString().contains(LOGICAL_LOCAL) shouldBe false

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
	fun `typed product authenticates complete replacement membership and latest correction`() = runTest {
			seedEvidence()
			val original = entry(
				runDefinitions = listOf(
					RunDefinition("captured", 10L, 20L, listOf(observation("one"))),
					RunDefinition(
						"gap",
						20L,
						30L,
						emptyList(),
						availability = PortableCellRunAvailability.NO_RETAINED_OBSERVATION,
						completeness = PortableCellAcquisitionCompleteness.PARTIAL,
					),
					RunDefinition(
						"not-captured",
						30L,
						40L,
						emptyList(),
						coverage = PortableCellCaptureCoverage.NOT_CAPTURED,
						availability = PortableCellRunAvailability.NOT_CAPTURED,
						completeness = PortableCellAcquisitionCompleteness.UNKNOWN,
					),
				),
			)
			val correctedObservation = observation("one", semanticRevision = 2L, qualityFlags = 7L)
			val corrected = entry(
				runDefinitions = listOf(
					RunDefinition("captured", 10L, 20L, listOf(correctedObservation)),
					RunDefinition(
						"gap",
						20L,
						30L,
						emptyList(),
						availability = PortableCellRunAvailability.NO_RETAINED_OBSERVATION,
						completeness = PortableCellAcquisitionCompleteness.PARTIAL,
					),
					RunDefinition(
						"not-captured",
						30L,
						40L,
						emptyList(),
						coverage = PortableCellCaptureCoverage.NOT_CAPTURED,
						availability = PortableCellRunAvailability.NOT_CAPTURED,
						completeness = PortableCellAcquisitionCompleteness.UNKNOWN,
					),
				),
			)
			importer().importEntry(request(original)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 3, 1)
			importer().importEntry(request(corrected, "correction", "entry-2")) shouldBe
				ImportPortableCapturedCellResult.Applied(2L, 3, 1)

			val evaluation = database.withTransaction {
				ImportedCellProductReader(database).selectRecentInTransaction(1).single()
			} as ImportedCellProductEvaluation.Readable

			evaluation.candidate.importRevision shouldBe 2L
			evaluation.entry shouldBe corrected
			evaluation.entry.runs.map { it.captureCoverage } shouldBe listOf(
				PortableCellCaptureCoverage.WHOLE_RUN,
				PortableCellCaptureCoverage.WHOLE_RUN,
				PortableCellCaptureCoverage.NOT_CAPTURED,
			)
			evaluation.deletedRunIdentities shouldBe emptySet()
			evaluation.retentionLimited shouldBe false
			evaluation.localOriginHandle shouldBe null
	}

	@Test
	fun `typed product retains every immutable revision identity in the collision namespace`() = runTest {
			seedEvidence()
			val removed = observation("removed")
			val survivor = observation("survivor")
			val original = entry(
				runDefinitions = listOf(RunDefinition("run", 10L, 20L, listOf(removed, survivor))),
			)
			val corrected = entry(
				runDefinitions = listOf(
					RunDefinition("run", 10L, 20L, listOf(survivor), retentionLoss = true),
				),
			)
			importer().importEntry(request(original)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 2)
			importer().importEntry(request(corrected, "correction", "entry-2")) shouldBe
				ImportPortableCapturedCellResult.Applied(2L, 1, 1)
			database.importedCellDao().insertEntryRevision(
				ImportedCellEntryRevisionEntity(
					identity = removed.identity.value,
					importRevision = 1L,
					supersedesImportRevision = null,
					contentChecksum = "a".repeat(64),
					sourceFormat = CellCapturedPortableFormatV1.FORMAT,
					sourceSchemaVersion = 1,
					sessionMode = "MANUAL",
					startTimeMs = 50L,
					endTimeMs = 60L,
					subscriptionGrouping = "UNKNOWN",
					collectedDataEpoch = EPOCH,
					importJobId = "collision",
					importEntryKey = "entry",
					importSourceName = "collision.trackercell",
					receivedAtMs = 70L,
				),
			)

			database.withTransaction {
				ImportedCellProductReader(database).selectIdentityInTransaction(original.identity)
			} shouldBe ImportedCellProductEvaluation.Unverifiable(
				database.importedCellDao().latestHistoryCandidate(original.identity.value)!!,
				ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
			)
	}

	@Test
	fun `typed product reflects current floor entry and run deletion and wrong epoch`() = runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			val reader = ImportedCellProductReader(database)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_evidence_state SET retained_from_ms = 101 WHERE id = 1",
			)
			(database.withTransaction {
				reader.selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Readable).retentionLimited shouldBe true

			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_evidence_state SET retained_from_ms = NULL WHERE id = 1",
			)
			val run = value.runs.single()
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					LOGICAL_LOCAL,
					"run",
					1L,
					EPOCH,
					499L,
				),
			)
			database.cellCapturedFactDao().insertDeletionGeneration(
				CellCaptureDeletionGenerationEntity(LOGICAL_LOCAL, "run", EPOCH, 1L, 499L),
			)
			database.importedCellDao().insertDeletionGeneration(
				ImportedCellDeletionGenerationEntity.create(
					run.identity.value,
					value.identity.value,
					run.deletionScopeDigest.value,
					EPOCH,
					1L,
					500L,
				),
			)
			(database.withTransaction {
				reader.selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Readable).deletedRunIdentities shouldBe
				setOf(run.identity.value)

			database.importedCellDao().insertEntryDeletion(
				ImportedCellEntryDeletionEntity.create(value.identity.value, EPOCH, 1L, 501L),
			)
			(database.withTransaction {
				reader.selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Readable).entryDeleted shouldBe true

			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_evidence_state SET collected_data_epoch = ? WHERE id = 1",
				arrayOf(EPOCH + 1L),
			)
			(database.withTransaction {
				reader.selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Unverifiable).reason shouldBe
				ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH
	}

	@Test
	fun `typed product rejects fully rehashed stored aggregate corruption and propagates cancellation`() =
			runTest {
				seedEvidence()
				val value = entry()
				importer().importEntry(request(value)) shouldBe
					ImportPortableCapturedCellResult.Applied(1L, 1, 1)
				val invalid = observationPayload("one").copy(
					knownQualityObservationCount = 0,
					weakObservationCount = 1,
					allKnownQualityIsWeak = true,
				)
				rehashStoredObservation(value, invalid)

				(database.withTransaction {
					ImportedCellProductReader(database).selectIdentityInTransaction(value.identity)
				} as ImportedCellProductEvaluation.Unverifiable).reason shouldBe
					ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
				shouldThrow<CancellationException> {
					withContext(Job().apply { cancel() }) {
						database.withTransaction {
							ImportedCellProductReader(database).selectRecentInTransaction(1)
						}
					}
				}
			}

	@Test
	fun `typed product preflight rejects cap plus one live identity owners`() = runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			repeat(ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1) { index ->
				database.sourceSessionDao().insertSession(localSession("reader-local-$index"))
			}

			(database.withTransaction {
				ImportedCellProductReader(database).selectIdentityInTransaction(value.identity)
			} as ImportedCellProductEvaluation.Unverifiable).reason shouldBe
				ImportedCellProductFailure.DEPENDENCY_OVERFLOW
	}

	@Test
	fun `latest imported reexport is outside Room and stale deleted cancellation storage are typed`() =
			runTest {
				seedEvidence()
				val first = entry()
				val corrected = entry(observation = observation("one", 2L, qualityFlags = 7L))
				importer().importEntry(request(first)) shouldBe
					ImportPortableCapturedCellResult.Applied(1L, 1, 1)
				importer().importEntry(request(corrected, "correction", "entry-2")) shouldBe
					ImportPortableCapturedCellResult.Applied(2L, 1, 1)
				val exporter = RoomReexportImportedPortableCapturedCell(database, Dispatchers.Unconfined)
				var emitted: PortableCapturedCellEntryV1? = null
				var sinkWasTransactional: Boolean? = null
				exporter.reexport(
					ReexportImportedPortableCapturedCellRequest(
						corrected.identity,
						2L,
						corrected.contentChecksum,
					),
				) { entry ->
					sinkWasTransactional = database.inTransaction()
					emitted = entry
				} shouldBe ExportPortableCapturedCellResult.Exported(
					corrected.identity,
					corrected.contentChecksum,
					1,
					1,
				)
				emitted shouldBe corrected
				sinkWasTransactional shouldBe false

				var staleSinkReached = false
				exporter.reexport(
					ReexportImportedPortableCapturedCellRequest(first.identity, 1L, first.contentChecksum),
				) { staleSinkReached = true } shouldBe ExportPortableCapturedCellResult.Unverifiable(
					PortableCellUnverifiableReason.IMPORTED_SELECTION_STALE,
				)
				staleSinkReached shouldBe false

				database.importedCellDao().insertEntryDeletion(
					ImportedCellEntryDeletionEntity.create(corrected.identity.value, EPOCH, 2L, 500L),
				)
				exporter.reexport(
					ReexportImportedPortableCapturedCellRequest(
						corrected.identity,
						2L,
						corrected.contentChecksum,
					),
				) { error("Deleted imported Cell must not reach the sink") } shouldBe
					ExportPortableCapturedCellResult.Deleted

				shouldThrow<CancellationException> {
					val cleanDatabase = AppDatabase.testDatabase(
						ApplicationProvider.getApplicationContext<Application>(),
					)
					try {
						cleanDatabase.sourceEvidenceStateDao().ensure(
							SourceEvidenceState(collectedDataEpoch = EPOCH),
						)
						RoomImportPortableCapturedCell(cleanDatabase, Dispatchers.Unconfined)
							.importEntry(request(first))
						RoomReexportImportedPortableCapturedCell(
							cleanDatabase,
							Dispatchers.Unconfined,
						).reexport(
							ReexportImportedPortableCapturedCellRequest(
								first.identity,
								1L,
								first.contentChecksum,
							),
						) { throw CancellationException("cancel imported Cell sink") }
					} finally {
						cleanDatabase.close()
					}
				}

				database.close()
				exporter.reexport(
					ReexportImportedPortableCapturedCellRequest(
						corrected.identity,
						2L,
						corrected.contentChecksum,
					),
				) { error("Closed storage must not reach the sink") } shouldBe
					ExportPortableCapturedCellResult.RetryableFailure(
						PortableCellRetryableReason.STORAGE_UNAVAILABLE,
					)
			}

	@Test
	fun `selected imported deletion records complete value-free authority before cascade`() = runTest {
		seedEvidence()
		val owner = observation("owner")
		val dependent = observation(
			"dependent",
			aggregateOwnerIdentity = owner.identity,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
		)
		val value = entry(
			runDefinitions = listOf(
				RunDefinition("run", 10L, 20L, listOf(owner, dependent)),
			),
		)
		importer().importEntry(request(value)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 2)
		val deletionRequest = DeleteSelectedImportedCellRequest(
			value.identity,
			1L,
			value.contentChecksum,
			EPOCH,
			600L,
		)

		RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(deletionRequest) shouldBe
			DeleteSelectedImportedCellResult.Deleted(1, 1, 2)

		rowCount("imported_cell_entry_revision") shouldBe 0L
		rowCount("imported_cell_receipt") shouldBe 0L
		rowCount("imported_cell_run") shouldBe 0L
		rowCount("imported_cell_observation") shouldBe 0L
		rowCount("imported_cell_entry_deletion") shouldBe 1L
		rowCount("imported_cell_deletion_generation") shouldBe 1L
		rowCount("imported_cell_entry_deletion_receipt") shouldBe 1L
		rowCount("imported_cell_deleted_identity") shouldBe 5L
		database.importedCellDao().deletedIdentitiesForEntry(value.identity.value, 6)
			.map { it.identityKind }.toSet() shouldBe setOf(
			ImportedCellDeletedIdentityEntity.ENTRY,
			ImportedCellDeletedIdentityEntity.RUN,
			ImportedCellDeletedIdentityEntity.DELETION_SCOPE,
			ImportedCellDeletedIdentityEntity.OBSERVATION,
		)
		RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(deletionRequest) shouldBe
			DeleteSelectedImportedCellResult.AlreadyDeleted
		importer().importEntry(request(value, "resurrection", "entry-2")) shouldBe
			ImportPortableCapturedCellResult.Blocked(PortableCellImportBlockedReason.DELETED_ENTRY)
		RoomReexportImportedPortableCapturedCell(database, Dispatchers.Unconfined).reexport(
			ReexportImportedPortableCapturedCellRequest(value.identity, 1L, value.contentChecksum),
		) { error("Deleted imported Cell must not reexport") } shouldBe
			ExportPortableCapturedCellResult.Deleted
	}

	@Test
	fun `selected deletion preserves terminal corrected observation after retention removal`() =
		runTest {
			seedEvidence()
			val owner = observation("terminal-owner")
			val dependent = observation(
				"terminal-dependent",
				aggregateOwnerIdentity = owner.identity,
				aggregateOwnerSemanticRevision = owner.semanticRevision,
			)
			val correctedDependent = observation(
				"terminal-dependent",
				semanticRevision = 2L,
				qualityFlags = 1L,
				aggregateOwnerIdentity = owner.identity,
				aggregateOwnerSemanticRevision = owner.semanticRevision,
			)
			val original = entry(
				runDefinitions = listOf(
					RunDefinition("run", 10L, 20L, listOf(owner, dependent)),
				),
			)
			val corrected = entry(
				runDefinitions = listOf(
					RunDefinition("run", 10L, 20L, listOf(owner, correctedDependent)),
				),
			)
			val retained = entry(
				runDefinitions = listOf(
					RunDefinition("run", 10L, 20L, listOf(owner), retentionLoss = true),
				),
			)
			importer().importEntry(request(original)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 2)
			importer().importEntry(request(corrected, "correction", "entry-2")) shouldBe
				ImportPortableCapturedCellResult.Applied(2L, 1, 2)
			importer().importEntry(request(retained, "retention", "entry-3")) shouldBe
				ImportPortableCapturedCellResult.Applied(3L, 1, 1)
			val deletion = DeleteSelectedImportedCellRequest(
				retained.identity,
				3L,
				retained.contentChecksum,
				EPOCH,
				600L,
			)
			val deleter = RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined)

			deleter.delete(deletion) shouldBe DeleteSelectedImportedCellResult.Deleted(3, 1, 2)

			val markers = database.importedCellDao().deletedIdentitiesForEntry(
				retained.identity.value,
				6,
			)
			markers.single {
				it.identityKind == ImportedCellDeletedIdentityEntity.RUN
			}.let { run ->
				run.contentChecksum shouldBe retained.runs.single().contentChecksum.value
				run.retentionLoss shouldBe true
				run.availability shouldBe retained.runs.single().availability.name
			}
			markers.single {
				it.protectedIdentity == dependent.identity.value
			}.let { terminal ->
				terminal.runIdentity shouldBe retained.runs.single().identity.value
				terminal.aggregateOwnerIdentity shouldBe owner.identity.value
				terminal.contentChecksum shouldBe correctedDependent.contentChecksum.value
				terminal.includedInLatest shouldBe false
				terminal.observationOrdinal shouldBe null
			}
			deleter.delete(deletion) shouldBe DeleteSelectedImportedCellResult.AlreadyDeleted
			importer().importEntry(request(retained, "resurrection", "entry-4")) shouldBe
				ImportPortableCapturedCellResult.Blocked(
					PortableCellImportBlockedReason.DELETED_ENTRY,
				)
		}

	@Test
	fun `deleted imported replay reauthenticates every marker and stale selections stay blocked`() =
		runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			val deletion = DeleteSelectedImportedCellRequest(
				value.identity,
				1L,
				value.contentChecksum,
				EPOCH,
				600L,
			)
			val deleter = RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined)
			deleter.delete(deletion) shouldBe DeleteSelectedImportedCellResult.Deleted(1, 1, 1)
			deleter.delete(
				deletion.copy(expectedContentChecksum = PortableCellDigest("a".repeat(64))),
			) shouldBe DeleteSelectedImportedCellResult.Blocked(
				ImportedCellDeletionBlockedReason.STALE_SELECTION,
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE imported_cell_deleted_identity SET effect_checksum = ? " +
					"WHERE identity_kind = ?",
				arrayOf("b".repeat(64), ImportedCellDeletedIdentityEntity.OBSERVATION),
			)

			deleter.delete(deletion) shouldBe DeleteSelectedImportedCellResult.Unverifiable(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}

	@Test
	fun `rehashed deleted hierarchy topology corruption blocks detail and reexport`() = runTest {
		repeat(6) { corruption ->
			if (corruption > 0) {
				database.close()
				database = AppDatabase.testDatabase(
					ApplicationProvider.getApplicationContext<Application>(),
				)
			}
			seedEvidence()
			val owner = observation("deleted-owner")
			val firstDependent = observation(
				"deleted-dependent-1",
				aggregateOwnerIdentity = owner.identity,
				aggregateOwnerSemanticRevision = owner.semanticRevision,
			)
			val secondDependent = observation(
				"deleted-dependent-2",
				aggregateOwnerIdentity = owner.identity,
				aggregateOwnerSemanticRevision = owner.semanticRevision,
			)
			val value = entry(
				runDefinitions = listOf(
					RunDefinition(
						"run",
						10L,
						20L,
						listOf(owner, firstDependent, secondDependent),
					),
				),
			)
			val request = DeleteSelectedImportedCellRequest(
				value.identity,
				1L,
				value.contentChecksum,
				EPOCH,
				600L,
			)
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 3)
			RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(request) shouldBe
				DeleteSelectedImportedCellResult.Deleted(1, 1, 3)
			val markers = database.importedCellDao()
				.deletedIdentitiesForEntry(value.identity.value, 10)
			val runMarker = markers.single {
				it.identityKind == ImportedCellDeletedIdentityEntity.RUN
			}
			val scopeMarker = markers.single {
				it.identityKind == ImportedCellDeletedIdentityEntity.DELETION_SCOPE
			}
			val observationMarkers = markers.filter {
				it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
			}
			val changed = when (corruption) {
				0 -> markers.map { marker ->
					if (marker == runMarker) marker.rehashed(
						deletionScopeDigest = "a".repeat(64),
					) else marker
				}
				1 -> markers - scopeMarker
				2 -> markers + scopeMarker.rehashed(protectedIdentity = "b".repeat(64),
					deletionScopeDigest = "b".repeat(64))
				3 -> markers.map { marker ->
					if (marker == observationMarkers.last()) marker.rehashed(
						runIdentity = "c".repeat(64),
					) else marker
				}
				4 -> markers.map { marker ->
					if (marker == observationMarkers.last()) marker.rehashed(
						aggregateOwnerIdentity = observationMarkers[1].protectedIdentity,
					) else marker
				}
				else -> markers.map { marker ->
					if (marker == runMarker) marker.rehashed(
						runStartTimeMs = requireNotNull(marker.runStartTimeMs) + 1L,
					) else marker
				}
			}
			replaceDeletedAuthority(value.identity.value, changed)

			database.withTransaction {
				database.authenticateDeletedImportedCellSelectionInTransaction(
					value.identity,
					1L,
					value.contentChecksum,
				)
			} shouldBe DeletedImportedCellSelectionAuthentication.Unverifiable
			var sinkReached = false
			RoomReexportImportedPortableCapturedCell(database, Dispatchers.Unconfined).reexport(
				ReexportImportedPortableCapturedCellRequest(
					value.identity,
					1L,
					value.contentChecksum,
				),
			) { sinkReached = true } shouldBe ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
			sinkReached shouldBe false
		}
	}

	@Test
	fun `deleted authority rejects rehashed fence generation mismatch and malformed source state`() =
		runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(
				DeleteSelectedImportedCellRequest(
					value.identity,
					1L,
					value.contentChecksum,
					EPOCH,
					600L,
				),
			) shouldBe DeleteSelectedImportedCellResult.Deleted(1, 1, 1)
			database.cellCapturedFactDao().insertDeletionGeneration(
				CellCaptureDeletionGenerationEntity(LOGICAL_LOCAL, "run", EPOCH, 1L, 600L),
			)
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					LOGICAL_LOCAL,
					"run",
					2L,
					EPOCH,
					600L,
				),
			)
			database.withTransaction {
				database.authenticateDeletedImportedCellSelectionInTransaction(
					value.identity,
					1L,
					value.contentChecksum,
				)
			} shouldBe DeletedImportedCellSelectionAuthentication.Unverifiable

			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_deletion_fence",
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM cell_capture_deletion_generation",
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_evidence_state SET revision = -1 WHERE id = 1",
			)
			database.withTransaction {
				database.authenticateDeletedImportedCellSelectionInTransaction(
					value.identity,
					1L,
					value.contentChecksum,
				)
			} shouldBe DeletedImportedCellSelectionAuthentication.Unverifiable
		}

	@Test
	fun `selected imported deletion cancellation and storage failure never publish partial authority`() =
		runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			val deletionRequest = DeleteSelectedImportedCellRequest(
				value.identity,
				1L,
				value.contentChecksum,
				EPOCH,
				600L,
			)
			val cancelling = RoomDeleteSelectedImportedCell(
				database,
				Dispatchers.Unconfined,
			) { checkpoint ->
				if (checkpoint == ImportedCellDeletionCheckpoint.MARKERS_RECORDED) {
					throw CancellationException("cancel selected imported Cell deletion")
				}
			}
			shouldThrow<CancellationException> { cancelling.delete(deletionRequest) }
			rowCount("imported_cell_entry_revision") shouldBe 1L
			rowCount("imported_cell_entry_deletion") shouldBe 0L
			rowCount("imported_cell_entry_deletion_receipt") shouldBe 0L
			rowCount("imported_cell_deleted_identity") shouldBe 0L
			database.close()
			RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined)
				.delete(deletionRequest) shouldBe
				DeleteSelectedImportedCellResult.RetryableFailure(
					ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE,
				)
		}

	@Test
	fun `full collected-data clear preserves value-free selected deletion identity authority`() = runTest {
		seedEvidence()
		val value = entry()
		importer().importEntry(request(value)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(
			DeleteSelectedImportedCellRequest(
				value.identity,
				1L,
				value.contentChecksum,
				EPOCH,
				600L,
			),
		) shouldBe DeleteSelectedImportedCellResult.Deleted(1, 1, 1)

		AppDatabase.deleteAllCollectedData(database, EPOCH + 1L, null, 700L)

		rowCount("imported_cell_entry_deletion_receipt") shouldBe 1L
		rowCount("imported_cell_deleted_identity") shouldBe 4L
		importer().importEntry(
			request(value, "after-clear", "entry-2", expectedEpoch = EPOCH + 1L),
		) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.DELETED_ENTRY,
		)
	}

	@Test
	fun `recent one hundred reuses page ownership snapshot and one owner union per batch`() = runTest {
		database.close()
		val statements = CopyOnWriteArrayList<String>()
		database = AppDatabase.inMemoryBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
		).allowMainThreadQueries()
			.setQueryCallback(
				{ sql, _ ->
					val normalized = sql.trimStart()
					if (normalized.startsWith("SELECT", true) ||
						normalized.startsWith("WITH", true)
					) statements += normalized
				},
				Executor { command -> command.run() },
			)
			.build()
		seedEvidence()
		repeat(100) { index ->
			val observation = observation("bulk-observation-$index")
			val value = entry(
				logicalLocal = "bulk-logical-$index",
				runDefinitions = listOf(
					RunDefinition("bulk-run-$index", 10L + index, 20L + index, listOf(observation)),
				),
			)
			importer().importEntry(request(
				value,
				jobId = "bulk-job-$index",
				entryKey = "bulk-entry-$index",
			)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		}
		statements.clear()

		val result = database.withTransaction {
			ImportedCellProductReader(database).selectRecentInTransaction(100)
		}

		result.size shouldBe 100
		statements.size shouldBeLessThanOrEqual 220
		statements.count { it.contains("FROM source_evidence_state", true) } shouldBe 1
		statements.count { it.contains("FROM logical_tracking_session", true) } shouldBe 1
		statements.count { it.contains("FROM source_service_run", true) } shouldBe 1
		statements.count { it.contains("FROM cell_captured_fact_revision", true) } shouldBe 1
		statements.count { it.contains("FROM source_session_completeness", true) } shouldBe 1
		statements.count { it.contains("FROM cell_capture_deletion_generation", true) } shouldBe 1
		statements.count { it.contains("AS revision_rows", true) } shouldBe 25
		statements.count { it.contains("UNION ALL", true) } shouldBe 25
	}

	@Test
	fun `scoped recent scan rejects entry outside Room and escaped use after authority changes`() =
		runTest {
			seedEvidence()
			val value = entry()
			importer().importEntry(request(value)) shouldBe
				ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			var evaluatedBatchCount = 0
			val reader = ImportedCellProductReader(
				database,
				batchCheckpoint = { evaluatedBatchCount += 1 },
			)
			shouldThrow<ImportedCellProductScanContextFailure> {
				reader.withRecentScanInTransaction { }
			}.reason shouldBe ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			var escaped: ImportedCellProductScanScope? = null
			database.withTransaction {
				reader.withRecentScanInTransaction { scope ->
					escaped = scope
					scope.selectRecentPageInTransaction(
						budget = ImportedCellProductReadBudget.sharedHistory(),
						limit = 1,
						beforeStartTimeMs = null,
						beforeIdentity = null,
					).evaluations.size shouldBe 1
				}
			}
			evaluatedBatchCount = 0
			shouldThrow<ImportedCellProductScanContextFailure> {
				requireNotNull(escaped).selectRecentPageInTransaction(
					budget = ImportedCellProductReadBudget.sharedHistory(),
					limit = 1,
					beforeStartTimeMs = null,
					beforeIdentity = null,
				)
			}.reason shouldBe ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			evaluatedBatchCount shouldBe 0
			database.sourceSessionDao().insertSession(localSession("late-scan-owner"))
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_evidence_state SET revision = revision + 1, " +
					"updated_at_ms = updated_at_ms + 1 WHERE id = 1",
			)
			evaluatedBatchCount = 0

			database.withTransaction {
				shouldThrow<ImportedCellProductScanContextFailure> {
					requireNotNull(escaped).selectRecentPageInTransaction(
						budget = ImportedCellProductReadBudget.sharedHistory(),
						limit = 1,
						beforeStartTimeMs = null,
						beforeIdentity = null,
					)
				}.reason shouldBe ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			}
			evaluatedBatchCount shouldBe 0
		}

	@Test
	fun `cancellation invalidates escaped recent scan before any later evaluation`() = runTest {
		seedEvidence()
		val value = entry()
		importer().importEntry(request(value)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		var evaluatedBatchCount = 0
		val reader = ImportedCellProductReader(
			database,
			batchCheckpoint = { evaluatedBatchCount += 1 },
		)
		var escaped: ImportedCellProductScanScope? = null

		shouldThrow<CancellationException> {
			database.withTransaction {
				reader.withRecentScanInTransaction { scope ->
					escaped = scope
					throw CancellationException("cancel scoped imported Cell scan")
				}
			}
		}
		evaluatedBatchCount = 0
		database.withTransaction {
			shouldThrow<ImportedCellProductScanContextFailure> {
				requireNotNull(escaped).selectRecentPageInTransaction(
					budget = ImportedCellProductReadBudget.sharedHistory(),
					limit = 1,
					beforeStartTimeMs = null,
					beforeIdentity = null,
				)
			}.reason shouldBe ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
		}
		evaluatedBatchCount shouldBe 0
	}

	@Test
	fun `recent cancellation between bounded lineage batches escapes immediately`() = runTest {
		seedEvidence()
		repeat(5) { index ->
			val observation = observation("cancel-observation-$index")
			val value = entry(
				logicalLocal = "cancel-logical-$index",
				runDefinitions = listOf(
					RunDefinition("cancel-run-$index", 10L + index, 20L + index, listOf(observation)),
				),
			)
			importer().importEntry(request(
				value,
				jobId = "cancel-job-$index",
				entryKey = "cancel-entry-$index",
			)) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		}
		val reader = ImportedCellProductReader(database) { completedBatchCount ->
			if (completedBatchCount == 1) throw CancellationException("cancel between batches")
		}

		shouldThrow<CancellationException> {
			database.withTransaction { reader.selectRecentInTransaction(5) }
		}
	}

	@Test
	fun `recent cancellation between opaque owner chunks escapes without further queries`() = runTest {
		seedEvidence()
		val observations = (0 until 300).map { index -> observation("owner-chunk-$index") }
		val value = entry(
			logicalLocal = "owner-chunk-logical",
			runDefinitions = listOf(
				RunDefinition("owner-chunk-run", 10L, 20L, observations),
			),
		)
		importer().importEntry(request(value)) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 300)
		val reader = ImportedCellProductReader(
			database = database,
			ownerChunkCheckpoint = { completedChunkCount ->
				if (completedChunkCount == 1) {
					throw CancellationException("cancel between opaque owner chunks")
				}
			},
		)

		shouldThrow<CancellationException> {
			database.withTransaction { reader.selectRecentInTransaction(1) }
		}
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
	fun `full collected-data clear removes payload and reepochs imported privacy authority`() = runTest {
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
			"imported_cell_observation",
		).forEach { rowCount(it) shouldBe 0L }
		rowCount("imported_cell_entry_deletion") shouldBe 2L
		rowCount("imported_cell_deletion_generation") shouldBe 2L
		rowCount("imported_cell_entry_deletion_receipt") shouldBe 1L
		rowCount("imported_cell_deleted_identity") shouldBe 4L
		database.openHelper.writableDatabase.query(
			"SELECT COUNT(*) FROM imported_cell_entry_deletion WHERE collected_data_epoch = ?",
			arrayOf(EPOCH + 1L),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 2L
		}
		database.openHelper.writableDatabase.query(
			"SELECT COUNT(*) FROM imported_cell_deletion_generation WHERE collected_data_epoch = ?",
			arrayOf(EPOCH + 1L),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 2L
		}
		importer().importEntry(
			request(entry, "after-full-clear", "entry-after-full-clear", EPOCH + 1L),
		) shouldBe ImportPortableCapturedCellResult.Blocked(
			PortableCellImportBlockedReason.DELETED_ENTRY,
		)
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

	private suspend fun replaceDeletedAuthority(
		entryIdentity: String,
		markers: List<ImportedCellDeletedIdentityEntity>,
	) {
		val dao = database.importedCellDao()
		val deletion = requireNotNull(dao.entryDeletion(entryIdentity))
		val receipt = requireNotNull(dao.entryDeletionReceipt(entryIdentity))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_cell_entry_deletion_receipt WHERE entry_identity = ?",
			arrayOf(entryIdentity),
		)
		dao.insertEntryDeletionReceipt(
			ImportedCellEntryDeletionReceiptEntity.create(
				entryDeletion = deletion,
				deletedContentChecksum = receipt.deletedContentChecksum,
				sessionMode = receipt.sessionMode,
				subscriptionGrouping = receipt.subscriptionGrouping,
				startTimeMs = markers.filter {
					it.identityKind == ImportedCellDeletedIdentityEntity.RUN
				}.minOfOrNull { requireNotNull(it.runStartTimeMs) } ?: receipt.startTimeMs,
				endTimeMs = markers.filter {
					it.identityKind == ImportedCellDeletedIdentityEntity.RUN
				}.maxOfOrNull { requireNotNull(it.runEndTimeMs) } ?: receipt.endTimeMs,
				receivedAtMs = receipt.receivedAtMs,
				retainedFromMs = receipt.retainedFromMs,
				revisionCount = receipt.expectedRevisionCount,
				receiptCount = receipt.expectedReceiptCount,
				runCount = receipt.expectedRunCount,
				observationCount = receipt.expectedObservationCount,
				protectedIdentities = markers,
			),
		)
		dao.insertDeletedIdentities(markers)
	}

	/** Rehash all three v1 levels independently; rejection must not rely on a stale checksum. */
	private fun rehashStoredObservation(entry: PortableCapturedCellEntryV1, observation: PortableCellObservationPayload) {
		val run = entry.runs.single()
		val observationChecksum = CellCapturedPortableIntegrity.observationChecksum(observation).value
		val runChecksum = testCanonicalChecksum("tracker-portable-cell-run-v1") {
			writeCellString(run.identity.value)
			writeCellString(run.deletionScopeDigest.value)
			writeLong(run.startTimeMs)
			writeLong(run.endTimeMs)
			writeCellString(run.captureCoverage.name)
			writeCellString(run.availability.name)
			writeCellString(run.acquisitionCompleteness.name)
			writeBoolean(run.retentionLoss)
			writeCellString(run.subscriptionGrouping.name)
			writeInt(1)
			writeCellString(observation.identity.value)
			writeCellString(observationChecksum)
		}
		val entryChecksum = testCanonicalChecksum("tracker-portable-cell-entry-v1") {
			writeCellString(CellCapturedPortableFormatV1.FORMAT)
			writeInt(CellCapturedPortableFormatV1.SCHEMA_VERSION)
			writeCellString(entry.identity.value)
			writeCellString(entry.sessionMode.name)
			writeLong(entry.startTimeMs)
			writeLong(entry.endTimeMs)
			writeCellString(entry.subscriptionGrouping.name)
			writeInt(1)
			writeCellString(run.identity.value)
			writeCellString(runChecksum)
		}
		val sql = database.openHelper.writableDatabase
		sql.execSQL("UPDATE imported_cell_observation SET coverage_start_time_ms = ?, observed_time_ms = ?, " +
			"latest_possible_time_ms = ?, wall_time_uncertainty_ms = ?, known_quality_observation_count = ?, " +
			"weak_observation_count = ?, all_known_quality_is_weak = ?, content_checksum = ? WHERE entry_identity = ?",
			arrayOf(observation.coverageStartTimeMs, observation.observedTimeMs, observation.latestPossibleTimeMs,
				observation.wallTimeUncertaintyMs, observation.knownQualityObservationCount, observation.weakObservationCount,
				if (observation.allKnownQualityIsWeak) 1 else 0, observationChecksum, entry.identity.value))
		sql.execSQL("UPDATE imported_cell_run SET content_checksum = ? WHERE entry_identity = ?", arrayOf(runChecksum, entry.identity.value))
		sql.execSQL("UPDATE imported_cell_entry_revision SET content_checksum = ? WHERE identity = ?", arrayOf(entryChecksum, entry.identity.value))
		sql.execSQL("UPDATE imported_cell_receipt SET entry_content_checksum = ? WHERE entry_identity = ?", arrayOf(entryChecksum, entry.identity.value))
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

private fun ImportedCellDeletedIdentityEntity.rehashed(
	protectedIdentity: String = this.protectedIdentity,
	runIdentity: String? = this.runIdentity,
	aggregateOwnerIdentity: String? = this.aggregateOwnerIdentity,
	deletionScopeDigest: String? = this.deletionScopeDigest,
	runStartTimeMs: Long? = this.runStartTimeMs,
	runEndTimeMs: Long? = this.runEndTimeMs,
) = ImportedCellDeletedIdentityEntity.create(
	protectedIdentity = protectedIdentity,
	entryIdentity = entryIdentity,
	identityKind = identityKind,
	runIdentity = runIdentity,
	aggregateOwnerIdentity = aggregateOwnerIdentity,
	contentChecksum = contentChecksum,
	includedInLatest = includedInLatest,
	observationOrdinal = observationOrdinal,
	deletionScopeDigest = deletionScopeDigest,
	runStartTimeMs = runStartTimeMs,
	runEndTimeMs = runEndTimeMs,
	captureCoverage = captureCoverage,
	availability = availability,
	acquisitionCompleteness = acquisitionCompleteness,
	retentionLoss = retentionLoss,
	subscriptionGrouping = subscriptionGrouping,
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
	return observationPayload(localId, semanticRevision, coverageStartTimeMs, latestPossibleTimeMs,
		qualityFlags, aggregateOwnerIdentity, aggregateOwnerSemanticRevision).toPortableValue()
}

@Suppress("LongParameterList")
private fun observationPayload(
	localId: String,
	semanticRevision: Long = 1L,
	coverageStartTimeMs: Long = 100L,
	latestPossibleTimeMs: Long = 112L,
	qualityFlags: Long = 0L,
	aggregateOwnerIdentity: PortableCellOpaqueIdentity? = null,
	aggregateOwnerSemanticRevision: Long? = null,
) = PortableCellObservationPayload(
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

private fun PortableCellObservationPayload.toPortableValue(): PortableCapturedCellObservationV1 {
	val payload = this
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

private fun testCanonicalChecksum(namespace: String, content: DataOutputStream.() -> Unit): String {
	val bytes = ByteArrayOutputStream()
	DataOutputStream(bytes).use {
		it.writeUTF(namespace)
		it.content()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
		.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}
