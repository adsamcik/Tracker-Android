package com.adsamcik.signalcollector.game.challenge.data.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.misc.Probability
import com.adsamcik.signalcollector.misc.extension.normalize
import com.adsamcik.signalcollector.misc.extension.rescale

abstract class ChallengeBuilder<ChallengeType : ChallengeInstance<*>>(private val definition: ChallengeDefinition<ChallengeType>) {
	protected var difficultyMultiplier: Double = 1.0

	protected var duration: Long = 0L
		private set

	protected var durationMultiplier: Double = 0.0
		private set

	protected var durationMultiplierNormalized: Double = 0.0
		private set

	protected lateinit var description: String
	protected lateinit var title: String

	protected open val difficulty: ChallengeDifficulty
		get() = when {
			difficultyMultiplier < 0.5 -> ChallengeDifficulty.VERY_EASY
			difficultyMultiplier < 0.8 -> ChallengeDifficulty.EASY
			difficultyMultiplier < 1.25 -> ChallengeDifficulty.MEDIUM
			difficultyMultiplier < 2 -> ChallengeDifficulty.HARD
			else -> ChallengeDifficulty.VERY_HARD
		}

	protected fun normalRandom(range: ClosedFloatingPointRange<Double>) = Probability.normal().first().coerceIn(0.0, 1.0).rescale(range)

	open fun selectDuration() {
		val range = MIN_DURATION_MULTIPLIER..MAX_DURATION_MULTIPLIER
		durationMultiplier = normalRandom(range)
		durationMultiplierNormalized = durationMultiplier.normalize(range)

		duration = (definition.defaultDuration * durationMultiplier).toLong()
	}

	private fun loadResources(context: Context) {
		val resources = context.resources
		title = resources.getString(definition.titleRes)
		description = resources.getString(definition.descriptionRes)
	}

	abstract fun selectChallengeSpecificParameters()

	private fun createEntry(context: Context, startAt: Long): ChallengeEntry {
		val entryDao = ChallengeDatabase.getAppDatabase(context).entryDao
		val entry = ChallengeEntry(definition.name, startAt, startAt + duration, difficulty)
		entryDao.insertSetId(entry)

		if (entry.id == 0L)
			throw Error("Id was 0 after insertion. Something is wrong.")

		return entry
	}

	fun build(context: Context, startAt: Long): ChallengeType {
		selectDuration()
		selectChallengeSpecificParameters()
		loadResources(context)

		val entry = createEntry(context, startAt)

		return buildChallenge(context, entry)
	}

	protected abstract fun buildChallenge(context: Context, entry: ChallengeEntry): ChallengeType

	companion object {
		const val MAX_DURATION_MULTIPLIER = 3.0
		const val MIN_DURATION_MULTIPLIER = 0.25
	}
}