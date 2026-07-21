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

/** Pins the v37 durable replay/idempotency migration independently of Room codegen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V36ToV37MigrationTest {
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
				.callback(object : SupportSQLiteOpenHelper.Callback(36) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createV36Tables(db)
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
		seedV36Rows(db)
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration assigns legacy identities, adds leases, and creates quarantine ledger`() {
		MIGRATION_36_37.migrate(db)

		columnNames("pending_signal") shouldBe listOf(
			"id",
			"session_id",
			"signal_json",
			"created_at",
			"signal_id",
			"envelope_version",
			"payload_checksum",
			"claim_token",
			"claim_expires_at",
			"delivery_attempt_count",
		)
		columnNames("cell_sample") shouldBe listOf("id", "source_signal_id", "source_item_index")
		columnNames("wifi_observation") shouldBe listOf("id", "source_signal_id", "source_item_index")
		columnNames("location_sample") shouldBe listOf("id", "source_signal_id")
		columnNames("location_observation") shouldBe listOf("id", "source_signal_id")
		columnNames("pressure_sample") shouldBe listOf("id", "source_signal_id")
		columnNames("step_interval") shouldBe listOf("id", "source_signal_id")
		columnNames("activity_snapshot") shouldBe listOf("id", "source_signal_id")

		stringAt("SELECT signal_id FROM pending_signal WHERE id = 1") shouldBe "legacy:pending_signal:1"
		stringAt("SELECT source_signal_id FROM cell_sample WHERE id = 1") shouldBe "legacy:cell_sample:1"
		longAt("SELECT source_item_index FROM cell_sample WHERE id = 1") shouldBe 0L
		stringAt("SELECT source_signal_id FROM wifi_observation WHERE id = 1") shouldBe "legacy:wifi_observation:1"
		longAt("SELECT delivery_attempt_count FROM pending_signal WHERE id = 1") shouldBe 0L

		indexNames("pending_signal") shouldBe setOf(
			"idx_pending_signal_session_time",
			"idx_pending_signal_signal_id",
			"idx_pending_signal_claimable",
		)
		indexNames("quarantined_signal") shouldBe setOf(
			"idx_quarantined_signal_source_pending",
			"idx_quarantined_signal_signal_id",
			"idx_quarantined_signal_time",
			"idx_quarantined_signal_reason",
		)
		listOf(
			"presence_interval",
			"analysis_cell",
			"presence_compaction_block",
			"presence_cell_contribution",
			"presence_compaction_checkpoint",
		).forEach { table ->
			tableExists(table) shouldBe false
		}
	}

	@Test
	fun `migration unique identities reject duplicate replay rows`() {
		MIGRATION_36_37.migrate(db)

		db.execSQL("INSERT INTO location_sample(id, source_signal_id) VALUES (2, 'signal-1')")
		val duplicate = runCatching {
			db.execSQL("INSERT INTO location_sample(id, source_signal_id) VALUES (3, 'signal-1')")
		}
		duplicate.isFailure shouldBe true

		db.execSQL("INSERT INTO cell_sample(id, source_signal_id, source_item_index) VALUES (2, 'signal-2', 0)")
		db.execSQL("INSERT INTO cell_sample(id, source_signal_id, source_item_index) VALUES (3, 'signal-2', 1)")
		val duplicateFanOut = runCatching {
			db.execSQL("INSERT INTO cell_sample(id, source_signal_id, source_item_index) VALUES (4, 'signal-2', 1)")
		}
		duplicateFanOut.isFailure shouldBe true
	}

	private fun createV36Tables(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE pending_signal (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				session_id INTEGER NOT NULL,
				signal_json TEXT NOT NULL,
				created_at INTEGER NOT NULL
			)
			""".trimIndent(),
		)
		database.execSQL("CREATE INDEX idx_pending_signal_session_time ON pending_signal(session_id, created_at)")
		database.execSQL("CREATE INDEX idx_pending_signal_recovery_order ON pending_signal(created_at, id)")
		listOf(
			"location_sample",
			"location_observation",
			"pressure_sample",
			"step_interval",
			"activity_snapshot",
			"cell_sample",
			"wifi_observation",
		).forEach { table ->
			database.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
		}
		// The v36 schema snapshot included these now-removed presence tables.
		// Minimal shapes are sufficient to pin their dependency-safe removal.
		database.execSQL("CREATE TABLE presence_interval (id INTEGER PRIMARY KEY NOT NULL)")
		database.execSQL("CREATE TABLE analysis_cell (cell_id TEXT PRIMARY KEY NOT NULL)")
		database.execSQL("CREATE TABLE presence_compaction_block (id INTEGER PRIMARY KEY NOT NULL)")
		database.execSQL("CREATE TABLE presence_cell_contribution (block_id INTEGER NOT NULL, cell_id TEXT NOT NULL)")
		database.execSQL("CREATE TABLE presence_compaction_checkpoint (pipeline_key TEXT PRIMARY KEY NOT NULL)")
	}

	private fun seedV36Rows(database: SupportSQLiteDatabase) {
		database.execSQL("INSERT INTO pending_signal(id, session_id, signal_json, created_at) VALUES (1, 7, '{}', 1000)")
		listOf(
			"location_sample",
			"location_observation",
			"pressure_sample",
			"step_interval",
			"activity_snapshot",
			"cell_sample",
			"wifi_observation",
		).forEach { table ->
			database.execSQL("INSERT INTO $table(id) VALUES (1)")
		}
	}

	private fun columnNames(table: String): List<String> =
		db.query("PRAGMA table_info($table)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(nameIndex))
			}
		}

	private fun indexNames(table: String): Set<String> =
		db.query("PRAGMA index_list($table)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(nameIndex))
			}
		}

	private fun tableExists(table: String): Boolean =
		db.query(
			"SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
			arrayOf<Any?>(table),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) == 1
		}

	private fun stringAt(query: String): String =
		db.query(query).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}

	private fun longAt(query: String): Long =
		db.query(query).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0)
		}

	private companion object {
		const val TEST_DB = "v36_to_v37_migration_test.db"
	}
}
