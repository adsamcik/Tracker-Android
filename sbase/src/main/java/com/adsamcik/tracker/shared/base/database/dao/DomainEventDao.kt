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

	/** Get events not yet processed by the given consumer (based on cursor offset). */
	@Query(
		"SELECT * FROM domain_event WHERE timestamp_ms > " +
			"COALESCE((SELECT last_processed_ms FROM domain_event_cursor " +
			"WHERE consumer_id = :consumerId), 0) " +
			"ORDER BY timestamp_ms ASC"
	)
	suspend fun getUnconsumedFor(consumerId: String): List<DomainEventEntity>

	/** Update or insert the consumer cursor to mark events as processed. */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertCursor(cursor: DomainEventCursorEntity)

	@Query("SELECT * FROM domain_event WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms ASC")
	fun observeSince(sinceMs: Long): Flow<List<DomainEventEntity>>

	@Query("DELETE FROM domain_event WHERE timestamp_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long)

	@Query("DELETE FROM domain_event")
	suspend fun deleteAll()

	@Query("DELETE FROM domain_event_cursor")
	suspend fun deleteAllCursors()
}
