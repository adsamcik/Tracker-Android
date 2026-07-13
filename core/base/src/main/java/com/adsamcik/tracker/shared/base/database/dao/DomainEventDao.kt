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

	/**
	 * Seek-style batch fetch using the composite `(timestamp_ms, id)` index, expressed as
	 * a UNION ALL of two bounded SEARCH branches.
	 *
	 * The obvious scalar form
	 *   `WHERE timestamp_ms > :lastMs OR (timestamp_ms = :lastMs AND id > :lastId)`
	 * is *logically* a clean seek on `(timestamp_ms, id)`, but the SQLite planner
	 * collapses the OR disjunction and plans it as a full `SCAN` of
	 * `index_domain_event_timestamp_ms_id` rather than two `SEARCH` ranges. With 50k–200k
	 * retained events and multiple lagging consumers each draining in batches, that scan
	 * burns CPU and wakelock on every poll.
	 *
	 * The natural row-value form `WHERE (timestamp_ms, id) > (:lastMs, :lastId)` is
	 * accepted by SQLite ≥ 3.15 and would yield the same clean seek, but Room's `@Query`
	 * parser rejects tuple comparisons and refuses to generate the DAO impl.
	 *
	 * UNION ALL of the two halves is the next-best alternative — each branch is
	 * individually SEARCH-able, and the outer wrapper re-applies the global ORDER/LIMIT
	 * to merge them deterministically. Each branch also carries its own LIMIT so the
	 * planner can stop early.
	 *
	 * EXPLAIN QUERY PLAN (expected):
	 *   SEARCH domain_event USING INDEX index_domain_event_timestamp_ms_id (timestamp_ms=? AND id>?)
	 *   SEARCH domain_event USING INDEX index_domain_event_timestamp_ms_id (timestamp_ms>?)
	 * (Plus a compound merge step. Crucially: no SCAN of `domain_event`.)
	 *
	 * Regression-guarded by `DomainEventDaoTest.seek query plans as SEARCH not SCAN…`.
	 */
	@Query(
		"""
		SELECT * FROM (
			SELECT * FROM (
				SELECT *
				FROM domain_event
				WHERE timestamp_ms = :lastMs AND id > :lastId
				ORDER BY id ASC
				LIMIT :limit
			)
			UNION ALL
			SELECT * FROM (
				SELECT *
				FROM domain_event
				WHERE timestamp_ms > :lastMs
				ORDER BY timestamp_ms ASC, id ASC
				LIMIT :limit
			)
		)
		ORDER BY timestamp_ms ASC, id ASC
		LIMIT :limit
		"""
	)
	suspend fun getUnconsumedBatchSeek(lastMs: Long, lastId: Long, limit: Int): List<DomainEventEntity>

	/**
	 * Legacy CTE batch fetch. Replaced by [getCursor] + [getUnconsumedBatchSeek] which
	 * allows the SQLite planner to use an index seek rather than an ordered scan.
	 */
	@Deprecated(
		message = "Use getCursor(consumerId) + getUnconsumedBatchSeek(lastMs, lastId, limit) for index-seek performance.",
		replaceWith = ReplaceWith("getCursor(consumerId).let { c -> getUnconsumedBatchSeek(c?.lastProcessedMs ?: 0L, c?.lastProcessedId ?: 0L, boundaryOffset + 1) }"),
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
