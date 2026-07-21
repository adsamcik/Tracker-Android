package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw barometric pressure sample captured during tracking.
 * Used for altitude estimation and vertical rate computation in activity recognition.
 */
@Entity(
    tableName = "pressure_sample",
    indices = [
        Index(value = ["time_ms"], name = "idx_pressure_sample_time"),
        Index(value = ["bucket_id"], name = "idx_pressure_sample_bucket"),
        Index(
            value = ["source_signal_id"],
            unique = true,
            name = "idx_pressure_sample_source_signal",
        )
    ]
)
data class PressureSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp in milliseconds since Unix epoch (wall clock time). */
    @ColumnInfo(name = "time_ms")
    val timeMs: Long,

    /** Elapsed real time in nanoseconds since boot (for correlating with other sensors). */
    @ColumnInfo(name = "elapsed_realtime_nanos")
    val elapsedRealtimeNanos: Long,

    /** Raw atmospheric pressure in hectopascals (hPa / mbar). */
    @ColumnInfo(name = "pressure_hpa")
    val pressureHpa: Float,

    /** Derived altitude in meters using standard atmosphere formula. Approximate, for relative changes only. */
    @ColumnInfo(name = "altitude_m")
    val altitudeM: Float,

    /** Optional bucket ID for spatial/temporal aggregation. Used by aging/compression. */
    @ColumnInfo(name = "bucket_id")
    val bucketId: Long?,

    /** Row creation timestamp (for auditing/debugging). */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Stable pending-signal identity used to make replay idempotent. */
    @ColumnInfo(name = "source_signal_id")
    val sourceSignalId: String? = null,
)
