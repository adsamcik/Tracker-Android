package com.adsamcik.tracker.app.startup

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.MIGRATION_12_13
import com.adsamcik.tracker.shared.base.database.MIGRATION_13_14
import com.adsamcik.tracker.shared.base.database.MIGRATION_14_15
import com.adsamcik.tracker.shared.base.database.MIGRATION_15_16
import com.adsamcik.tracker.shared.base.database.MIGRATION_16_17
import com.adsamcik.tracker.shared.base.database.MIGRATION_17_18
import com.adsamcik.tracker.shared.base.database.MIGRATION_18_19
import com.adsamcik.tracker.shared.base.database.MIGRATION_19_20
import com.adsamcik.tracker.shared.base.database.MIGRATION_20_21
import com.adsamcik.tracker.shared.base.database.MIGRATION_21_22
import com.adsamcik.tracker.shared.base.database.MIGRATION_22_23
import com.adsamcik.tracker.shared.base.database.MIGRATION_23_24
import com.adsamcik.tracker.shared.base.database.MIGRATION_24_25
import com.adsamcik.tracker.shared.base.database.MIGRATION_25_26
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportRoomCallback
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.database.legacy.hasCompletedLegacyImport
import java.io.File
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real startup/import wiring on host Framework SQLite; this does not exercise native SQLiteX. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LegacyReleasedDatabaseUpgradeIntegrationTest {
	private lateinit var context: Application
	private val databases = mutableListOf<AppDatabase>()

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		clearFixture()
	}

	@After fun tearDown() {
		databases.forEach(AppDatabase::close)
		databases.clear()
		clearFixture()
	}

	@Test fun `startup imports released v12 without changing its source`(): Unit = runBlocking {
		createReleasedSource()
		val original = sourceSnapshot()
		val database = buildActive()
		val repository = LegacyDatabaseRepository(context)
		val coordinator = LegacyDatabaseUpgradeCoordinator(Provider { database }, repository)

		assertFalse(coordinator.isReady())
		assertEquals(LegacyDatabaseStartupResult.Ready, coordinator.ensureReady())
		assertTrue(coordinator.isReady())
		assertImported(database.openHelper.writableDatabase)
		assertEquals(LegacyImportStatus.COMPLETE, repository.currentState().importStatus)
		assertEquals(12, repository.currentState().report?.sourceVersion)
		assertEquals(original, sourceSnapshot())
	}

	@Test fun `new coordinator reopens the completed Room database without duplicating its import`(): Unit = runBlocking {
		createReleasedSource()
		val original = sourceSnapshot()
		val first = buildActive()
		assertEquals(LegacyDatabaseStartupResult.Ready,
			LegacyDatabaseUpgradeCoordinator(Provider { first }, LegacyDatabaseRepository(context)).ensureReady())
		assertImported(first.openHelper.writableDatabase)
		first.close()

		val reopened = buildActive()
		val repository = LegacyDatabaseRepository(context)
		val coordinator = LegacyDatabaseUpgradeCoordinator(Provider { reopened }, repository)
		assertFalse(coordinator.isReady())
		assertEquals(LegacyDatabaseStartupResult.Ready, coordinator.ensureReady())
		assertEquals(LegacyDatabaseStartupResult.Ready, coordinator.ensureReady())
		assertImported(reopened.openHelper.writableDatabase)
		assertEquals(LegacyImportStatus.COMPLETE, repository.currentState().importStatus)
		assertEquals(original, sourceSnapshot())
	}

	@Test fun `failed real import requires explicit retry and recovers on the same Room helper`(): Unit = runBlocking {
		createReleasedSource()
		editSource { execSQL("ALTER TABLE location_data RENAME TO location_data_needs_repair") }
		val database = buildActive()
		val helper = database.openHelper
		val repository = LegacyDatabaseRepository(context)
		val coordinator = LegacyDatabaseUpgradeCoordinator(Provider { database }, repository)

		val failure = assertIs<LegacyDatabaseStartupResult.Failed>(coordinator.ensureReady())
		assertTrue(failure.requiresExplicitRetry)
		assertFalse(coordinator.isReady())
		assertEquals(LegacyImportStatus.FAILED, repository.currentState().importStatus)
		editSource { execSQL("ALTER TABLE location_data_needs_repair RENAME TO location_data") }
		val repaired = sourceSnapshot()
		assertTrue(assertIs<LegacyDatabaseStartupResult.Failed>(coordinator.ensureReady()).requiresExplicitRetry)

		assertEquals(LegacyDatabaseStartupResult.Ready, coordinator.ensureReady(retry = true))
		assertSame(helper, database.openHelper)
		assertTrue(coordinator.isReady())
		assertImported(helper.writableDatabase)
		assertEquals(LegacyImportStatus.COMPLETE, repository.currentState().importStatus)
		assertEquals(repaired, sourceSnapshot())
	}

	private fun buildActive(): AppDatabase = AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME)
		.openHelperFactory(FrameworkSQLiteOpenHelperFactory())
		.addCallback(LegacyImportRoomCallback(context, arrayOf(
			MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
			MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
			MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
			MIGRATION_24_25, MIGRATION_25_26,
		), FrameworkSQLiteOpenHelperFactory()))
		.allowMainThreadQueries()
		.build()
		.also(databases::add)

	private fun createReleasedSource() {
		val relative = "core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/12.json"
		val file = sequenceOf(File(relative), File("../$relative")).firstOrNull(File::isFile)
			?: error("Missing committed released v12 Room schema")
		val schema = JSONObject(file.readText()).getJSONObject("database")
		assertEquals(12, schema.getInt("version"))
		assertEquals("603376ce119f1b4f1d3e4866aec15b4e", schema.getString("identityHash"))
		val destination = context.getDatabasePath(LEGACY_DATABASE_NAME)
		destination.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(destination, null).use { source ->
			val entities = schema.getJSONArray("entities")
			for (index in 0 until entities.length()) {
				val entity = entities.getJSONObject(index)
				val table = entity.getString("tableName")
				source.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
				val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
				for (indexOfIndex in 0 until indices.length()) {
					source.execSQL(indices.getJSONObject(indexOfIndex).getString("createSql")
						.replace("${'$'}{TABLE_NAME}", table))
				}
			}
			val setup = schema.getJSONArray("setupQueries")
			for (index in 0 until setup.length()) source.execSQL(setup.getString(index))
			source.execSQL("INSERT INTO activity(id, name, iconName) VALUES (7, 'Walk', 'walk')")
			source.execSQL("INSERT INTO location_data(id, time, lat, lon, activity, confidence) " +
				"VALUES (101, 1700100000000, 50.1, 14.2, 7, 90)")
			source.version = 12
		}
	}

	private fun assertImported(database: SupportSQLiteDatabase) {
		assertEquals(30, database.version)
		assertTrue(hasCompletedLegacyImport(database))
		for (table in listOf("location_sample", "location_observation", "import_job_receipt")) {
			database.query("SELECT COUNT(*) FROM `$table`").use { row ->
				assertTrue(row.moveToFirst())
				assertEquals(1L, row.getLong(0))
			}
		}
		database.query("SELECT lat_e7, lon_e7, time_ms FROM location_sample WHERE id = 101").use { row ->
			assertTrue(row.moveToFirst())
			assertEquals(501_000_000, row.getInt(0))
			assertEquals(142_000_000, row.getInt(1))
			assertEquals(1_700_100_000_000L, row.getLong(2))
		}
	}

	private fun sourceSnapshot(): List<String?> = SQLiteDatabase.openDatabase(
		context.getDatabasePath(LEGACY_DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
	).use { source ->
		assertEquals(12, source.version)
		buildList {
			for (query in listOf("SELECT type, name, sql FROM sqlite_master ORDER BY type, name",
				"SELECT * FROM location_data ORDER BY id", "SELECT * FROM activity ORDER BY id")) {
				source.rawQuery(query, null).use { rows ->
					while (rows.moveToNext()) for (index in 0 until rows.columnCount) add(rows.getString(index))
				}
			}
		}
	}

	private fun editSource(edit: SQLiteDatabase.() -> Unit) {
		SQLiteDatabase.openDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null, SQLiteDatabase.OPEN_READWRITE).use(edit)
	}

	private fun clearFixture() {
		listOf(ACTIVE_DATABASE_NAME, LEGACY_DATABASE_NAME, "legacy_import_v26_staging")
			.forEach(context::deleteDatabase)
		context.getSharedPreferences("legacy_database_v27", Context.MODE_PRIVATE).edit().clear().commit()
	}
}
