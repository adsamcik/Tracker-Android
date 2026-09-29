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

/** Purpose-owned radio details. Callers authenticate the corresponding fact before presenting rows. */
@Dao
interface RadioObservationChildDao {
	@Insert
	suspend fun insertWifiSession(rows: List<WifiCapturedChildEntity>)

	@Insert
	suspend fun insertCellSession(rows: List<CellCapturedChildEntity>)

	@Insert
	suspend fun insertWifiBackground(rows: List<AmbientWifiChildEntity>)

	@Insert
	suspend fun insertCellBackground(rows: List<AmbientCellChildEntity>)

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

	@Query("SELECT * FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun wifiBackgroundChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<AmbientWifiChildEntity>

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

	@Query("SELECT * FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND " +
		"semantic_revision = :revision ORDER BY item_index LIMIT :limit")
	suspend fun cellBackgroundChildren(writerId: String, writerVersion: Int, factId: String, revision: Long,
		limit: Int): List<AmbientCellChildEntity>

	@Query("SELECT child.* FROM ambient_cell_child AS child " +
		"INNER JOIN ambient_cell_fact_cursor AS cursor ON " +
		"cursor.writer_id = child.writer_id AND cursor.writer_version = child.writer_version AND " +
		"cursor.logical_fact_id = child.logical_fact_id AND " +
		"cursor.latest_semantic_revision = child.semantic_revision WHERE " +
		"child.logical_fact_id IN (:factIds) " +
		"ORDER BY child.logical_fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<AmbientCellChildEntity>

	@Query("SELECT * FROM ambient_cell_child WHERE logical_fact_id IN (:factIds) " +
		"ORDER BY logical_fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<AmbientCellChildEntity>

	@Query("SELECT * FROM ambient_cell_child WHERE logical_fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY writer_id, writer_version, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<AmbientCellChildEntity>

	@Query("SELECT * FROM wifi_captured_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun wifiSessionInRange(fromMs: Long, toMs: Long, limit: Int): List<WifiCapturedChildEntity>

	@Query("SELECT * FROM cell_captured_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun cellSessionInRange(fromMs: Long, toMs: Long, limit: Int): List<CellCapturedChildEntity>

	@Query("SELECT * FROM ambient_wifi_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun wifiBackgroundInRange(fromMs: Long, toMs: Long, limit: Int): List<AmbientWifiChildEntity>

	@Query("SELECT * FROM ambient_cell_child WHERE observed_wall_time_ms >= :fromMs AND " +
		"observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, logical_fact_id, item_index LIMIT :limit")
	suspend fun cellBackgroundInRange(fromMs: Long, toMs: Long, limit: Int): List<AmbientCellChildEntity>

	@Query("DELETE FROM wifi_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteWifiSessionFact(writerId: String, writerVersion: Int, factId: String): Int

	@Query("DELETE FROM wifi_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteWifiSessionRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	@Query("DELETE FROM cell_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteCellSessionFact(writerId: String, writerVersion: Int, factId: String): Int

	@Query("DELETE FROM cell_captured_child WHERE writer_projection_id = :writerId AND " +
		"writer_projection_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteCellSessionRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	@Query("DELETE FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteWifiBackgroundFact(writerId: String, writerVersion: Int, factId: String): Int

	@Query("DELETE FROM ambient_wifi_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteWifiBackgroundRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	@Query("DELETE FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId")
	suspend fun deleteCellBackgroundFact(writerId: String, writerVersion: Int, factId: String): Int

	@Query("DELETE FROM ambient_cell_child WHERE writer_id = :writerId AND " +
		"writer_version = :writerVersion AND logical_fact_id = :factId AND semantic_revision = :revision")
	suspend fun deleteCellBackgroundRevision(writerId: String, writerVersion: Int, factId: String,
		revision: Long): Int

	@Query("DELETE FROM wifi_captured_child")
	suspend fun deleteAllWifiSession()

	@Query("DELETE FROM cell_captured_child")
	suspend fun deleteAllCellSession()

	@Query("DELETE FROM ambient_wifi_child")
	suspend fun deleteAllWifiBackground()

	@Query("DELETE FROM ambient_cell_child")
	suspend fun deleteAllCellBackground()

	@Query("SELECT * FROM legacy_wifi_child WHERE time_ms >= :fromMs AND time_ms < :toMs " +
		"ORDER BY time_ms, legacy_row_id LIMIT :limit")
	suspend fun legacyWifiInRange(fromMs: Long, toMs: Long, limit: Int): List<LegacyWifiChildEntity>

	@Query("SELECT * FROM legacy_cell_child WHERE time_ms >= :fromMs AND time_ms < :toMs " +
		"ORDER BY time_ms, legacy_row_id LIMIT :limit")
	suspend fun legacyCellInRange(fromMs: Long, toMs: Long, limit: Int): List<LegacyCellChildEntity>

	@Query("DELETE FROM legacy_wifi_child")
	suspend fun deleteAllLegacyWifi()

	@Query("DELETE FROM legacy_cell_child")
	suspend fun deleteAllLegacyCell()
}
