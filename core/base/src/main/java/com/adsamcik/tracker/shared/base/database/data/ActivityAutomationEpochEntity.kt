package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Activity-only generation that invalidates automatic-start authority independently of policy. */
@Entity(tableName = "activity_automation_epoch")
data class ActivityAutomationEpochEntity(
	@PrimaryKey
	@ColumnInfo(name = "id")
	val id: Int = SINGLETON_ID,
	@ColumnInfo(name = "epoch")
	val epoch: Long = INITIAL_EPOCH,
	@ColumnInfo(name = "automatic_control_enabled")
	val automaticControlEnabled: Boolean = false,
	@ColumnInfo(name = "lock_suppressed")
	val lockSuppressed: Boolean = false,
	@ColumnInfo(name = "power_saver_suppressed")
	val powerSaverSuppressed: Boolean = false,
	@ColumnInfo(name = "boot_clock_domain_id")
	val bootClockDomainId: String = UNINITIALIZED_BOOT_CLOCK_DOMAIN,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos")
	val effectiveElapsedRealtimeNanos: Long = 0L,
	@ColumnInfo(name = "last_rotation_reason")
	val lastRotationReason: String = INITIAL_REASON,
	@ColumnInfo(name = "updated_at_ms")
	val updatedAtMs: Long = 0L,
) {
	init {
		require(id == SINGLETON_ID)
		require(epoch > 0L)
		require(bootClockDomainId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(lastRotationReason.isNotBlank())
		require(updatedAtMs >= 0L)
	}

	companion object {
		const val SINGLETON_ID = 1
		const val INITIAL_EPOCH = 1L
		const val INITIAL_REASON = "V28_INITIALIZED"
		const val UNINITIALIZED_BOOT_CLOCK_DOMAIN = "V28_UNINITIALIZED"
	}
}
