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
