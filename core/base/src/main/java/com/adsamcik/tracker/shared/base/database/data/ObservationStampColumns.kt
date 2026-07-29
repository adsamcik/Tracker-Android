package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Reusable Room columns that preserve a source observation's own clock and receipt context.
 *
 * These fields are embedded in typed evidence tables. They intentionally do not replace each
 * table's domain-specific timestamp; they explain how that timestamp was obtained and how safely
 * it can be correlated with other sources.
 */
data class ObservationStampColumns(
	@ColumnInfo(name = "source_time_ms")
	val sourceTimeMs: Long? = null,
	@ColumnInfo(name = "source_elapsed_realtime_nanos")
	val sourceElapsedRealtimeNanos: Long? = null,
	@ColumnInfo(name = "source_first_elapsed_realtime_nanos")
	val sourceFirstElapsedRealtimeNanos: Long? = null,
	@ColumnInfo(name = "received_time_ms")
	val receivedTimeMs: Long? = null,
	@ColumnInfo(name = "received_elapsed_realtime_nanos")
	val receivedElapsedRealtimeNanos: Long? = null,
	@ColumnInfo(name = "source_sequence")
	val sourceSequence: Long? = null,
	@ColumnInfo(name = "source_first_sequence")
	val sourceFirstSequence: Long? = null,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String? = null,
	@ColumnInfo(name = "boot_clock_domain_id")
	val bootClockDomainId: String? = null,
	@ColumnInfo(name = "source_age_ms")
	val sourceAgeMs: Long? = null,
	@ColumnInfo(name = "time_uncertainty_ms")
	val timeUncertaintyMs: Long? = null,
	@ColumnInfo(name = "capability_flags")
	val capabilityFlags: String? = null,
	@ColumnInfo(name = "permission_precision")
	val permissionPrecision: String? = null,
)
