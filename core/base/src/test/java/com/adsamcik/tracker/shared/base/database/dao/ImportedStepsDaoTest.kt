package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
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
class ImportedStepsDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedStepsDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.importedStepsDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `durable hierarchy preserves original identity scope zone and independent settlement`() = runTest {
		dao.insertEntry(entry())
		dao.insertRun(run())
		dao.insertManifest(manifest())
		dao.entry(ENTRY) shouldBe entry()
		dao.run(RUN) shouldBe run()
		dao.manifests(RUN) shouldBe listOf(manifest())
		database.sourceSessionDao().serviceRun(RUN) shouldBe null
		database.sourceSessionDao().activeSession() shouldBe null
	}

	@Test
	fun `identity or scope conflicts cannot overwrite existing origin`() = runTest {
		dao.insertEntry(entry())
		dao.insertRun(run())
		dao.insertManifest(manifest())
		shouldThrow<SQLiteConstraintException> { dao.insertEntry(entry().copy(contentChecksum = OTHER)) }
		shouldThrow<SQLiteConstraintException> { dao.insertRun(run().copy(storedZoneId = "UTC")) }
		shouldThrow<SQLiteConstraintException> { dao.insertRun(run().copy(identity = OTHER)) }
		shouldThrow<SQLiteConstraintException> { dao.insertManifest(manifest().copy(captureConsentEpoch = 9L)) }
		dao.entry(ENTRY) shouldBe entry()
		dao.run(RUN) shouldBe run()
		dao.manifests(RUN) shouldBe listOf(manifest())
	}

	@Test
	fun `missing exact parent is rejected and full collected clear cascades all origin metadata`() = runTest {
		shouldThrow<SQLiteConstraintException> { dao.insertRun(run()) }
		shouldThrow<SQLiteConstraintException> { dao.insertManifest(manifest()) }
		dao.insertEntry(entry())
		dao.insertRun(run())
		dao.insertManifest(manifest())
		AppDatabase.deleteAllCollectedData(database, 2L, null, 50L)
		dao.entry(ENTRY) shouldBe null
		dao.run(RUN) shouldBe null
		dao.manifests(RUN) shouldBe emptyList()
	}

	private fun entry() = ImportedStepsEntryEntity(ENTRY, OTHER, "MANUAL", 10L, 20L, 1L)
	private fun run() = ImportedStepsRunEntity(
		RUN, ENTRY, "4".repeat(64), 10L, 20L, "Europe/Prague", "WHOLE_RUN", "PARTIAL",
		appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = true,
	)
	private fun manifest() = ImportedStepsManifestEntity(RUN, 1L, 9L, 5L, 3L)

	private companion object {
		val ENTRY = "sha256:${"1".repeat(64)}"
		val RUN = "sha256:${"2".repeat(64)}"
		val OTHER = "sha256:${"3".repeat(64)}"
	}
}
