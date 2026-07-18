package com.adsamcik.tracker.map.data

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioQueryExecutionTest {

	@Test
	fun wifiQueryExecutesAndMergesSpatialRepresentatives() {
		SQLiteDatabase.create(null).use { database ->
			database.execSQL(
				"""
				CREATE TABLE wifi_observation (
					id INTEGER PRIMARY KEY,
					lat_e7 INTEGER,
					lon_e7 INTEGER,
					time_ms INTEGER NOT NULL,
					bssid TEXT NOT NULL,
					level INTEGER NOT NULL,
					frequency INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			database.execSQL(
				"INSERT INTO wifi_observation VALUES " +
					"(1, 500000000, 140000000, 1000, '00:11:22:33:44:55', -60, 2412), " +
					"(2, 500000100, 140000100, 2000, '00:11:22:33:44:55', -50, 2412), " +
					"(3, 500100000, 140100000, 3000, '66:77:88:99:AA:BB', -70, 5180)",
			)

			val query = RadioQueryBuilder.wifi(GeoQuery(source = GeoSource.WIFI, sampleLimit = 10))
			database.rawQuery(query.sql, emptyArray()).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2, cursor.count)
			}
		}
	}

	@Test
	fun cellQueryExecutesAndExcludesUnsupportedSignal() {
		SQLiteDatabase.create(null).use { database ->
			database.execSQL(
				"""
				CREATE TABLE cell_sample (
					id INTEGER PRIMARY KEY,
					lat_e7 INTEGER,
					lon_e7 INTEGER,
					time_ms INTEGER NOT NULL,
					cell_id INTEGER NOT NULL,
					lac INTEGER NOT NULL,
					mcc INTEGER NOT NULL,
					mnc INTEGER NOT NULL,
					network_type INTEGER NOT NULL,
					signal_strength INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			database.execSQL(
				"INSERT INTO cell_sample VALUES " +
					"(1, 500000000, 140000000, 1000, 42, 10, 230, 1, 0, 0), " +
					"(2, 500100000, 140100000, 2000, 43, 10, 230, 1, 4, 55)",
			)

			val query = RadioQueryBuilder.cell(GeoQuery(source = GeoSource.CELL, sampleLimit = 10))
			database.rawQuery(query.sql, emptyArray()).use { cursor ->
				val asuColumn = cursor.getColumnIndexOrThrow("asu")
				assertTrue(cursor.moveToFirst())
				assertEquals(1, cursor.count)
				assertEquals(43L, cursor.getLong(cursor.getColumnIndexOrThrow("cell_id")))
				assertEquals(55, cursor.getInt(asuColumn))
			}
		}
	}
}
