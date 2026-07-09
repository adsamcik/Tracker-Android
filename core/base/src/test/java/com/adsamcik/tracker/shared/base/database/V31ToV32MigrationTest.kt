package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the contract of [MIGRATION_31_32]: drop every `osm_way_cell` row so the
 * new 0.01° [com.adsamcik.tracker.osm.io.OsmGridIndex] cells can be rebuilt by
 * the background reindexer; preserve `osm_import` and `osm_way` rows including
 * bbox columns so the reindex needs no polyline decode or re-download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V31ToV32MigrationTest {

	private lateinit var context: Application
	private lateinit var helper: SupportSQLiteOpenHelper
	private lateinit var db: SupportSQLiteDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(TEST_DB)
		helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(TEST_DB)
				.callback(object : SupportSQLiteOpenHelper.Callback(31) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createOsmTables(db)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build()
		)
		db = helper.writableDatabase
		seedOsmData()
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration drops every osm_way_cell row so reindexer can rebuild them`() {
		countRows("osm_way_cell") shouldBe 6

		MIGRATION_31_32.migrate(db)

		countRows("osm_way_cell") shouldBe 0
	}

	@Test
	fun `migration preserves osm_import rows so DefaultSpeedLimitSource stays in OSM mode`() {
		countRows("osm_import") shouldBe 1

		MIGRATION_31_32.migrate(db)

		countRows("osm_import") shouldBe 1
		db.query("SELECT display_name FROM osm_import").use { c ->
			c.moveToFirst() shouldBe true
			c.getString(0) shouldBe "Prague.osm.pbf"
		}
	}

	@Test
	fun `migration preserves osm_way rows including bbox columns so reindex needs no polyline decode`() {
		countRows("osm_way") shouldBe 2

		MIGRATION_31_32.migrate(db)

		countRows("osm_way") shouldBe 2
		db.query(
			"SELECT id, bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7 " +
				"FROM osm_way ORDER BY id"
		).use { c ->
			c.moveToNext() shouldBe true
			c.getLong(0) shouldBe 100L
			c.getInt(1) shouldBe 500_000_000
			c.getInt(2) shouldBe 500_100_000
			c.getInt(3) shouldBe 144_000_000
			c.getInt(4) shouldBe 144_100_000
			c.moveToNext() shouldBe true
			c.getLong(0) shouldBe 200L
			c.getInt(1) shouldBe 500_500_000
			c.getInt(2) shouldBe 500_700_000
			c.getInt(3) shouldBe 144_500_000
			c.getInt(4) shouldBe 144_800_000
		}
	}

	@Test
	fun `migration leaves osm_way_cell schema and indices intact`() {
		val cellIndicesBefore = indexNames("osm_way_cell")

		MIGRATION_31_32.migrate(db)

		indexNames("osm_way_cell") shouldBe cellIndicesBefore
		// Schema (column list) is also unchanged.
		columnNames("osm_way_cell") shouldBe listOf("cell_key", "way_id")
	}

	@Test
	fun `migration is idempotent so re-running it on an empty osm_way_cell is a no-op`() {
		MIGRATION_31_32.migrate(db)
		MIGRATION_31_32.migrate(db)

		countRows("osm_way_cell") shouldBe 0
		countRows("osm_way") shouldBe 2
		countRows("osm_import") shouldBe 1
	}

	private fun createOsmTables(db: SupportSQLiteDatabase) {
		// Schema for v31 osm_import. Indices match the @Entity declaration.
		db.execSQL(
			"""
			CREATE TABLE osm_import (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				display_name TEXT NOT NULL,
				file_uri TEXT NOT NULL,
				imported_at INTEGER NOT NULL,
				way_count INTEGER NOT NULL,
				node_count INTEGER NOT NULL,
				min_lat_e7 INTEGER NOT NULL,
				max_lat_e7 INTEGER NOT NULL,
				min_lon_e7 INTEGER NOT NULL,
				max_lon_e7 INTEGER NOT NULL
			)
			""".trimIndent()
		)

		db.execSQL(
			"""
			CREATE TABLE osm_way (
				id INTEGER PRIMARY KEY NOT NULL,
				import_id INTEGER NOT NULL,
				name TEXT,
				road_class TEXT NOT NULL,
				maxspeed_kmh INTEGER NOT NULL,
				maxspeed_explicit INTEGER NOT NULL,
				is_oneway INTEGER NOT NULL,
				geom_polyline_e7 BLOB NOT NULL,
				bbox_min_lat_e7 INTEGER NOT NULL,
				bbox_max_lat_e7 INTEGER NOT NULL,
				bbox_min_lon_e7 INTEGER NOT NULL,
				bbox_max_lon_e7 INTEGER NOT NULL,
				FOREIGN KEY(import_id) REFERENCES osm_import(id) ON DELETE CASCADE
			)
			""".trimIndent()
		)
		db.execSQL("CREATE INDEX idx_osm_way_import ON osm_way(import_id)")

		db.execSQL(
			"""
			CREATE TABLE osm_way_cell (
				cell_key INTEGER NOT NULL,
				way_id INTEGER NOT NULL,
				PRIMARY KEY(cell_key, way_id),
				FOREIGN KEY(way_id) REFERENCES osm_way(id) ON DELETE CASCADE
			)
			""".trimIndent()
		)
		db.execSQL("CREATE INDEX idx_osm_way_cell_cell ON osm_way_cell(cell_key)")
		db.execSQL("CREATE INDEX idx_osm_way_cell_way ON osm_way_cell(way_id)")
	}

	private fun seedOsmData() {
		db.execSQL(
			"""
			INSERT INTO osm_import
			(id, display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7)
			VALUES (1, 'Prague.osm.pbf', 'content://test/prague.osm.pbf', 1700000000000, 2, 8,
				500000000, 500700000, 144000000, 144800000)
			""".trimIndent()
		)

		// Two ways with bboxes inside the Prague import region. Polyline blob is
		// intentionally empty bytes — the migration must not need to decode it.
		db.execSQL(
			"""
			INSERT INTO osm_way
			(id, import_id, name, road_class, maxspeed_kmh, maxspeed_explicit,
				is_oneway, geom_polyline_e7,
				bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7)
			VALUES (100, 1, 'Test Street', 'residential', 50, 1, 0, x'00',
				500000000, 500100000, 144000000, 144100000)
			""".trimIndent()
		)
		db.execSQL(
			"""
			INSERT INTO osm_way
			(id, import_id, name, road_class, maxspeed_kmh, maxspeed_explicit,
				is_oneway, geom_polyline_e7,
				bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7)
			VALUES (200, 1, NULL, 'secondary', 70, 0, 1, x'00',
				500500000, 500700000, 144500000, 144800000)
			""".trimIndent()
		)

		// Six stale cells (3 per way) keyed under the old 0.08° grid layout.
		// Concrete numeric values don't matter — the migration deletes every row.
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (101, 100)")
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (102, 100)")
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (103, 100)")
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (201, 200)")
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (202, 200)")
		db.execSQL("INSERT INTO osm_way_cell (cell_key, way_id) VALUES (203, 200)")
	}

	private fun countRows(table: String): Int =
		db.query("SELECT COUNT(*) FROM $table").use { c ->
			c.moveToFirst()
			c.getInt(0)
		}

	private fun indexNames(table: String): List<String> =
		db.query("PRAGMA index_list('$table')").use { c ->
			buildList {
				while (c.moveToNext()) {
					add(c.getString(c.getColumnIndexOrThrow("name")))
				}
			}
		}

	private fun columnNames(table: String): List<String> =
		db.query("PRAGMA table_info('$table')").use { c ->
			buildList {
				while (c.moveToNext()) {
					add(c.getString(c.getColumnIndexOrThrow("name")))
				}
			}
		}

	private companion object {
		const val TEST_DB = "v31-to-v32-migration"
	}
}
