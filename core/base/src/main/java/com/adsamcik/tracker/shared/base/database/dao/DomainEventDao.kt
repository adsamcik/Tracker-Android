package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

	@Query(
		"""
		SELECT COUNT(*)
		FROM domain_event
		WHERE event_type = :eventType
			AND processor_id = :processorId
			AND timestamp_ms = :timestampMs
			AND payload = :payload
		"""
	)
	suspend fun countMatching(
		eventType: String,
		processorId: String,
		timestampMs: Long,
		payload: String,
	): Int

	@Transaction
	suspend fun insertAllIdempotent(events: List<DomainEventEntity>) {
		events.forEach { event ->
			if (
				countMatching(
					eventType = event.eventType,
					processorId = event.processorId,
					timestampMs = event.timestampMs,
					payload = event.payload,
				) == 0
			) {
				insertAll(listOf(event))
			}
		}
	}

	/** Look up a consumer's current cursor position. Returns null when the consumer has never acked. */
	@Query("SELECT * FROM domain_event_cursor WHERE consumer_id = :consumerId")
	suspend fun getCursor(consumerId: String): DomainEventCursorEntity?

	/** Delivery follows insertion order so late events cannot fall behind a timestamp cursor. */
	@Query(
		"""
		SELECT *
		FROM domain_event
		WHERE id > :lastId
		ORDER BY id ASC
		LIMIT :limit
		"""
	)
	suspend fun getUnconsumedBatchById(lastId: Long, limit: Int): List<DomainEventEntity>

	/**
	 * Legacy timestamp-ordered CTE batch fetch. Delivery consumers must use
	 * [getCursor] + [getUnconsumedBatchById] so delayed events are not skipped.
	 */
	@Deprecated(
		message = "Use getCursor(consumerId) + getUnconsumedBatchById(lastId, limit) for insertion-ordered delivery.",
		replaceWith = ReplaceWith("getCursor(consumerId).let { c -> getUnconsumedBatchById(c?.lastProcessedId ?: 0L, boundaryOffset + 1) }"),
	)
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

	/** Advance both cursor fields monotonically so stale acknowledgements cannot regress progress. */
	@Query(
		"""
		INSERT INTO domain_event_cursor (consumer_id, last_processed_ms, last_processed_id)
		VALUES (:consumerId, :lastProcessedMs, :lastProcessedId)
		ON CONFLICT(consumer_id) DO UPDATE SET
			last_processed_ms = MAX(last_processed_ms, excluded.last_processed_ms),
			last_processed_id = MAX(last_processed_id, excluded.last_processed_id)
		"""
	)
	suspend fun upsertCursor(
		consumerId: String,
		lastProcessedMs: Long,
		lastProcessedId: Long,
	)

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
