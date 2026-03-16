package com.adsamcik.tracker.stats.api.scheduler

/**
 * Schedules background achievement evaluation on the platform-specific implementation.
 */
interface AchievementEvaluationScheduler {
	fun scheduleEvaluation()
}
