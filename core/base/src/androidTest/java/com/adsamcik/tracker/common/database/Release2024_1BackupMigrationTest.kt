package com.adsamcik.tracker.common.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.migration.MigrationBackupOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Release2024_1BackupMigrationTest {
	private lateinit var context: Context
	private lateinit var backupStore: DatabaseMigrationBackupStore
	private var database: AppDatabase? = null

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		context.deleteDatabase(DATABASE_NAME)
		backupStore = DatabaseMigrationBackupStore(context)
		backupStore.deleteAll()
		val target = context.getDatabasePath(DATABASE_NAME)
		target.parentFile?.mkdirs()
		context.assets.open(FIXTURE).use { input ->
			target.outputStream().use(input::copyTo)
		}
	}

	@After
	fun tearDown() {
		database?.close()
		context.deleteDatabase(DATABASE_NAME)
		backupStore.deleteAll()
	}

	@Test
	fun backupPrecedesFullMigration() {
		val migrated = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
			.openHelperFactory(
				MigrationBackupOpenHelperFactory(
					delegate = FrameworkSQLiteOpenHelperFactory(),
					backupStore = backupStore,
					databaseName = DATABASE_NAME,
					targetVersion = CURRENT_VERSION,
				),
			)
			.addMigrations(*AppDatabase.migrations)
			.build()
		database = migrated

		val target = migrated.openHelper.writableDatabase
		assertEquals(CURRENT_VERSION, target.version)
		assertEquals(6, count(target, "location_sample"))
		assertEquals(6, count(target, "location_observation"))
		assertEquals(6, count(target, "activity_snapshot"))
		assertEquals(35, count(target, "session_segment"))
		assertEquals(2, count(target, "legacy_rejected_tracker_session"))
		assertEquals(2, count(target, "wifi_observation"))
		assertEquals(3, count(target, "cell_sample"))
		assertEquals(2, count(target, "legacy_location_wifi_count"))
		assertEquals(34, count(target, "activity"))
		assertEquals(3, count(target, "network_operator"))
		target.query(
			"SELECT legacy_lat, legacy_lon, quality FROM location_sample WHERE id = 1",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(48.1234567, cursor.getDouble(0), 0.0)
			assertEquals(17.9876543, cursor.getDouble(1), 0.0)
			assertEquals("HIGH", cursor.getString(2))
		}
		target.query(
			"""
			SELECT start_time_ms, end_time_ms, distance_m, steps, legacy_activity_id, source
			FROM session_segment WHERE id = 1
			""".trimIndent(),
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(10_000L, cursor.getLong(0))
			assertEquals(15_000L, cursor.getLong(1))
			assertEquals(100.25, cursor.getDouble(2), 0.0)
			assertEquals(10, cursor.getInt(3))
			assertEquals(-34L, cursor.getLong(4))
			assertEquals("LEGACY_MIGRATION", cursor.getString(5))
		}
		target.query(
			"""
			SELECT ssid, frequency, level, legacy_lat, legacy_lon
			FROM wifi_observation WHERE bssid = 'AA:BB:CC:DD:EE:01'
			""".trimIndent(),
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("Home 'quoted'", cursor.getString(0))
			assertEquals(2412, cursor.getInt(1))
			assertEquals(-55, cursor.getInt(2))
			assertEquals(48.125, cursor.getDouble(3), 0.0)
			assertEquals(17.875, cursor.getDouble(4), 0.0)
		}
		target.query(
			"""
			SELECT cell_id, mcc, mnc, network_type, signal_strength, legacy_source_id
			FROM cell_sample WHERE id = 2
			""".trimIndent(),
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(68_719_476_735L, cursor.getLong(0))
			assertEquals(310, cursor.getInt(1))
			assertEquals(260, cursor.getInt(2))
			assertEquals(20, cursor.getInt(3))
			assertEquals(97, cursor.getInt(4))
			assertEquals(2L, cursor.getLong(5))
		}
		target.query("SELECT name, iconName FROM activity WHERE id = 7").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("Custom run", cursor.getString(0))
			assertEquals("custom_run", cursor.getString(1))
		}
		target.query(
			"SELECT name FROM network_operator WHERE mcc = '001' AND mnc = '01'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("Leading Zero", cursor.getString(0))
		}

		val backup = backupStore.latestBackup()
		assertNotNull(backup)
		assertEquals(RELEASE_VERSION, backup?.sourceVersion)
		SQLiteDatabase.openDatabase(
			checkNotNull(backup).file.path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { source ->
			assertEquals(RELEASE_VERSION, source.version)
			assertEquals(6, source.count("location_data"))
			assertEquals(37, source.count("tracker_session"))
			assertEquals(2, source.count("wifi_data"))
			assertEquals(3, source.count("cell_location"))
			source.rawQuery(
				"SELECT lat, lon, hor_acc, activity, confidence FROM location_data WHERE id = 1",
				null,
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(48.1234567, cursor.getDouble(0), 0.0)
				assertEquals(17.9876543, cursor.getDouble(1), 0.0)
				assertEquals(5.0, cursor.getDouble(2), 0.0)
				assertEquals(0, cursor.getInt(3))
				assertEquals(80, cursor.getInt(4))
			}
		}

	}

	private fun count(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
		database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getInt(0)
		}

	private fun SQLiteDatabase.count(table: String): Int =
		rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getInt(0)
		}

	private companion object {
		const val DATABASE_NAME = "release_2024_1_backup.db"
		const val FIXTURE = "baseline/2024.1/main_database.db"
		const val RELEASE_VERSION = 10
		const val CURRENT_VERSION = 36
	}
}
