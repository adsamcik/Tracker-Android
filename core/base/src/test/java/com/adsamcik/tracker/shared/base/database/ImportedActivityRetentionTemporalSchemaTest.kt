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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedActivityRetentionTemporalSchemaTest {
	private lateinit var helper: SupportSQLiteOpenHelper

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createLegacyReceiptTable(db)
						insertLegacyReceipt(db, "legacy-before", "legacy-effect-before")
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = error("This isolated fixture has no version upgrade")
				})
				.build(),
		)
	}

	@After
	fun tearDown() {
		helper.close()
	}

	@Test
	fun `additive temporal columns preserve legacy checksums and unavailable defaults`() {
		val database = helper.writableDatabase

		addImportedActivityRetentionTemporalAuthorityColumns(database)
		insertLegacyReceipt(database, "legacy-after", "legacy-effect-after")

		listOf(
			"legacy-before" to "legacy-effect-before",
			"legacy-after" to "legacy-effect-after",
		).forEach { (identity, checksum) ->
			database.query(
				"SELECT temporal_authority_state, latest_member_start_time_ms, " +
					"latest_member_identity, structural_zone_range_count, " +
					"structural_zone_ranges_payload, structural_zone_coverage_complete, " +
					"effect_checksum FROM imported_activity_retention_receipt " +
					"WHERE entry_identity = ?",
				arrayOf(identity),
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "UNAVAILABLE"
				cursor.isNull(1) shouldBe true
				cursor.isNull(2) shouldBe true
				cursor.getInt(3) shouldBe 0
				cursor.getString(4) shouldBe ""
				cursor.getInt(5) shouldBe 0
				cursor.getString(6) shouldBe checksum
				cursor.moveToNext() shouldBe false
			}
		}

		val columns = buildMap {
			database.query("PRAGMA table_info(imported_activity_retention_receipt)").use { cursor ->
				while (cursor.moveToNext()) {
					put(
						cursor.getString(cursor.getColumnIndexOrThrow("name")),
						ColumnShape(
							notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
							defaultValue = cursor.getString(
								cursor.getColumnIndexOrThrow("dflt_value"),
							),
						),
					)
				}
			}
		}
		columns["temporal_authority_state"] shouldBe ColumnShape(1, "'UNAVAILABLE'")
		columns["latest_member_start_time_ms"] shouldBe ColumnShape(0, null)
		columns["latest_member_identity"] shouldBe ColumnShape(0, null)
		columns["structural_zone_range_count"] shouldBe ColumnShape(1, "0")
		columns["structural_zone_ranges_payload"] shouldBe ColumnShape(1, "''")
		columns["structural_zone_coverage_complete"] shouldBe ColumnShape(1, "0")
	}

	@Test
	fun `post migration available and redacted temporal authority persists exactly`() {
		val database = helper.writableDatabase
		addImportedActivityRetentionTemporalAuthorityColumns(database)

		insertTemporalReceipt(
			database = database,
			entryIdentity = "available",
			startTimeMs = 10L,
			endTimeMs = 20L,
			receivedAtMs = 20L,
			state = "AVAILABLE",
			latestMemberStartTimeMs = 15L,
			latestMemberIdentity = "sha256:${"a".repeat(64)}",
			rangeCount = 1,
			rangePayload = "UTC:10:20",
			coverageComplete = 1,
			effectChecksum = "available-effect",
		)
		insertTemporalReceipt(
			database = database,
			entryIdentity = "redacted",
			startTimeMs = 0L,
			endTimeMs = 0L,
			receivedAtMs = 0L,
			state = "REDACTED",
			latestMemberStartTimeMs = null,
			latestMemberIdentity = null,
			rangeCount = 0,
			rangePayload = "",
			coverageComplete = 0,
			effectChecksum = "redacted-effect",
		)

		assertTemporalReceipt(
			database,
			"available",
			TemporalShape(
				"AVAILABLE",
				10L,
				20L,
				20L,
				15L,
				"sha256:${"a".repeat(64)}",
				1,
				"UTC:10:20",
				1,
				"available-effect",
			),
		)
		assertTemporalReceipt(
			database,
			"redacted",
			TemporalShape("REDACTED", 0L, 0L, 0L, null, null, 0, "", 0, "redacted-effect"),
		)
	}

	private fun createLegacyReceiptTable(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE imported_activity_retention_receipt (
				entry_identity TEXT NOT NULL PRIMARY KEY,
				collected_data_epoch INTEGER NOT NULL,
				source_evidence_revision INTEGER NOT NULL,
				retained_from_ms INTEGER NOT NULL,
				retained_at_ms INTEGER NOT NULL,
				latest_import_revision INTEGER NOT NULL,
				latest_content_checksum TEXT NOT NULL,
				start_time_ms INTEGER NOT NULL,
				end_time_ms INTEGER NOT NULL,
				received_at_ms INTEGER NOT NULL,
				revision_count INTEGER NOT NULL,
				import_receipt_count INTEGER NOT NULL,
				run_row_count INTEGER NOT NULL,
				zone_epoch_row_count INTEGER NOT NULL,
				window_row_count INTEGER NOT NULL,
				fragment_row_count INTEGER NOT NULL,
				run_deletion_count INTEGER NOT NULL,
				run_deletion_set_checksum TEXT NOT NULL,
				source_fence_count INTEGER NOT NULL,
				source_fence_set_checksum TEXT NOT NULL,
				protected_identity_count INTEGER NOT NULL,
				protected_identity_set_checksum TEXT NOT NULL,
				lineage_authority_checksum TEXT NOT NULL,
				effect_checksum TEXT NOT NULL
			)
			""".trimIndent(),
		)
	}

	private fun insertLegacyReceipt(
		database: SupportSQLiteDatabase,
		entryIdentity: String,
		effectChecksum: String,
	) {
		database.execSQL(
			"""
			INSERT INTO imported_activity_retention_receipt (
				entry_identity, collected_data_epoch, source_evidence_revision,
				retained_from_ms, retained_at_ms, latest_import_revision,
				latest_content_checksum, start_time_ms, end_time_ms, received_at_ms,
				revision_count, import_receipt_count, run_row_count, zone_epoch_row_count,
				window_row_count, fragment_row_count, run_deletion_count,
				run_deletion_set_checksum, source_fence_count, source_fence_set_checksum,
				protected_identity_count, protected_identity_set_checksum,
				lineage_authority_checksum, effect_checksum
			) VALUES (?, 7, 3, 100, 200, 1, 'content', 10, 20, 30, 1, 1, 1, 1, 1, 1,
				1, 'run-set', 1, 'fence-set', 4, 'identity-set', 'lineage', ?)
			""".trimIndent(),
			arrayOf(entryIdentity, effectChecksum),
		)
	}

	private fun insertTemporalReceipt(
		database: SupportSQLiteDatabase,
		entryIdentity: String,
		startTimeMs: Long,
		endTimeMs: Long,
		receivedAtMs: Long,
		state: String,
		latestMemberStartTimeMs: Long?,
		latestMemberIdentity: String?,
		rangeCount: Int,
		rangePayload: String,
		coverageComplete: Int,
		effectChecksum: String,
	) {
		database.execSQL(
			"""
			INSERT INTO imported_activity_retention_receipt (
				entry_identity, collected_data_epoch, source_evidence_revision,
				retained_from_ms, retained_at_ms, latest_import_revision,
				latest_content_checksum, start_time_ms, end_time_ms, received_at_ms,
				temporal_authority_state, latest_member_start_time_ms, latest_member_identity,
				structural_zone_range_count, structural_zone_ranges_payload,
				structural_zone_coverage_complete, revision_count, import_receipt_count,
				run_row_count, zone_epoch_row_count, window_row_count, fragment_row_count,
				run_deletion_count, run_deletion_set_checksum, source_fence_count,
				source_fence_set_checksum, protected_identity_count,
				protected_identity_set_checksum, lineage_authority_checksum, effect_checksum
			) VALUES (?, 7, 3, 100, 200, 1, 'content', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 1,
				1, 1, 1, 1, 'run-set', 1, 'fence-set', 4, 'identity-set', 'lineage', ?)
			""".trimIndent(),
			arrayOf(
				entryIdentity,
				startTimeMs,
				endTimeMs,
				receivedAtMs,
				state,
				latestMemberStartTimeMs,
				latestMemberIdentity,
				rangeCount,
				rangePayload,
				coverageComplete,
				effectChecksum,
			),
		)
	}

	private fun assertTemporalReceipt(
		database: SupportSQLiteDatabase,
		entryIdentity: String,
		expected: TemporalShape,
	) {
		database.query(
			"SELECT temporal_authority_state, start_time_ms, end_time_ms, received_at_ms, " +
				"latest_member_start_time_ms, latest_member_identity, " +
				"structural_zone_range_count, structural_zone_ranges_payload, " +
				"structural_zone_coverage_complete, effect_checksum " +
				"FROM imported_activity_retention_receipt " +
				"WHERE entry_identity = ?",
			arrayOf(entryIdentity),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			TemporalShape(
				state = cursor.getString(0),
				startTimeMs = cursor.getLong(1),
				endTimeMs = cursor.getLong(2),
				receivedAtMs = cursor.getLong(3),
				latestMemberStartTimeMs =
					if (cursor.isNull(4)) null else cursor.getLong(4),
				latestMemberIdentity = if (cursor.isNull(5)) null else cursor.getString(5),
				rangeCount = cursor.getInt(6),
				rangePayload = cursor.getString(7),
				coverageComplete = cursor.getInt(8),
				effectChecksum = cursor.getString(9),
			) shouldBe expected
			cursor.moveToNext() shouldBe false
		}
	}

	private data class ColumnShape(
		val notNull: Int,
		val defaultValue: String?,
	)

	private data class TemporalShape(
		val state: String,
		val startTimeMs: Long,
		val endTimeMs: Long,
		val receivedAtMs: Long,
		val latestMemberStartTimeMs: Long?,
		val latestMemberIdentity: String?,
		val rangeCount: Int,
		val rangePayload: String,
		val coverageComplete: Int,
		val effectChecksum: String,
	)
}
