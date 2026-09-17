package com.adsamcik.tracker.diagnostics

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity(
	tableName = "tracking_diagnostic_event",
	indices = [
		Index(
			value = ["source", "purpose", "last_observed_at_ms", "event_id"],
			name = "index_tracking_diagnostic_event_source_purpose_recency",
		),
		Index(
			value = ["last_observed_at_ms", "event_id"],
			name = "index_tracking_diagnostic_event_recency",
		),
	],
)
internal data class TrackingDiagnosticEventEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "event_id")
	val eventId: Long = 0L,
	@ColumnInfo(name = "source")
	val source: String,
	@ColumnInfo(name = "purpose")
	val purpose: String,
	@ColumnInfo(name = "pipeline_stage")
	val pipelineStage: String,
	@ColumnInfo(name = "operation")
	val operation: String,
	@ColumnInfo(name = "result")
	val result: String,
	@ColumnInfo(name = "reason")
	val reason: String,
	@ColumnInfo(name = "lifecycle")
	val lifecycle: String,
	@ColumnInfo(name = "operation_scope")
	val operationScope: String,
	@ColumnInfo(name = "scope_sequence")
	val scopeSequence: String,
	@ColumnInfo(name = "coarse_local_timestamp")
	val coarseLocalTimestamp: String,
	@ColumnInfo(name = "scope_duration_bucket")
	val scopeDurationBucket: String,
	@ColumnInfo(name = "encoded_envelope_size_bucket")
	val encodedEnvelopeSizeBucket: String?,
	@ColumnInfo(name = "queue_backlog_bucket")
	val queueBacklogBucket: String?,
	@ColumnInfo(name = "drained_envelope_count_bucket")
	val drainedEnvelopeCountBucket: String?,
	@ColumnInfo(name = "remaining_envelope_backlog_bucket")
	val remainingEnvelopeBacklogBucket: String?,
	@ColumnInfo(name = "persisted_envelope_count_bucket")
	val persistedEnvelopeCountBucket: String?,
	@ColumnInfo(name = "last_observed_at_ms")
	val lastObservedAtMs: Long,
	@ColumnInfo(name = "repeat_count")
	val repeatCount: Int,
	@ColumnInfo(name = "encoded_byte_count")
	val encodedByteCount: Int,
)

@Entity(tableName = "tracking_diagnostic_rate_limit")
internal data class TrackingDiagnosticRateLimitEntity(
	@PrimaryKey
	@ColumnInfo(name = "rate_key")
	val rateKey: String,
	@ColumnInfo(name = "window_started_at_ms")
	val windowStartedAtMs: Long,
	@ColumnInfo(name = "accepted_count")
	val acceptedCount: Int,
)

internal data class TrackingDiagnosticStoredFootprint(
	@ColumnInfo(name = "event_id")
	val eventId: Long,
	@ColumnInfo(name = "encoded_byte_count")
	val encodedByteCount: Int,
)

@Dao
internal interface TrackingDiagnosticDao {
	@Insert
	suspend fun insert(event: TrackingDiagnosticEventEntity): Long

	@Update
	suspend fun update(event: TrackingDiagnosticEventEntity): Int

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		  AND purpose = :purpose
		  AND pipeline_stage = :pipelineStage
		  AND operation = :operation
		  AND result = :result
		  AND reason = :reason
		  AND lifecycle = :lifecycle
		  AND scope_duration_bucket = :scopeDurationBucket
		  AND encoded_envelope_size_bucket IS :encodedEnvelopeSizeBucket
		  AND queue_backlog_bucket IS :queueBacklogBucket
		  AND drained_envelope_count_bucket IS :drainedEnvelopeCountBucket
		  AND remaining_envelope_backlog_bucket IS :remainingEnvelopeBacklogBucket
		  AND persisted_envelope_count_bucket IS :persistedEnvelopeCountBucket
		  AND last_observed_at_ms >= :observedAfterMs
		ORDER BY last_observed_at_ms DESC, event_id DESC
		LIMIT 1
		""",
	)
	suspend fun findAggregationCandidate(
		source: String,
		purpose: String,
		pipelineStage: String,
		operation: String,
		result: String,
		reason: String,
		lifecycle: String,
		scopeDurationBucket: String,
		encodedEnvelopeSizeBucket: String?,
		queueBacklogBucket: String?,
		drainedEnvelopeCountBucket: String?,
		remainingEnvelopeBacklogBucket: String?,
		persistedEnvelopeCountBucket: String?,
		observedAfterMs: Long,
	): TrackingDiagnosticEventEntity?

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		ORDER BY last_observed_at_ms DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourceFirst(source: String, limit: Int): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		  AND (
		    last_observed_at_ms < :beforeObservedAtMs OR
		    (last_observed_at_ms = :beforeObservedAtMs AND event_id < :beforeRowId)
		  )
		ORDER BY last_observed_at_ms DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourceBefore(
		source: String,
		beforeObservedAtMs: Long,
		beforeRowId: Long,
		limit: Int,
	): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source AND purpose = :purpose
		ORDER BY last_observed_at_ms DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourcePurposeFirst(
		source: String,
		purpose: String,
		limit: Int,
	): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source AND purpose = :purpose
		  AND (
		    last_observed_at_ms < :beforeObservedAtMs OR
		    (last_observed_at_ms = :beforeObservedAtMs AND event_id < :beforeRowId)
		  )
		ORDER BY last_observed_at_ms DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourcePurposeBefore(
		source: String,
		purpose: String,
		beforeObservedAtMs: Long,
		beforeRowId: Long,
		limit: Int,
	): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT event_id, encoded_byte_count FROM tracking_diagnostic_event
		WHERE source = :source
		ORDER BY last_observed_at_ms ASC, event_id ASC
		""",
	)
	suspend fun sourceFootprint(source: String): List<TrackingDiagnosticStoredFootprint>

	@Query(
		"""
		SELECT event_id, encoded_byte_count FROM tracking_diagnostic_event
		ORDER BY last_observed_at_ms ASC, event_id ASC
		""",
	)
	suspend fun globalFootprint(): List<TrackingDiagnosticStoredFootprint>

	@Query("DELETE FROM tracking_diagnostic_event WHERE event_id IN (:eventIds)")
	suspend fun deleteEvents(eventIds: List<Long>): Int

	@Query("DELETE FROM tracking_diagnostic_event WHERE last_observed_at_ms < :cutoffMs")
	suspend fun deleteExpired(cutoffMs: Long): Int

	@Query("DELETE FROM tracking_diagnostic_event")
	suspend fun deleteAllEvents()

	@Query("SELECT * FROM tracking_diagnostic_rate_limit WHERE rate_key = :rateKey")
	suspend fun readRateLimit(rateKey: String): TrackingDiagnosticRateLimitEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun writeRateLimit(rateLimit: TrackingDiagnosticRateLimitEntity)

	@Query("DELETE FROM tracking_diagnostic_rate_limit")
	suspend fun deleteAllRateLimits()
}

@Database(
	entities = [
		TrackingDiagnosticEventEntity::class,
		TrackingDiagnosticRateLimitEntity::class,
	],
	version = 1,
	exportSchema = true,
)
internal abstract class TrackingDiagnosticDatabase : RoomDatabase() {
	abstract fun diagnosticDao(): TrackingDiagnosticDao

	companion object {
		internal const val DATABASE_NAME = "tracking_diagnostics.db"

		fun create(context: Context): TrackingDiagnosticDatabase =
			Room.databaseBuilder(
				context.applicationContext,
				TrackingDiagnosticDatabase::class.java,
				DATABASE_NAME,
			)
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.build()
	}
}
