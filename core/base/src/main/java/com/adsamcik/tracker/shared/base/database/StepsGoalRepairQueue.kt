package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity

/**
 * Enqueues one source-backed structural day only when an existing goal decision is now stale.
 * Call from the same Room transaction that advanced source evidence or repaired its projection.
 */
suspend fun AppDatabase.enqueueStepsGoalRepairDay(epochDay: Long) {
	enqueueStepsGoalRepairDayRange(epochDay, epochDay)
}

/**
 * Enqueues one representative structural day for every stale effect intersecting the range.
 * The caller may provide a conservative all-zone range; repair still uses each effect's exact
 * stored calendar authority and therefore never assigns facts to a guessed zone.
 */
suspend fun AppDatabase.enqueueStepsGoalRepairDayRange(
	firstEpochDay: Long,
	lastEpochDayInclusive: Long,
) {
	require(firstEpochDay <= lastEpochDayInclusive)
	sourceEvidenceStateDao().ensure()
	val sourceState = requireNotNull(sourceEvidenceStateDao().get()) {
		"Steps goal repair requires source-evidence state"
	}
	val revision = sourceState.revision
	val stale = stepsGoalEffectDao().getStaleAffectedByDayRange(
		firstEpochDay,
		lastEpochDayInclusive,
		revision,
	)
	val repairDays = stale.map { effect ->
		maxOf(effect.periodStartEpochDay, firstEpochDay)
	}.distinct()
	if (repairDays.isNotEmpty()) {
		achievementProgressDao().markQualifiedStepsMaterializing(
			sourceEvidenceRevision = revision,
			updatedAtMs = sourceState.updatedAtMs,
		)
		repairDays.forEach { epochDay -> stepsGoalRepairDayDao().enqueue(epochDay, revision) }
	}
}

/** Retention has no trustworthy per-row wall-time ownership, so invalidate every stale period. */
suspend fun AppDatabase.enqueueAllStepsGoalRepairs() {
	sourceEvidenceStateDao().ensure()
	val sourceState = requireNotNull(sourceEvidenceStateDao().get()) {
		"Steps goal repair requires source-evidence state"
	}
	val stale = stepsGoalEffectDao().getAllStale(sourceState.revision)
	if (stale.isEmpty()) return
	achievementProgressDao().markQualifiedStepsMaterializing(
		sourceEvidenceRevision = sourceState.revision,
		updatedAtMs = sourceState.updatedAtMs,
	)
	stale.map(StepsGoalEffectEntity::periodStartEpochDay).distinct().forEach { epochDay ->
		stepsGoalRepairDayDao().enqueue(epochDay, sourceState.revision)
	}
}
