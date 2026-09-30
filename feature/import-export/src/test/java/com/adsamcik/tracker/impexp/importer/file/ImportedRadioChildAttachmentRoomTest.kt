package com.adsamcik.tracker.impexp.importer.file

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.portable.CellBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.CellSessionChildV2
import com.adsamcik.tracker.impexp.portable.WifiBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.WifiSessionChildV2
import com.adsamcik.tracker.impexp.portable.radioChildDigest
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.prepareOrResumeRetentionFloorSettlement
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room parent/detail transactions; authored only, execution is deferred to convergence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedRadioChildAttachmentRoomTest {
	private lateinit var db: AppDatabase
	private lateinit var attachment: ImportedRadioChildAttachment

	@Before fun setup() = runTest {
		db = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		db.sourceEvidenceStateDao().ensure()
		attachment = ImportedRadioChildAttachment(db)
	}
	@After fun close() { db.close() }

	@Test fun `both session sources attach exact details once and reject a wider uncertainty interval`() = runTest {
		seedSession("wifi")
		seedSession("cell")
		val wifi = wifiSession()
		val cell = cellSession()
		repeat(2) {
			attachment.wifiSession(listOf(wifi), 1, 0)
			attachment.cellSession(listOf(cell), 1, 0)
		}
		count("imported_wifi_child") shouldBe 1L
		count("imported_cell_child") shouldBe 1L
		db.importedRadioChildDao().wifiSessionChild(ENTRY, 1, RUN, OBSERVATION, 0)?.ssid shouldBe "Cafe"
		db.importedRadioChildDao().cellSessionChild(ENTRY, 1, RUN, OBSERVATION, 0)?.towerIdentity shouldBe "tower"
		assertFailsWith<IllegalStateException> { attachment.wifiSession(listOf(wifiSession(uncertainty = 101)), 1, 0) }
		assertFailsWith<IllegalStateException> { attachment.cellSession(listOf(cellSession(uncertainty = 101)), 1, 0) }
		count("imported_wifi_child") shouldBe 1L
		count("imported_cell_child") shouldBe 1L
	}

	@Test fun `background details and overlapping file retries retain the original exact owner`() = runTest {
		seedBackground("wifi")
		seedBackground("cell")
		repeat(2) {
			// A child carries exact fact/revision identity, independent of its new outer file.
			attachment.wifiBackground(listOf(wifiBackground()), 0)
			attachment.cellBackground(listOf(cellBackground()), 0)
		}
		count("imported_ambient_wifi_child") shouldBe 1L
		count("imported_ambient_cell_child") shouldBe 1L
		db.importedRadioChildDao().wifiBackgroundChild(ARCHIVE, FACT, 1, 0)?.archiveId shouldBe ARCHIVE
		db.importedRadioChildDao().cellBackgroundChild(ARCHIVE, FACT, 1, 0)?.archiveId shouldBe ARCHIVE
	}

	@Test fun `missing later parent rolls back earlier details in the same batch`() = runTest {
		seedBackground("wifi")
		seedBackground("cell")
		assertFailsWith<IllegalStateException> {
			attachment.wifiBackground(listOf(wifiBackground(), wifiBackground(fact = MISSING)), 0)
		}
		assertFailsWith<IllegalStateException> {
			attachment.cellBackground(listOf(cellBackground(), cellBackground(fact = MISSING)), 0)
		}
		count("imported_ambient_wifi_child") shouldBe 0L
		count("imported_ambient_cell_child") shouldBe 0L
	}

	@Test fun `conflicting detailed content keeps the original and rolls back a new sibling`() = runTest {
		seedBackground("wifi", count = 2)
		seedBackground("cell", count = 2)
		attachment.wifiBackground(listOf(wifiBackground()), 0)
		attachment.cellBackground(listOf(cellBackground()), 0)
		assertFailsWith<IllegalStateException> {
			attachment.wifiBackground(listOf(wifiBackground(index = 1), wifiBackground(ssid = "Edited")), 0)
		}
		assertFailsWith<IllegalStateException> {
			attachment.cellBackground(listOf(cellBackground(index = 1), cellBackground(tower = "edited")), 0)
		}
		count("imported_ambient_wifi_child") shouldBe 1L
		count("imported_ambient_cell_child") shouldBe 1L
		db.importedRadioChildDao().wifiBackgroundChild(ARCHIVE, FACT, 1, 0)?.ssid shouldBe "Cafe"
		db.importedRadioChildDao().cellBackgroundChild(ARCHIVE, FACT, 1, 0)?.towerIdentity shouldBe "tower"
	}

	@Test fun `storage failure rolls back one batch and a retry attaches both details once`() = runTest {
		seedBackground("wifi", count = 2)
		db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_radio_child BEFORE INSERT ON imported_ambient_wifi_child " +
			"WHEN NEW.item_index = 1 BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END")
		val children = listOf(wifiBackground(), wifiBackground(index = 1))
		assertFailsWith<SQLiteException> { attachment.wifiBackground(children, 0) }
		count("imported_ambient_wifi_child") shouldBe 0L
		db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_radio_child")
		repeat(2) { attachment.wifiBackground(children, 0) }
		count("imported_ambient_wifi_child") shouldBe 2L
	}

	@Test fun `retention advancing between parent admission and details rejects every source lane`() = runTest {
		seedSession("wifi")
		seedSession("cell")
		seedBackground("wifi")
		seedBackground("cell")
		// Simulates maintenance after the source importer committed its summaries.
		db.sourceEvidenceStateDao().updateLifecycle(0, 901, 2_000)
		assertFailsWith<IllegalStateException> { attachment.wifiSession(listOf(wifiSession()), 1, 0) }
		assertFailsWith<IllegalStateException> { attachment.cellSession(listOf(cellSession()), 1, 0) }
		assertFailsWith<IllegalStateException> { attachment.wifiBackground(listOf(wifiBackground()), 0) }
		assertFailsWith<IllegalStateException> { attachment.cellBackground(listOf(cellBackground()), 0) }
		assertEmptyDetails()
	}

	@Test fun `active cleanup is retryable for empty and populated attachments`() = runTest {
		seedBackground("wifi")
		seedBackground("cell")
		db.prepareOrResumeRetentionFloorSettlement("radio-cleanup", 901, 0, 2_000)
		assertFailsWith<IOException> { attachment.wifiBackground(listOf(wifiBackground()), 0) }
		assertFailsWith<IOException> { attachment.cellBackground(listOf(cellBackground()), 0) }
		assertFailsWith<IOException> { attachment.wifiSession(emptyList(), 1, 0) }
		assertFailsWith<IOException> { attachment.cellSession(emptyList(), 1, 0) }
		assertEmptyDetails()
	}

	@Test fun `clear after decoding cannot attach details under a replacement data epoch`() = runTest {
		seedSession("wifi")
		seedSession("cell")
		seedBackground("wifi")
		seedBackground("cell")
		db.sourceEvidenceStateDao().updateLifecycle(1, null, 2_000)
		assertFailsWith<IllegalStateException> { attachment.wifiSession(listOf(wifiSession()), 1, 0) }
		assertFailsWith<IllegalStateException> { attachment.cellSession(listOf(cellSession()), 1, 0) }
		assertFailsWith<IllegalStateException> { attachment.wifiBackground(listOf(wifiBackground()), 0) }
		assertFailsWith<IllegalStateException> { attachment.cellBackground(listOf(cellBackground()), 0) }
		assertFailsWith<IllegalStateException> { attachment.wifiBackground(emptyList(), 0) }
		assertEmptyDetails()
	}

	private fun assertEmptyDetails() = listOf("imported_wifi_child", "imported_cell_child",
		"imported_ambient_wifi_child", "imported_ambient_cell_child").forEach { count(it) shouldBe 0L }
	private fun count(table: String): Long = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table")
		.use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

	/** Handcrafted admitted parents isolate production attachment; no mocked DAO or source admission. */
	private fun seedSession(source: String) {
		val entry = mutableMapOf<String, Any?>("identity" to ENTRY, "import_revision" to 1,
			"supersedes_import_revision" to null, "content_checksum" to CHECKSUM,
			"source_format" to "tracker-portable-captured-$source", "source_schema_version" to 1,
			"session_mode" to "MANUAL", "start_time_ms" to 900, "end_time_ms" to 1_100,
			"collected_data_epoch" to 0, "import_job_id" to "job", "import_entry_key" to "entry",
			"import_source_name" to "fixture.tracker$source", "received_at_ms" to 2_000)
		if (source == "cell") entry["subscription_grouping"] = "UNKNOWN"
		insert("imported_${source}_entry_revision", entry)
		val run = mutableMapOf<String, Any?>("entry_identity" to ENTRY, "entry_import_revision" to 1,
			"identity" to RUN, "deletion_scope_digest" to CHECKSUM, "content_checksum" to CHECKSUM,
			"start_time_ms" to 900, "end_time_ms" to 1_100, "capture_coverage" to "WHOLE_RUN",
			"availability" to "RETAINED", "acquisition_completeness" to "COMPLETE",
			"retention_loss" to 0, "collected_data_epoch" to 0, "scope_deletion_generation" to 0)
		if (source == "wifi") run["has_unresolved_provider_range"] = 0 else run["subscription_grouping"] = "UNKNOWN"
		insert("imported_${source}_run", run)
		val observation = mutableMapOf<String, Any?>("entry_identity" to ENTRY, "entry_import_revision" to 1,
			"run_identity" to RUN, "identity" to OBSERVATION, "semantic_revision" to 1,
			"supersedes_semantic_revision" to null, "aggregate_owner_identity" to null,
			"aggregate_owner_semantic_revision" to null, "content_checksum" to CHECKSUM,
			"coverage_start_time_ms" to 900, "observed_time_ms" to 1_000,
			"latest_possible_time_ms" to 1_100, "wall_time_uncertainty_ms" to 100, "stored_zone_id" to "UTC")
		observation.putAll(summary(source, 1))
		if (source == "wifi") observation.putAll(mapOf("availability" to "AVAILABLE", "result_completeness" to "COMPLETE",
			"submitted_result_count" to 1, "accepted_result_count" to 1, "stale_result_count" to 0,
			"clock_unverifiable_result_count" to 0, "malformed_result_count" to 0,
			"source_quality_flags" to 0, "source_quality_confidence" to null))
		else observation.putAll(mapOf("child_completeness" to "COMPLETE", "subscription_grouping" to "UNKNOWN",
			"submitted_child_count" to 1, "accepted_child_count" to 1, "stale_child_count" to 0,
			"future_time_child_count" to 0, "missing_time_child_count" to 0, "clock_unverifiable_child_count" to 0,
			"authority_mismatch_child_count" to 0, "unsupported_technology_child_count" to 0,
			"weak_observation_count" to 0, "known_quality_observation_count" to 0, "all_known_quality_is_weak" to 0,
			"quality_flags" to 0, "quality_confidence" to null))
		insert("imported_${source}_observation", observation)
	}

	private fun seedBackground(source: String, count: Int = 1) {
		val fact = mutableMapOf<String, Any?>("archive_id" to ARCHIVE, "fact_id" to FACT, "semantic_revision" to 1,
			"supersedes_semantic_revision" to null, "content_checksum" to CHECKSUM, "portable_effect_checksum" to CHECKSUM,
			"portable_origin" to "LOCAL_DEVICE", "coverage_start_time_ms" to 900, "observed_time_ms" to 1_000,
			"latest_possible_time_ms" to 1_100, "stored_zone_id" to "UTC", "structural_epoch_day" to 0,
			"coverage_completeness" to "COMPLETE", "retention_policy_id" to "test-policy", "retention_approval_revision" to 1,
			"collected_data_epoch" to 0, "import_deletion_generation" to 0, "received_at_ms" to 2_000)
		fact.putAll(summary(source, count))
		if (source == "cell") fact["subscription_completeness"] = "UNKNOWN"
		insert("imported_ambient_${source}_fact", fact)
	}

	private fun summary(source: String, count: Int): Map<String, Any?> = if (source == "wifi") mapOf(
		"observation_count" to count, "two_point_four_ghz_count" to count, "five_ghz_count" to 0,
		"six_ghz_count" to 0, "other_band_count" to 0, "strongest_signal_dbm" to -60,
		"weakest_signal_dbm" to -60, "mean_signal_dbm" to -60.0)
	else mapOf("observation_count" to count, "registered_observation_count" to count,
		"gsm_count" to 0, "cdma_count" to 0, "wcdma_count" to 0, "tdscdma_count" to 0, "lte_count" to count, "nr_count" to 0,
		"quality_unknown_count" to count, "quality_none_or_unknown_count" to 0, "quality_poor_count" to 0,
		"quality_moderate_count" to 0, "quality_good_count" to 0, "quality_great_count" to 0)

	private fun insert(table: String, values: Map<String, Any?>) {
		val content = ContentValues().apply { values.forEach { (key, value) -> when (value) {
			null -> putNull(key)
			is String -> put(key, value)
			is Int -> put(key, value)
			is Long -> put(key, value)
			is Double -> put(key, value)
			else -> error("Unsupported fixture value")
		} } }
		db.openHelper.writableDatabase.insert(table, SQLiteDatabase.CONFLICT_ABORT, content)
	}

	private fun wifiSession(uncertainty: Long = 100) = WifiSessionChildV2(ENTRY, RUN, OBSERVATION, 1, 0, 1,
		1_000, uncertainty, "aa:bb", "Cafe", 2_412, -60,
		radioChildDigest("wifi", listOf(ENTRY, RUN, OBSERVATION), listOf(1L, 0, 1L, 1_000L, uncertainty, "aa:bb", "Cafe", 2_412, -60)))
	private fun cellSession(uncertainty: Long = 100) = CellSessionChildV2(ENTRY, RUN, OBSERVATION, 1, 0, 1,
		1_000, uncertainty, "tower", "LTE", true, -60,
		radioChildDigest("cell", listOf(ENTRY, RUN, OBSERVATION), listOf(1L, 0, 1L, 1_000L, uncertainty, "tower", "LTE", true, -60)))
	private fun wifiBackground(fact: String = FACT, index: Int = 0, ssid: String = "Cafe") =
		WifiBackgroundChildV2(fact, 1, index, 1, 1_000, 100, "aa:bb", ssid, 2_412, -60,
			radioChildDigest("wifi", listOf(fact), listOf(1L, index, 1L, 1_000L, 100L, "aa:bb", ssid, 2_412, -60)))
	private fun cellBackground(fact: String = FACT, index: Int = 0, tower: String = "tower") =
		CellBackgroundChildV2(fact, 1, index, 1, 1_000, 100, tower, "LTE", true, -60,
			radioChildDigest("cell", listOf(fact), listOf(1L, index, 1L, 1_000L, 100L, tower, "LTE", true, -60)))

	private companion object {
		val ENTRY = "a".repeat(64)
		val RUN = "b".repeat(64)
		val OBSERVATION = "c".repeat(64)
		val ARCHIVE = "d".repeat(64)
		val FACT = "e".repeat(64)
		val MISSING = "f".repeat(64)
		val CHECKSUM = "0".repeat(64)
	}
}
