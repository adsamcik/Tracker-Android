package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedActivityMigration27To28Test {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun before() {
		context.deleteDatabase(DATABASE)
	}

	@After
	fun after() {
		context.deleteDatabase(DATABASE)
	}

	@Test
	fun activityImportedHierarchyIsAdditiveAndSurvivesProductionReopen() {
		helper.createDatabase(DATABASE, 27).close()
		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			listOf(
				"imported_activity_entry_revision",
				"imported_activity_receipt",
				"imported_activity_run",
				"imported_activity_zone_epoch",
				"imported_activity_window",
				"imported_activity_fragment",
				"imported_activity_entry_deletion",
				"imported_activity_entry_deletion_receipt",
				"imported_activity_deletion_generation",
			).forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 0L, cursor.getLong(0))
				}
			}
		}

		openDatabase().let { database ->
			try {
				runBlocking { insertHierarchy(database) }
			} finally {
				database.close()
			}
		}
		openDatabase().let { database ->
			try {
				runBlocking {
					val dao = database.importedActivityDao()
					assertEquals(ENTRY, dao.latestEntryRevision(ENTRY)?.identity)
					assertEquals(RUN, dao.runs(ENTRY, 1L).single().identity)
					assertEquals("UTC", dao.zoneEpochs(ENTRY, 1L, RUN).single().zoneId)
					assertEquals(WINDOW, dao.windows(ENTRY, 1L, RUN).single().identity)
					assertEquals(1, dao.fragments(ENTRY, 1L, RUN, WINDOW).size)
					assertEquals(7L, dao.entryDeletion(TOMBSTONED_ENTRY)?.collectedDataEpoch)
					assertEquals(
						digest('a'),
						dao.entryDeletionReceipt(TOMBSTONED_ENTRY)?.deletedContentChecksum,
					)
					assertEquals(
						1L,
						dao.deletionGenerations(listOf(TOMBSTONED_RUN)).single().generation,
					)
				}
			} finally {
				database.close()
			}
		}
	}

	private suspend fun insertHierarchy(database: AppDatabase) {
		val dao = database.importedActivityDao()
		dao.insertEntryRevision(
			ImportedActivityEntryRevisionEntity(
				ENTRY, 1L, null, digest('2'), ActivityCapturedPortableFormatV1.FORMAT, 1,
				"MANUAL", 10L, 20L, 7L, "job", "entry", "backup.trackeractivity", 30L,
			),
		)
		dao.insertRun(
			ImportedActivityRunEntity(
				ENTRY, 1L, RUN, digest('4'), digest('5'), 10L, 20L, "WHOLE_RUN", 7L, 0L,
			),
		)
		dao.insertZoneEpoch(ImportedActivityZoneEpochEntity(ENTRY, 1L, RUN, 0, 10L, "UTC"))
		dao.insertWindow(
			ImportedActivityWindowEntity(
				ENTRY, 1L, RUN, WINDOW, digest('7'), 0L, 100L, "UTC", "COMPLETE",
				100L, 0L, 0L, 0L,
			),
		)
		dao.insertFragment(
			ImportedActivityFragmentEntity(
				ENTRY, 1L, RUN, WINDOW, 0, ImportedActivityFragmentEntity.KIND_BAND,
				0L, 100L, null, "WALKING", "TRANSITION", null, "TRANSITION_SIGNAL",
				null, null, null, 10L, 0L, "EXACT_PROVIDER_OBSERVATION",
				11L, 0L, "SAME_CLOCK_EXTRAPOLATION", "SAME_ANCHOR",
			),
		)
		dao.insertReceipt(
			ImportedActivityReceiptEntity(
				"job", "entry", "backup.trackeractivity", 30L, ENTRY, 1L, digest('2'), 7L,
			),
		)
		val entryDeletion = ImportedActivityEntryDeletionEntity.create(
			TOMBSTONED_ENTRY,
			7L,
			1L,
			40L,
		)
		val runDeletion = ImportedActivityDeletionGenerationEntity.create(
			TOMBSTONED_RUN,
			7L,
			1L,
			40L,
		)
		dao.insertEntryDeletion(entryDeletion)
		dao.insertDeletionGeneration(runDeletion)
		dao.insertEntryDeletionReceipt(
			ImportedActivityEntryDeletionReceiptEntity.create(
				entryDeletion = entryDeletion,
				deletedContentChecksum = digest('a'),
				runScopes = listOf(TOMBSTONED_RUN to digest('b')),
				windowIdentities = listOf(digest('c')),
				runDeletions = listOf(runDeletion),
				sourceFences = emptyList(),
				retainedFromMs = null,
			),
		)
	}

	private fun openDatabase(): AppDatabase = Room.databaseBuilder(
		context,
		AppDatabase::class.java,
		DATABASE,
	).openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
		.addMigrations(MIGRATION_27_28)
		.allowMainThreadQueries()
		.build()

	private fun digest(character: Char) = character.toString().repeat(64)

	private companion object {
		const val DATABASE = "migration-imported-activity-27-28"
		val ENTRY = "1".repeat(64)
		val RUN = "3".repeat(64)
		val WINDOW = "6".repeat(64)
		val TOMBSTONED_ENTRY = "8".repeat(64)
		val TOMBSTONED_RUN = "9".repeat(64)
	}
}
