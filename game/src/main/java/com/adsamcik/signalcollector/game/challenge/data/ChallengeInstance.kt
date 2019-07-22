package com.adsamcik.signalcollector.game.challenge.data

import android.content.Context
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

abstract class ChallengeInstance<ExtraData : ChallengeEntryExtra, Instance : ChallengeInstance<ExtraData, Instance>>(
		val data: ChallengeEntry,
		val definition: ChallengeDefinition<Instance>,
		val extra: ExtraData) {

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
	 * Runs a batch process on a specified session
	 */
	protected abstract fun processSession(context: Context, session: TrackerSession)

	fun process(context: Context, session: TrackerSession, onChallengeCompletedListener: (Instance) -> Unit) {
		if (extra.isCompleted) return

		processSession(context, session)
		if (checkCompletionConditions()) {
			extra.isCompleted = true
			@Suppress("UNCHECKED_CAST")
			onChallengeCompletedListener.invoke(this as Instance)
		}
		@Suppress("UNCHECKED_CAST")
		persistence.persist(context, this as Instance)
	}
}