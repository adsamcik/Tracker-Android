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
	private val difficultyValues = mutableListOf<Double>()

	protected fun addDifficulty(value: Double) {
		require(value > 0.0) { "Difficulty value must be positive." }
		difficultyValues.add(value)
	}

	protected var duration: Long = 0L
		private set

	protected var durationMultiplier: Double = 0.0
		private set

	protected var durationMultiplierNormalized: Double = 0.0
		private set

	protected open val difficulty: ChallengeDifficulty
		get() {
			val difficultyAvg = difficultyValues.average()
			return when {
				difficultyAvg < 0.8 -> ChallengeDifficulty.VERY_EASY
				difficultyAvg < 0.9 -> ChallengeDifficulty.EASY
				difficultyAvg < 1.1 -> ChallengeDifficulty.MEDIUM
				difficultyAvg < 1.3 -> ChallengeDifficulty.HARD
				else -> ChallengeDifficulty.VERY_HARD
			}
		}

	protected fun normalRandom(range: ClosedFloatingPointRange<Double>): Double {
		val mean = (range.start + range.endInclusive) / 2
		val standardDeviation = (range.endInclusive - range.start) / 6 // Approx 99.7% within range
		var value: Double
		do {
			value = Probability.normal(mean, standardDeviation).first()
		} while (value < range.start || value > range.endInclusive)
		return value
	}

	open fun selectDuration() {
		val range = definition.minDurationMultiplier..definition.maxDurationMultiplier
		durationMultiplier = normalRandom(range)

		require(durationMultiplier > 0)

		durationMultiplierNormalized = (durationMultiplier - range.start) / (range.endInclusive - range.start)

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


