package com.adsamcik.tracker.stats.data.worker

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import javax.inject.Inject

interface AchievementEvaluationTransactionRunner {
	suspend fun run(block: suspend () -> Unit)
}

class RoomAchievementEvaluationTransactionRunner @Inject constructor(
	private val database: AppDatabase,
) : AchievementEvaluationTransactionRunner {
	override suspend fun run(block: suspend () -> Unit) {
		database.withTransaction { block() }
	}
}
