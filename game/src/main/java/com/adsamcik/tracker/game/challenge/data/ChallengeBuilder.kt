package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.extension.normalize
import com.adsamcik.tracker.shared.base.extension.rescale
import com.adsamcik.tracker.shared.base.extension.standardDeviation
import com.adsamcik.tracker.shared.base.misc.Probability

abstract class ChallengeBuilder<ChallengeType : ChallengeInstance<*, *>>(
		private val definition: ChallengeDefinition<ChallengeType>
) {
	private var difficulties = mutableListOf<Double>()

	protected fun addDifficulty(value: Double) {
		require(value > 0.0)
		difficulties.add(value)
	}

	protected var duration: Long = 0L
		private set

	protected var durationMultiplier: Double = 0.0
		private set

	protected var durationMultiplierNormalized: Double = 0.0
		private set

	protected open val difficulty: ChallengeDifficulty
		get() {
			val difficultyAvg = difficulties.average()
			val difficultyStdDev = difficulties.standardDeviation()
			return when {
				difficultyAvg < difficultyAvg - difficultyStdDev -> ChallengeDifficulty.VERY_EASY
				difficultyAvg < difficultyAvg -> ChallengeDifficulty.EASY
				difficultyAvg < difficultyAvg + difficultyStdDev -> ChallengeDifficulty.MEDIUM
				difficultyAvg < difficultyAvg + 2 * difficultyStdDev -> ChallengeDifficulty.HARD
				else -> ChallengeDifficulty.VERY_HARD
			}
		}

	protected fun normalRandom(range: ClosedFloatingPointRange<Double>): Double {
		val mean = 0.5
		val standardDeviation = 0.22
		val value = Probability.truncatedNormal(mean, standardDeviation, 0.0, 1.0)
		return value.rescale(range)
	}

	open fun selectDuration() {
		val range = definition.minDurationMultiplier..definition.maxDurationMultiplier
		durationMultiplier = normalRandom(range)

		require(durationMultiplier > 0)

		durationMultiplierNormalized = durationMultiplier.normalize(range)

		duration = (definition.defaultDuration * durationMultiplier).toLong()

		require(duration > 0L)
	}

	abstract fun selectChallengeSpecificParameters()

	private fun createEntry(database: ChallengeDatabase, startAt: Long): ChallengeEntry {
		val entryDao = database.entryDao()
		val entry = ChallengeEntry(definition.type, startAt, startAt + duration, difficulty)
		entryDao.insertSetId(entry)

		require(entry.id > 0L) {
			"Id was ${entry.id} for $entry after insertion. Something is wrong."
		}

		return entry
	}

	fun build(context: Context, startAt: Long): ChallengeType {
		val database = ChallengeDatabase.database(context)

		selectDuration()
		selectChallengeSpecificParameters()

		val entry = createEntry(database, startAt)

		return buildChallenge(context, entry).also { persistExtra(database, it) }
	}

	protected abstract fun buildChallenge(context: Context, entry: ChallengeEntry): ChallengeType

	protected abstract fun persistExtra(database: ChallengeDatabase, challenge: ChallengeType)
}

