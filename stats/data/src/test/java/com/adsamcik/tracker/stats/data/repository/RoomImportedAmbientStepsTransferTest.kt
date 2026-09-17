package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwner
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerKind
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerState
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.deleteFullClearPayloadInCurrentTransaction
import com.adsamcik.tracker.shared.base.database.prepareFullClearFencesInCurrentTransaction
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIntegrity
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
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryFactOriginKind
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsImportedDisposition
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
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
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.summary.RoomStepsNumericSummaryRepository
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		) shouldBe null
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
		val publicHistory = history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot
		publicHistory.days.first().total shouldBe AmbientStepsHistoryValue.Exact(0L)
		publicHistory.days.last().total shouldBe AmbientStepsHistoryValue.Partial(
			12L,
			setOf(
				com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.PARTIAL_COVERAGE,
				com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.EXPLICIT_GAP,
			),
		)
		publicHistory.days.all {
			it.importedDisposition == AmbientStepsImportedDisposition.PRESENT &&
				it.factOrigins.all { origin ->
					origin.kind == AmbientStepsHistoryFactOriginKind.PORTABLE_IMPORT
				}

				@Test
				fun `production retention gate rejects portable Steps before any import write`() = runTest {
					val archive = archive(completeDay(LocalDate.of(2026, 10, 25), count = 1L))
					val subject = RoomImportPortableAmbientSteps(
						database = database,
						dao = database.importedAmbientStepsDao(),
						ioDispatcher = Dispatchers.Unconfined,
						ensurePortableRetention = { true },
						requirePortableRetentionAuthority = true,
					)

					subject.importArchive(request(archive)) shouldBe ImportPortableAmbientStepsResult.Blocked(
						PortableAmbientStepsImportBlockedReason.RETENTION_POLICY_UNAVAILABLE,
					)
					database.importedAmbientStepsDao().dayRevisionCount() shouldBe 0L
				}
		} shouldBe true
		publicHistory.days.map { it.opaqueDayIdentity } shouldBe
			archive.days.map { it.identity.value }
		val recent = history(database).readRecent(
			com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(2),
		) as com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead.Page
		recent.days.map { it.day.epochDay } shouldBe
			archive.days.map { it.structuralEpochDay }.sortedDescending()
		recent.next shouldBe null
		val numeric = database.withTransaction {
			val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
			history(database).readNumericRangeInCurrentTransaction(
				historyRequest(archive),
				revision,
			)
		} as AmbientStepsNumericRangeRead.Snapshot
		numeric.days.map { it.total } shouldBe listOf(
			AmbientStepsHistoryValue.Exact(0L),
			AmbientStepsHistoryValue.Partial(
				12L,
				setOf(
					com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
						.PARTIAL_COVERAGE,
					com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
						.EXPLICIT_GAP,
				),
			),
		)
		val wrongZone = history(database).readRange(
			AmbientStepsHistoryRangeRequest(
				listOf(
					AmbientStepsStructuralDay(
						archive.days.first().structuralEpochDay,
						"UTC",
					),
				),
			),
		) as AmbientStepsHistoryRead.Snapshot
		wrongZone.days.single().total shouldBe AmbientStepsHistoryValue.Unavailable(
			setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.NO_EVIDENCE),
		)
		wrongZone.days.single().factOrigins shouldBe emptyList()
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
		val initialRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		importer(database).importArchive(initialRequest) shouldBe
			ImportPortableAmbientStepsResult.Duplicate(initial.identity, 1)
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe initialRevision
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
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe
			initialRevision + 2L
		readReady(database, correction).products.single().total shouldBe
			AmbientStepsNumericValue.Exact(12L)
		(history(database).readRange(historyRequest(correction)) as
			AmbientStepsHistoryRead.Snapshot).days.single().total shouldBe
			AmbientStepsHistoryValue.Exact(12L)

		importer(database).importArchive(
			request(initial, jobId = "late-copy", archiveKey = "late-copy"),
		) shouldBe ImportPortableAmbientStepsResult.Duplicate(initial.identity, 1)
		readReady(database, correction).archive shouldBe correction
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 2L
	}

	@Test
	fun `exact receipt replay authenticates rehashed stored fact payload`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 1, 6), 4L))
		val request = request(archive)
		importer(database).importArchive(request) shouldBe applied(archive, 1)
		val fact = archive.days.single().facts.single()
		val corruptCount = fact.stepCount + 1L
		val rehashed = AmbientStepsPortableIntegrity.factChecksum(
			fact.identity,
			fact.intervalStartTimeMs,
			fact.intervalEndTimeMs,
			corruptCount,
		)
		val beforeRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_fact SET step_count = ?, content_checksum = ? " +
				"WHERE fact_identity = ?",
			arrayOf(corruptCount, rehashed.value, fact.identity.value),
		)

		importer(database).importArchive(request) shouldBe
			ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedAmbientStepsDao().receiptCount() shouldBe 1L
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 1L
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe beforeRevision
	}

	@Test
	fun `alternate receipt authenticates every archive member and rejects rehashed gap`() = runTest {
		val archive = archive(
			completeDay(LocalDate.of(2026, 1, 7), 2L),
			partialDay(LocalDate.of(2026, 1, 8), 3L, "UTC"),
		)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 2)
		val gap = archive.days.last().gaps.single()
		val corruptReason = PortableAmbientStepsGapReason.PROVIDER_CHANGED
		val rehashed = AmbientStepsPortableIntegrity.gapChecksum(
			gap.identity,
			gap.intervalStartTimeMs,
			gap.intervalEndTimeMs,
			corruptReason,
		)
		val beforeRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_gap SET reason = ?, content_checksum = ? " +
				"WHERE gap_identity = ?",
			arrayOf(corruptReason.name, rehashed.value, gap.identity.value),
		)

		importer(database).importArchive(
			request(archive, jobId = "alternate-corrupt", archiveKey = "alternate-corrupt"),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		database.importedAmbientStepsDao().receiptCount() shouldBe 1L
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 2L
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe beforeRevision
	}

	@Test
	fun `alternate receipt cannot predate immutable archive first receipt`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 1, 4), 4L))
		val initial = request(archive)
		importer(database).importArchive(initial) shouldBe applied(archive, 1)

		importer(database).importArchive(
			request(
				archive,
				jobId = "earlier",
				archiveKey = "earlier",
				receivedAtMs = initial.receipt.receivedAtMs - 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT,
		)
		val correction = archive(completeDay(LocalDate.of(2026, 1, 4), 6L))
		val correctionReceivedAtMs = initial.receipt.receivedAtMs + 100L
		importer(database).importArchive(
			request(
				correction,
				jobId = "earlier-correction",
				archiveKey = "earlier-correction",
				receivedAtMs = initial.receipt.receivedAtMs - 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT,
		)
		importer(database).importArchive(
			request(
				correction,
				jobId = "later-correction",
				archiveKey = "later-correction",
				receivedAtMs = correctionReceivedAtMs,
			),
		) shouldBe applied(correction, 1)
		val unseenArchive = archive(
			completeDay(LocalDate.of(2026, 1, 3), 2L),
			archive.days.single(),
		)
		importer(database).importArchive(
			request(
				unseenArchive,
				jobId = "unseen-stale-membership",
				archiveKey = "unseen-stale-membership",
				receivedAtMs = correctionReceivedAtMs - 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT,
		)
		readReady(database, correction).archive shouldBe correction
	}

	@Test
	fun `receipt cap accepts exact 256 and rejects 257 without poisoning reads`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 1, 5), 4L))
		val initial = request(archive)
		importer(database).importArchive(initial) shouldBe applied(archive, 1)
		val dao = database.importedAmbientStepsDao()
		repeat(com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
			.MAX_RECEIPTS_PER_ARCHIVE - 2
		) { index ->
			val job = "receipt-${index + 2}"
			dao.insertReceipt(
				ImportedAmbientStepsReceiptEntity(
					importJobId = job,
					archiveKey = job,
					receiptIdentity = ImportedAmbientStepsIdentity.receipt(job, job),
					sourceName = "backup.trackerambientsteps",
					receivedAtMs = initial.receipt.receivedAtMs + index + 1L,
					archiveIdentity = archive.identity.value,
					archiveContentChecksum = archive.contentChecksum.value,
					collectedDataEpoch = EPOCH,
				),
			)
		}
		importer(database).importArchive(
			request(
				archive,
				jobId = "receipt-256",
				archiveKey = "receipt-256",
				receivedAtMs = initial.receipt.receivedAtMs + 300L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Duplicate(archive.identity, 1)
		dao.receiptCount() shouldBe 256L

		importer(database).importArchive(
			request(
				archive,
				jobId = "receipt-257",
				archiveKey = "receipt-257",
				receivedAtMs = initial.receipt.receivedAtMs + 301L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		dao.receiptCount() shouldBe 256L
		readReady(database, archive).archive shouldBe archive
	}

	@Test
	fun `day archive cap accepts exact 256 and rejects 257`() = runTest {
		val firstDate = LocalDate.of(2027, 1, 1)
		val sharedDay = completeDay(firstDate, 4L)
		val initial = archive(sharedDay)
		importer(database).importArchive(request(initial)) shouldBe applied(initial, 1)
		repeat(com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
			.MAX_ARCHIVES_PER_DAY - 2
		) { index ->
			val candidate = archive(
				sharedDay,
				completeDay(firstDate.plusDays(index + 1L), index + 1L),
			)
			importer(database).importArchive(
				request(
					candidate,
					jobId = "day-archive-${index + 2}",
					archiveKey = "day-archive-${index + 2}",
				),
			) shouldBe applied(candidate, 1)
		}
		val boundary = archive(
			sharedDay,
			completeDay(firstDate.plusDays(255L), 255L),
		)
		importer(database).importArchive(
			request(boundary, jobId = "day-archive-256", archiveKey = "day-archive-256"),
		) shouldBe applied(boundary, 1)

		val overflow = archive(
			sharedDay,
			completeDay(firstDate.plusDays(256L), 256L),
		)
		importer(database).importArchive(
			request(overflow, jobId = "day-archive-257", archiveKey = "day-archive-257"),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedAmbientStepsDao().archiveDaysForDay(
			sharedDay.identity.value,
			257,
		).size shouldBe 256
		readReady(database, initial).archive shouldBe initial
	}

	@Test
	fun `lineage dependency admission accepts exact bounds and rejects overflow`() {
		importedAmbientStepsLineageAdditionFits(
			existingArchiveMemberCount =
				com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
					.MAX_ARCHIVE_MEMBERS_PER_LINEAGE - AmbientStepsPortableFormatV1.MAX_DAYS,
			existingReceiptCount =
				com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
					.MAX_RECEIPTS_PER_LINEAGE - 1,
			incomingArchiveMemberCount = AmbientStepsPortableFormatV1.MAX_DAYS,
		) shouldBe true
		importedAmbientStepsLineageAdditionFits(
			existingArchiveMemberCount =
				com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
					.MAX_ARCHIVE_MEMBERS_PER_LINEAGE -
					AmbientStepsPortableFormatV1.MAX_DAYS + 1,
			existingReceiptCount = 0,
			incomingArchiveMemberCount = AmbientStepsPortableFormatV1.MAX_DAYS,
		) shouldBe false
		importedAmbientStepsLineageAdditionFits(
			existingArchiveMemberCount = 0,
			existingReceiptCount =
				com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
					.MAX_RECEIPTS_PER_LINEAGE,
			incomingArchiveMemberCount = 1,
		) shouldBe false
	}

	@Test
	fun `public range observation refreshes after import and correction without daily summary`() = runTest {
		val initial = archive(completeDay(LocalDate.of(2026, 1, 3), 5L))
		val request = historyRequest(initial)
		val repository = history(database)
		val emissions = async {
			repository.observeRange(request).take(3).toList()
		}
		yield()
		importer(database).importArchive(
			request(initial, jobId = "observe-initial", archiveKey = "observe-initial"),
		) shouldBe applied(initial, 1)
		yield()
		val correction = archive(completeDay(LocalDate.of(2026, 1, 3), 9L))
		importer(database).importArchive(
			request(correction, jobId = "observe-correction", archiveKey = "observe-correction"),
		) shouldBe applied(correction, 1)

		val snapshots = emissions.await().map { it as AmbientStepsHistoryRead.Snapshot }
		snapshots[0].days.single().total shouldBe AmbientStepsHistoryValue.Unavailable(
			setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.NO_EVIDENCE),
		)
		snapshots[1].days.single().total shouldBe AmbientStepsHistoryValue.Exact(5L)
		snapshots[2].days.single().total shouldBe AmbientStepsHistoryValue.Exact(9L)
		database.dailySummaryDao().getByDay(initial.days.single().structuralEpochDay) shouldBe null
	}

	@Test
	fun `thirty two imported structural days compose through one bounded range snapshot`() = runTest {
		val first = LocalDate.of(2026, 1, 10)
		val archive = archive(
			*(0 until 32).map { offset ->
				completeDay(first.plusDays(offset.toLong()), offset.toLong())
			}.toTypedArray(),
		)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 32)

		val snapshot = history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot

		snapshot.days.size shouldBe 32
		snapshot.days.map { (it.total as AmbientStepsHistoryValue.Exact).count } shouldBe
			(0L until 32L).toList()
	}

	@Test
	fun `recent cursor continues across noncanonical imported day identity`() = runTest {
		val older = archive(
			reidentifyDay(
				completeDay(LocalDate.of(2026, 1, 20), 2L),
				"noncanonical-older",
			),
		)
		val newer = archive(completeDay(LocalDate.of(2026, 1, 21), 3L))
		importer(database).importArchive(request(older)) shouldBe applied(older, 1)
		importer(database).importArchive(
			request(newer, jobId = "recent-newer", archiveKey = "recent-newer"),
		) shouldBe applied(newer, 1)

		val first = history(database).readRecent(
			com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(1),
		) as com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead.Page
		first.days.single().day.epochDay shouldBe newer.days.single().structuralEpochDay
		val cursor = requireNotNull(first.next)
		val second = history(database).readRecent(
			com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(1, cursor),
		) as com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead.Page
		second.days.single().day.epochDay shouldBe older.days.single().structuralEpochDay
		second.days.single().opaqueDayIdentity shouldBe AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.DAY,
			"${older.days.single().structuralEpochDay}|${older.days.single().storedZoneId}|" +
				"${older.days.single().structuralDayStartTimeMs}|" +
				older.days.single().structuralDayEndTimeMs,
		).value
	}

	@Test
	fun `recent cursor is rejected after correction deletion or full-clear epoch change`() = runTest {
		suspend fun seededCursor(): Pair<
			PortableAmbientStepsArchiveV1,
			com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentCursor,
		> {
			val first = archive(completeDay(LocalDate.of(2026, 1, 22), 2L))
			val second = archive(completeDay(LocalDate.of(2026, 1, 23), 3L))
			importer(database).importArchive(request(first)) shouldBe applied(first, 1)
			importer(database).importArchive(
				request(second, jobId = "cursor-second", archiveKey = "cursor-second"),
			) shouldBe applied(second, 1)
			val page = history(database).readRecent(
				com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(1),
			) as com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead.Page
			return second to requireNotNull(page.next)
		}
		suspend fun assertRejected(
			cursor: com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentCursor,
		) {
			history(database).readRecent(
				com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(1, cursor),
			) shouldBe com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead
				.Unavailable(
					com.adsamcik.tracker.stats.api.repository
						.AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
				)
		}

		var (latest, cursor) = seededCursor()
		val correction = archive(completeDay(LocalDate.of(2026, 1, 23), 4L))
		importer(database).importArchive(
			request(correction, jobId = "cursor-correction", archiveKey = "cursor-correction"),
		) shouldBe applied(correction, 1)
		assertRejected(cursor)

		database.close()
		database = newDatabase()
		seedEvidence(database)
		val deletionSeed = seededCursor()
		latest = deletionSeed.first
		cursor = deletionSeed.second
		RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteDay(
			DeleteImportedAmbientStepsDayRequest(
				latest.days.single().identity,
				EPOCH,
				latest.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)
		assertRejected(cursor)

		database.close()
		database = newDatabase()
		seedEvidence(database)
		val clearSeed = seededCursor()
		cursor = clearSeed.second
		database.withTransaction {
			val state = requireNotNull(database.sourceEvidenceStateDao().get())
			val clearedAtMs = clearSeed.first.days.single().structuralDayEndTimeMs + 1L
			database.importedAmbientStepsDao().prepareFullClearFencesInCurrentTransaction(
				EPOCH,
				EPOCH + 1L,
				state.revision + 1L,
				clearedAtMs,
			)
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				EPOCH + 1L,
				null,
				state.deletedSourceEventHighWaterOrdinal,
				clearedAtMs,
			) shouldBe 1
			database.importedAmbientStepsDao()
				.deleteFullClearPayloadInCurrentTransaction(EPOCH + 1L)
		}
		assertRejected(cursor)
	}

	@Test
	fun `recent limit plus one candidates fail closed when their structural span is unbounded`() =
		runTest {
			val older = archive(completeDay(LocalDate.of(2024, 1, 1), 2L))
			val newer = archive(completeDay(LocalDate.of(2026, 1, 1), 3L))
			importer(database).importArchive(request(older)) shouldBe applied(older, 1)
			importer(database).importArchive(
				request(newer, jobId = "span-newer", archiveKey = "span-newer"),
			) shouldBe applied(newer, 1)

			history(database).readRecent(
				com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest(1),
			) shouldBe com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead
				.Unavailable(
					com.adsamcik.tracker.stats.api.repository
						.AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
				)
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
						AmbientStepsPortableLocalOwnerState.ACTIVE,
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
	fun `receipt identity cannot be reused as an incoming fact`() = runTest {
		val receiptIdentity = ImportedAmbientStepsIdentity.receipt("job-1", "archive-1")
		val day = completeDay(LocalDate.of(2026, 2, 4), 3L)
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity(receiptIdentity),
			day.structuralDayStartTimeMs,
			day.structuralDayEndTimeMs,
			3L,
		)
		val collision = archive(
			PortableAmbientStepsDayV1.create(
				identity = day.identity,
				structuralEpochDay = day.structuralEpochDay,
				storedZoneId = day.storedZoneId,
				structuralDayStartTimeMs = day.structuralDayStartTimeMs,
				structuralDayEndTimeMs = day.structuralDayEndTimeMs,
				retainedFromTimeMs = null,
				coverage = PortableAmbientStepsCoverage.COMPLETE,
				partialCauses = emptyList(),
				retainedStepCount = 3L,
				facts = listOf(fact),
				gaps = emptyList(),
			),
		)

		importer(database).importArchive(request(collision)) shouldBe
			ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
			)
	}

	@Test
	fun `incoming day and gap cannot reuse native owner namespaces`() = runTest {
		val dayArchive = archive(completeDay(LocalDate.of(2026, 2, 5), 3L))
		val daySubject = RoomImportPortableAmbientSteps(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			localOriginSource = { identities ->
				listOf(
					AmbientStepsPortableLocalOwner(
						identities.single { it == dayArchive.days.single().identity.value },
						AmbientStepsPortableLocalOwnerKind.FACT,
						AmbientStepsPortableLocalOwnerState.ACTIVE,
						null,
					),
				)
			},
		)
		daySubject.importArchive(
			request(dayArchive, jobId = "day-native", archiveKey = "day-native"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)

		val gapDay = partialDay(LocalDate.of(2026, 2, 6), 3L, "UTC")
		val gapArchive = archive(gapDay)
		val gapIdentity = gapDay.gaps.single().identity.value
		val gapSubject = RoomImportPortableAmbientSteps(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			localOriginSource = {
				listOf(
					AmbientStepsPortableLocalOwner(
						gapIdentity,
						AmbientStepsPortableLocalOwnerKind.GAP,
						AmbientStepsPortableLocalOwnerState.ACTIVE,
						gapDay.gaps.single().contentChecksum.value,
					),
				)
			},
		)
		gapSubject.importArchive(
			request(gapArchive, jobId = "gap-native", archiveKey = "gap-native"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
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
	fun `mutated over-cap fact list is rejected before snapshot copying`() = runTest {
		val date = LocalDate.of(2026, 2, 7)
		val original = completeDay(date, 1L)
		val mutableFacts = original.facts.toMutableList()
		val mutableDay = original.copy(facts = mutableFacts)
		val mutableDays = mutableListOf(mutableDay)
		val archive = PortableAmbientStepsArchiveV1.create(mutableDays)
		repeat(AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY) {
			mutableFacts += original.facts.single()
		}

		importer(database).importArchive(
			request(archive).copy(
				metadata = metadata(archive).copy(
					factCount = AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY,
				),
			),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	@Test
	fun `full-window outside-authority days remain partial for zero and positive counts`() = runTest {
		val archive = archive(
			outsideAuthorityDay(LocalDate.of(2026, 2, 8), 0L),
			outsideAuthorityDay(LocalDate.of(2026, 2, 9), 9L),
		)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 2)

		val snapshot = history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot
		snapshot.days.map { it.total } shouldBe listOf(
			AmbientStepsHistoryValue.Partial(
				0L,
				setOf(
					com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
						.PARTIAL_COVERAGE,
				),
			),
			AmbientStepsHistoryValue.Partial(
				9L,
				setOf(
					com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
						.PARTIAL_COVERAGE,
				),
			),
		)
		val numeric = database.withTransaction {
			val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
			history(database).readNumericRangeInCurrentTransaction(
				historyRequest(archive),
				revision,
			)
		} as AmbientStepsNumericRangeRead.Snapshot
		numeric.days.map { it.total } shouldBe snapshot.days.map { it.total }
		reexport(database, archive) shouldBe archive
	}

	@Test
	fun `production numeric chain discovers imported DST zone before fallback and tracks mutations`() =
		runTest {
			val day = completeDay(LocalDate.of(2026, 10, 25), 5L, "Europe/Prague")
			val archive = archive(day)
			importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
			val numeric = RoomStepsNumericSummaryRepository(
				database,
				Dispatchers.Unconfined,
				history(database),
			)
			val request = StepsNumericSummaryRequest(
				day.structuralEpochDay,
				day.structuralEpochDay,
				"UTC",
			)
			numeric.read(request) shouldBe StepsNumericSummary.Ready(
				listOf(StepsNumericDay(day.structuralEpochDay, 5L)),
			)
			val observed = async { numeric.observe(request).take(3).toList() }
			yield()

			val correction = archive(
				completeDay(LocalDate.of(2026, 10, 25), 8L, "Europe/Prague"),
			)
			importer(database).importArchive(
				request(correction, jobId = "numeric-correction", archiveKey = "numeric-correction"),
			) shouldBe applied(correction, 1)
			yield()
			numeric.read(request) shouldBe StepsNumericSummary.Ready(
				listOf(StepsNumericDay(day.structuralEpochDay, 8L)),
			)

			RoomDeleteImportedAmbientStepsDay(
				database,
				database.importedAmbientStepsDao(),
				Dispatchers.Unconfined,
			).deleteDay(
				DeleteImportedAmbientStepsDayRequest(
					day.identity,
					EPOCH,
					day.structuralDayEndTimeMs + 1L,
				),
			) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(2)
			numeric.read(request) shouldBe StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
			observed.await() shouldBe listOf(
				StepsNumericSummary.Ready(
					listOf(StepsNumericDay(day.structuralEpochDay, 5L)),
				),
				StepsNumericSummary.Ready(
					listOf(StepsNumericDay(day.structuralEpochDay, 8L)),
				),
				StepsNumericSummary.Unverifiable(
					StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
				),
			)
		}

	@Test
	fun `production numeric chain preserves 23 hour authority and rejects stored-zone conflict`() =
		runTest {
			val day = completeDay(LocalDate.of(2026, 3, 29), 6L, "Europe/Prague")
			day.structuralDayEndTimeMs - day.structuralDayStartTimeMs shouldBe 23L * 60L * 60L * 1_000L
			val archive = archive(day)
			importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
			val numeric = RoomStepsNumericSummaryRepository(
				database,
				Dispatchers.Unconfined,
				history(database),
			)
			val request = StepsNumericSummaryRequest(
				day.structuralEpochDay,
				day.structuralEpochDay,
				"Pacific/Honolulu",
			)
			numeric.read(request) shouldBe StepsNumericSummary.Ready(
				listOf(StepsNumericDay(day.structuralEpochDay, 6L)),
			)

			database.dailySummaryDao().upsert(
				dateEpochDay = day.structuralEpochDay,
				totalDistanceM = 0f,
				totalSteps = 0,
				totalDurationMs = 0L,
				tripCount = 0,
				activeTrackingMs = 0L,
				lastUpdatedMs = day.structuralDayEndTimeMs,
				calendarZoneId = "UTC",
			)
			numeric.read(request) shouldBe StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
		}

	@Test
	fun `pending imported deletion affects only imported-owned numeric days`() = runTest {
		val sessionOnlyDay = completeDay(LocalDate.of(2026, 6, 20), 7L)
		val importedDay = completeDay(LocalDate.of(2026, 6, 21), 9L)
		seedCompleteSessionDay(sessionOnlyDay, 7L, 1)
		seedCompleteSessionDay(importedDay, 4L, 2)
		seedSessionCaptureWithAmbientRevoked()
		val archive = archive(importedDay)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		RoomDeleteImportedAmbientStepsAfterConsentReset(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				SESSION_AMBIENT_REVOKED_CONSENT,
				importedDay.structuralDayEndTimeMs + 1L,
			),
		) shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Deleted(1)
		val numeric = RoomStepsNumericSummaryRepository(
			database,
			Dispatchers.Unconfined,
			history(database),
		)

		numeric.read(
			StepsNumericSummaryRequest(
				sessionOnlyDay.structuralEpochDay,
				sessionOnlyDay.structuralEpochDay,
				"UTC",
			),
		) shouldBe StepsNumericSummary.Ready(
			listOf(StepsNumericDay(sessionOnlyDay.structuralEpochDay, 7L)),
		)
		numeric.read(
			StepsNumericSummaryRequest(
				importedDay.structuralEpochDay,
				importedDay.structuralEpochDay,
				"UTC",
			),
		) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
		(history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot).days.single().let { day ->
			day.importedDisposition shouldBe AmbientStepsImportedDisposition.DELETED
			day.total shouldBe AmbientStepsHistoryValue.Unavailable(
				setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.DELETED),
			)
		}
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
		val reidentified = archive(reidentifyDay(archive.days.single(), "retained-reidentified"))
		importer(database).importArchive(
			request(
				reidentified,
				jobId = "retained-reidentified",
				archiveKey = "retained-reidentified",
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.RETAINED_DAY,
		)
		reexportResult(database, archive) shouldBe ExportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_RETAINED,
		)
		(history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot).days.single().let { day ->
			day.importedDisposition shouldBe AmbientStepsImportedDisposition.RETAINED
			day.total shouldBe AmbientStepsHistoryValue.Unavailable(
				setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.RETAINED),
			)
		}
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
		(history(database).readRange(historyRequest(archive)) as
			AmbientStepsHistoryRead.Snapshot).days.single().importedDisposition shouldBe
			AmbientStepsImportedDisposition.DELETED
		val reidentified = archive(reidentifyDay(day, "deleted-reidentified"))
		importer(database).importArchive(
			request(
				reidentified,
				jobId = "deleted-reidentified",
				archiveKey = "deleted-reidentified",
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
		val unrelated = archive(completeDay(LocalDate.of(2026, 5, 4), 2L))
		importer(database).importArchive(
			request(unrelated, jobId = "unrelated-day", archiveKey = "unrelated-day"),
		) shouldBe applied(unrelated, 1)
		val unrelatedZone = archive(
			completeDay(LocalDate.of(2026, 5, 1), 3L, "Pacific/Honolulu"),
		)
		importer(database).importArchive(
			request(
				unrelatedZone,
				jobId = "unrelated-zone",
				archiveKey = "unrelated-zone",
			),
		) shouldBe applied(unrelatedZone, 1)
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
	fun `structurally corrupt rehashed fence is typed stored corruption`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 5, 5), 8L))
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
		val fence = requireNotNull(
			database.importedAmbientStepsDao().fence(archive.days.single().identity.value),
		)
		val corruptZone = "Europe/Prague"
		val rehashed = ImportedAmbientStepsIdentity.digest(
			"tracker-imported-ambient-steps-day-fence-v1",
			listOf(
				fence.dayIdentity,
				fence.deletionScopeIdentity,
				fence.fenceKind,
				fence.collectedDataEpoch,
				fence.sourceEvidenceRevision,
				fence.fencedAtMs,
				fence.retainedFromMs,
				fence.latestImportRevision,
				fence.latestContentChecksum,
				fence.structuralEpochDay,
				corruptZone,
				fence.structuralDayStartTimeMs,
				fence.structuralDayEndTimeMs,
				fence.revisionCount,
				fence.archiveCount,
				fence.factRowCount,
				fence.gapRowCount,
				fence.protectedIdentityCount,
				fence.protectedIdentitySetChecksum,
				fence.lineageChecksum,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_fence SET stored_zone_id = ?, " +
				"effect_checksum = ? WHERE day_identity = ?",
			arrayOf(corruptZone, rehashed, archive.days.single().identity.value),
		)

		val unrelated = archive(completeDay(LocalDate.of(2026, 5, 6), 2L))
		importer(database).importArchive(
			request(
				unrelated,
				jobId = "after-fence-corrupt",
				archiveKey = "after-fence-corrupt",
			),
		) shouldBe ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `consent reset primitive requires revoked authority and deletes one lineage`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 6, 1), 8L))
		val secondArchive = archive(completeDay(LocalDate.of(2026, 6, 2), 2L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		importer(database).importArchive(
			request(secondArchive, jobId = "second-before-revoke", archiveKey = "second-before-revoke"),
		) shouldBe applied(secondArchive, 1)
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
		seedEligibleConsent(database)
		val later = archive(completeDay(LocalDate.of(2026, 6, 3), 3L))
		importer(database).importArchive(
			request(later, jobId = "before-delete-complete", archiveKey = "before-delete-complete"),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.SOURCE_DELETED,
		)
		(history(database).readRange(historyRequest(secondArchive)) as
			AmbientStepsHistoryRead.Snapshot).days.single().let { day ->
			day.importedDisposition shouldBe AmbientStepsImportedDisposition.DELETED
			day.total shouldBe AmbientStepsHistoryValue.Unavailable(
				setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.DELETED),
			)
		}
		seedSecondRevokedConsent(database)
		deleter.deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				SECOND_REVOKED_CONSENT_EPOCH,
				secondArchive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Deleted(1)
		deleter.deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				SECOND_REVOKED_CONSENT_EPOCH,
				secondArchive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Complete
		seedSecondEligibleConsent(database)
		importer(database).importArchive(
			request(later, jobId = "after-reset", archiveKey = "after-reset"),
		) shouldBe applied(later, 1)
		val subsequent = archive(completeDay(LocalDate.of(2026, 6, 4), 4L))
		importer(database).importArchive(
			request(subsequent, jobId = "after-reset-2", archiveKey = "after-reset-2"),
		) shouldBe applied(subsequent, 1)
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
	fun `native overlap cancellation propagates identically without imported mutation`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 7, 2), 8L))
		val cancellation = CancellationException("cancel native overlap")
		val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		val subject = RoomImportPortableAmbientSteps(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			localOriginSource = { throw cancellation },
		)

		assertFailsWith<CancellationException> {
			subject.importArchive(request(archive))
		} shouldBe cancellation
		val dao = database.importedAmbientStepsDao()
		dao.archiveCount() shouldBe 0L
		dao.receiptCount() shouldBe 0L
		dao.dayRevisionCount() shouldBe 0L
		dao.fenceCount() shouldBe 0L
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe revision
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
		val unrelated = archive(completeDay(LocalDate.of(2026, 8, 2), 2L))
		importer(database).importArchive(
			request(unrelated, jobId = "after-corruption", archiveKey = "after-corruption"),
		) shouldBe applied(unrelated, 1)
		readReady(database, unrelated).archive shouldBe unrelated
		importer(database).importArchive(request(archive)) shouldBe
			ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
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
	fun `full collected data clear preserves no resurrection authority across epoch`() = runTest {
		val deletedArchive = archive(completeDay(LocalDate.of(2026, 9, 2), 8L))
		val liveArchive = archive(completeDay(LocalDate.of(2026, 9, 3), 9L))
		importer(database).importArchive(request(deletedArchive)) shouldBe applied(deletedArchive, 1)
		importer(database).importArchive(
			request(liveArchive, jobId = "live-before-clear", archiveKey = "live-before-clear"),
		) shouldBe applied(liveArchive, 1)
		RoomDeleteImportedAmbientStepsDay(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteDay(
			DeleteImportedAmbientStepsDayRequest(
				deletedArchive.days.single().identity,
				EPOCH,
				deletedArchive.days.single().structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)
		database.withTransaction {
			val state = requireNotNull(database.sourceEvidenceStateDao().get())
			val nextRevision = Math.addExact(state.revision, 1L)
			val dao = database.importedAmbientStepsDao()
			dao.prepareFullClearFencesInCurrentTransaction(
				oldCollectedDataEpoch = EPOCH,
				newCollectedDataEpoch = EPOCH + 1L,
				sourceEvidenceRevision = nextRevision,
				clearedAtMs = liveArchive.days.single().structuralDayEndTimeMs + 1L,
			)
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				epoch = EPOCH + 1L,
				retainedFromMs = null,
				deletedSourceEventHighWaterOrdinal = state.deletedSourceEventHighWaterOrdinal,
				updatedAtMs = liveArchive.days.single().structuralDayEndTimeMs + 1L,
			) shouldBe 1
			dao.deleteFullClearPayloadInCurrentTransaction(EPOCH + 1L)
		}
		(database.importedAmbientStepsDao().protectedIdentityCount() > 0L) shouldBe true
		database.importedAmbientStepsDao().allFences(3).all {
			it.collectedDataEpoch == EPOCH + 1L
		} shouldBe true

		importer(database).importArchive(
			request(
				deletedArchive,
				jobId = "old-replay",
				archiveKey = "old-replay",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
		importer(database).importArchive(
			request(
				liveArchive,
				jobId = "live-replay",
				archiveKey = "live-replay",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
		val reidentifiedLive = archive(
			reidentifyDay(liveArchive.days.single(), "full-clear-reidentified"),
		)
		importer(database).importArchive(
			request(
				reidentifiedLive,
				jobId = "full-clear-reidentified",
				archiveKey = "full-clear-reidentified",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe ImportPortableAmbientStepsResult.Blocked(
			PortableAmbientStepsImportBlockedReason.DELETED_DAY,
		)
		val unrelated = archive(completeDay(LocalDate.of(2026, 9, 4), 3L))
		importer(database).importArchive(
			request(
				unrelated,
				jobId = "unrelated-new-epoch",
				archiveKey = "unrelated-new-epoch",
				expectedEpoch = EPOCH + 1L,
			),
		) shouldBe applied(unrelated, 1)
	}

	@Test
	fun `full clear preserves shared archive authority for every structural day`() = runTest {
		val archive = archive(
			completeDay(LocalDate.of(2026, 9, 5), 4L),
			completeDay(LocalDate.of(2026, 9, 6), 5L),
		)
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 2)
		val dao = database.importedAmbientStepsDao()
		database.withTransaction {
			val state = requireNotNull(database.sourceEvidenceStateDao().get())
			val clearedAtMs = archive.days.maxOf { it.structuralDayEndTimeMs }
			dao.prepareFullClearFencesInCurrentTransaction(
				oldCollectedDataEpoch = EPOCH,
				newCollectedDataEpoch = EPOCH + 1L,
				sourceEvidenceRevision = state.revision + 1L,
				clearedAtMs = clearedAtMs,
			)
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				epoch = EPOCH + 1L,
				retainedFromMs = null,
				deletedSourceEventHighWaterOrdinal = state.deletedSourceEventHighWaterOrdinal,
				updatedAtMs = clearedAtMs,
			) shouldBe 1
			dao.deleteFullClearPayloadInCurrentTransaction(EPOCH + 1L)
		}

		archive.days.forEach { day ->
			dao.fence(day.identity.value)?.fenceKind shouldBe
				ImportedAmbientStepsDayFenceEntity.FENCE_FULL_CLEAR
		}
		dao.protectedIdentityOwners(listOf(archive.identity.value), 3)
			.map { it.ownerDayIdentity }
			.toSet() shouldBe archive.days.map { it.identity.value }.toSet()
		dao.dayRevisionCount() shouldBe 0L
	}

	@Test
	fun `full clear preparation cancellation rolls back fences epoch and payload`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 4), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val before = requireNotNull(database.sourceEvidenceStateDao().get())

		assertFailsWith<CancellationException> {
			database.withTransaction {
				database.importedAmbientStepsDao().prepareFullClearFencesInCurrentTransaction(
					oldCollectedDataEpoch = EPOCH,
					newCollectedDataEpoch = EPOCH + 1L,
					sourceEvidenceRevision = before.revision + 1L,
					clearedAtMs = archive.days.single().structuralDayEndTimeMs + 1L,
				)
				throw CancellationException("cancel full clear")
			}
		}

		requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch shouldBe EPOCH
		database.importedAmbientStepsDao().fence(archive.days.single().identity.value) shouldBe null
		readReady(database, archive).archive shouldBe archive
		assertFailsWith<com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure> {
			database.withTransaction {
				database.importedAmbientStepsDao().prepareFullClearFencesInCurrentTransaction(
					oldCollectedDataEpoch = EPOCH + 1L,
					newCollectedDataEpoch = EPOCH + 2L,
					sourceEvidenceRevision = before.revision + 1L,
					clearedAtMs = archive.days.single().structuralDayEndTimeMs + 1L,
				)
			}
		}
	}

	@Test
	fun `full clear failure after epoch write rolls back all authority changes`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 7), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val before = requireNotNull(database.sourceEvidenceStateDao().get())

		assertFailsWith<IllegalStateException> {
			database.withTransaction {
				val dao = database.importedAmbientStepsDao()
				dao.prepareFullClearFencesInCurrentTransaction(
					oldCollectedDataEpoch = EPOCH,
					newCollectedDataEpoch = EPOCH + 1L,
					sourceEvidenceRevision = before.revision + 1L,
					clearedAtMs = archive.days.single().structuralDayEndTimeMs + 1L,
				)
				database.sourceEvidenceStateDao().updateAfterFullDeletion(
					epoch = EPOCH + 1L,
					retainedFromMs = null,
					deletedSourceEventHighWaterOrdinal =
						before.deletedSourceEventHighWaterOrdinal,
					updatedAtMs = archive.days.single().structuralDayEndTimeMs + 1L,
				) shouldBe 1
				error("fail after epoch")
			}
		}

		requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch shouldBe EPOCH
		database.importedAmbientStepsDao().fence(archive.days.single().identity.value) shouldBe null
		readReady(database, archive).archive shouldBe archive
	}

	@Test
	fun `full clear lineage overflow aborts before installing fences`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 8), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val day = archive.days.single()
		val dao = database.importedAmbientStepsDao()
		repeat(com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
			.MAX_ARCHIVES_PER_DAY
		) { index ->
			val checksum = "sha256:" + (index + 1).toString(16).padStart(64, '0')
			val archiveIdentity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.ARCHIVE,
				checksum,
			).value
			dao.insertArchive(
				ImportedAmbientStepsArchiveEntity(
					archiveIdentity = archiveIdentity,
					contentChecksum = checksum,
					sourceFormat = AmbientStepsPortableFormatV1.FORMAT,
					sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
					encodedByteCount = 1L,
					dayCount = 1,
					factCount = 1,
					gapCount = 0,
					collectedDataEpoch = EPOCH,
					firstReceivedAtMs = day.structuralDayEndTimeMs,
				),
			)
			dao.insertArchiveDays(listOf(
				ImportedAmbientStepsArchiveDayEntity(
					archiveIdentity = archiveIdentity,
					ordinal = 0,
					dayIdentity = day.identity.value,
					dayContentChecksum = day.contentChecksum.value,
					boundDayImportRevision = 1L,
					factCount = 1,
					gapCount = 0,
				),
			))
		}

		val failure = assertFailsWith<
			com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure,
		> {
			database.withTransaction {
				dao.prepareFullClearFencesInCurrentTransaction(
					oldCollectedDataEpoch = EPOCH,
					newCollectedDataEpoch = EPOCH + 1L,
					sourceEvidenceRevision =
						requireNotNull(database.sourceEvidenceStateDao().get()).revision + 1L,
					clearedAtMs = day.structuralDayEndTimeMs + 1L,
				)
			}
		}
		failure.reason shouldBe
			com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
				.DEPENDENCY_OVERFLOW
		dao.fence(day.identity.value) shouldBe null
		dao.dayRevisionCount() shouldBe 1L
	}

	@Test
	fun `known zone and enum corruption is typed unverifiable rather than retryable storage`() = runTest {
		val zoneArchive = archive(completeDay(LocalDate.of(2026, 9, 5), 8L))
		importer(database).importArchive(request(zoneArchive)) shouldBe applied(zoneArchive, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_revision SET stored_zone_id = 'Not/AZone', " +
				"day_content_checksum = ? WHERE day_identity = ?",
			arrayOf("sha256:${"f".repeat(64)}", zoneArchive.days.single().identity.value),
		)
		ImportedAmbientStepsRoomReader(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).read(range(zoneArchive)) shouldBe ImportedAmbientStepsSnapshot.Unverifiable(
			ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
		)

		database.close()
		database = newDatabase()
		seedEvidence(database)
		val enumArchive = archive(completeDay(LocalDate.of(2026, 9, 6), 8L))
		importer(database).importArchive(request(enumArchive)) shouldBe applied(enumArchive, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_revision SET coverage = 'UNKNOWN_VALUE', " +
				"day_content_checksum = ? WHERE day_identity = ?",
			arrayOf("sha256:${"e".repeat(64)}", enumArchive.days.single().identity.value),
		)
		ImportedAmbientStepsRoomReader(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).read(range(enumArchive)) shouldBe ImportedAmbientStepsSnapshot.Unverifiable(
			ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Test
	fun `retention maps corrupt candidate zone without authority mutation`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 9), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		val day = archive.days.single()
		val floor = day.structuralDayEndTimeMs
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, floor, floor) shouldBe 1
		val beforeRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_revision SET stored_zone_id = 'Not/AZone' " +
				"WHERE day_identity = ?",
			arrayOf(day.identity.value),
		)

		RoomTruncateImportedAmbientStepsRetention(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).truncateNext(
			TruncateImportedAmbientStepsRetentionRequest(floor, EPOCH, floor + 1L),
		) shouldBe TruncateImportedAmbientStepsRetentionResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		database.importedAmbientStepsDao().fence(day.identity.value) shouldBe null
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 1L
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe beforeRevision
	}

	@Test
	fun `consent deletion maps corrupt candidate coverage and rolls back source fence`() = runTest {
		val archive = archive(completeDay(LocalDate.of(2026, 9, 10), 8L))
		importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
		seedRevokedConsent(database)
		val day = archive.days.single()
		val beforeRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_steps_day_revision SET coverage = 'UNKNOWN_VALUE' " +
				"WHERE day_identity = ?",
			arrayOf(day.identity.value),
		)

		RoomDeleteImportedAmbientStepsAfterConsentReset(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
		).deleteNext(
			DeleteImportedAmbientStepsAfterConsentResetRequest(
				EPOCH,
				REVOKED_CONSENT_EPOCH,
				day.structuralDayEndTimeMs,
			),
		) shouldBe DeleteImportedAmbientStepsAfterConsentResetResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		database.importedAmbientStepsDao().sourceFence() shouldBe null
		database.importedAmbientStepsDao().fence(day.identity.value) shouldBe null
		database.importedAmbientStepsDao().dayRevisionCount() shouldBe 1L
		requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe beforeRevision
	}

	@Test
	fun `rehashed arithmetic corruption is typed unverifiable rather than retryable storage`() =
		runTest {
			val date = LocalDate.of(2026, 9, 7)
			val (start, end) = dayBounds(date, "UTC")
			val boundary = start + (end - start) / 2L
			val facts = listOf(
				PortableAmbientStepsFactV1.create(
					identity(AmbientStepsPortableIdentityKind.FACT, "overflow-first"),
					start,
					boundary,
					1L,
				),
				PortableAmbientStepsFactV1.create(
					identity(AmbientStepsPortableIdentityKind.FACT, "overflow-second"),
					boundary,
					end,
					2L,
				),
			)
			val archive = archive(portableDay(date, "UTC", facts, emptyList(), emptyList()))
			importer(database).importArchive(request(archive)) shouldBe applied(archive, 1)
			facts.forEach { fact ->
				val checksum = AmbientStepsPortableIntegrity.factChecksum(
					fact.identity,
					fact.intervalStartTimeMs,
					fact.intervalEndTimeMs,
					Long.MAX_VALUE,
				).value
				database.openHelper.writableDatabase.execSQL(
					"UPDATE imported_ambient_steps_fact SET step_count = ?, " +
						"content_checksum = ? WHERE fact_identity = ?",
					arrayOf(Long.MAX_VALUE, checksum, fact.identity.value),
				)
			}

			ImportedAmbientStepsRoomReader(
				database,
				database.importedAmbientStepsDao(),
				Dispatchers.Unconfined,
			).read(range(archive)) shouldBe ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		}

	private fun importer(database: AppDatabase) = RoomImportPortableAmbientSteps(
		database,
		database.importedAmbientStepsDao(),
		Dispatchers.Unconfined,
	)

	private fun history(database: AppDatabase) = DefaultAmbientStepsHistoryRepository(
		database,
		database.importedAmbientStepsDao(),
		StepsSegmentHistorySelector(database, SourceProductLaneExecutionAuthority { false }),
		Dispatchers.Unconfined,
	)

	private fun historyRequest(
		archive: PortableAmbientStepsArchiveV1,
	) = AmbientStepsHistoryRangeRequest(
		archive.days.map {
			AmbientStepsStructuralDay(it.structuralEpochDay, it.storedZoneId)
		}.sortedWith(
			compareBy(AmbientStepsStructuralDay::epochDay)
				.thenBy(AmbientStepsStructuralDay::storedZoneId),
		),
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
		receivedAtMs: Long = archive.days.maxOf { it.structuralDayEndTimeMs },
	) = ImportPortableAmbientStepsRequest(
		archive = archive,
		receipt = PortableAmbientStepsImportReceipt(
			jobId,
			archiveKey,
			"backup.trackerambientsteps",
			receivedAtMs,
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

	private fun outsideAuthorityDay(
		date: LocalDate,
		count: Long,
		zoneId: String = "UTC",
	): PortableAmbientStepsDayV1 {
		val (start, end) = dayBounds(date, zoneId)
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "outside-fact-${date.toEpochDay()}"),
			start,
			end,
			count,
		)
		return PortableAmbientStepsDayV1.create(
			identity = identity(
				AmbientStepsPortableIdentityKind.DAY,
				"outside-${date.toEpochDay()}|$zoneId|$start|$end",
			),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = zoneId,
			structuralDayStartTimeMs = start,
			structuralDayEndTimeMs = end,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.PARTIAL,
			partialCauses = listOf(PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY),
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

	private fun reidentifyDay(
		day: PortableAmbientStepsDayV1,
		suffix: String,
	): PortableAmbientStepsDayV1 {
		val facts = day.facts.mapIndexed { index, fact ->
			PortableAmbientStepsFactV1.create(
				identity(AmbientStepsPortableIdentityKind.FACT, "$suffix-fact-$index"),
				fact.intervalStartTimeMs,
				fact.intervalEndTimeMs,
				fact.stepCount,
			)
		}
		val gaps = day.gaps.mapIndexed { index, gap ->
			PortableAmbientStepsGapV1.create(
				identity(AmbientStepsPortableIdentityKind.GAP, "$suffix-gap-$index"),
				gap.intervalStartTimeMs,
				gap.intervalEndTimeMs,
				gap.reason,
			)
		}
		return PortableAmbientStepsDayV1.create(
			identity = identity(AmbientStepsPortableIdentityKind.DAY, "$suffix-day"),
			structuralEpochDay = day.structuralEpochDay,
			storedZoneId = day.storedZoneId,
			structuralDayStartTimeMs = day.structuralDayStartTimeMs,
			structuralDayEndTimeMs = day.structuralDayEndTimeMs,
			retainedFromTimeMs = day.retainedFromTimeMs,
			coverage = day.coverage,
			partialCauses = day.partialCauses,
			retainedStepCount = day.retainedStepCount,
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

	@Suppress("LongMethod")
	private suspend fun seedCompleteSessionDay(
		day: PortableAmbientStepsDayV1,
		steps: Long,
		index: Int,
	) {
		require(index in 1..2)
		val logicalId = "pending-delete-logical-$index"
		val runId = "pending-delete-run-$index"
		val admissionOrdinal = SESSION_ADMISSION_BASE + index
		val endTimeMs = day.structuralDayStartTimeMs + SESSION_DURATION_MS
		if (index == 1) {
			database.sourceProjectionStateDao().installProductLane(
				SourceProductProjectionLaneEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
					projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
					projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
					captureModeMask = 1L,
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
					activatedRolloutRevision = 1L,
					activationOrdinal = 1L,
					contiguousAdmissionOrdinal = SESSION_ADMISSION_BASE + 2L,
					captureAdmissionCutoffOrdinal = null,
					retentionRequired = true,
					status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
					installedAtMs = 1L,
					updatedAtMs = 2L,
				),
			)
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = SESSION_START_ORIGIN,
				clockDomainId = SESSION_BOOT_ID,
				startedAtMs = day.structuralDayStartTimeMs,
				startedElapsedNanos = 1L,
				cutoffAtMs = endTimeMs,
				cutoffElapsedNanos = 1L + SESSION_DURATION_MS * 1_000_000L,
				completedAtMs = endTimeMs,
				finalAdmissionOrdinal = admissionOrdinal,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = SESSION_BOOT_ID,
			),
		)
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = day.structuralDayStartTimeMs,
				endTimeMs = endTimeMs,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = endTimeMs,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = day.structuralDayStartTimeMs,
				startedElapsedNanos = 1L,
				completedAtMs = endTimeMs,
				completionReason = "USER_STOP",
				bootId = SESSION_BOOT_ID,
				leaseGeneration = 1L,
				startOrigin = SESSION_START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "pending-delete-delivery-$index",
				startCommandGeneration = index.toLong(),
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = endTimeMs,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = endTimeMs,
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = 1L,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = SESSION_CAPTURE_CONSENT,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = 1L,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = SESSION_POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = SESSION_START_ORIGIN,
			effectiveBootId = SESSION_BOOT_ID,
			effectiveElapsedRealtimeNanos = 1L,
			effectiveWallTimeMs = day.structuralDayStartTimeMs,
			zoneId = day.storedZoneId,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(
					unsignedManifest,
					listOf(source),
				),
			),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "pending-delete-instance-$index",
				registrationGeneration = index.toLong(),
				lastAdmissionOrdinal = admissionOrdinal,
				lastSourceSequence = 1L,
				appDrainComplete = true,
				providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = endTimeMs,
			),
		)
		val sourceEventId = "pending-delete-event-$index"
		val logicalFactId = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:$sourceEventId"
		val unsignedFact = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = sourceEventId,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = day.structuralDayStartTimeMs,
			intervalEndTimeMs = endTimeMs,
			intervalStartElapsedRealtimeNanos = 1L,
			intervalEndElapsedRealtimeNanos = 1L + SESSION_DURATION_MS * 1_000_000L,
			clockDomainId = SESSION_BOOT_ID,
			bootClockDomainId = SESSION_BOOT_ID,
			cumulativeStepCountStart = 100L,
			cumulativeStepCountEnd = 100L + steps,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = steps,
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = SESSION_POLICY_REVISION,
			captureConsentEpoch = SESSION_CAPTURE_CONSENT,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending",
			appliedAtMs = endTimeMs,
		)
		database.stepFactRevisionDao().insert(
			unsignedFact.copy(
				effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedFact),
			),
		)
	}

	private suspend fun seedSessionCaptureWithAmbientRevoked() {
		val policy = SourcePolicyEntity(
			policyRevision = SESSION_POLICY_REVISION,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			enabled = true,
			qosCode = 1,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = SESSION_CAPTURE_CONSENT,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = SESSION_BOOT_ID,
			effectiveElapsedRealtimeNanos = 1L,
			effectiveWallTimeMs = 0L,
			changeReason = "test pending imported deletion",
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy))
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = SESSION_CAPTURE_CONSENT,
					eligible = true,
					persistenceEligible = true,
					policyRevision = SESSION_POLICY_REVISION,
					effectiveBootId = SESSION_BOOT_ID,
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 0L,
					changeReason = "test session capture",
				),
				SourceConsentEpochEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					epoch = SESSION_AMBIENT_REVOKED_CONSENT,
					eligible = false,
					persistenceEligible = false,
					policyRevision = SESSION_POLICY_REVISION,
					effectiveBootId = SESSION_BOOT_ID,
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 0L,
					changeReason = "test ambient revoke",
				),
			),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = SESSION_POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 1L,
			),
		)
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

	private suspend fun seedSecondRevokedConsent(database: AppDatabase) {
		val policy = policyForConsent(
			policyRevision = 4L,
			consentEpoch = null,
			eligible = false,
			changeReason = "test second revoke",
		)
		val consent = consentForPolicy(
			epoch = SECOND_REVOKED_CONSENT_EPOCH,
			policyRevision = policy.policyRevision,
			eligible = false,
			changeReason = "test second revoke",
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy))
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent))
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 3L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = policy.policyRevision,
			legacySettingsFingerprint = null,
			updatedAtMs = 4L,
		) shouldBe 1
	}

	private suspend fun seedSecondEligibleConsent(database: AppDatabase) {
		val policy = policyForConsent(
			policyRevision = 5L,
			consentEpoch = SECOND_ELIGIBLE_CONSENT_EPOCH,
			eligible = true,
			changeReason = "test second reset",
		)
		val consent = consentForPolicy(
			epoch = SECOND_ELIGIBLE_CONSENT_EPOCH,
			policyRevision = policy.policyRevision,
			eligible = true,
			changeReason = "test second reset",
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy))
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent))
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 4L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = policy.policyRevision,
			legacySettingsFingerprint = null,
			updatedAtMs = 5L,
		) shouldBe 1
	}

	private fun policyForConsent(
		policyRevision: Long,
		consentEpoch: Long?,
		eligible: Boolean,
		changeReason: String,
	) = SourcePolicyEntity(
		policyRevision = policyRevision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = eligible,
		captureConsentEpoch = null,
		controlConsentEpoch = null,
		ambientConsentEpoch = consentEpoch,
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = policyRevision,
		effectiveWallTimeMs = policyRevision,
		changeReason = changeReason,
	)

	private fun consentForPolicy(
		epoch: Long,
		policyRevision: Long,
		eligible: Boolean,
		changeReason: String,
	) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		epoch = epoch,
		eligible = eligible,
		persistenceEligible = eligible,
		policyRevision = policyRevision,
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = policyRevision,
		effectiveWallTimeMs = policyRevision,
		changeReason = changeReason,
	)

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
		const val SECOND_REVOKED_CONSENT_EPOCH = 4L
		const val SECOND_ELIGIBLE_CONSENT_EPOCH = 5L
		const val SESSION_POLICY_REVISION = 6L
		const val SESSION_CAPTURE_CONSENT = 7L
		const val SESSION_AMBIENT_REVOKED_CONSENT = 8L
		const val SESSION_ADMISSION_BASE = 20L
		const val SESSION_DURATION_MS = 60L * 60L * 1_000L
		const val SESSION_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val SESSION_BOOT_ID = "pending-delete-boot"
		const val REOPEN_DATABASE = "imported-ambient-steps-reopen"
	}
}
