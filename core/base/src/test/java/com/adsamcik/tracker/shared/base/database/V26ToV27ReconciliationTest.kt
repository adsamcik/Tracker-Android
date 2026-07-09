package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V26ToV27ReconciliationTest {

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
				.callback(object : SupportSQLiteOpenHelper.Callback(26) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createDriftedV26Schema(db)
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
		seedCursor()
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration adds missing session segment end time index`() {
		MIGRATION_26_27.migrate(db)

		indexNames("session_segment") shouldContain "idx_session_segment_end_time_ms"
		indexColumns("idx_session_segment_end_time_ms") shouldBe listOf("end_time_ms")
	}

	@Test
	fun `migration adds missing session segment primary activity index`() {
		MIGRATION_26_27.migrate(db)

		indexNames("session_segment") shouldContain "idx_session_segment_primary_activity"
		indexColumns("idx_session_segment_primary_activity") shouldBe listOf("primary_activity")
	}

	@Test
	fun `migration replaces single column domain event timestamp index with composite index`() {
		MIGRATION_26_27.migrate(db)

		val indexes = indexNames("domain_event")
		indexes shouldNotContain "index_domain_event_timestamp_ms"
		indexes shouldContain "index_domain_event_timestamp_ms_id"
		indexColumns("index_domain_event_timestamp_ms_id") shouldBe listOf("timestamp_ms", "id")
	}

	@Test
	fun `migration adds last processed id column with default zero`() {
		MIGRATION_26_27.migrate(db)

		val column = columns("domain_event_cursor").single { it.name == "last_processed_id" }
		column.type shouldBe "INTEGER"
		column.notNull shouldBe true
		column.defaultValue shouldBe "0"
	}

	@Test
	fun `migration preserves existing cursor timestamp while backfilling last processed id`() {
		MIGRATION_26_27.migrate(db)

		db.query(
			"SELECT last_processed_ms, last_processed_id FROM domain_event_cursor WHERE consumer_id = ?",
			arrayOf("achievement-processor")
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 1_700_000_123_456L
			cursor.getLong(1) shouldBe 0L
		}
	}

	@Test
	fun `migration can be re-run after reconciliation without duplicating columns or indexes`() {
		MIGRATION_26_27.migrate(db)
		MIGRATION_26_27.migrate(db)

		indexNames("domain_event").count { it == "index_domain_event_timestamp_ms_id" } shouldBe 1
		indexNames("session_segment").count { it == "idx_session_segment_end_time_ms" } shouldBe 1
		indexNames("session_segment").count { it == "idx_session_segment_primary_activity" } shouldBe 1
		columns("domain_event_cursor").count { it.name == "last_processed_id" } shouldBe 1
	}

	private fun createDriftedV26Schema(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE domain_event (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				timestamp_ms INTEGER NOT NULL
			)
			""".trimIndent()
		)
		db.execSQL("CREATE INDEX index_domain_event_timestamp_ms ON domain_event(timestamp_ms)")
		db.execSQL(
			"""
			CREATE TABLE domain_event_cursor (
				consumer_id TEXT PRIMARY KEY NOT NULL,
				last_processed_ms INTEGER NOT NULL
			)
			""".trimIndent()
		)
		db.execSQL(
			"""
			CREATE TABLE session_segment (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				end_time_ms INTEGER NOT NULL,
				primary_activity TEXT
			)
			""".trimIndent()
		)
	}

	private fun seedCursor() {
		db.execSQL(
			"""
			INSERT INTO domain_event_cursor (consumer_id, last_processed_ms)
			VALUES ('achievement-processor', 1700000123456)
			""".trimIndent()
		)
	}

	private fun indexNames(table: String): List<String> =
		db.query("PRAGMA index_list('$table')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
				}
			}
		}

	private fun indexColumns(index: String): List<String> =
		db.query("PRAGMA index_info('$index')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
				}
			}
		}

	private fun columns(table: String): List<ColumnInfo> =
		db.query("PRAGMA table_info('$table')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ColumnInfo(
							name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
							type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
							notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
							defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
						)
					)
				}
			}
		}

	private data class ColumnInfo(
		val name: String,
		val type: String,
		val notNull: Boolean,
		val defaultValue: String?,
	)

	private companion object {
		const val TEST_DB = "v26-to-v27-reconciliation"
	}
}
