package com.adsamcik.tracker.common.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every possible upgrade entry point from the 2024.1 schema onward.
 */
@RunWith(AndroidJUnit4::class)
class Release2024_1MigrationMatrixTest {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java.canonicalName,
		FrameworkSQLiteOpenHelperFactory(),
	)

	@Test
	fun everyVersionFrom2024_1ToCurrentValidatesAgainstTheCurrentRoomSchema() {
		val failures = mutableListOf<String>()
		for (startVersion in RELEASE_DATABASE_VERSION until CURRENT_DATABASE_VERSION) {
			runCatching {
				val databaseName = "release_2024_1_matrix_$startVersion.db"
				helper.createDatabase(databaseName, startVersion).close()
				val requiredMigrations = migrations.filter { it.startVersion >= startVersion }.toTypedArray()
				helper.runMigrationsAndValidate(
					databaseName,
					CURRENT_DATABASE_VERSION,
					true,
					*requiredMigrations,
				).close()
			}.exceptionOrNull()?.let { failure ->
				failures += "v$startVersion: ${failure.message}"
			}
		}
		check(failures.isEmpty()) {
			"Migration matrix failures:\n${failures.joinToString("\n")}"
		}
	}

	@Test
	fun v16StepIntervalRowsSurviveHistoricalColumnReconciliation() {
		val databaseName = "release_2024_1_v16_step_interval.db"
		helper.createDatabase(databaseName, 16).use { db ->
			db.execSQL(
				"""
				INSERT INTO step_interval(
					id, start_time_ms, end_time_ms, step_count,
					sensor_value_start, sensor_value_end, sensor_reset, createdAt
				) VALUES (41, 1000, 2000, 321, 10000, 10321, 0, 1700000000000)
				""".trimIndent(),
			)
		}

		helper.runMigrationsAndValidate(
			databaseName,
			CURRENT_DATABASE_VERSION,
			true,
			*migrations.filter { it.startVersion >= 16 }.toTypedArray(),
		).use { db ->
			db.query(
				"""
				SELECT id, start_time_ms, end_time_ms, step_count,
					sensor_value_start, sensor_value_end, sensor_reset, created_at
				FROM step_interval
				""".trimIndent(),
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(41L, cursor.getLong(0))
				assertEquals(1000L, cursor.getLong(1))
				assertEquals(2000L, cursor.getLong(2))
				assertEquals(321, cursor.getInt(3))
				assertEquals(10000L, cursor.getLong(4))
				assertEquals(10321L, cursor.getLong(5))
				assertEquals(0, cursor.getInt(6))
				assertEquals(1_700_000_000_000L, cursor.getLong(7))
			}
		}
	}

	@Test
	fun v20CellIdCollisionPreservesModernAndLegacyRows() {
		val databaseName = "release_2024_1_v20_cell_collision.db"
		helper.createDatabase(databaseName, 20).use { db ->
			db.execSQL(
				"""
				INSERT INTO cell_sample(
					id, time_ms, cell_id, lac, mcc, mnc, network_type,
					signal_strength, lat_e7, lon_e7, provenance, created_at
				) VALUES (1, 900, 111, 22, 230, 1, 13, 30, 480000000, 170000000,
					'DIRECT', 1000)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO cell_location(
					id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt
				) VALUES (1, 1000, '310', '260', 222, 20, 97, 48.12345678, 17.87654321, 250.5)
				""".trimIndent(),
			)
		}

		helper.runMigrationsAndValidate(
			databaseName,
			CURRENT_DATABASE_VERSION,
			true,
			*migrations.filter { it.startVersion >= 20 }.toTypedArray(),
		).use { db ->
			db.query("SELECT COUNT(*) FROM cell_sample").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2, cursor.getInt(0))
			}
			db.query(
				"""
				SELECT id, legacy_source_id, cell_id, legacy_mcc, legacy_mnc,
					legacy_lat, legacy_lon
				FROM cell_sample
				WHERE provenance = 'LEGACY_MIGRATION'
				""".trimIndent(),
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertTrue(cursor.getLong(0) != 1L)
				assertEquals(1L, cursor.getLong(1))
				assertEquals(222L, cursor.getLong(2))
				assertEquals("310", cursor.getString(3))
				assertEquals("260", cursor.getString(4))
				assertEquals(48.12345678, cursor.getDouble(5), 0.0)
				assertEquals(17.87654321, cursor.getDouble(6), 0.0)
			}
		}
	}

	@Test
	fun v20WifiIdentityCollisionPreservesModernAndLegacyRows() {
		val databaseName = "release_2024_1_v20_wifi_collision.db"
		helper.createDatabase(databaseName, 20).use { db ->
			db.execSQL(
				"""
				INSERT INTO wifi_observation(
					id, time_ms, bssid, ssid, capabilities, frequency, level,
					lat_e7, lon_e7, provenance, created_at
				) VALUES (1, 2000, 'AA:BB:CC:DD:EE:FF', 'modern', '[WPA3]', 5955, -40,
					480000000, 170000000, 'DIRECT', 1000)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO wifi_data(
					bssid, longitude, latitude, altitude, first_seen, last_seen,
					ssid, capabilities, frequency, level
				) VALUES ('AA:BB:CC:DD:EE:FF', 17.87654321, 48.12345678, 250.5,
					1500, 2000, 'legacy', '[WPA2]', 2412, -70)
				""".trimIndent(),
			)
		}

		helper.runMigrationsAndValidate(
			databaseName,
			CURRENT_DATABASE_VERSION,
			true,
			*migrations.filter { it.startVersion >= 20 }.toTypedArray(),
		).use { db ->
			db.query(
				"""
				SELECT ssid, capabilities, frequency, level, legacy_first_seen_ms,
					legacy_alt_m, legacy_lat, legacy_lon
				FROM wifi_observation
				WHERE bssid = 'AA:BB:CC:DD:EE:FF'
				ORDER BY id
				""".trimIndent(),
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("modern", cursor.getString(0))
				assertTrue(cursor.moveToNext())
				assertEquals("legacy", cursor.getString(0))
				assertEquals("[WPA2]", cursor.getString(1))
				assertEquals(2412, cursor.getInt(2))
				assertEquals(-70, cursor.getInt(3))
				assertEquals(1500L, cursor.getLong(4))
				assertEquals(250.5, cursor.getDouble(5), 0.0)
				assertEquals(48.12345678, cursor.getDouble(6), 0.0)
				assertEquals(17.87654321, cursor.getDouble(7), 0.0)
				assertTrue(!cursor.moveToNext())
			}
		}
	}

	private companion object {
		const val RELEASE_DATABASE_VERSION = 10
		const val CURRENT_DATABASE_VERSION = 40

		val migrations = AppDatabase.migrations.filter { it.startVersion >= RELEASE_DATABASE_VERSION }
	}
}
