package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.common.extension.normalize
import com.adsamcik.tracker.common.extension.rescale
import com.adsamcik.tracker.common.misc.Probability
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry

abstract class ChallengeBuilder<ChallengeType : ChallengeInstance<*, *>>(private val definition: ChallengeDefinition<ChallengeType>) {
	protected var difficultyMultiplier: Double = 1.0

	protected var duration: Long = 0L
		private set

	protected var durationMultiplier: Double = 0.0
		private set

	protected var durationMultiplierNormalized: Double = 0.0
		private set

	protected open val difficulty: ChallengeDifficulty
		get() = when {
			difficultyMultiplier < 0.5 -> ChallengeDifficulty.VERY_EASY
			difficultyMultiplier < 0.8 -> ChallengeDifficulty.EASY
			difficultyMultiplier < 1.25 -> ChallengeDifficulty.MEDIUM
			difficultyMultiplier < 2 -> ChallengeDifficulty.HARD
			else -> ChallengeDifficulty.VERY_HARD
		}

	//todo improve this. It is not quite normal distribution due to clamping of values to 0 and 1. It is kinda ok, because the probability is around 2% iirc but still.
	protected fun normalRandom(range: ClosedFloatingPointRange<Double>) = Probability.normal().first().coerceIn(
			0.0,
			1.0
	).rescale(range)

	open fun selectDuration() {
		val range = definition.minDurationMultiplier..definition.maxDurationMultiplier
		durationMultiplier = normalRandom(range)
		durationMultiplierNormalized = durationMultiplier.normalize(range)

		duration = (definition.defaultDuration * durationMultiplier).toLong()
	}

	abstract fun selectChallengeSpecificParameters()

	private fun createEntry(database: ChallengeDatabase, startAt: Long): ChallengeEntry {
		val entryDao = database.entryDao
		val entry = ChallengeEntry(definition.type, startAt, startAt + duration, difficulty)
		entryDao.insertSetId(entry)

		if (entry.id == 0L)
			throw Error("Id was 0 after insertion. Something is wrong.")

		return entry
	}

	fun build(context: Context, startAt: Long): ChallengeType {
		val database = ChallengeDatabase.getDatabase(context)

		selectDuration()
		selectChallengeSpecificParameters()

		val entry = createEntry(database, startAt)

		return buildChallenge(context, entry).also { persistExtra(database, it) }
	}

	protected abstract fun buildChallenge(context: Context, entry: ChallengeEntry): ChallengeType

	protected abstract fun persistExtra(database: ChallengeDatabase, challenge: ChallengeType)
}

