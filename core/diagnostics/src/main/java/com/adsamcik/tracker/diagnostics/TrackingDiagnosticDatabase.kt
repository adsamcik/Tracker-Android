package com.adsamcik.tracker.diagnostics

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity(
	tableName = "tracking_diagnostic_event",
	indices = [
		Index(
			value = ["source", "purpose", "coarse_time_bucket", "event_id"],
			name = "index_tracking_diagnostic_event_source_purpose_recency",
		),
		Index(
			value = ["coarse_time_bucket", "event_id"],
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
	@ColumnInfo(name = "coarse_time_bucket")
	val coarseTimeBucket: Long,
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
	@ColumnInfo(name = "occurrence_count_bucket")
	val occurrenceCountBucket: String,
)

@Dao
internal interface TrackingDiagnosticDao {
	@Insert
	suspend fun insert(event: TrackingDiagnosticEventEntity): Long

	@Update
	suspend fun update(event: TrackingDiagnosticEventEntity): Int

	@Query("SELECT * FROM tracking_diagnostic_event WHERE event_id = :eventId")
	suspend fun findById(eventId: Long): TrackingDiagnosticEventEntity?

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		ORDER BY coarse_time_bucket DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourceFirst(source: String, limit: Int): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		  AND (
		    coarse_time_bucket < :beforeCoarseTimeBucket OR
		    (coarse_time_bucket = :beforeCoarseTimeBucket AND event_id < :beforeRowId)
		  )
		ORDER BY coarse_time_bucket DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourceBefore(
		source: String,
		beforeCoarseTimeBucket: Long,
		beforeRowId: Long,
		limit: Int,
	): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source AND purpose = :purpose
		ORDER BY coarse_time_bucket DESC, event_id DESC
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
		    coarse_time_bucket < :beforeCoarseTimeBucket OR
		    (coarse_time_bucket = :beforeCoarseTimeBucket AND event_id < :beforeRowId)
		  )
		ORDER BY coarse_time_bucket DESC, event_id DESC
		LIMIT :limit
		""",
	)
	suspend fun querySourcePurposeBefore(
		source: String,
		purpose: String,
		beforeCoarseTimeBucket: Long,
		beforeRowId: Long,
		limit: Int,
	): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		WHERE source = :source
		ORDER BY coarse_time_bucket ASC, event_id ASC
		""",
	)
	suspend fun sourceRowsOldest(source: String): List<TrackingDiagnosticEventEntity>

	@Query(
		"""
		SELECT * FROM tracking_diagnostic_event
		ORDER BY coarse_time_bucket ASC, event_id ASC
		""",
	)
	suspend fun globalRowsOldest(): List<TrackingDiagnosticEventEntity>

	@Query("DELETE FROM tracking_diagnostic_event WHERE event_id IN (:eventIds)")
	suspend fun deleteEvents(eventIds: List<Long>): Int

	@Query("DELETE FROM tracking_diagnostic_event WHERE coarse_time_bucket < :cutoffBucket")
	suspend fun deleteExpired(cutoffBucket: Long): Int

	@Query("DELETE FROM tracking_diagnostic_event")
	suspend fun deleteAllEvents()
}

/**
 * Unreleased standalone v1 database.
 *
 * Schema JSON export is intentionally contained until the final convergence batch. Before the
 * first version increment or migration, enable export and commit the generated v1 snapshot.
 */
@Database(
	entities = [TrackingDiagnosticEventEntity::class],
	version = 1,
	exportSchema = false,
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
