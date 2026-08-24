package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity

/**
 * Activity-specific Room transaction helpers. Callers must already own the surrounding Room
 * transaction so epoch rotation and closure of a pending automatic start are one durable change.
 */
suspend fun AppDatabase.reconcileActivityAutomationEpochInTransaction(
	automaticControlEnabled: Boolean? = null,
	lockSuppressed: Boolean? = null,
	powerSaverSuppressed: Boolean? = null,
	bootClockDomainId: String,
	effectiveElapsedRealtimeNanos: Long,
	reason: String,
	updatedAtMs: Long,
): ActivityAutomationEpochEntity {
	require(reason.isNotBlank())
	require(bootClockDomainId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L)
	require(updatedAtMs >= 0L)
	val dao = activityAutomationEpochDao()
	dao.ensure()
	val current = requireNotNull(dao.current()) {
		"Activity automation epoch disappeared inside transaction"
	}
	val nextControlEnabled = automaticControlEnabled ?: current.automaticControlEnabled
	val nextLockSuppressed = lockSuppressed ?: current.lockSuppressed
	val nextPowerSuppressed = powerSaverSuppressed ?: current.powerSaverSuppressed
	if (nextControlEnabled == current.automaticControlEnabled &&
		nextLockSuppressed == current.lockSuppressed &&
		nextPowerSuppressed == current.powerSaverSuppressed &&
		bootClockDomainId == current.bootClockDomainId
	) {
		return current
	}
	return rotateActivityAutomationEpochInTransaction(
		reason = reason,
		updatedAtMs = updatedAtMs,
		bootClockDomainId = bootClockDomainId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		automaticControlEnabled = nextControlEnabled,
		lockSuppressed = nextLockSuppressed,
		powerSaverSuppressed = nextPowerSuppressed,
	)
}

suspend fun AppDatabase.rotateActivityAutomationEpochInTransaction(
	reason: String,
	updatedAtMs: Long,
	bootClockDomainId: String,
	effectiveElapsedRealtimeNanos: Long,
): ActivityAutomationEpochEntity {
	val dao = activityAutomationEpochDao()
	dao.ensure()
	val current = requireNotNull(dao.current()) {
		"Activity automation epoch disappeared inside transaction"
	}
	return rotateActivityAutomationEpochInTransaction(
		reason = reason,
		updatedAtMs = updatedAtMs,
		bootClockDomainId = bootClockDomainId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		automaticControlEnabled = current.automaticControlEnabled,
		lockSuppressed = current.lockSuppressed,
		powerSaverSuppressed = current.powerSaverSuppressed,
	)
}

private suspend fun AppDatabase.rotateActivityAutomationEpochInTransaction(
	reason: String,
	updatedAtMs: Long,
	automaticControlEnabled: Boolean,
	lockSuppressed: Boolean,
	powerSaverSuppressed: Boolean,
	bootClockDomainId: String,
	effectiveElapsedRealtimeNanos: Long,
): ActivityAutomationEpochEntity {
	require(reason.isNotBlank())
	require(updatedAtMs >= 0L)
	require(bootClockDomainId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L)
	val dao = activityAutomationEpochDao()
	val current = requireNotNull(dao.current()) {
		"Activity automation epoch disappeared inside transaction"
	}
	check(current.epoch < Long.MAX_VALUE) { "Activity automation epoch exhausted" }
	val next = current.copy(
		epoch = current.epoch + 1L,
		automaticControlEnabled = automaticControlEnabled,
		lockSuppressed = lockSuppressed,
		powerSaverSuppressed = powerSaverSuppressed,
		bootClockDomainId = bootClockDomainId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		lastRotationReason = reason,
		updatedAtMs = updatedAtMs,
	)
	check(
		dao.rotateExact(
			expectedEpoch = current.epoch,
			newEpoch = next.epoch,
			automaticControlEnabled = next.automaticControlEnabled,
			lockSuppressed = next.lockSuppressed,
			powerSaverSuppressed = next.powerSaverSuppressed,
			bootClockDomainId = next.bootClockDomainId,
			effectiveElapsedRealtimeNanos = next.effectiveElapsedRealtimeNanos,
			reason = next.lastRotationReason,
			updatedAtMs = next.updatedAtMs,
		) == 1,
	) { "Activity automation epoch changed during rotation" }
	activityAutomaticStartActionDao().markPendingTerminalForEpochRotation(
		terminalAtMs = updatedAtMs,
		reason = "AUTOMATION_EPOCH_ROTATED:$reason",
	)
	return next
}
