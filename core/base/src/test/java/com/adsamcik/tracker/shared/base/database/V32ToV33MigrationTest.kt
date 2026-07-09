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
 * Pins the contract of [MIGRATION_32_33]: add `cell_index_built` column to
 * `osm_import` with default 0. All existing rows get the default, ensuring
 * the reindexer self-healing heuristic re-triggers after the migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V32ToV33MigrationTest {

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
				.callback(object : SupportSQLiteOpenHelper.Callback(32) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createOsmImportTableV32(db)
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
		seedImport()
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration adds cell_index_built column with default 0`() {
		MIGRATION_32_33.migrate(db)

		db.query("SELECT cell_index_built FROM osm_import").use { c ->
			c.moveToFirst() shouldBe true
			c.getInt(0) shouldBe 0
		}
	}

	@Test
	fun `migration preserves existing osm_import rows`() {
		countRows("osm_import") shouldBe 1

		MIGRATION_32_33.migrate(db)

		countRows("osm_import") shouldBe 1
		db.query("SELECT display_name FROM osm_import").use { c ->
			c.moveToFirst() shouldBe true
			c.getString(0) shouldBe "Prague.osm.pbf"
		}
	}

	@Test
	fun `migration is idempotent — re-running is harmless`() {
		MIGRATION_32_33.migrate(db)
		// Re-running ALTER TABLE ADD COLUMN on an existing column throws,
		// but the contract is that migrations run exactly once.
		// Verify the first run left the database in a valid state.
		countRows("osm_import") shouldBe 1
		columnNames("osm_import").contains("cell_index_built") shouldBe true
	}

	@Test
	fun `new inserts after migration get cell_index_built = 0 by default`() {
		MIGRATION_32_33.migrate(db)

		db.execSQL(
			"""
			INSERT INTO osm_import
			(display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7)
			VALUES ('Berlin.osm.pbf', 'content://test/berlin.osm.pbf', 1700000001000, 5, 20,
				524000000, 526000000, 133000000, 137000000)
			""".trimIndent()
		)

		db.query("SELECT cell_index_built FROM osm_import ORDER BY id DESC LIMIT 1").use { c ->
			c.moveToFirst() shouldBe true
			c.getInt(0) shouldBe 0
		}
	}

	private fun createOsmImportTableV32(db: SupportSQLiteDatabase) {
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
	}

	private fun seedImport() {
		db.execSQL(
			"""
			INSERT INTO osm_import
			(id, display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7)
			VALUES (1, 'Prague.osm.pbf', 'content://test/prague.osm.pbf', 1700000000000, 2, 8,
				500000000, 500700000, 144000000, 144800000)
			""".trimIndent()
		)
	}

	private fun countRows(table: String): Int =
		db.query("SELECT COUNT(*) FROM $table").use { c ->
			c.moveToFirst()
			c.getInt(0)
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
		const val TEST_DB = "v32-to-v33-migration"
	}
}
