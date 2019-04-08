package com.adsamcik.signalcollector.game.challenge.data.instance

import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra
import com.adsamcik.signalcollector.tracker.data.TrackerSession

abstract class ChallengeInstance<ExtraData : ChallengeEntryExtra>(
		val data: ChallengeEntry,
		val title: String,
		protected val descriptionTemplate: String,
		val extra: ExtraData) {

	val startTime: Long get() = data.startTime

	val endTime: Long get() = data.endTime

	val difficulty: ChallengeDifficulty get() = data.difficulty

	/**
	 * Duration of the challenge
	 */
	val duration: Long get() = data.endTime - data.startTime

	abstract val description: String

	abstract val progress: Int

	protected abstract fun checkCompletionConditions(): Boolean

	/**
	 * Runs a batch process on a specified session
	 */
	abstract fun processSession(session: TrackerSession)

	fun batchProcess(sessionList: List<TrackerSession>) {
		if (extra.isCompleted) return

		sessionList.forEach {
			processSession(it)
			if (checkCompletionConditions()) {
				extra.isCompleted = true
				return@forEach
			}
		}
	}
}