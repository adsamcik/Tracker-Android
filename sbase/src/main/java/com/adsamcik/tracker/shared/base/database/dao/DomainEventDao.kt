package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the domain_event and domain_event_cursor tables.
 * Uses consumer-cursor pattern for multi-consumer event processing.
 */
@Dao
interface DomainEventDao {

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertAll(events: List<DomainEventEntity>)

	/**
	 * Get the next bounded batch of events not yet processed by the given consumer.
	 *
	 * Cursor is composite `(last_processed_ms, last_processed_id)`: events are returned
	 * where `(timestamp_ms > lastMs) OR (timestamp_ms = lastMs AND id > lastId)`. This
	 * prevents the "same-millisecond skip" bug — two events sharing a timestamp would
	 * have been silently dropped by the previous timestamp-only `>` predicate after the
	 * first one was acked.
	 *
	 * The boundary is chosen using the (timestamp, id) of the Nth event, then expanded
	 * to include all events whose (timestamp, id) is at-or-below that boundary so a
	 * caller acking the last returned event always advances the cursor strictly.
	 */
	@Query(
		"""
		WITH cursor_value AS (
			SELECT
				COALESCE((SELECT last_processed_ms FROM domain_event_cursor WHERE consumer_id = :consumerId), 0) AS last_ms,
				COALESCE((SELECT last_processed_id FROM domain_event_cursor WHERE consumer_id = :consumerId), 0) AS last_id
		),
		batch_boundary AS (
			SELECT timestamp_ms AS boundary_ms, id AS boundary_id
			FROM domain_event
			WHERE
				timestamp_ms > (SELECT last_ms FROM cursor_value)
				OR (timestamp_ms = (SELECT last_ms FROM cursor_value) AND id > (SELECT last_id FROM cursor_value))
			ORDER BY timestamp_ms ASC, id ASC
			LIMIT 1 OFFSET :boundaryOffset
		)
		SELECT *
		FROM domain_event
		WHERE
			(
				timestamp_ms > (SELECT last_ms FROM cursor_value)
				OR (timestamp_ms = (SELECT last_ms FROM cursor_value) AND id > (SELECT last_id FROM cursor_value))
			)
			AND (
				(SELECT boundary_ms FROM batch_boundary) IS NULL
				OR timestamp_ms < (SELECT boundary_ms FROM batch_boundary)
				OR (timestamp_ms = (SELECT boundary_ms FROM batch_boundary) AND id <= (SELECT boundary_id FROM batch_boundary))
			)
		ORDER BY timestamp_ms ASC, id ASC
		"""
	)
	suspend fun getUnconsumedBatchFor(
		consumerId: String,
		boundaryOffset: Int,
	): List<DomainEventEntity>

	/** Update or insert the consumer cursor to mark events as processed. */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertCursor(cursor: DomainEventCursorEntity)

	@Deprecated(
		message = "Unbounded domain-event flows can allocate very large lists. Use observeSinceLimited or cursor batches.",
	)
	@Query("SELECT * FROM domain_event WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms ASC, id ASC")
	fun observeSince(sinceMs: Long): Flow<List<DomainEventEntity>>

	@Query(
		"""
		SELECT *
		FROM (
			SELECT *
			FROM domain_event
			WHERE timestamp_ms >= :sinceMs
			ORDER BY timestamp_ms DESC, id DESC
			LIMIT :limit
		) AS latest_events
		ORDER BY timestamp_ms ASC, id ASC
		"""
	)
	fun observeSinceLimited(sinceMs: Long, limit: Int): Flow<List<DomainEventEntity>>

	@Query("SELECT MIN(last_processed_ms) FROM domain_event_cursor")
	suspend fun getMinimumCursorTimestampMs(): Long?

	@Query("DELETE FROM domain_event WHERE timestamp_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long)

	@Query("DELETE FROM domain_event")
	suspend fun deleteAll()

	@Query("DELETE FROM domain_event_cursor")
	suspend fun deleteAllCursors()
}
