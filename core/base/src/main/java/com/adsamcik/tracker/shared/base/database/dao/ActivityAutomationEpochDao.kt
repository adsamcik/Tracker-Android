package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity

@Dao
interface ActivityAutomationEpochDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun ensure(entity: ActivityAutomationEpochEntity = ActivityAutomationEpochEntity()): Long

	@Query("SELECT * FROM activity_automation_epoch WHERE id = 1")
	suspend fun current(): ActivityAutomationEpochEntity?

	@Query(
		"UPDATE activity_automation_epoch SET epoch = :newEpoch, " +
			"automatic_control_enabled = :automaticControlEnabled, " +
			"lock_suppressed = :lockSuppressed, " +
			"power_saver_suppressed = :powerSaverSuppressed, " +
			"boot_clock_domain_id = :bootClockDomainId, " +
			"effective_elapsed_realtime_nanos = :effectiveElapsedRealtimeNanos, " +
			"last_rotation_reason = :reason, updated_at_ms = :updatedAtMs " +
			"WHERE id = 1 AND epoch = :expectedEpoch",
	)
	suspend fun rotateExact(
		expectedEpoch: Long,
		newEpoch: Long,
		automaticControlEnabled: Boolean,
		lockSuppressed: Boolean,
		powerSaverSuppressed: Boolean,
		bootClockDomainId: String,
		effectiveElapsedRealtimeNanos: Long,
		reason: String,
		updatedAtMs: Long,
	): Int
}
