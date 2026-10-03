package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedCellDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedCellDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.importedCellDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `entry deletion cascades immutable runs and receipts`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run())
		dao.insertReceipt(receipt())

		dao.deleteAllEntries()

		dao.boundedEntryRevisions(ENTRY) shouldBe emptyList()
		dao.allRunsForAdmission(ENTRY) shouldBe emptyList()
		dao.receiptsForAdmission(ENTRY) shouldBe emptyList()
	}

	@Test
	fun `one revision cannot assign one deletion scope to two runs`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run())

		shouldThrow<SQLiteConstraintException> {
			dao.insertRun(run().copy(identity = digest('5')))
		}
	}

	@Test
	fun `run tombstone identity and scope are each durable owners`() = runTest {
		val marker = ImportedCellDeletionGenerationEntity.create(
			RUN, ENTRY, SCOPE, 3L, 1L, 9L,
		)
		dao.insertDeletionGeneration(marker)

		dao.deletionGenerationOwners(listOf(RUN), 2) shouldBe listOf(marker)
		dao.deletionGenerationOwners(listOf(SCOPE), 2) shouldBe listOf(marker)
	}

	@Test
	fun `entry deletion receipt retains complete identity owners after payload cascade`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run())
		dao.insertReceipt(receipt())
		val deletion = ImportedCellEntryDeletionEntity.create(ENTRY, 3L, 1L, 9L)
		val identities = listOf(
			ImportedCellDeletedIdentityEntity.create(
				ENTRY, ENTRY, ImportedCellDeletedIdentityEntity.ENTRY,
				contentChecksum = digest('4'),
			),
			ImportedCellDeletedIdentityEntity.create(
				RUN, ENTRY, ImportedCellDeletedIdentityEntity.RUN, RUN,
				deletionScopeDigest = SCOPE,
				runStartTimeMs = 1L,
				runEndTimeMs = 2L,
				contentChecksum = digest('6'),
			),
			ImportedCellDeletedIdentityEntity.create(
				SCOPE, ENTRY, ImportedCellDeletedIdentityEntity.DELETION_SCOPE, RUN,
				deletionScopeDigest = SCOPE,
			),
			ImportedCellDeletedIdentityEntity.create(
				digest('7'), ENTRY, ImportedCellDeletedIdentityEntity.OBSERVATION, RUN,
				contentChecksum = digest('7'),
				observationOrdinal = 0,
			),
		)
		dao.insertEntryDeletion(deletion)
		dao.insertEntryDeletionReceipt(
			ImportedCellEntryDeletionReceiptEntity.create(
				deletion,
				digest('4'),
				"MANUAL",
				"UNKNOWN",
				1L,
				2L,
				8L,
				null,
				1,
				1,
				1,
				1,
				identities,
			),
		)
		dao.insertDeletedIdentities(identities)
		dao.deleteEntryRevisions(ENTRY) shouldBe 1

		dao.boundedEntryRevisions(ENTRY) shouldBe emptyList()
		dao.entryDeletionReceipt(ENTRY)?.expectedProtectedIdentityCount shouldBe 4
		dao.deletedIdentitiesForEntry(ENTRY, 5).toSet() shouldBe identities.toSet()
		dao.deletedIdentityOwners(listOf(digest('7')), 5).single().entryIdentity shouldBe ENTRY
	}

	@Test
	fun `opaque owner probe returns typed entry run and scope rows in one query`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run())

		val owners = dao.opaqueOwnerProbe(listOf(ENTRY, RUN, SCOPE), 10)

		owners.map { it.ownerKind }.toSet() shouldBe setOf(
			ImportedCellOpaqueOwnerRow.ENTRY,
			ImportedCellOpaqueOwnerRow.RUN,
			ImportedCellOpaqueOwnerRow.SCOPE,
		)
		owners.single { it.ownerKind == ImportedCellOpaqueOwnerRow.RUN }.let { owner ->
			owner.entryIdentity shouldBe ENTRY
			owner.runIdentity shouldBe RUN
			owner.deletionScopeDigest shouldBe SCOPE
		}
	}

	@Test
	fun `Cell export discovers scans beyond headers and preserves half-open date edges`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run().copy(retentionLoss = false, availability = "RETAINED"))
		dao.insertObservation(exportObservation())
		dao.historyCandidatePageInWallRange(150L, 200L, 3, null, null) shouldBe emptyList()
		dao.exportCandidatePageInWallRange(150L, 200L, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
		dao.exportCandidatePageInWallRange(151L, 200L, 3, null, null) shouldBe emptyList()
		dao.exportCandidatePageInWallRange(90L, 100L, 3, null, null) shouldBe emptyList()
	}

	@Test
	fun `Cell gap-only instantaneous run is a candidate only when retention records a gap`() = runTest {
		dao.insertEntryRevision(header().copy(startTimeMs = 100L, endTimeMs = 100L))
		dao.insertRun(run().copy(startTimeMs = 100L, endTimeMs = 100L))
		dao.exportCandidatePageInWallRange(100L, 101L, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
		dao.exportCandidatePageInWallRange(99L, 100L, 3, null, null) shouldBe emptyList()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_run SET retention_loss = 0")
		dao.exportCandidatePageInWallRange(100L, 101L, 3, null, null) shouldBe emptyList()
		dao.insertObservation(exportObservation().copy(coverageStartTimeMs = 100L, observedTimeMs = 100L,
			latestPossibleTimeMs = 100L, wallTimeUncertaintyMs = 0L))
		dao.exportCandidatePageInWallRange(100L, 101L, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
	}

	@Test
	fun `Cell export does not read an outside-date corrupt cohort but retains unknown clocks`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run().copy(retentionLoss = false, availability = "RETAINED"))
		dao.insertObservation(exportObservation())
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_entry_revision SET content_checksum = 'broken'")
		dao.exportCandidatePageInWallRange(1_000L, 1_100L, 3, null, null) shouldBe emptyList()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_observation SET wall_time_uncertainty_ms = 50")
		dao.exportCandidatePageInWallRange(1_000L, 1_100L, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
	}

	@Test
	fun `Cell export considers only latest revision and pages by the original header key`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run().copy(retentionLoss = false, availability = "RETAINED"))
		dao.insertObservation(exportObservation())
		dao.insertEntryRevision(header().copy(importRevision = 2L, supersedesImportRevision = 1L,
			importJobId = "correction", importEntryKey = "correction", contentChecksum = digest('a')))
		dao.insertRun(run().copy(entryImportRevision = 2L, startTimeMs = 200L, endTimeMs = 250L))
		dao.exportCandidatePageInWallRange(100L, 151L, 3, null, null) shouldBe emptyList()
		val second = digest('b')
		dao.insertEntryRevision(header().copy(identity = second, startTimeMs = 10L, endTimeMs = 250L,
			importJobId = "second", importEntryKey = "second"))
		dao.insertRun(run().copy(entryIdentity = second, identity = digest('c'), startTimeMs = 200L, endTimeMs = 250L))
		val first = dao.exportCandidatePageInWallRange(200L, 220L, 1, null, null).single()
		first.identity shouldBe second
		dao.exportCandidatePageInWallRange(200L, 220L, 1, first.startTimeMs, first.identity).map {
			it.identity to it.importRevision
		} shouldBe listOf(ENTRY to 2L)
	}

	@Test
	fun `all-data Cell candidates keep empty gapless records and narrow exports keep overflow clocks`() = runTest {
		dao.insertEntryRevision(header())
		dao.insertRun(run().copy(retentionLoss = false))
		dao.exportCandidatePageInWallRange(0L, Long.MAX_VALUE, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
		dao.exportCandidatePageInWallRange(1L, 2L, 3, null, null) shouldBe emptyList()
		dao.insertObservation(exportObservation())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_observation SET observed_time_ms = ?, wall_time_uncertainty_ms = 10",
			arrayOf(Long.MAX_VALUE))
		dao.exportCandidatePageInWallRange(2_000L, 2_100L, 3, null, null).map { it.identity } shouldBe listOf(ENTRY)
	}

	private fun exportObservation() = ImportedCellObservationEntity(
		entryIdentity = ENTRY, entryImportRevision = 1L, runIdentity = RUN, identity = digest('7'),
		semanticRevision = 1L, supersedesSemanticRevision = null, aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null, contentChecksum = digest('8'), coverageStartTimeMs = 100L,
		observedTimeMs = 125L, latestPossibleTimeMs = 150L, wallTimeUncertaintyMs = 25L,
		storedZoneId = "Europe/Prague", childCompleteness = "COMPLETE", subscriptionGrouping = "UNKNOWN",
		submittedChildCount = 0, acceptedChildCount = 0, staleChildCount = 0, futureTimeChildCount = 0,
		missingTimeChildCount = 0, clockUnverifiableChildCount = 0, authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0, observationCount = 0, registeredObservationCount = 0,
		gsmCount = 0, cdmaCount = 0, wcdmaCount = 0, tdscdmaCount = 0, lteCount = 0, nrCount = 0,
		qualityUnknownCount = 0, qualityNoneOrUnknownCount = 0, qualityPoorCount = 0, qualityModerateCount = 0,
		qualityGoodCount = 0, qualityGreatCount = 0, weakObservationCount = 0, knownQualityObservationCount = 0,
		allKnownQualityIsWeak = false, qualityFlags = 0L, qualityConfidence = null,
	)

	private fun header() = ImportedCellEntryRevisionEntity(
		identity = ENTRY,
		importRevision = 1L,
		supersedesImportRevision = null,
		contentChecksum = digest('4'),
		sourceFormat = CellCapturedPortableFormatV1.FORMAT,
		sourceSchemaVersion = CellCapturedPortableFormatV1.SCHEMA_VERSION,
		sessionMode = "MANUAL",
		startTimeMs = 1L,
		endTimeMs = 2L,
		subscriptionGrouping = "UNKNOWN",
		collectedDataEpoch = 3L,
		importJobId = "job",
		importEntryKey = "entry",
		importSourceName = "backup.trackercell",
		receivedAtMs = 8L,
	)

	private fun run() = ImportedCellRunEntity(
		entryIdentity = ENTRY,
		entryImportRevision = 1L,
		identity = RUN,
		deletionScopeDigest = SCOPE,
		contentChecksum = digest('6'),
		startTimeMs = 1L,
		endTimeMs = 2L,
		captureCoverage = "WHOLE_RUN",
		availability = "NO_RETAINED_OBSERVATION",
		acquisitionCompleteness = "PARTIAL",
		retentionLoss = true,
		subscriptionGrouping = "UNKNOWN",
		collectedDataEpoch = 3L,
		scopeDeletionGeneration = 0L,
	)

	private fun receipt() = ImportedCellReceiptEntity(
		"job", "entry", "backup.trackercell", 8L, ENTRY, 1L, digest('4'), 3L,
	)

	private fun digest(value: Char) = value.toString().repeat(64)

	private companion object {
		val ENTRY = "1".repeat(64)
		val RUN = "2".repeat(64)
		val SCOPE = "3".repeat(64)
	}
}
