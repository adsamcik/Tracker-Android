package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity

@Dao
interface ActivityAutomaticStartActionDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfSlotFree(entity: ActivityAutomaticStartActionEntity): Long

	@Query("SELECT * FROM activity_automatic_start_action WHERE slot_id = 1")
	suspend fun current(): ActivityAutomaticStartActionEntity?

	@Query("SELECT * FROM activity_automatic_start_action WHERE trigger_id = :triggerId")
	suspend fun action(triggerId: String): ActivityAutomaticStartActionEntity?

	/**
	 * Exact service/coordinator fence. The join makes the deletion epoch part of the same Room read
	 * as the action status instead of relying on a previously cached preference snapshot.
	 */
	@Query(
		"SELECT start_action.* FROM activity_automatic_start_action AS start_action " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"WHERE start_action.trigger_id = :triggerId " +
			"AND start_action.status = 'START_REQUESTED' " +
			"AND start_action.collected_data_epoch = :collectedDataEpoch " +
			"AND evidence.collected_data_epoch = :collectedDataEpoch",
	)
	suspend fun requestedActionInCurrentDataEpoch(
		triggerId: String,
		collectedDataEpoch: Long,
	): ActivityAutomaticStartActionEntity?

	@Query(
		"UPDATE activity_automatic_start_action SET status = 'START_REQUESTED', " +
			"start_requested_at_ms = :requestedAtMs, terminal_at_ms = NULL, terminal_reason = NULL " +
			"WHERE trigger_id = :triggerId AND status = 'RESERVED' " +
			"AND collected_data_epoch = :collectedDataEpoch",
	)
	suspend fun markStartRequested(
		triggerId: String,
		collectedDataEpoch: Long,
		requestedAtMs: Long,
	): Int

	@Query(
		"UPDATE activity_automatic_start_action " +
			"SET status = 'LIFECYCLE_INTENT_ACCEPTED', " +
			"lifecycle_intent_accepted_at_ms = :acceptedAtMs, " +
			"accepted_logical_tracking_id = :logicalTrackingId, " +
			"accepted_intent_revision = :intentRevision, " +
			"terminal_at_ms = NULL, terminal_reason = NULL " +
			"WHERE trigger_id = :triggerId AND status = 'START_REQUESTED' " +
			"AND collected_data_epoch = :collectedDataEpoch",
	)
	suspend fun markLifecycleIntentAccepted(
		triggerId: String,
		collectedDataEpoch: Long,
		logicalTrackingId: String,
		intentRevision: Long,
		acceptedAtMs: Long,
	): Int

	@Query(
		"UPDATE activity_automatic_start_action SET status = 'TERMINAL', " +
			"terminal_at_ms = :terminalAtMs, terminal_reason = :reason " +
			"WHERE trigger_id = :triggerId AND collected_data_epoch = :collectedDataEpoch " +
			"AND status IN ('RESERVED', 'START_REQUESTED')",
	)
	suspend fun markTerminal(
		triggerId: String,
		collectedDataEpoch: Long,
		terminalAtMs: Long,
		reason: String,
	): Int

	@Query(
		"UPDATE activity_automatic_start_action SET status = 'TERMINAL', " +
			"terminal_at_ms = :terminalAtMs, terminal_reason = :reason " +
			"WHERE trigger_id = :triggerId AND collected_data_epoch = :collectedDataEpoch " +
			"AND status = 'LIFECYCLE_INTENT_ACCEPTED' " +
			"AND accepted_logical_tracking_id = :logicalTrackingId " +
			"AND accepted_intent_revision = :intentRevision",
	)
	suspend fun markAcceptedLifecycleTerminal(
		triggerId: String,
		collectedDataEpoch: Long,
		logicalTrackingId: String,
		intentRevision: Long,
		terminalAtMs: Long,
		reason: String,
	): Int

	@Query(
		"UPDATE activity_automatic_start_action SET status = 'TERMINAL', " +
			"terminal_at_ms = :terminalAtMs, terminal_reason = :reason " +
			"WHERE slot_id = 1 AND status IN ('RESERVED', 'START_REQUESTED')",
	)
	suspend fun markPendingTerminalForEpochRotation(
		terminalAtMs: Long,
		reason: String,
	): Int

	@Query(
		"DELETE FROM activity_automatic_start_action WHERE trigger_id = :triggerId " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND status IN ('LIFECYCLE_INTENT_ACCEPTED', 'TERMINAL')",
	)
	suspend fun clearCompletedSlot(triggerId: String, collectedDataEpoch: Long): Int

	@Query("DELETE FROM activity_automatic_start_action")
	fun deleteAll()
}
