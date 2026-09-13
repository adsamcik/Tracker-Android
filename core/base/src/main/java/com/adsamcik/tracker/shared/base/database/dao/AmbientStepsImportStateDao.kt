package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity

/** Source-local persistence primitives for a future Ambient Steps importer transaction. */
@Dao
interface AmbientStepsImportStateDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(cursor: AmbientStepsImportCursorEntity): Long

	@Query(
		"SELECT * FROM ambient_steps_import_cursor " +
			"WHERE registration_generation = :registrationGeneration",
	)
	suspend fun cursor(registrationGeneration: Long): AmbientStepsImportCursorEntity?

	@Query(
		"SELECT * FROM ambient_steps_import_cursor WHERE provider = :provider " +
			"AND status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"ORDER BY registration_generation DESC LIMIT 1",
	)
	suspend fun latestActiveCursor(provider: String): AmbientStepsImportCursorEntity?

	/**
	 * Monotonic compare-and-set used inside the eventual provider-read/fact transaction.
	 * A repeated correction read may retain the same time high-water, but it must advance both the
	 * observation timestamp and cursor revision. A stale importer cannot change the row.
	 */
	@Query(
		"UPDATE ambient_steps_import_cursor SET " +
			"imported_through_time_ms = :newImportedThroughTimeMs, " +
			"last_observed_at_ms = :newObservedAtMs, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs " +
			"WHERE registration_generation = :registrationGeneration " +
			"AND status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"AND continuity_segment_generation = :expectedContinuitySegmentGeneration " +
			"AND last_gap_sequence = :expectedLastGapSequence " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND imported_through_time_ms = :expectedImportedThroughTimeMs " +
			"AND last_observed_at_ms = :expectedObservedAtMs " +
			"AND last_observed_boot_id = :expectedBootId " +
			"AND last_observed_zone_id = :expectedZoneId " +
			"AND :newImportedThroughTimeMs >= imported_through_time_ms " +
			"AND :newImportedThroughTimeMs % 1000 = 0 " +
			"AND :newObservedAtMs >= :newImportedThroughTimeMs " +
			"AND :newObservedAtMs >= last_observed_at_ms " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= :newObservedAtMs",
	)
	suspend fun advanceExact(
		registrationGeneration: Long,
		expectedContinuitySegmentGeneration: Long,
		expectedLastGapSequence: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		expectedObservedAtMs: Long,
		expectedBootId: String,
		expectedZoneId: String,
		newImportedThroughTimeMs: Long,
		newObservedAtMs: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertGap(gap: AmbientStepsImportGapEntity): Long

	@Query(
		"SELECT * FROM ambient_steps_import_gap WHERE registration_generation = :registrationGeneration " +
			"AND gap_sequence = :gapSequence",
	)
	suspend fun gap(
		registrationGeneration: Long,
		gapSequence: Long,
	): AmbientStepsImportGapEntity?

	@Query(
		"SELECT * FROM ambient_steps_import_gap WHERE registration_generation = :registrationGeneration " +
			"ORDER BY gap_sequence ASC",
	)
	suspend fun gaps(registrationGeneration: Long): List<AmbientStepsImportGapEntity>

	/**
	 * Starts the next exact continuity segment after its immutable gap was inserted in the same
	 * outer Room transaction. This update cannot bridge a boot or zone change accidentally because
	 * both new authorities are explicit and the old segment/gap/revision tuple must still match.
	 */
	@Query(
		"UPDATE ambient_steps_import_cursor SET " +
			"continuity_segment_generation = :newContinuitySegmentGeneration, " +
			"segment_start_time_ms = :newSegmentStartTimeMs, " +
			"imported_through_time_ms = :newSegmentStartTimeMs, " +
			"last_observed_at_ms = :newObservedAtMs, " +
			"last_observed_boot_id = :newBootId, last_observed_zone_id = :newZoneId, " +
			"last_gap_sequence = :newLastGapSequence, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs " +
			"WHERE registration_generation = :registrationGeneration " +
			"AND status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"AND continuity_segment_generation = :expectedContinuitySegmentGeneration " +
			"AND last_gap_sequence = :expectedLastGapSequence " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND imported_through_time_ms = :expectedImportedThroughTimeMs " +
			"AND :newLastGapSequence = last_gap_sequence + 1 " +
			"AND :newContinuitySegmentGeneration = continuity_segment_generation + 1 " +
			"AND :newContinuitySegmentGeneration = :newLastGapSequence + 1 " +
			"AND EXISTS (SELECT 1 FROM ambient_steps_import_gap AS gap " +
			"WHERE gap.registration_generation = ambient_steps_import_cursor.registration_generation " +
			"AND gap.gap_sequence = :newLastGapSequence " +
			"AND gap.gap_end_time_ms = :newSegmentStartTimeMs " +
			"AND gap.next_clock_domain_id = :newBootId " +
			"AND gap.next_zone_id = :newZoneId) " +
			"AND :newBootId = registration_clock_domain_id " +
			"AND :newSegmentStartTimeMs >= imported_through_time_ms " +
			"AND :newSegmentStartTimeMs % 1000 = 0 " +
			"AND :newObservedAtMs >= :newSegmentStartTimeMs " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= :newObservedAtMs",
	)
	suspend fun beginNextSegmentExact(
		registrationGeneration: Long,
		expectedContinuitySegmentGeneration: Long,
		expectedLastGapSequence: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		newLastGapSequence: Long,
		newContinuitySegmentGeneration: Long,
		newSegmentStartTimeMs: Long,
		newObservedAtMs: Long,
		newBootId: String,
		newZoneId: String,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"UPDATE ambient_steps_import_cursor SET " +
			"status = '${AmbientStepsImportCursorEntity.STATUS_RETIRED}', " +
			"cursor_revision = :newCursorRevision, updated_at_ms = :updatedAtMs " +
			"WHERE registration_generation = :registrationGeneration " +
			"AND status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND imported_through_time_ms = :expectedImportedThroughTimeMs " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= last_observed_at_ms",
	)
	suspend fun retireExact(
		registrationGeneration: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query("SELECT COUNT(*) FROM ambient_steps_import_cursor")
	suspend fun countCursors(): Long

	@Query("SELECT COUNT(*) FROM ambient_steps_import_gap")
	suspend fun countGaps(): Long

	@Query("DELETE FROM ambient_steps_import_gap")
	fun deleteAllGaps()

	@Query("DELETE FROM ambient_steps_import_cursor")
	fun deleteAllCursors()
}
