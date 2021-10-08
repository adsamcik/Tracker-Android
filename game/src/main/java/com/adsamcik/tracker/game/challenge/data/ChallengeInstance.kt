package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntryExtra
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.data.TrackerSession

abstract class ChallengeInstance<ExtraData : ChallengeEntryExtra, Instance : ChallengeInstance<ExtraData, Instance>>(
		val data: ChallengeEntry,
		val definition: ChallengeDefinition<Instance>,
		val extra: ExtraData
) {

	val startTime: Long get() = data.startTime

	val endTime: Long get() = data.endTime

	val difficulty: ChallengeDifficulty get() = data.difficulty

	/**
	 * Duration of the challenge
	 */
	val duration: Long get() = data.endTime - data.startTime

	abstract val progress: Double

	abstract fun getDescription(context: Context): String

	fun getTitle(context: Context): String = context.getString(definition.titleRes)

	protected abstract val persistence: ChallengePersistence<Instance>

	protected abstract fun checkCompletionConditions(): Boolean

	/**
	 * Runs a batch process onDataUpdated a specified session
	 */
	protected abstract fun processSession(context: Context, session: TrackerSession)

	fun process(
			context: Context,
			session: TrackerSession,
			onChallengeCompletedListener: (Instance) -> Unit
	) {
		if (extra.isCompleted) return

		val startProgress = progress
		processSession(context, session)

		logGame(
				LogData(
						message = "Processed ${getTitle(context)} and progressed from $startProgress to $progress",
						source = CHALLENGE_LOG_SOURCE
				)
		)
		if (checkCompletionConditions()) {
			extra.isCompleted = true
			@Suppress("UNCHECKED_CAST")
			onChallengeCompletedListener.invoke(this as Instance)
		}
		@Suppress("UNCHECKED_CAST")
		persistence.persist(context, this as Instance)
	}
}

