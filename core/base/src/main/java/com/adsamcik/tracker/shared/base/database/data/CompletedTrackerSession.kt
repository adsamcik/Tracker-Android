package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Monotonic bounds for one durably completed tracker session.
 *
 * A session can contain several tracker-run rows because policy changes close and reopen those
 * rows. Lifecycle START/STOP evidence is therefore the canonical reconstruction boundary.
 */
data class CompletedTrackerSession(
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String,
	@ColumnInfo(name = "start_elapsed_realtime_nanos")
	val startElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "end_elapsed_realtime_nanos")
	val endElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "start_wall_time_ms")
	val startWallTimeMs: Long,
	@ColumnInfo(name = "end_wall_time_ms")
	val endWallTimeMs: Long,
)
