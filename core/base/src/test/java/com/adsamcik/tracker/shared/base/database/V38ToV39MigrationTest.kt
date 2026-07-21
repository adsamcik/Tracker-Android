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
 * Pins the v38 -> v39 publication-state migration. Existing imports were
 * already user-visible before this status existed, so they migrate as READY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V38ToV39MigrationTest {
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
				.callback(object : SupportSQLiteOpenHelper.Callback(38) {
					override fun onCreate(db: SupportSQLiteDatabase) {
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
								max_lon_e7 INTEGER NOT NULL,
								cell_index_built INTEGER NOT NULL DEFAULT 0
							)
							""".trimIndent(),
						)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		db = helper.writableDatabase
		db.execSQL(
			"""
			INSERT INTO osm_import
			(id, display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7, cell_index_built)
			VALUES (1, 'Prague.osm.pbf', 'content://test/prague.osm.pbf',
				1700000000000, 42, 123, 500000000, 500700000, 144000000, 144800000, 1)
			""".trimIndent(),
		)
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration marks existing imports READY without changing their metadata`() {
		MIGRATION_38_39.migrate(db)

		db.query(
			"""
			SELECT display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7, cell_index_built, status
			FROM osm_import WHERE id = 1
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "Prague.osm.pbf"
			cursor.getString(1) shouldBe "content://test/prague.osm.pbf"
			cursor.getLong(2) shouldBe 1_700_000_000_000L
			cursor.getLong(3) shouldBe 42L
			cursor.getLong(4) shouldBe 123L
			cursor.getInt(5) shouldBe 500_000_000
			cursor.getInt(6) shouldBe 500_700_000
			cursor.getInt(7) shouldBe 144_000_000
			cursor.getInt(8) shouldBe 144_800_000
			cursor.getInt(9) shouldBe 1
			cursor.getString(10) shouldBe "READY"
		}
	}

	private companion object {
		const val TEST_DB = "v38-to-v39-migration"
	}
}
