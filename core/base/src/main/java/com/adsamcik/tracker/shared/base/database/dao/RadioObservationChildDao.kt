package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity

/** Current native Cell fact count and durable carrier version, independent of child presence. */
data class CellCurrentDetailExpectation(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "accepted_child_count") val acceptedChildCount: Int,
)

/** Current source-owned parent; export qualifies its selected identity before reading details. */
data class NativeRadioExportOwner(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int?,
	@ColumnInfo(name = "accepted_child_count") val acceptedChildCount: Int,
)

/** Purpose-owned radio details. Callers authenticate the corresponding fact before presenting rows. */
// This interface contains Room declarations only; keep this source’s SQL together.
@Suppress("LargeClass")
@Dao
interface RadioObservationChildDao {
	/**
	 * Inserts [WifiCapturedChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert
	suspend fun insertWifiSession(rows: List<WifiCapturedChildEntity>)

	/**
	 * Inserts [CellCapturedChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert
	suspend fun insertCellSession(rows: List<CellCapturedChildEntity>)

	/**
	 * Inserts [AmbientWifiChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert
	suspend fun insertWifiBackground(rows: List<AmbientWifiChildEntity>)

	/**
	 * Inserts [AmbientCellChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert
	suspend fun insertCellBackground(rows: List<AmbientCellChildEntity>)

	/**
	 * Reads `wifi_captured_child` rows. Query conditions include `writer_projection_id = :writerId`,
	 * `writer_projection_version = :writerVersion`, `logical_fact_id = :factId`, `semantic_revision =
	 * :revision`. Orders by `item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM wifi_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun wifiSessionChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<WifiCapturedChildEntity>

	/** Latest revisions only, loaded in bounded fact-ID chunks for history without N+1 reads. */
	@Query("SELECT child.* FROM wifi_captured_child AS child " +
		"INNER JOIN wifi_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = child.writer_projection_id AND " +
		"cursor.writer_projection_version = child.writer_projection_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"child.writer_projection_id = :writerId AND child.writer_projection_version = :writerVersion " +
		"AND child.logical_fact_id IN (:factIds) ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun wifiCurrentSessionChildrenByFactIds(writerId: String, writerVersion: Int,
		factIds: List<String>, limit: Int): List<WifiCapturedChildEntity>

	/**
	 * Reads `wifi_captured_child` rows. Query conditions include `cursor.logical_tracking_id =
	 * :logicalTrackingId`. Orders by `child.logical_fact_id, child.item_index`. Returns at most
	 * `limit` rows.
	 */
	@Query("SELECT child.* FROM wifi_captured_child AS child " +
		"INNER JOIN wifi_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = child.writer_projection_id AND " +
		"cursor.writer_projection_version = child.writer_projection_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"cursor.logical_tracking_id = :logicalTrackingId " +
		"ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun wifiCurrentSessionChildrenForLogicalTracking(
		logicalTrackingId: String, limit: Int,
	): List<WifiCapturedChildEntity>

	/** Parent version remains available when mandatory details are missing. */
	@Query("SELECT fact.logical_fact_id, fact.semantic_revision, fact.service_run_id, " +
		"fact.payload_version, fact.accepted_result_count AS accepted_child_count " +
		"FROM wifi_captured_fact_revision AS fact INNER JOIN wifi_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = fact.writer_projection_id AND " +
		"cursor.writer_projection_version = fact.writer_projection_version AND " +
		"cursor.logical_fact_id = fact.logical_fact_id AND cursor.latest_semantic_revision = fact.semantic_revision " +
		"AND cursor.logical_tracking_id = fact.logical_tracking_id AND cursor.service_run_id = fact.service_run_id " +
		"WHERE fact.writer_projection_id = :writerId AND fact.writer_projection_version = :writerVersion " +
		"AND fact.logical_tracking_id = :logicalTrackingId AND fact.purpose = 'SESSION_CAPTURE' " +
		"ORDER BY fact.logical_fact_id LIMIT :limit")
	suspend fun wifiCurrentExportOwners(logicalTrackingId: String, writerId: String,
		writerVersion: Int, limit: Int): List<NativeRadioExportOwner>

	/** Exact current parent owners only, so excluded readings cannot crowd the export limit. */
	@Query("SELECT child.* FROM wifi_captured_child AS child " +
		"INNER JOIN wifi_captured_fact_revision AS fact ON " +
		"fact.writer_projection_id = child.writer_projection_id AND " +
		"fact.writer_projection_version = child.writer_projection_version AND " +
		"fact.logical_fact_id = child.logical_fact_id AND fact.semantic_revision = child.semantic_revision " +
		"INNER JOIN wifi_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = fact.writer_projection_id AND " +
		"cursor.writer_projection_version = fact.writer_projection_version AND " +
		"cursor.logical_fact_id = fact.logical_fact_id AND cursor.latest_semantic_revision = fact.semantic_revision " +
		"AND cursor.logical_tracking_id = fact.logical_tracking_id AND cursor.service_run_id = fact.service_run_id " +
		"WHERE child.writer_projection_id = :writerId AND child.writer_projection_version = :writerVersion " +
		"AND child.logical_fact_id IN (:factIds) AND child.semantic_revision = :revision " +
		"AND fact.logical_tracking_id = :logicalTrackingId AND fact.service_run_id = :serviceRunId " +
		"AND fact.purpose = 'SESSION_CAPTURE' ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	@Suppress("LongParameterList")
	suspend fun wifiCurrentSessionChildrenForOwners(logicalTrackingId: String, serviceRunId: String,
		writerId: String, writerVersion: Int, revision: Long, factIds: List<String>,
		limit: Int): List<WifiCapturedChildEntity>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `writer_projection_id = :writerId`,
	 * `writer_projection_version = :writerVersion`, `logical_fact_id = :factId`, `semantic_revision =
	 * :revision`. Orders by `item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM cell_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun cellSessionChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<CellCapturedChildEntity>

	/** Latest Cell revisions only, bounded by fact IDs and child count. */
	@Query("SELECT child.* FROM cell_captured_child AS child " +
		"INNER JOIN cell_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = child.writer_projection_id AND " +
		"cursor.writer_projection_version = child.writer_projection_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"child.writer_projection_id = :writerId AND child.writer_projection_version = :writerVersion " +
		"AND child.logical_fact_id IN (:factIds) ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentSessionChildrenByFactIds(writerId: String, writerVersion: Int,
		factIds: List<String>, limit: Int): List<CellCapturedChildEntity>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `fact.logical_tracking_id =
	 * :logicalTrackingId`. Orders by `fact.source_admission_ordinal, child.logical_fact_id,
	 * child.item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT child.* FROM cell_captured_child AS child " +
		"INNER JOIN cell_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = child.writer_projection_id AND " +
		"cursor.writer_projection_version = child.writer_projection_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision " +
		"INNER JOIN cell_captured_fact_revision AS fact ON " +
		"fact.writer_projection_id = child.writer_projection_id AND " +
		"fact.writer_projection_version = child.writer_projection_version AND " +
		"fact.logical_fact_id = child.logical_fact_id AND " +
		"fact.semantic_revision = child.semantic_revision " +
		"WHERE fact.logical_tracking_id = :logicalTrackingId " +
		"ORDER BY fact.source_admission_ordinal, child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentSessionChildrenForLogicalTracking(logicalTrackingId: String,
		limit: Int): List<CellCapturedChildEntity>

