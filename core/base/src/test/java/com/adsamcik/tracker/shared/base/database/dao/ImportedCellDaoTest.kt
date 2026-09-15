package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
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
