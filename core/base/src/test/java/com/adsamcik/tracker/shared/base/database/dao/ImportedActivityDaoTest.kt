package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
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
class ImportedActivityDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedActivityDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.importedActivityDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `exact hierarchy and alternate receipt remain queryable`() = runTest {
		insertHierarchy()
		dao.insertReceipt(receipt("alternate", 40L))

		dao.entryRevisionsForAdmission(ENTRY) shouldBe listOf(entry())
		dao.receiptsForAdmission(ENTRY) shouldBe listOf(receipt("alternate", 40L), receipt())
		dao.runs(ENTRY, 1L) shouldBe listOf(run())
		dao.zoneEpochs(ENTRY, 1L, RUN) shouldBe listOf(zone())
		dao.windows(ENTRY, 1L, RUN) shouldBe listOf(window())
		dao.fragments(ENTRY, 1L, RUN, WINDOW) shouldBe listOf(fragment())
		database.sourceSessionDao().serviceRun(RUN) shouldBe null
	}

	@Test
	fun `foreign owners and immutable receipt conflicts abort without overwrite`() = runTest {
		shouldThrow<SQLiteConstraintException> { dao.insertRun(run()) }
		insertHierarchy()
		shouldThrow<SQLiteConstraintException> {
			dao.insertReceipt(receipt().copy(importSourceName = "retargeted"))
		}
		shouldThrow<SQLiteConstraintException> {
			dao.insertWindow(window().copy(runIdentity = digest('8')))
		}
		dao.receipt("job", "entry") shouldBe receipt()
	}

	@Test
	fun `full collected clear removes imported payload and its old epoch tombstones`() = runTest {
		insertHierarchy()
		val entryDeletion = ImportedActivityEntryDeletionEntity.create(ENTRY, 7L, 1L, 40L)
		val runDeletion = ImportedActivityDeletionGenerationEntity.create(RUN, 7L, 1L, 40L)
		dao.insertEntryDeletion(entryDeletion)
		dao.insertDeletionGeneration(runDeletion)
		dao.insertEntryDeletionReceipt(
			ImportedActivityEntryDeletionReceiptEntity.create(
				entryDeletion = entryDeletion,
				deletedContentChecksum = digest('2'),
				runScopes = listOf(RUN to digest('4')),
				windowIdentities = listOf(WINDOW),
				runDeletions = listOf(runDeletion),
				sourceFences = emptyList(),
				retainedFromMs = null,
			),
		)
		val retainedEntry = digest('a')
		val retainedHeader = entry().copy(
			identity = retainedEntry,
			contentChecksum = digest('b'),
			importJobId = "retained-job",
			importEntryKey = "retained-entry",
		)
		val retainedImportReceipt = receipt().copy(
			importJobId = "retained-job",
			importEntryKey = "retained-entry",
			entryIdentity = retainedEntry,
			entryContentChecksum = digest('b'),
		)
		val retainedMarkers = listOf(
			ImportedActivityRetainedIdentityEntity(
				retainedEntry, retainedEntry, ImportedActivityRetainedIdentityEntity.ENTRY,
			),
			ImportedActivityRetainedIdentityEntity(
				digest('c'), retainedEntry, ImportedActivityRetainedIdentityEntity.RUN,
			),
			ImportedActivityRetainedIdentityEntity(
				digest('d'), retainedEntry, ImportedActivityRetainedIdentityEntity.WINDOW,
			),
			ImportedActivityRetainedIdentityEntity(
				digest('e'), retainedEntry, ImportedActivityRetainedIdentityEntity.DELETION_SCOPE,
			),
		)
		dao.insertRetentionReceipts(listOf(
			ImportedActivityRetentionReceiptEntity.create(
				retainedEntry, 7L, 1L, 10L, 40L, 1L, digest('b'), 1L, 9L, 30L,
				1, 1, 1, 1, 1, 1, emptyList(), emptyList(), retainedMarkers,
				ImportedActivityRetentionReceiptEntity.lineageAuthorityChecksum(
					listOf(retainedHeader),
					listOf(retainedImportReceipt),
				),
				latestMemberStartTimeMs = 1L,
				latestMemberIdentity = digest('c'),
				structuralZoneRanges = listOf(
					com.adsamcik.tracker.shared.base.database.data
						.ImportedActivityRetainedZoneRange(1L, 8L, "UTC"),
				),
				structuralZoneCoverageComplete = true,
			),
		))
		dao.insertRetainedIdentities(retainedMarkers)

		AppDatabase.deleteAllCollectedData(database, 8L, null, 50L)

		dao.latestEntryRevision(ENTRY) shouldBe null
		dao.entryDeletion(ENTRY) shouldBe null
		dao.entryDeletionReceipt(ENTRY) shouldBe null
		dao.deletionGenerations(listOf(RUN)) shouldBe emptyList()
		dao.retentionReceipt(retainedEntry) shouldBe null
		dao.retainedIdentityCount() shouldBe 0L
	}

	private suspend fun insertHierarchy() {
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertZoneEpoch(zone())
		dao.insertWindow(window())
		dao.insertFragment(fragment())
		dao.insertReceipt(receipt())
	}

	private fun entry() = ImportedActivityEntryRevisionEntity(
		ENTRY, 1L, null, digest('2'), ImportedActivityEntryRevisionEntity.SOURCE_FORMAT, 1,
		"MANUAL", 10L, 20L, 7L, "job", "entry", "backup.trackeractivity", 30L,
	)

	private fun receipt(job: String = "job", receivedAtMs: Long = 30L) = ImportedActivityReceiptEntity(
		job, "entry", "backup.trackeractivity", receivedAtMs, ENTRY, 1L, digest('2'), 7L,
	)

	private fun run() = ImportedActivityRunEntity(
		ENTRY, 1L, RUN, digest('4'), digest('5'), 10L, 20L, "WHOLE_RUN", 7L, 0L,
	)

	private fun zone() = ImportedActivityZoneEpochEntity(ENTRY, 1L, RUN, 0, 10L, "UTC")

	private fun window() = ImportedActivityWindowEntity(
		ENTRY, 1L, RUN, WINDOW, digest('7'), 0L, 100L, "UTC", "COMPLETE",
		100L, 0L, 0L, 0L,
	)

	private fun fragment() = ImportedActivityFragmentEntity(
		entryIdentity = ENTRY,
		entryImportRevision = 1L,
		runIdentity = RUN,
		windowIdentity = WINDOW,
		ordinal = 0,
		fragmentKind = ImportedActivityFragmentEntity.KIND_BAND,
		startOffsetNanos = 0L,
		endOffsetNanos = 100L,
		gapReason = null,
		activity = "WALKING",
		mechanism = "TRANSITION",
		refinedTransitionActivity = null,
		confidenceKind = "TRANSITION_SIGNAL",
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = 10L,
		startWallTimeUncertaintyMs = 0L,
		startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
		endWallTimeMs = 11L,
		endWallTimeUncertaintyMs = 0L,
		endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
		wallTimeContinuity = "SAME_ANCHOR",
	)

	private fun digest(character: Char) = character.toString().repeat(64)

	private companion object {
		val ENTRY = "1".repeat(64)
		val RUN = "3".repeat(64)
		val WINDOW = "6".repeat(64)
	}
}
