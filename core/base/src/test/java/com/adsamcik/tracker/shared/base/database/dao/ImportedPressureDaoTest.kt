package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
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
class ImportedPressureDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedPressureDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.importedPressureDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `exact portable hierarchy survives as imported origin without local runtime`() = runTest {
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertWindow(window())
		dao.insertReceipt(receipt())

		dao.latestEntryRevision(ENTRY) shouldBe entry()
		dao.entryRevisionsForAdmission(ENTRY) shouldBe listOf(entry())
		dao.receipt("job-1", "entry-1") shouldBe receipt()
		dao.receiptsForAdmission(ENTRY) shouldBe listOf(receipt())
		dao.runs(ENTRY, 1L) shouldBe listOf(run())
		dao.windows(ENTRY, 1L, RUN) shouldBe listOf(window())
		dao.runCount(ENTRY, 1L) shouldBe 1
		dao.windowCount(ENTRY, 1L, RUN) shouldBe 1
		database.sourceSessionDao().session(ENTRY) shouldBe null
		database.sourceSessionDao().serviceRun(RUN) shouldBe null
	}

	@Test
	fun `correction appends a destination receipt revision without replacing prior evidence`() = runTest {
		dao.insertEntryRevision(entry())
		val corrected = entry().copy(
			importRevision = 2L,
			supersedesImportRevision = 1L,
			contentChecksum = OTHER,
			importJobId = "job-2",
			receivedAtMs = 40L,
		)
		dao.insertEntryRevision(corrected)
		dao.entryRevision(ENTRY, 1L) shouldBe entry()
		dao.latestEntryRevision(ENTRY) shouldBe corrected
		shouldThrow<SQLiteConstraintException> { dao.insertEntryRevision(corrected.copy(contentChecksum = WINDOW)) }
	}

	@Test
	fun `rollback cascades only one receipt while privacy generation remains until full clear`() = runTest {
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertWindow(window())
		dao.insertReceipt(receipt())
		val generation = ImportedPressureDeletionGenerationEntity.create(RUN, 7L, 1L, 50L)
		dao.insertDeletionGeneration(generation)

		dao.deleteEntryRevision(ENTRY, 1L) shouldBe 1
		dao.receipt("job-1", "entry-1") shouldBe null
		dao.runs(ENTRY, 1L) shouldBe emptyList()
		dao.windows(ENTRY, 1L, RUN) shouldBe emptyList()
		dao.deletionGeneration(RUN) shouldBe generation

		AppDatabase.deleteAllCollectedData(database, 8L, null, 60L)
		dao.deletionGeneration(RUN) shouldBe null
	}

	@Test
	fun `alternate receipt binds exact revision and cannot be reused`() = runTest {
		dao.insertEntryRevision(entry())
		val original = receipt()
		val alternate = receipt().copy(importJobId = "job-2", receivedAtMs = 40L)
		dao.insertReceipt(original)
		dao.insertReceipt(alternate)

		dao.receipt("job-2", "entry-1") shouldBe alternate
		shouldThrow<SQLiteConstraintException> {
			dao.insertReceipt(alternate.copy(entryContentChecksum = OTHER))
		}
	}

	@Test
	fun `missing exact composite parents and duplicate generations abort`() = runTest {
		shouldThrow<SQLiteConstraintException> { dao.insertRun(run()) }
		shouldThrow<SQLiteConstraintException> { dao.insertWindow(window()) }
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertWindow(window())
		shouldThrow<SQLiteConstraintException> { dao.insertWindow(window()) }
		val generation = ImportedPressureDeletionGenerationEntity.create(RUN, 7L, 1L, 50L)
		dao.insertDeletionGeneration(generation)
		val advanced = ImportedPressureDeletionGenerationEntity.create(RUN, 7L, 2L, 60L)
		val otherEpoch = ImportedPressureDeletionGenerationEntity.create(RUN, 8L, 2L, 60L)
		shouldThrow<IllegalArgumentException> {
			dao.advanceDeletionGeneration(7L, 1L, otherEpoch)
		}
		val sameGeneration = ImportedPressureDeletionGenerationEntity.create(RUN, 7L, 1L, 60L)
		shouldThrow<IllegalArgumentException> {
			dao.advanceDeletionGeneration(7L, 1L, sameGeneration)
		}
		dao.deletionGeneration(RUN) shouldBe generation
		dao.advanceDeletionGeneration(7L, 1L, advanced) shouldBe 1
		dao.advanceDeletionGeneration(7L, 1L, advanced) shouldBe 0
		val lowerGeneration = ImportedPressureDeletionGenerationEntity.create(RUN, 7L, 1L, 70L)
		shouldThrow<IllegalArgumentException> {
			dao.advanceDeletionGeneration(7L, 2L, lowerGeneration)
		}
		shouldThrow<IllegalArgumentException> {
			dao.advanceDeletionGeneration(7L, Long.MAX_VALUE, advanced)
		}
		dao.deletionGeneration(RUN) shouldBe advanced
		shouldThrow<SQLiteConstraintException> {
			dao.insertDeletionGeneration(generation)
		}
		shouldThrow<IllegalArgumentException> {
			dao.insertDeletionGeneration(
				ImportedPressureDeletionGenerationEntity.create(OTHER, 7L, 2L, 80L),
			)
		}
	}

	private fun entry() = ImportedPressureEntryRevisionEntity(
		ENTRY, 1L, null, CHECKSUM,
		ImportedPressureEntryRevisionEntity.SOURCE_FORMAT,
		ImportedPressureEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
		10L, 20L, 7L, "job-1", "entry-1", "pressure.trackerpressure", 30L,
	)

	private fun run() = ImportedPressureRunEntity(
		ENTRY, 1L, RUN, 10L, 20L, capturedForWholeRun = true,
		availability = ImportedPressureRunEntity.AVAILABILITY_RETAINED,
		coverage = ImportedPressureRunEntity.COVERAGE_COMPLETE,
		retentionLoss = false, collectedDataEpoch = 7L, scopeDeletionGeneration = 0L,
	)

	private fun receipt() = ImportedPressureReceiptEntity(
		"job-1", "entry-1", "pressure.trackerpressure", 30L,
		ENTRY, 1L, CHECKSUM, 7L,
	)

	private fun window() = ImportedPressureWindowEntity(
		ENTRY, 1L, RUN, WINDOW, CHECKSUM, 10L, 10L, 2L, 0L, 1, 1,
		1_000.0, 0.0, 1_000f, 1_000f, 1_000f, 1_000f, null, null, "HIGH",
		1_000, 0, 1_000_000L, 0L, "TARGET_ELAPSED", "COMPLETE", 0L, 1f, "UTC",
	)

	private companion object {
		val ENTRY = opaque('1')
		val RUN = opaque('2')
		val WINDOW = opaque('3')
		val CHECKSUM = opaque('4')
		val OTHER = opaque('5')
		fun opaque(character: Char): String = "sha256:${character.toString().repeat(64)}"
	}
}
