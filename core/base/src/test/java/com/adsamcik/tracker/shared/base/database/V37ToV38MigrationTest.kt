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

/** Pins the v38 source-evidence schema independently of Room code generation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V37ToV38MigrationTest {
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
				.callback(object : SupportSQLiteOpenHelper.Callback(37) {
					override fun onCreate(db: SupportSQLiteDatabase) = createV37Tables(db)

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		db = helper.writableDatabase
		db.execSQL("INSERT INTO location_observation(id) VALUES (7)")
		db.execSQL("INSERT INTO location_sample(id) VALUES (9)")
		db.execSQL(
			"INSERT INTO pending_signal(id, session_id, signal_json, created_at) VALUES (11, 1, '{}', 12)",
		)
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration adds provider identities lifecycle guard and append-only source evidence`() {
		MIGRATION_37_38.migrate(db)

		stringAt("SELECT source_event_id FROM location_observation WHERE id = 7") shouldBe
			"legacy:location_observation_event:7"
		stringAt("SELECT callback_id FROM location_observation WHERE id = 7") shouldBe
			"legacy:location_observation_callback:7"
		stringAt("SELECT clock_domain_id FROM location_observation WHERE id = 7") shouldBe
			"legacy:unknown"
		longAt("SELECT captured_epoch FROM pending_signal WHERE id = 11") shouldBe 0L
		longAt("SELECT acquired_at_ms FROM pending_signal WHERE id = 11") shouldBe 0L
		longAt("SELECT revision FROM source_evidence_state WHERE id = 1") shouldBe 0L
		longAt("SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1") shouldBe 0L

		tableExists("location_observation_decision") shouldBe true
		tableExists("tracker_state_event") shouldBe true
		tableExists("source_evidence_state") shouldBe true
		indexExists("location_observation", "idx_location_observation_source_event") shouldBe true
		indexExists("location_sample", "idx_location_sample_source_event") shouldBe true
		indexExists("location_observation_decision", "idx_location_observation_decision_event") shouldBe true
	}

	private fun createV37Tables(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE location_observation (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				source_signal_id TEXT,
				fix_elapsed_realtime_nanos INTEGER NOT NULL DEFAULT 0
			)
			""".trimIndent(),
		)
		database.execSQL(
			"""
			CREATE TABLE location_sample (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				source_signal_id TEXT
			)
			""".trimIndent(),
		)
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
	}

	private fun tableExists(table: String): Boolean =
		db.query(
			"SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
			arrayOf<Any?>(table),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) == 1
		}

	private fun indexExists(table: String, index: String): Boolean =
		db.query("PRAGMA index_list($table)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == index }
		}

	private fun stringAt(query: String): String = db.query(query).use { cursor ->
		cursor.moveToFirst() shouldBe true
		cursor.getString(0)
	}

	private fun longAt(query: String): Long = db.query(query).use { cursor ->
		cursor.moveToFirst() shouldBe true
		cursor.getLong(0)
	}

	private companion object {
		const val TEST_DB = "v37_to_v38_migration_test.db"
	}
}
