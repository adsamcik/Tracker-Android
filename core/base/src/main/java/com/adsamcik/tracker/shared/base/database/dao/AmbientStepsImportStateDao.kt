package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity

data class AmbientStepsEffectiveGapInterval(
	@ColumnInfo(name = "gap_id") val gapId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "gap_sequence") val gapSequence: Long,
	@ColumnInfo(name = "provider") val provider: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "declared_gap_start_time_ms") val declaredGapStartTimeMs: Long,
	@ColumnInfo(name = "declared_gap_end_time_ms") val declaredGapEndTimeMs: Long,
	@ColumnInfo(name = "gap_start_time_ms") val gapStartTimeMs: Long,
	@ColumnInfo(name = "gap_end_time_ms") val gapEndTimeMs: Long,
	@ColumnInfo(name = "predecessor_registration_generation")
	val predecessorRegistrationGeneration: Long?,
	@ColumnInfo(name = "predecessor_provider") val predecessorProvider: String?,
	@ColumnInfo(name = "previous_clock_domain_id") val previousClockDomainId: String,
	@ColumnInfo(name = "next_clock_domain_id") val nextClockDomainId: String,
	@ColumnInfo(name = "previous_zone_id") val previousZoneId: String,
	@ColumnInfo(name = "next_zone_id") val nextZoneId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
)

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

	/** Bounded singular-provider proof for current product availability and materialization. */
	@Query(
		"SELECT * FROM ambient_steps_import_cursor " +
			"WHERE status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"ORDER BY registration_generation DESC LIMIT :limit",
	)
	suspend fun activeCursors(limit: Int): List<AmbientStepsImportCursorEntity>

	/** Fixed-count historical cursor lookup for one bounded fact page. */
	@Query(
		"SELECT * FROM ambient_steps_import_cursor " +
			"WHERE registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation",
	)
	suspend fun cursors(
		registrationGenerations: List<Long>,
	): List<AmbientStepsImportCursorEntity>

	/** Bounded complete maintenance snapshot; callers request one overflow row. */
	@Query(
		"SELECT * FROM ambient_steps_import_cursor ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun maintenanceCursors(limit: Int): List<AmbientStepsImportCursorEntity>

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
			"AND updated_at_ms = :expectedUpdatedAtMs " +
			"AND last_observed_boot_id = :expectedBootId " +
			"AND last_observed_zone_id = :expectedZoneId " +
			"AND :newImportedThroughTimeMs >= imported_through_time_ms " +
			"AND :newImportedThroughTimeMs % 1000 = 0 " +
			"AND :newObservedAtMs >= :newImportedThroughTimeMs " +
			"AND :newObservedAtMs >= last_observed_at_ms " +
			"AND (:newImportedThroughTimeMs > imported_through_time_ms " +
			"OR :newObservedAtMs > last_observed_at_ms) " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= updated_at_ms " +
			"AND :updatedAtMs >= :newObservedAtMs",
	)
	suspend fun advanceExact(
		registrationGeneration: Long,
		expectedContinuitySegmentGeneration: Long,
		expectedLastGapSequence: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		expectedObservedAtMs: Long,
		expectedUpdatedAtMs: Long,
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

	/** Bounded complete maintenance snapshot; callers request one overflow row. */
	@Query(
		"SELECT * FROM ambient_steps_import_gap " +
			"ORDER BY registration_generation, gap_sequence LIMIT :limit",
	)
	suspend fun maintenanceGaps(limit: Int): List<AmbientStepsImportGapEntity>

	/**
	 * Subtracts the union of latest-effective Ambient Steps facts from declared gaps. Fact UPSERTs
	 * can split one gap into two intervals; a later RETRACT removes that fact from the subtraction.
	 */
	@Query(
		"WITH effective_fact AS (" +
			"SELECT fact.* FROM ambient_steps_fact_revision AS fact " +
			"WHERE fact.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND fact.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state " +
			"WHERE state.writer_id = fact.writer_id AND state.writer_version = fact.writer_version " +
			"AND state.logical_fact_id = fact.logical_fact_id)), " +
			"boundary AS (SELECT gap.gap_id, gap.gap_start_time_ms AS boundary_time_ms " +
			"FROM ambient_steps_import_gap AS gap WHERE gap.registration_generation = :registrationGeneration " +
			"UNION SELECT gap.gap_id, MIN(fact.window_end_time_ms, gap.gap_end_time_ms) " +
			"FROM ambient_steps_import_gap AS gap JOIN effective_fact AS fact " +
			"ON fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms " +
			"WHERE gap.registration_generation = :registrationGeneration) " +
			"SELECT gap.gap_id AS gap_id, gap.registration_generation AS registration_generation, " +
			"gap.gap_sequence AS gap_sequence, gap.provider AS provider, " +
			"gap.source_instance_id AS source_instance_id, gap.reason AS reason, " +
			"gap.gap_start_time_ms AS declared_gap_start_time_ms, " +
			"gap.gap_end_time_ms AS declared_gap_end_time_ms, " +
			"boundary.boundary_time_ms AS gap_start_time_ms, " +
			"MIN(gap.gap_end_time_ms, COALESCE((SELECT MIN(fact.window_start_time_ms) " +
			"FROM effective_fact AS fact WHERE fact.window_start_time_ms > boundary.boundary_time_ms " +
			"AND fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms), gap.gap_end_time_ms)) " +
			"AS gap_end_time_ms, gap.predecessor_registration_generation AS " +
			"predecessor_registration_generation, gap.predecessor_provider AS predecessor_provider, " +
			"gap.previous_clock_domain_id AS previous_clock_domain_id, " +
			"gap.next_clock_domain_id AS next_clock_domain_id, gap.previous_zone_id AS previous_zone_id, " +
			"gap.next_zone_id AS next_zone_id, gap.collected_data_epoch AS collected_data_epoch, " +
			"gap.recorded_at_ms AS recorded_at_ms FROM boundary JOIN ambient_steps_import_gap AS gap " +
			"ON gap.gap_id = boundary.gap_id WHERE boundary.boundary_time_ms < gap.gap_end_time_ms " +
			"AND NOT EXISTS (SELECT 1 FROM effective_fact AS fact " +
			"WHERE fact.window_start_time_ms <= boundary.boundary_time_ms " +
			"AND fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_end_time_ms > boundary.boundary_time_ms " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms) " +
			"ORDER BY gap_start_time_ms ASC",
	)
	suspend fun effectiveGapIntervals(
		registrationGeneration: Long,
	): List<AmbientStepsEffectiveGapInterval>

	/**
	 * Bounded page-level equivalent of [effectiveGapIntervals]. It subtracts latest-effective facts
	 * before returning gaps, avoiding one registration query per discovered structural day.
	 */
	@Query(
		"WITH effective_fact AS (SELECT fact.* FROM ambient_steps_fact_revision AS fact " +
			"WHERE fact.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND fact.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state WHERE state.writer_id = fact.writer_id " +
			"AND state.writer_version = fact.writer_version " +
			"AND state.logical_fact_id = fact.logical_fact_id)), " +
			"scoped_gap AS (SELECT * FROM ambient_steps_import_gap " +
			"WHERE gap_end_time_ms > :fromTimeMs AND gap_start_time_ms < :toTimeMs), " +
			"boundary AS (SELECT gap.gap_id, gap.gap_start_time_ms AS boundary_time_ms " +
			"FROM scoped_gap AS gap UNION SELECT gap.gap_id, MIN(fact.window_end_time_ms, gap.gap_end_time_ms) " +
			"FROM scoped_gap AS gap JOIN effective_fact AS fact ON fact.provider = gap.provider " +
			"AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms) " +
			"SELECT gap.gap_id AS gap_id, gap.registration_generation AS registration_generation, " +
			"gap.gap_sequence AS gap_sequence, gap.provider AS provider, " +
			"gap.source_instance_id AS source_instance_id, gap.reason AS reason, " +
			"gap.gap_start_time_ms AS declared_gap_start_time_ms, " +
			"gap.gap_end_time_ms AS declared_gap_end_time_ms, " +
			"MAX(boundary.boundary_time_ms, :fromTimeMs) AS gap_start_time_ms, " +
			"MIN(gap.gap_end_time_ms, :toTimeMs, COALESCE((SELECT MIN(fact.window_start_time_ms) " +
			"FROM effective_fact AS fact WHERE fact.window_start_time_ms > boundary.boundary_time_ms " +
			"AND fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms), gap.gap_end_time_ms)) AS gap_end_time_ms, " +
			"gap.predecessor_registration_generation AS predecessor_registration_generation, " +
			"gap.predecessor_provider AS predecessor_provider, " +
			"gap.previous_clock_domain_id AS previous_clock_domain_id, " +
			"gap.next_clock_domain_id AS next_clock_domain_id, gap.previous_zone_id AS previous_zone_id, " +
			"gap.next_zone_id AS next_zone_id, gap.collected_data_epoch AS collected_data_epoch, " +
			"gap.recorded_at_ms AS recorded_at_ms " +
			"FROM boundary JOIN scoped_gap AS gap ON gap.gap_id = boundary.gap_id " +
			"WHERE MAX(boundary.boundary_time_ms, :fromTimeMs) < MIN(gap.gap_end_time_ms, :toTimeMs) " +
			"AND NOT EXISTS (SELECT 1 FROM effective_fact AS fact " +
			"WHERE fact.window_start_time_ms <= boundary.boundary_time_ms " +
			"AND fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_end_time_ms > boundary.boundary_time_ms " +
			"AND fact.window_end_time_ms > gap.gap_start_time_ms " +
			"AND fact.window_start_time_ms < gap.gap_end_time_ms) " +
			"ORDER BY gap_start_time_ms ASC LIMIT :limit",
	)
	suspend fun effectiveGapIntervalsOverlapping(
		fromTimeMs: Long,
		toTimeMs: Long,
		limit: Int,
	): List<AmbientStepsEffectiveGapInterval>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAuthorityTransition(
		transition: AmbientStepsImportAuthorityTransitionEntity,
	): Long

	@Query(
		"SELECT * FROM ambient_steps_import_authority_transition " +
			"WHERE registration_generation = :registrationGeneration " +
			"ORDER BY transition_sequence ASC",
	)
	suspend fun authorityTransitions(
		registrationGeneration: Long,
	): List<AmbientStepsImportAuthorityTransitionEntity>

	/** Bounded complete maintenance snapshot; callers request one overflow row. */
	@Query(
		"SELECT * FROM ambient_steps_import_authority_transition " +
			"ORDER BY registration_generation, transition_sequence LIMIT :limit",
	)
	suspend fun maintenanceAuthorityTransitions(
		limit: Int,
	): List<AmbientStepsImportAuthorityTransitionEntity>

	/** One bounded page-level transition snapshot for all referenced registrations. */
	@Query(
		"SELECT * FROM ambient_steps_import_authority_transition " +
			"WHERE registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation ASC, transition_sequence ASC LIMIT :limit",
	)
	suspend fun authorityTransitionsBounded(
		registrationGenerations: List<Long>,
		limit: Int,
	): List<AmbientStepsImportAuthorityTransitionEntity>

	/** Atomically closes one legal authority and opens the next at the same exact boundary. */
	@Query(
		"UPDATE ambient_steps_import_cursor SET " +
			"authorization_revision = :newAuthorizationRevision, " +
			"authorization_fingerprint = :newAuthorizationFingerprint, " +
			"authorization_effective_boot_id = :newAuthorizationEffectiveBootId, " +
			"authorization_effective_elapsed_realtime_nanos = " +
			":newAuthorizationEffectiveElapsedRealtimeNanos, " +
			"authorization_effective_wall_time_ms = :newAuthorizationEffectiveWallTimeMs, " +
			"source_policy_revision = :newSourcePolicyRevision, " +
			"ambient_consent_epoch = :newAmbientConsentEpoch, " +
			"eligible_from_time_ms = :effectiveBoundaryTimeMs, " +
			"continuity_segment_generation = :newContinuitySegmentGeneration, " +
			"segment_start_time_ms = :effectiveBoundaryTimeMs, " +
			"imported_through_time_ms = :effectiveBoundaryTimeMs, " +
			"last_observed_at_ms = :newObservedAtMs, " +
			"authority_transition_sequence = :newAuthorityTransitionSequence, " +
			"cursor_revision = :newCursorRevision, updated_at_ms = :updatedAtMs " +
			"WHERE registration_generation = :registrationGeneration " +
			"AND status = '${AmbientStepsImportCursorEntity.STATUS_ACTIVE}' " +
			"AND continuity_segment_generation = :expectedContinuitySegmentGeneration " +
			"AND last_gap_sequence = :expectedLastGapSequence " +
			"AND authority_transition_sequence = :expectedAuthorityTransitionSequence " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND imported_through_time_ms = :effectiveBoundaryTimeMs " +
			"AND last_observed_at_ms = :expectedObservedAtMs " +
			"AND updated_at_ms = :expectedUpdatedAtMs " +
			"AND :newAuthorityTransitionSequence = authority_transition_sequence + 1 " +
			"AND :newContinuitySegmentGeneration = continuity_segment_generation + 1 " +
			"AND :newContinuitySegmentGeneration = last_gap_sequence + " +
			":newAuthorityTransitionSequence + 1 " +
			"AND :newAuthorizationRevision > authorization_revision " +
			"AND :newAuthorizationEffectiveBootId = registration_clock_domain_id " +
			"AND :effectiveBoundaryTimeMs >= eligible_from_time_ms " +
			"AND :effectiveBoundaryTimeMs % 1000 = 0 " +
			"AND :newObservedAtMs >= last_observed_at_ms " +
			"AND :newObservedAtMs >= :effectiveBoundaryTimeMs " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= updated_at_ms AND :updatedAtMs >= :newObservedAtMs " +
			"AND EXISTS (SELECT 1 FROM ambient_steps_import_authority_transition AS transition " +
			"WHERE transition.registration_generation = " +
			"ambient_steps_import_cursor.registration_generation " +
			"AND transition.transition_sequence = :newAuthorityTransitionSequence " +
			"AND transition.provider = ambient_steps_import_cursor.provider " +
			"AND transition.source_instance_id = ambient_steps_import_cursor.source_instance_id " +
			"AND transition.collected_data_epoch = ambient_steps_import_cursor.collected_data_epoch " +
			"AND transition.from_continuity_segment_generation = " +
			"ambient_steps_import_cursor.continuity_segment_generation " +
			"AND transition.to_continuity_segment_generation = :newContinuitySegmentGeneration " +
			"AND transition.from_authorization_revision = " +
			"ambient_steps_import_cursor.authorization_revision " +
			"AND transition.from_authorization_fingerprint = " +
			"ambient_steps_import_cursor.authorization_fingerprint " +
			"AND transition.from_source_policy_revision = " +
			"ambient_steps_import_cursor.source_policy_revision " +
			"AND transition.from_ambient_consent_epoch = " +
			"ambient_steps_import_cursor.ambient_consent_epoch " +
			"AND transition.to_authorization_revision = :newAuthorizationRevision " +
			"AND transition.to_authorization_fingerprint = :newAuthorizationFingerprint " +
			"AND transition.to_authorization_effective_boot_id = " +
			":newAuthorizationEffectiveBootId " +
			"AND transition.to_authorization_effective_elapsed_realtime_nanos = " +
			":newAuthorizationEffectiveElapsedRealtimeNanos " +
			"AND transition.to_authorization_effective_wall_time_ms = " +
			":newAuthorizationEffectiveWallTimeMs " +
			"AND transition.to_source_policy_revision = :newSourcePolicyRevision " +
			"AND transition.to_ambient_consent_epoch = :newAmbientConsentEpoch " +
			"AND transition.registration_accepted_at_ms = " +
			"ambient_steps_import_cursor.registration_accepted_at_ms " +
			"AND transition.effective_boundary_time_ms = :effectiveBoundaryTimeMs)",
	)
	suspend fun rotateAuthorityExact(
		registrationGeneration: Long,
		expectedContinuitySegmentGeneration: Long,
		expectedLastGapSequence: Long,
		expectedAuthorityTransitionSequence: Long,
		expectedCursorRevision: Long,
		expectedObservedAtMs: Long,
		expectedUpdatedAtMs: Long,
		newAuthorityTransitionSequence: Long,
		newContinuitySegmentGeneration: Long,
		newAuthorizationRevision: Long,
		newAuthorizationFingerprint: String,
		newAuthorizationEffectiveBootId: String,
		newAuthorizationEffectiveElapsedRealtimeNanos: Long,
		newAuthorizationEffectiveWallTimeMs: Long,
		newSourcePolicyRevision: Long,
		newAmbientConsentEpoch: Long,
		effectiveBoundaryTimeMs: Long,
		newObservedAtMs: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

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
			"AND last_observed_at_ms = :expectedObservedAtMs " +
			"AND updated_at_ms = :expectedUpdatedAtMs " +
			"AND :newLastGapSequence = last_gap_sequence + 1 " +
			"AND :newContinuitySegmentGeneration = continuity_segment_generation + 1 " +
			"AND :newContinuitySegmentGeneration = :newLastGapSequence + " +
			"authority_transition_sequence + 1 " +
			"AND EXISTS (SELECT 1 FROM ambient_steps_import_gap AS gap " +
			"WHERE gap.registration_generation = ambient_steps_import_cursor.registration_generation " +
			"AND gap.gap_sequence = :newLastGapSequence " +
			"AND gap.provider = ambient_steps_import_cursor.provider " +
			"AND gap.source_instance_id = ambient_steps_import_cursor.source_instance_id " +
			"AND gap.collected_data_epoch = ambient_steps_import_cursor.collected_data_epoch " +
			"AND gap.gap_start_time_ms <= ambient_steps_import_cursor.imported_through_time_ms " +
			"AND ambient_steps_import_cursor.imported_through_time_ms <= gap.gap_end_time_ms " +
			"AND gap.gap_end_time_ms >= :newSegmentStartTimeMs " +
			"AND gap.previous_clock_domain_id = ambient_steps_import_cursor.last_observed_boot_id " +
			"AND gap.previous_zone_id = ambient_steps_import_cursor.last_observed_zone_id " +
			"AND gap.next_clock_domain_id = :newBootId " +
			"AND gap.next_zone_id = :newZoneId " +
			"AND NOT EXISTS (SELECT 1 FROM ambient_steps_fact_revision AS fact " +
			"WHERE fact.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id " +
			"AND fact.registration_generation = gap.registration_generation " +
			"AND fact.collected_data_epoch = gap.collected_data_epoch " +
			"AND fact.window_start_time_ms <= ambient_steps_import_cursor.imported_through_time_ms " +
			"AND fact.window_end_time_ms > ambient_steps_import_cursor.imported_through_time_ms " +
			"AND fact.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state " +
			"WHERE state.writer_id = fact.writer_id AND state.writer_version = fact.writer_version " +
			"AND state.logical_fact_id = fact.logical_fact_id)) " +
			"AND (ambient_steps_import_cursor.imported_through_time_ms = gap.gap_start_time_ms " +
			"OR EXISTS (SELECT 1 FROM ambient_steps_fact_revision AS predecessor " +
			"WHERE predecessor.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND predecessor.provider = gap.provider " +
			"AND predecessor.source_instance_id = gap.source_instance_id " +
			"AND predecessor.registration_generation = gap.registration_generation " +
			"AND predecessor.collected_data_epoch = gap.collected_data_epoch " +
			"AND MIN(predecessor.window_end_time_ms, gap.gap_end_time_ms) = " +
			"ambient_steps_import_cursor.imported_through_time_ms " +
			"AND predecessor.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state " +
			"WHERE state.writer_id = predecessor.writer_id " +
			"AND state.writer_version = predecessor.writer_version " +
			"AND state.logical_fact_id = predecessor.logical_fact_id))) " +
			"AND :newSegmentStartTimeMs = MIN(gap.gap_end_time_ms, COALESCE((" +
			"SELECT MIN(successor.window_start_time_ms) " +
			"FROM ambient_steps_fact_revision AS successor " +
			"WHERE successor.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND successor.provider = gap.provider " +
			"AND successor.source_instance_id = gap.source_instance_id " +
			"AND successor.registration_generation = gap.registration_generation " +
			"AND successor.collected_data_epoch = gap.collected_data_epoch " +
			"AND successor.window_start_time_ms > " +
			"ambient_steps_import_cursor.imported_through_time_ms " +
			"AND successor.window_start_time_ms < gap.gap_end_time_ms " +
			"AND successor.window_end_time_ms > gap.gap_start_time_ms " +
			"AND successor.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state " +
			"WHERE state.writer_id = successor.writer_id " +
			"AND state.writer_version = successor.writer_version " +
			"AND state.logical_fact_id = successor.logical_fact_id)), gap.gap_end_time_ms))) " +
			"AND :newBootId = registration_clock_domain_id " +
			"AND (:newSegmentStartTimeMs > imported_through_time_ms " +
			"OR (:newSegmentStartTimeMs = imported_through_time_ms " +
			"AND gap.gap_start_time_ms = gap.gap_end_time_ms)) " +
			"AND :newSegmentStartTimeMs % 1000 = 0 " +
			"AND :newObservedAtMs >= :newSegmentStartTimeMs " +
			"AND :newObservedAtMs >= last_observed_at_ms " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= updated_at_ms AND :updatedAtMs >= :newObservedAtMs",
	)
	suspend fun beginNextSegmentExact(
		registrationGeneration: Long,
		expectedContinuitySegmentGeneration: Long,
		expectedLastGapSequence: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		expectedObservedAtMs: Long,
		expectedUpdatedAtMs: Long,
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
			"AND updated_at_ms = :expectedUpdatedAtMs " +
			"AND :newCursorRevision = cursor_revision + 1 " +
			"AND :updatedAtMs >= updated_at_ms " +
			"AND :updatedAtMs >= last_observed_at_ms",
	)
	suspend fun retireExact(
		registrationGeneration: Long,
		expectedCursorRevision: Long,
		expectedImportedThroughTimeMs: Long,
		expectedUpdatedAtMs: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query("SELECT COUNT(*) FROM ambient_steps_import_cursor")
	suspend fun countCursors(): Long

	@Query("SELECT COUNT(*) FROM ambient_steps_import_gap")
	suspend fun countGaps(): Long

	@Query("SELECT COUNT(*) FROM ambient_steps_import_authority_transition")
	suspend fun countAuthorityTransitions(): Long

	@Query("DELETE FROM ambient_steps_import_authority_transition")
	fun deleteAllAuthorityTransitions()

	@Query("DELETE FROM ambient_steps_import_gap")
	fun deleteAllGaps()

	@Query("DELETE FROM ambient_steps_import_cursor")
	fun deleteAllCursors()
}