	/** Includes facts with zero saved children, so a v6 detail loss cannot look like a v5 summary. */
	@Query("SELECT fact.logical_fact_id, fact.semantic_revision, fact.payload_version, " +
		"fact.accepted_child_count FROM cell_captured_fact_revision AS fact " +
		"INNER JOIN cell_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = fact.writer_projection_id AND " +
		"cursor.writer_projection_version = fact.writer_projection_version AND " +
		"cursor.logical_fact_id = fact.logical_fact_id AND " +
		"cursor.latest_semantic_revision = fact.semantic_revision " +
		"WHERE cursor.logical_tracking_id = :logicalTrackingId " +
		"ORDER BY fact.logical_fact_id LIMIT :limit")
	suspend fun cellCurrentDetailExpectationsForLogicalTracking(
		logicalTrackingId: String, limit: Int,
	): List<CellCurrentDetailExpectation>

	/**
	 * Reads `cell_captured_fact_revision` rows. Query conditions include `fact.writer_projection_id =
	 * :writerId`, `fact.writer_projection_version = :writerVersion`, `fact.logical_tracking_id =
	 * :logicalTrackingId`, `fact.purpose = 'SESSION_CAPTURE'`. Orders by `fact.logical_fact_id`.
	 * Returns at most `limit` rows.
	 */
	@Query("SELECT fact.logical_fact_id, fact.semantic_revision, fact.service_run_id, " +
		"fact.payload_version, fact.accepted_child_count " +
		"FROM cell_captured_fact_revision AS fact INNER JOIN cell_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = fact.writer_projection_id AND " +
		"cursor.writer_projection_version = fact.writer_projection_version AND " +
		"cursor.logical_fact_id = fact.logical_fact_id AND cursor.latest_semantic_revision = fact.semantic_revision " +
		"AND cursor.logical_tracking_id = fact.logical_tracking_id AND cursor.service_run_id = fact.service_run_id " +
		"WHERE fact.writer_projection_id = :writerId AND fact.writer_projection_version = :writerVersion " +
		"AND fact.logical_tracking_id = :logicalTrackingId AND fact.purpose = 'SESSION_CAPTURE' " +
		"ORDER BY fact.logical_fact_id LIMIT :limit")
	suspend fun cellCurrentExportOwners(logicalTrackingId: String, writerId: String,
		writerVersion: Int, limit: Int): List<NativeRadioExportOwner>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `child.writer_projection_id =
	 * :writerId`, `child.writer_projection_version = :writerVersion`, `child.logical_fact_id IN
	 * (:factIds)`, `child.semantic_revision = :revision`. Orders by `child.logical_fact_id,
	 * child.item_index`. Returns at most `limitLongParameterList` rows.
	 */
	@Query("SELECT child.* FROM cell_captured_child AS child " +
		"INNER JOIN cell_captured_fact_revision AS fact ON " +
		"fact.writer_projection_id = child.writer_projection_id AND " +
		"fact.writer_projection_version = child.writer_projection_version AND " +
		"fact.logical_fact_id = child.logical_fact_id AND fact.semantic_revision = child.semantic_revision " +
		"INNER JOIN cell_captured_fact_cursor AS cursor ON " +
		"cursor.writer_projection_id = fact.writer_projection_id AND " +
		"cursor.writer_projection_version = fact.writer_projection_version AND " +
		"cursor.logical_fact_id = fact.logical_fact_id AND cursor.latest_semantic_revision = fact.semantic_revision " +
		"AND cursor.logical_tracking_id = fact.logical_tracking_id AND cursor.service_run_id = fact.service_run_id " +
		"WHERE child.writer_projection_id = :writerId AND child.writer_projection_version = :writerVersion " +
		"AND child.logical_fact_id IN (:factIds) AND child.semantic_revision = :revision " +
		"AND fact.logical_tracking_id = :logicalTrackingId AND fact.service_run_id = :serviceRunId " +
		"AND fact.purpose = 'SESSION_CAPTURE' ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	@Suppress("LongParameterList")
	suspend fun cellCurrentSessionChildrenForOwners(logicalTrackingId: String, serviceRunId: String,
		writerId: String, writerVersion: Int, revision: Long, factIds: List<String>,
		limit: Int): List<CellCapturedChildEntity>

	/**
	 * Reads `ambient_wifi_child` rows. Query conditions include `writer_id = :writerId`,
	 * `writer_version = :writerVersion`, `logical_fact_id = :factId`, `semantic_revision =
	 * :revision`. Orders by `item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun wifiBackgroundChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<AmbientWifiChildEntity>

	/**
	 * Reads `ambient_wifi_child` rows. Query conditions include `child.logical_fact_id IN
	 * (:factIds)`. Orders by `child.logical_fact_id, child.item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT child.* FROM ambient_wifi_child AS child " +
		"INNER JOIN ambient_wifi_fact_cursor AS cursor ON " +
		"cursor.writer_id = child.writer_id AND cursor.writer_version = child.writer_version " +
		"AND cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"child.logical_fact_id IN (:factIds) " +
		"ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun wifiCurrentBackgroundChildrenForFactIds(
		factIds: List<String>, limit: Int,
	): List<AmbientWifiChildEntity>

	/** Every retained correction revision, not only the latest background cursor. */
	@Query("SELECT * FROM ambient_wifi_child WHERE logical_fact_id IN (:factIds) " +
		"ORDER BY logical_fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<AmbientWifiChildEntity>

	/** A selected portable correction must never be crowded out by older revisions. */
	@Query("SELECT * FROM ambient_wifi_child WHERE logical_fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY writer_id, writer_version, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<AmbientWifiChildEntity>

	/**
	 * Reads `ambient_cell_child` rows. Query conditions include `writer_id = :writerId`,
	 * `writer_version = :writerVersion`, `logical_fact_id = :factId`, `semantic_revision =
	 * :revision`. Orders by `item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun cellBackgroundChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<AmbientCellChildEntity>

	/**
	 * Reads `ambient_cell_child` rows. Query conditions include `child.logical_fact_id IN
	 * (:factIds)`. Orders by `child.logical_fact_id, child.item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT child.* FROM ambient_cell_child AS child " +
		"INNER JOIN ambient_cell_fact_cursor AS cursor ON " +
		"cursor.writer_id = child.writer_id AND cursor.writer_version = child.writer_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"child.logical_fact_id IN (:factIds) " +
		"ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<AmbientCellChildEntity>

	/**
	 * Reads `ambient_cell_child` rows. Query conditions include `logical_fact_id IN (:factIds)`.
	 * Orders by `logical_fact_id, semantic_revision, item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_child WHERE logical_fact_id IN (:factIds) " +
		"ORDER BY logical_fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<AmbientCellChildEntity>

	/**
	 * Reads `ambient_cell_child` rows. Query conditions include `logical_fact_id = :factId`,
	 * `semantic_revision = :revision`. Orders by `writer_id, writer_version, item_index`. Returns at
	 * most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_child WHERE logical_fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY writer_id, writer_version, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<AmbientCellChildEntity>

	/**
	 * Reads `wifi_captured_child` rows. Query conditions include `observed_wall_time_ms >= :fromMs`,
	 * `observed_wall_time_ms < :toMs`. Orders by `observed_wall_time_ms, logical_fact_id,
	 * item_index`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("SELECT * FROM wifi_captured_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun wifiSessionInRange(fromMs: Long, toMs: Long, limit: Int): List<WifiCapturedChildEntity>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `observed_wall_time_ms >= :fromMs`,
	 * `observed_wall_time_ms < :toMs`. Orders by `observed_wall_time_ms, logical_fact_id,
	 * item_index`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("SELECT * FROM cell_captured_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun cellSessionInRange(fromMs: Long, toMs: Long, limit: Int): List<CellCapturedChildEntity>

	/**
	 * Reads `ambient_wifi_child` rows. Query conditions include `observed_wall_time_ms >= :fromMs`,
	 * `observed_wall_time_ms < :toMs`. Orders by `observed_wall_time_ms, logical_fact_id,
	 * item_index`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("SELECT * FROM ambient_wifi_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun wifiBackgroundInRange(fromMs: Long, toMs: Long, limit: Int): List<AmbientWifiChildEntity>

	/**
	 * Reads `ambient_cell_child` rows. Query conditions include `observed_wall_time_ms >= :fromMs`,
	 * `observed_wall_time_ms < :toMs`. Orders by `observed_wall_time_ms, logical_fact_id,
	 * item_index`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("SELECT * FROM ambient_cell_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun cellBackgroundInRange(fromMs: Long, toMs: Long, limit: Int): List<AmbientCellChildEntity>

	/**
	 * Deletes `wifi_captured_child` rows matching the supplied scope. Query conditions include
	 * `writer_projection_id = :writerId`, `writer_projection_version = :writerVersion`,
	 * `logical_fact_id = :factId`. Returns the affected row count.
	 */
	@Query("DELETE FROM wifi_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteWifiSessionFact(writerId: String, writerVersion: Int, factId: String): Int

	/**
	 * Deletes `wifi_captured_child` rows matching the supplied scope. Query conditions include
	 * `writer_projection_id = :writerId`, `writer_projection_version = :writerVersion`,
	 * `logical_fact_id = :factId`, `semantic_revision = :revision`. Returns the affected row count.
	 */
	@Query("DELETE FROM wifi_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteWifiSessionRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	/**
	 * Deletes `cell_captured_child` rows matching the supplied scope. Query conditions include
	 * `writer_projection_id = :writerId`, `writer_projection_version = :writerVersion`,
	 * `logical_fact_id = :factId`. Returns the affected row count.
	 */
	@Query("DELETE FROM cell_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteCellSessionFact(writerId: String, writerVersion: Int, factId: String): Int

	/**
	 * Deletes `cell_captured_child` rows matching the supplied scope. Query conditions include
	 * `writer_projection_id = :writerId`, `writer_projection_version = :writerVersion`,
	 * `logical_fact_id = :factId`, `semantic_revision = :revision`. Returns the affected row count.
	 */
	@Query("DELETE FROM cell_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteCellSessionRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	/**
	 * Deletes `ambient_wifi_child` rows matching the supplied scope. Query conditions include
	 * `writer_id = :writerId`, `writer_version = :writerVersion`, `logical_fact_id = :factId`.
	 * Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteWifiBackgroundFact(writerId: String, writerVersion: Int, factId: String): Int

	/**
	 * Deletes `ambient_wifi_child` rows matching the supplied scope. Query conditions include
	 * `writer_id = :writerId`, `writer_version = :writerVersion`, `logical_fact_id = :factId`,
	 * `semantic_revision = :revision`. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteWifiBackgroundRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	/**
	 * Deletes `ambient_cell_child` rows matching the supplied scope. Query conditions include
	 * `writer_id = :writerId`, `writer_version = :writerVersion`, `logical_fact_id = :factId`.
	 * Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteCellBackgroundFact(writerId: String, writerVersion: Int, factId: String): Int

	/**
	 * Deletes `ambient_cell_child` rows matching the supplied scope. Query conditions include
	 * `writer_id = :writerId`, `writer_version = :writerVersion`, `logical_fact_id = :factId`,
	 * `semantic_revision = :revision`. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteCellBackgroundRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	/**
	 * Deletes `wifi_captured_child` rows.
	 */
	@Query("DELETE FROM wifi_captured_child")
	suspend fun deleteAllWifiSession()

	/**
	 * Deletes `cell_captured_child` rows.
	 */
	@Query("DELETE FROM cell_captured_child")
	suspend fun deleteAllCellSession()

	/**
	 * Deletes `ambient_wifi_child` rows.
	 */
	@Query("DELETE FROM ambient_wifi_child")
	suspend fun deleteAllWifiBackground()

	/**
	 * Deletes `ambient_cell_child` rows.
	 */
	@Query("DELETE FROM ambient_cell_child")
	suspend fun deleteAllCellBackground()

	/**
	 * Reads `legacy_wifi_child` rows. Query conditions include `time_ms >= :fromMs`, `time_ms <
	 * :toMs`. Orders by `time_ms, legacy_row_id`. Returns at most `limit` rows. Timestamp bounds use
	 * UTC milliseconds.
	 */
	@Query("SELECT * FROM legacy_wifi_child WHERE time_ms >= :fromMs AND time_ms < :toMs " +
		"ORDER BY time_ms, legacy_row_id LIMIT :limit")
	suspend fun legacyWifiInRange(fromMs: Long, toMs: Long, limit: Int): List<LegacyWifiChildEntity>

	/**
	 * Reads `legacy_cell_child` rows. Query conditions include `time_ms >= :fromMs`, `time_ms <
	 * :toMs`. Orders by `time_ms, legacy_row_id`. Returns at most `limit` rows. Timestamp bounds use
	 * UTC milliseconds.
	 */
	@Query("SELECT * FROM legacy_cell_child WHERE time_ms >= :fromMs AND time_ms < :toMs " +
		"ORDER BY time_ms, legacy_row_id LIMIT :limit")
	suspend fun legacyCellInRange(fromMs: Long, toMs: Long, limit: Int): List<LegacyCellChildEntity>

	/**
	 * Deletes `legacy_wifi_child` rows.
	 */
	@Query("DELETE FROM legacy_wifi_child")
	suspend fun deleteAllLegacyWifi()

	/**
	 * Deletes `legacy_cell_child` rows.
	 */
	@Query("DELETE FROM legacy_cell_child")
	suspend fun deleteAllLegacyCell()

	/** Key-only measured discovery; exact source qualification remains in the same caller snapshot. */
	@Query("""
		WITH b0_raw AS (
		SELECT 'captured_wifi' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.writer_projection_id AS TEXT)) || ':' || CAST(c.writer_projection_id AS TEXT) || ':' || length(CAST(c.writer_projection_version AS TEXT)) || ':' || CAST(c.writer_projection_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_projection_id AS writerId, c.writer_projection_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.bssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.bssid AS BLOB)) END || ':' || CASE WHEN c.ssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.ssid AS BLOB)) END || ':' || CASE WHEN c.frequency_mhz IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.frequency_mhz AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM wifi_captured_child c JOIN wifi_captured_fact_cursor k ON
		  k.writer_projection_id = c.writer_projection_id AND k.writer_projection_version = c.writer_projection_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN wifi_captured_fact_revision f ON f.writer_projection_id = c.writer_projection_id AND f.writer_projection_version = c.writer_projection_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		),
		b0_eligible AS (
		SELECT * FROM b0_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b0_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b0_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b0_chosen AS (
		SELECT e.* FROM b0_eligible e JOIN b0_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b1_raw AS (
		SELECT 'captured_wifi_summary' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(f.writer_projection_id AS TEXT)) || ':' || CAST(f.writer_projection_id AS TEXT) || ':' || length(CAST(f.writer_projection_version AS TEXT)) || ':' || CAST(f.writer_projection_version AS TEXT) || ':' || length(CAST(f.logical_fact_id AS TEXT)) || ':' || CAST(f.logical_fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, f.observed_wall_time_ms - (f.coverage_interval_end_nanos - f.coverage_interval_start_nanos) / 1000000 AS startTimeMs, f.observed_wall_time_ms AS endTimeMs,
		  NULL AS rowId, f.writer_projection_id AS writerId, f.writer_projection_version AS writerVersion,
		  f.logical_fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM wifi_captured_fact_revision f JOIN wifi_captured_fact_cursor k ON
		  k.writer_projection_id = f.writer_projection_id AND k.writer_projection_version = f.writer_projection_version AND k.logical_fact_id = f.logical_fact_id
		  AND k.latest_semantic_revision = f.semantic_revision
		WHERE f.fact_kind IN ('AGGREGATE', 'COVERAGE_ONLY') AND NOT EXISTS (SELECT 1 FROM wifi_captured_child c WHERE c.writer_projection_id = f.writer_projection_id
		  AND c.writer_projection_version = f.writer_projection_version AND c.logical_fact_id = f.logical_fact_id
		  AND c.semantic_revision = f.semantic_revision)
		),
		b1_eligible AS (
		SELECT * FROM b1_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b1_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b1_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b1_chosen AS (
		SELECT e.* FROM b1_eligible e JOIN b1_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b2_raw AS (
		SELECT 'ambient_wifi' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.writer_id AS TEXT)) || ':' || CAST(c.writer_id AS TEXT) || ':' || length(CAST(c.writer_version AS TEXT)) || ':' || CAST(c.writer_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_id AS writerId, c.writer_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.bssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.bssid AS BLOB)) END || ':' || CASE WHEN c.ssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.ssid AS BLOB)) END || ':' || CASE WHEN c.frequency_mhz IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.frequency_mhz AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM ambient_wifi_child c JOIN ambient_wifi_fact_cursor k ON
		  k.writer_id = c.writer_id AND k.writer_version = c.writer_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN ambient_wifi_fact_revision f ON f.writer_id = c.writer_id AND f.writer_version = c.writer_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		),
		b2_eligible AS (
		SELECT * FROM b2_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b2_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b2_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b2_chosen AS (
		SELECT e.* FROM b2_eligible e JOIN b2_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b3_raw AS (
		SELECT 'ambient_wifi_summary' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(f.writer_id AS TEXT)) || ':' || CAST(f.writer_id AS TEXT) || ':' || length(CAST(f.writer_version AS TEXT)) || ':' || CAST(f.writer_version AS TEXT) || ':' || length(CAST(f.logical_fact_id AS TEXT)) || ':' || CAST(f.logical_fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, min(f.coverage_start_time_ms, f.observed_wall_time_ms - ((f.observed_elapsed_nanos - f.observed_interval_start_nanos) / 1000000 + CASE WHEN (f.observed_elapsed_nanos - f.observed_interval_start_nanos) % 1000000 != 0 THEN 1 ELSE 0 END)) - f.wall_time_uncertainty_ms AS startTimeMs, f.observed_wall_time_ms AS endTimeMs,
		  NULL AS rowId, f.writer_id AS writerId, f.writer_version AS writerVersion,
		  f.logical_fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM ambient_wifi_fact_revision f JOIN ambient_wifi_fact_cursor k ON
		  k.writer_id = f.writer_id AND k.writer_version = f.writer_version AND k.logical_fact_id = f.logical_fact_id
		  AND k.latest_semantic_revision = f.semantic_revision
		WHERE f.fact_kind IN ('AGGREGATE', 'COVERAGE_ONLY') AND NOT EXISTS (SELECT 1 FROM ambient_wifi_child c WHERE c.writer_id = f.writer_id
		  AND c.writer_version = f.writer_version AND c.logical_fact_id = f.logical_fact_id
		  AND c.semantic_revision = f.semantic_revision)
		),
		b3_eligible AS (
		SELECT * FROM b3_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b3_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b3_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b3_chosen AS (
		SELECT e.* FROM b3_eligible e JOIN b3_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b4_raw AS (
		SELECT 'imported_wifi' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.entry_identity AS TEXT)) || ':' || CAST(c.entry_identity AS TEXT) || ':' || length(CAST(c.entry_import_revision AS TEXT)) || ':' || CAST(c.entry_import_revision AS TEXT) || ':' || length(CAST(c.run_identity AS TEXT)) || ':' || CAST(c.run_identity AS TEXT) || ':' || length(CAST(c.observation_identity AS TEXT)) || ':' || CAST(c.observation_identity AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.entry_identity AS writerId, NULL AS writerVersion,
		  c.observation_identity AS factId, c.entry_import_revision AS factRevision, c.item_index AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_wifi_child c JOIN imported_wifi_entry_revision e ON e.identity = c.entry_identity
		  AND e.import_revision = c.entry_import_revision
		WHERE e.import_revision = (SELECT MAX(n.import_revision) FROM imported_wifi_entry_revision n
		  WHERE n.identity = e.identity)
		),
		b4_eligible AS (
		SELECT * FROM b4_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b4_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b4_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b4_chosen AS (
		SELECT e.* FROM b4_eligible e JOIN b4_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b5_raw AS (
		SELECT 'imported_wifi_summary' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(f.entry_identity AS TEXT)) || ':' || CAST(f.entry_identity AS TEXT) || ':' || length(CAST(f.entry_import_revision AS TEXT)) || ':' || CAST(f.entry_import_revision AS TEXT) || ':' || length(CAST(f.run_identity AS TEXT)) || ':' || CAST(f.run_identity AS TEXT) || ':' || length(CAST(f.identity AS TEXT)) || ':' || CAST(f.identity AS TEXT) AS identity, f.coverage_start_time_ms AS startTimeMs, f.observed_time_ms AS endTimeMs,
		  NULL AS rowId, f.entry_identity AS writerId, NULL AS writerVersion,
		  f.identity AS factId, f.entry_import_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_wifi_observation f JOIN imported_wifi_entry_revision e ON
		  e.identity = f.entry_identity AND e.import_revision = f.entry_import_revision
		WHERE e.import_revision = (SELECT MAX(n.import_revision) FROM imported_wifi_entry_revision n
		  WHERE n.identity = e.identity)
		AND NOT EXISTS (SELECT 1 FROM imported_wifi_child c WHERE c.entry_identity = f.entry_identity
		  AND c.entry_import_revision = f.entry_import_revision AND c.run_identity = f.run_identity
		  AND c.observation_identity = f.identity)
		),
		b5_eligible AS (
		SELECT * FROM b5_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b5_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b5_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b5_chosen AS (
		SELECT e.* FROM b5_eligible e JOIN b5_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b6_raw AS (
		SELECT 'imported_ambient_wifi' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.archive_id AS TEXT)) || ':' || CAST(c.archive_id AS TEXT) || ':' || length(CAST(c.fact_id AS TEXT)) || ':' || CAST(c.fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.archive_id AS writerId, NULL AS writerVersion,
		  c.fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_wifi_child c JOIN imported_ambient_wifi_fact f ON f.archive_id = c.archive_id
		  AND f.fact_id = c.fact_id AND f.semantic_revision = c.semantic_revision
		WHERE f.semantic_revision = (SELECT MAX(n.semantic_revision) FROM imported_ambient_wifi_fact n
		  WHERE n.fact_id = f.fact_id)
		),
		b6_eligible AS (
		SELECT * FROM b6_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b6_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b6_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b6_chosen AS (
		SELECT e.* FROM b6_eligible e JOIN b6_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b7_raw AS (
		SELECT 'imported_ambient_wifi_summary' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(f.archive_id AS TEXT)) || ':' || CAST(f.archive_id AS TEXT) || ':' || length(CAST(f.fact_id AS TEXT)) || ':' || CAST(f.fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, f.coverage_start_time_ms AS startTimeMs, f.observed_time_ms AS endTimeMs,
		  NULL AS rowId, f.archive_id AS writerId, NULL AS writerVersion,
		  f.fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_wifi_fact f WHERE f.semantic_revision =
		  (SELECT MAX(n.semantic_revision) FROM imported_ambient_wifi_fact n WHERE n.fact_id = f.fact_id)
		AND NOT EXISTS (SELECT 1 FROM imported_ambient_wifi_child c WHERE c.archive_id = f.archive_id
		  AND c.fact_id = f.fact_id AND c.semantic_revision = f.semantic_revision)
		),
		b7_eligible AS (
		SELECT * FROM b7_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b7_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b7_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b7_chosen AS (
		SELECT e.* FROM b7_eligible e JOIN b7_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b8_raw AS (
		SELECT 'ambient_wifi_gap' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(g.gap_id AS TEXT)) || ':' || CAST(g.gap_id AS TEXT) AS identity, g.gap_start_time_ms AS startTimeMs, g.gap_end_time_ms AS endTimeMs,
		  NULL AS rowId, NULL AS writerId, NULL AS writerVersion,
		  g.gap_id AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM ambient_wifi_gap g
		),
		b8_eligible AS (
		SELECT * FROM b8_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b8_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b8_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b8_chosen AS (
		SELECT e.* FROM b8_eligible e JOIN b8_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b9_raw AS (
		SELECT 'imported_ambient_wifi_gap' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(g.archive_id AS TEXT)) || ':' || CAST(g.archive_id AS TEXT) || ':' || length(CAST(g.gap_id AS TEXT)) || ':' || CAST(g.gap_id AS TEXT) AS identity, g.start_time_ms AS startTimeMs, g.end_time_ms AS endTimeMs,
		  NULL AS rowId, g.archive_id AS writerId, NULL AS writerVersion,
		  g.gap_id AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_wifi_gap g
		),
		b9_eligible AS (
		SELECT * FROM b9_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b9_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b9_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b9_chosen AS (
		SELECT e.* FROM b9_eligible e JOIN b9_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b10_raw AS (
		SELECT 'legacy_wifi' AS kind, 'LEGACY' AS origin, 'UNKNOWN' AS purpose,
		  length(CAST(l.legacy_row_id AS TEXT)) || ':' || CAST(l.legacy_row_id AS TEXT) AS identity, l.time_ms AS startTimeMs, NULL AS endTimeMs,
		  l.legacy_row_id AS rowId, NULL AS writerId, NULL AS writerVersion,
		  NULL AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM legacy_wifi_child l
		),
		b10_eligible AS (
		SELECT * FROM b10_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b10_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b10_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b10_chosen AS (
		SELECT e.* FROM b10_eligible e JOIN b10_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		eligible AS (
		SELECT * FROM b0_chosen
		UNION ALL
		SELECT * FROM b1_chosen
		UNION ALL
		SELECT * FROM b2_chosen
		UNION ALL
		SELECT * FROM b3_chosen
		UNION ALL
		SELECT * FROM b4_chosen
		UNION ALL
		SELECT * FROM b5_chosen
		UNION ALL
		SELECT * FROM b6_chosen
		UNION ALL
		SELECT * FROM b7_chosen
		UNION ALL
		SELECT * FROM b8_chosen
		UNION ALL
		SELECT * FROM b9_chosen
		UNION ALL
		SELECT * FROM b10_chosen
		),
		heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(CASE purpose WHEN 'CAPTURE' THEN '0' ELSE '1' END || ':' || kind || ':' || identity) AS winner
		FROM eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END, coalesce(physicalIdentity, identity)
		),
		chosen AS (
		SELECT e.* FROM eligible e JOIN heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_wifi' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND CASE e.purpose WHEN 'CAPTURE' THEN '0' ELSE '1' END || ':' || e.kind || ':' || e.identity = h.winner
		)
		SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId, writerId, writerVersion,
		  factId, factRevision, itemIndex, physicalEventId, physicalIdentity FROM chosen
		ORDER BY startTimeMs DESC, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_wifi' END ASC, coalesce(physicalIdentity, identity) ASC LIMIT :limit
	""")
	suspend fun wifiMeasuredHistoryCandidates(fromMs: Long, toMs: Long, origins: List<String>,
		purposes: List<String>, afterTime: Long?, afterTag: String?, afterIdentity: String?,
		limit: Int): List<MeasuredHistoryCandidate>

	/**
	 * Reads `wifi_captured_child` rows. Query conditions include `c.observed_wall_time_ms IN
	 * (:observedTimes)`, `physicalIdentity IN (:identities)`, `purpose IN (:purposes)`, `kind >
	 * :afterKind`. Orders by `kind, identity`. Returns at most `limit` rows.
	 */
	@Query("""
		WITH candidates AS (
		SELECT 'captured_wifi' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.writer_projection_id AS TEXT)) || ':' || CAST(c.writer_projection_id AS TEXT) || ':' || length(CAST(c.writer_projection_version AS TEXT)) || ':' || CAST(c.writer_projection_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_projection_id AS writerId, c.writer_projection_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.bssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.bssid AS BLOB)) END || ':' || CASE WHEN c.ssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.ssid AS BLOB)) END || ':' || CASE WHEN c.frequency_mhz IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.frequency_mhz AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM wifi_captured_child c JOIN wifi_captured_fact_cursor k ON
		  k.writer_projection_id = c.writer_projection_id AND k.writer_projection_version = c.writer_projection_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN wifi_captured_fact_revision f ON f.writer_projection_id = c.writer_projection_id AND f.writer_projection_version = c.writer_projection_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		WHERE c.observed_wall_time_ms IN (:observedTimes)
		UNION ALL
		SELECT 'ambient_wifi' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.writer_id AS TEXT)) || ':' || CAST(c.writer_id AS TEXT) || ':' || length(CAST(c.writer_version AS TEXT)) || ':' || CAST(c.writer_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_id AS writerId, c.writer_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.bssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.bssid AS BLOB)) END || ':' || CASE WHEN c.ssid IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.ssid AS BLOB)) END || ':' || CASE WHEN c.frequency_mhz IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.frequency_mhz AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM ambient_wifi_child c JOIN ambient_wifi_fact_cursor k ON
		  k.writer_id = c.writer_id AND k.writer_version = c.writer_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN ambient_wifi_fact_revision f ON f.writer_id = c.writer_id AND f.writer_version = c.writer_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		WHERE c.observed_wall_time_ms IN (:observedTimes)
		)
		SELECT * FROM candidates
		WHERE physicalIdentity IN (:identities) AND purpose IN (:purposes)
		  AND (:afterKind IS NULL OR kind > :afterKind OR (kind = :afterKind AND identity > :afterIdentity))
		ORDER BY kind, identity LIMIT :limit
	""")
	suspend fun wifiNativeMeasuredPurposeCandidates(identities: List<String>, purposes: List<String>,
        observedTimes: List<Long>,
		afterKind: String?, afterIdentity: String?, limit: Int): List<MeasuredHistoryCandidate>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `startTimeMs >= 0`, `startTimeMs <
	 * :toMs`, `startTimeMs >= :fromMs`, `endTimeMs > :fromMs`. Orders by `startTimeMs DESC, lower ||
	 * || CASE WHEN physicalIdentity IS NULL THEN kind ELSE END ASC, coalesce ASC`. Returns at most
	 * `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("""
		WITH b0_raw AS (
		SELECT 'captured_cell' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.writer_projection_id AS TEXT)) || ':' || CAST(c.writer_projection_id AS TEXT) || ':' || length(CAST(c.writer_projection_version AS TEXT)) || ':' || CAST(c.writer_projection_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_projection_id AS writerId, c.writer_projection_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.tower_identity IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.tower_identity AS BLOB)) END || ':' || CASE WHEN c.radio_type IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.radio_type AS BLOB)) END || ':' || CASE WHEN c.registered IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.registered AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM cell_captured_child c JOIN cell_captured_fact_cursor k ON
		  k.writer_projection_id = c.writer_projection_id AND k.writer_projection_version = c.writer_projection_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN cell_captured_fact_revision f ON f.writer_projection_id = c.writer_projection_id AND f.writer_projection_version = c.writer_projection_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		),
		b0_eligible AS (
		SELECT * FROM b0_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b0_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b0_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b0_chosen AS (
		SELECT e.* FROM b0_eligible e JOIN b0_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b1_raw AS (
		SELECT 'captured_cell_summary' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(f.writer_projection_id AS TEXT)) || ':' || CAST(f.writer_projection_id AS TEXT) || ':' || length(CAST(f.writer_projection_version AS TEXT)) || ':' || CAST(f.writer_projection_version AS TEXT) || ':' || length(CAST(f.logical_fact_id AS TEXT)) || ':' || CAST(f.logical_fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, w.wall_time_ms - ((w.observed_elapsed_nanos - f.coverage_interval_start_nanos) / 1000000 + CASE WHEN (w.observed_elapsed_nanos - f.coverage_interval_start_nanos) % 1000000 != 0 THEN 1 ELSE 0 END) AS startTimeMs, f.observed_wall_time_ms AS endTimeMs,
		  NULL AS rowId, f.writer_projection_id AS writerId, f.writer_projection_version AS writerVersion,
		  f.logical_fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM cell_captured_fact_revision f JOIN cell_captured_fact_cursor k ON
		  k.writer_projection_id = f.writer_projection_id AND k.writer_projection_version = f.writer_projection_version AND k.logical_fact_id = f.logical_fact_id
		  AND k.latest_semantic_revision = f.semantic_revision
		JOIN source_event_wal w ON w.event_id = f.source_event_id
		  AND w.admission_ordinal = f.source_admission_ordinal
		WHERE f.fact_kind IN ('AGGREGATE', 'COVERAGE_ONLY') AND NOT EXISTS (SELECT 1 FROM cell_captured_child c WHERE c.writer_projection_id = f.writer_projection_id
		  AND c.writer_projection_version = f.writer_projection_version AND c.logical_fact_id = f.logical_fact_id
		  AND c.semantic_revision = f.semantic_revision)
		),
		b1_eligible AS (
		SELECT * FROM b1_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b1_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b1_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b1_chosen AS (
		SELECT e.* FROM b1_eligible e JOIN b1_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b2_raw AS (
		SELECT 'ambient_cell' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.writer_id AS TEXT)) || ':' || CAST(c.writer_id AS TEXT) || ':' || length(CAST(c.writer_version AS TEXT)) || ':' || CAST(c.writer_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_id AS writerId, c.writer_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.tower_identity IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.tower_identity AS BLOB)) END || ':' || CASE WHEN c.radio_type IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.radio_type AS BLOB)) END || ':' || CASE WHEN c.registered IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.registered AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM ambient_cell_child c JOIN ambient_cell_fact_cursor k ON
		  k.writer_id = c.writer_id AND k.writer_version = c.writer_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN ambient_cell_fact_revision f ON f.writer_id = c.writer_id AND f.writer_version = c.writer_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		),
		b2_eligible AS (
		SELECT * FROM b2_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b2_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b2_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b2_chosen AS (
		SELECT e.* FROM b2_eligible e JOIN b2_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b3_raw AS (
		SELECT 'ambient_cell_summary' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(f.writer_id AS TEXT)) || ':' || CAST(f.writer_id AS TEXT) || ':' || length(CAST(f.writer_version AS TEXT)) || ':' || CAST(f.writer_version AS TEXT) || ':' || length(CAST(f.logical_fact_id AS TEXT)) || ':' || CAST(f.logical_fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, min(f.coverage_start_time_ms, f.observed_wall_time_ms - ((f.observed_elapsed_nanos - f.observed_interval_start_nanos) / 1000000 + CASE WHEN (f.observed_elapsed_nanos - f.observed_interval_start_nanos) % 1000000 != 0 THEN 1 ELSE 0 END)) - f.wall_time_uncertainty_ms AS startTimeMs, f.observed_wall_time_ms AS endTimeMs,
		  NULL AS rowId, f.writer_id AS writerId, f.writer_version AS writerVersion,
		  f.logical_fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM ambient_cell_fact_revision f JOIN ambient_cell_fact_cursor k ON
		  k.writer_id = f.writer_id AND k.writer_version = f.writer_version AND k.logical_fact_id = f.logical_fact_id
		  AND k.latest_semantic_revision = f.semantic_revision
		WHERE f.fact_kind IN ('AGGREGATE', 'COVERAGE_ONLY') AND NOT EXISTS (SELECT 1 FROM ambient_cell_child c WHERE c.writer_id = f.writer_id
		  AND c.writer_version = f.writer_version AND c.logical_fact_id = f.logical_fact_id
		  AND c.semantic_revision = f.semantic_revision)
		),
		b3_eligible AS (
		SELECT * FROM b3_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b3_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b3_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b3_chosen AS (
		SELECT e.* FROM b3_eligible e JOIN b3_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b4_raw AS (
		SELECT 'imported_cell' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.entry_identity AS TEXT)) || ':' || CAST(c.entry_identity AS TEXT) || ':' || length(CAST(c.entry_import_revision AS TEXT)) || ':' || CAST(c.entry_import_revision AS TEXT) || ':' || length(CAST(c.run_identity AS TEXT)) || ':' || CAST(c.run_identity AS TEXT) || ':' || length(CAST(c.observation_identity AS TEXT)) || ':' || CAST(c.observation_identity AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.entry_identity AS writerId, NULL AS writerVersion,
		  c.observation_identity AS factId, c.entry_import_revision AS factRevision, c.item_index AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_cell_child c JOIN imported_cell_entry_revision e ON e.identity = c.entry_identity
		  AND e.import_revision = c.entry_import_revision
		WHERE e.import_revision = (SELECT MAX(n.import_revision) FROM imported_cell_entry_revision n
		  WHERE n.identity = e.identity)
		),
		b4_eligible AS (
		SELECT * FROM b4_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b4_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b4_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b4_chosen AS (
		SELECT e.* FROM b4_eligible e JOIN b4_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b5_raw AS (
		SELECT 'imported_cell_summary' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(f.entry_identity AS TEXT)) || ':' || CAST(f.entry_identity AS TEXT) || ':' || length(CAST(f.entry_import_revision AS TEXT)) || ':' || CAST(f.entry_import_revision AS TEXT) || ':' || length(CAST(f.run_identity AS TEXT)) || ':' || CAST(f.run_identity AS TEXT) || ':' || length(CAST(f.identity AS TEXT)) || ':' || CAST(f.identity AS TEXT) AS identity, f.coverage_start_time_ms AS startTimeMs, f.observed_time_ms AS endTimeMs,
		  NULL AS rowId, f.entry_identity AS writerId, NULL AS writerVersion,
		  f.identity AS factId, f.entry_import_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_cell_observation f JOIN imported_cell_entry_revision e ON
		  e.identity = f.entry_identity AND e.import_revision = f.entry_import_revision
		WHERE e.import_revision = (SELECT MAX(n.import_revision) FROM imported_cell_entry_revision n
		  WHERE n.identity = e.identity)
		AND NOT EXISTS (SELECT 1 FROM imported_cell_child c WHERE c.entry_identity = f.entry_identity
		  AND c.entry_import_revision = f.entry_import_revision AND c.run_identity = f.run_identity
		  AND c.observation_identity = f.identity)
		),
		b5_eligible AS (
		SELECT * FROM b5_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b5_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b5_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b5_chosen AS (
		SELECT e.* FROM b5_eligible e JOIN b5_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b6_raw AS (
		SELECT 'imported_ambient_cell' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.archive_id AS TEXT)) || ':' || CAST(c.archive_id AS TEXT) || ':' || length(CAST(c.fact_id AS TEXT)) || ':' || CAST(c.fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.archive_id AS writerId, NULL AS writerVersion,
		  c.fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_cell_child c JOIN imported_ambient_cell_fact f ON f.archive_id = c.archive_id
		  AND f.fact_id = c.fact_id AND f.semantic_revision = c.semantic_revision
		WHERE f.semantic_revision = (SELECT MAX(n.semantic_revision) FROM imported_ambient_cell_fact n
		  WHERE n.fact_id = f.fact_id)
		),
		b6_eligible AS (
		SELECT * FROM b6_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b6_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b6_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b6_chosen AS (
		SELECT e.* FROM b6_eligible e JOIN b6_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b7_raw AS (
		SELECT 'imported_ambient_cell_summary' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(f.archive_id AS TEXT)) || ':' || CAST(f.archive_id AS TEXT) || ':' || length(CAST(f.fact_id AS TEXT)) || ':' || CAST(f.fact_id AS TEXT) || ':' || length(CAST(f.semantic_revision AS TEXT)) || ':' || CAST(f.semantic_revision AS TEXT) AS identity, f.coverage_start_time_ms AS startTimeMs, f.observed_time_ms AS endTimeMs,
		  NULL AS rowId, f.archive_id AS writerId, NULL AS writerVersion,
		  f.fact_id AS factId, f.semantic_revision AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_cell_fact f WHERE f.semantic_revision =
		  (SELECT MAX(n.semantic_revision) FROM imported_ambient_cell_fact n WHERE n.fact_id = f.fact_id)
		AND NOT EXISTS (SELECT 1 FROM imported_ambient_cell_child c WHERE c.archive_id = f.archive_id
		  AND c.fact_id = f.fact_id AND c.semantic_revision = f.semantic_revision)
		),
		b7_eligible AS (
		SELECT * FROM b7_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b7_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b7_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b7_chosen AS (
		SELECT e.* FROM b7_eligible e JOIN b7_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b8_raw AS (
		SELECT 'ambient_cell_gap' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(g.gap_id AS TEXT)) || ':' || CAST(g.gap_id AS TEXT) AS identity, g.gap_start_time_ms AS startTimeMs, g.gap_end_time_ms AS endTimeMs,
		  NULL AS rowId, NULL AS writerId, NULL AS writerVersion,
		  g.gap_id AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM ambient_cell_gap g
		),
		b8_eligible AS (
		SELECT * FROM b8_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b8_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b8_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b8_chosen AS (
		SELECT e.* FROM b8_eligible e JOIN b8_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b9_raw AS (
		SELECT 'imported_ambient_cell_gap' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(g.archive_id AS TEXT)) || ':' || CAST(g.archive_id AS TEXT) || ':' || length(CAST(g.gap_id AS TEXT)) || ':' || CAST(g.gap_id AS TEXT) AS identity, g.start_time_ms AS startTimeMs, g.end_time_ms AS endTimeMs,
		  NULL AS rowId, g.archive_id AS writerId, NULL AS writerVersion,
		  g.gap_id AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM imported_ambient_cell_gap g
		),
		b9_eligible AS (
		SELECT * FROM b9_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b9_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b9_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b9_chosen AS (
		SELECT e.* FROM b9_eligible e JOIN b9_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		b10_raw AS (
		SELECT 'legacy_cell' AS kind, 'LEGACY' AS origin, 'UNKNOWN' AS purpose,
		  length(CAST(l.legacy_row_id AS TEXT)) || ':' || CAST(l.legacy_row_id AS TEXT) AS identity, l.time_ms AS startTimeMs, NULL AS endTimeMs,
		  l.legacy_row_id AS rowId, NULL AS writerId, NULL AS writerVersion,
		  NULL AS factId, NULL AS factRevision, NULL AS itemIndex,
		  NULL AS physicalEventId, NULL AS physicalIdentity
		FROM legacy_cell_child l
		),
		b10_eligible AS (
		SELECT * FROM b10_raw
		WHERE startTimeMs >= 0 AND startTimeMs < :toMs
		  AND (startTimeMs >= :fromMs OR endTimeMs > :fromMs OR
		    (endTimeMs = :fromMs AND kind LIKE '%_summary'))
		  AND origin IN (:origins) AND purpose IN (:purposes)
		  AND (:afterTime IS NULL OR startTimeMs < :afterTime OR
		    (startTimeMs = :afterTime AND (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END > :afterTag OR
		      (lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END = :afterTag AND coalesce(physicalIdentity, identity) > :afterIdentity))))
		),
		b10_heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(identity) AS winner
		FROM b10_eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		ORDER BY startTimeMs DESC, headTag ASC, headIdentity ASC LIMIT :limit
		),
		b10_chosen AS (
		SELECT e.* FROM b10_eligible e JOIN b10_heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND e.identity = h.winner
		),
		eligible AS (
		SELECT * FROM b0_chosen
		UNION ALL
		SELECT * FROM b1_chosen
		UNION ALL
		SELECT * FROM b2_chosen
		UNION ALL
		SELECT * FROM b3_chosen
		UNION ALL
		SELECT * FROM b4_chosen
		UNION ALL
		SELECT * FROM b5_chosen
		UNION ALL
		SELECT * FROM b6_chosen
		UNION ALL
		SELECT * FROM b7_chosen
		UNION ALL
		SELECT * FROM b8_chosen
		UNION ALL
		SELECT * FROM b9_chosen
		UNION ALL
		SELECT * FROM b10_chosen
		),
		heads AS (
		SELECT startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END AS headTag, coalesce(physicalIdentity, identity) AS headIdentity, min(CASE purpose WHEN 'CAPTURE' THEN '0' ELSE '1' END || ':' || kind || ':' || identity) AS winner
		FROM eligible GROUP BY startTimeMs, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END, coalesce(physicalIdentity, identity)
		),
		chosen AS (
		SELECT e.* FROM eligible e JOIN heads h ON e.startTimeMs = h.startTimeMs
		  AND lower(e.origin) || ':' || CASE WHEN e.physicalIdentity IS NULL THEN e.kind ELSE 'native_cell' END = h.headTag AND coalesce(e.physicalIdentity, e.identity) = h.headIdentity
		  AND CASE e.purpose WHEN 'CAPTURE' THEN '0' ELSE '1' END || ':' || e.kind || ':' || e.identity = h.winner
		)
		SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId, writerId, writerVersion,
		  factId, factRevision, itemIndex, physicalEventId, physicalIdentity FROM chosen
		ORDER BY startTimeMs DESC, lower(origin) || ':' || CASE WHEN physicalIdentity IS NULL THEN kind ELSE 'native_cell' END ASC, coalesce(physicalIdentity, identity) ASC LIMIT :limit
	""")
	suspend fun cellMeasuredHistoryCandidates(fromMs: Long, toMs: Long, origins: List<String>,
		purposes: List<String>, afterTime: Long?, afterTag: String?, afterIdentity: String?,
		limit: Int): List<MeasuredHistoryCandidate>

	/**
	 * Reads `cell_captured_child` rows. Query conditions include `c.observed_wall_time_ms IN
	 * (:observedTimes)`, `physicalIdentity IN (:identities)`, `purpose IN (:purposes)`, `kind >
	 * :afterKind`. Orders by `kind, identity`. Returns at most `limit` rows.
	 */
	@Query("""
		WITH candidates AS (
		SELECT 'captured_cell' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
		  length(CAST(c.writer_projection_id AS TEXT)) || ':' || CAST(c.writer_projection_id AS TEXT) || ':' || length(CAST(c.writer_projection_version AS TEXT)) || ':' || CAST(c.writer_projection_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_projection_id AS writerId, c.writer_projection_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.tower_identity IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.tower_identity AS BLOB)) END || ':' || CASE WHEN c.radio_type IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.radio_type AS BLOB)) END || ':' || CASE WHEN c.registered IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.registered AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM cell_captured_child c JOIN cell_captured_fact_cursor k ON
		  k.writer_projection_id = c.writer_projection_id AND k.writer_projection_version = c.writer_projection_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN cell_captured_fact_revision f ON f.writer_projection_id = c.writer_projection_id AND f.writer_projection_version = c.writer_projection_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		WHERE c.observed_wall_time_ms IN (:observedTimes)
		UNION ALL
		SELECT 'ambient_cell' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
		  length(CAST(c.writer_id AS TEXT)) || ':' || CAST(c.writer_id AS TEXT) || ':' || length(CAST(c.writer_version AS TEXT)) || ':' || CAST(c.writer_version AS TEXT) || ':' || length(CAST(c.logical_fact_id AS TEXT)) || ':' || CAST(c.logical_fact_id AS TEXT) || ':' || length(CAST(c.semantic_revision AS TEXT)) || ':' || CAST(c.semantic_revision AS TEXT) || ':' || length(CAST(c.item_index AS TEXT)) || ':' || CAST(c.item_index AS TEXT) AS identity, c.observed_wall_time_ms AS startTimeMs, NULL AS endTimeMs,
		  NULL AS rowId, c.writer_id AS writerId, c.writer_version AS writerVersion,
		  c.logical_fact_id AS factId, c.semantic_revision AS factRevision, c.item_index AS itemIndex,
		  f.source_event_id AS physicalEventId, 'physical:' || CASE WHEN f.source_event_id IS NULL THEN 'N' ELSE 'V' || hex(CAST(f.source_event_id AS BLOB)) END || ':' || CASE WHEN c.item_index IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.item_index AS BLOB)) END || ':' || CASE WHEN c.provider_timestamp_nanos IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.provider_timestamp_nanos AS BLOB)) END || ':' || CASE WHEN c.observed_wall_time_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.observed_wall_time_ms AS BLOB)) END || ':' || CASE WHEN c.wall_time_uncertainty_ms IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.wall_time_uncertainty_ms AS BLOB)) END || ':' || CASE WHEN c.tower_identity IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.tower_identity AS BLOB)) END || ':' || CASE WHEN c.radio_type IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.radio_type AS BLOB)) END || ':' || CASE WHEN c.registered IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.registered AS BLOB)) END || ':' || CASE WHEN c.signal_dbm IS NULL THEN 'N' ELSE 'V' || hex(CAST(c.signal_dbm AS BLOB)) END AS physicalIdentity
		FROM ambient_cell_child c JOIN ambient_cell_fact_cursor k ON
		  k.writer_id = c.writer_id AND k.writer_version = c.writer_version AND k.logical_fact_id = c.logical_fact_id
		  AND k.latest_semantic_revision = c.semantic_revision
		JOIN ambient_cell_fact_revision f ON f.writer_id = c.writer_id AND f.writer_version = c.writer_version
		  AND f.logical_fact_id = c.logical_fact_id AND f.semantic_revision = c.semantic_revision
		WHERE c.observed_wall_time_ms IN (:observedTimes)
		)
		SELECT * FROM candidates
		WHERE physicalIdentity IN (:identities) AND purpose IN (:purposes)
		  AND (:afterKind IS NULL OR kind > :afterKind OR (kind = :afterKind AND identity > :afterIdentity))
		ORDER BY kind, identity LIMIT :limit
	""")
	suspend fun cellNativeMeasuredPurposeCandidates(identities: List<String>, purposes: List<String>,
        observedTimes: List<Long>,
		afterKind: String?, afterIdentity: String?, limit: Int): List<MeasuredHistoryCandidate>

	/**
	 * Reads `legacy_wifi_child` rows. Query conditions include `legacy_row_id IN (:ids)`. Orders by
	 * `legacy_row_id`.
	 */
	@Query("SELECT * FROM legacy_wifi_child WHERE legacy_row_id IN (:ids) ORDER BY legacy_row_id")
	suspend fun legacyWifiByIds(ids: List<Long>): List<LegacyWifiChildEntity>

	/**
	 * Reads `legacy_cell_child` rows. Query conditions include `legacy_row_id IN (:ids)`. Orders by
	 * `legacy_row_id`.
	 */
	@Query("SELECT * FROM legacy_cell_child WHERE legacy_row_id IN (:ids) ORDER BY legacy_row_id")
	suspend fun legacyCellByIds(ids: List<Long>): List<LegacyCellChildEntity>
}
