package com.adsamcik.signalcollector.game.challenge.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.Challenge
import com.adsamcik.signalcollector.misc.Probability
import com.adsamcik.signalcollector.misc.extension.rescale

abstract class ChallengeBuilder<T>(private val definition: ChallengeDefinition) where T : Challenge<*> {
	protected var difficultyMultiplier: Double = 1.0
	protected var duration: Long = 0L

	protected lateinit var description: String
	protected lateinit var name: String

	protected open val difficulty: ChallengeDifficulty
		get() = when {
			difficultyMultiplier < 0.5 -> ChallengeDifficulty.VERY_EASY
			difficultyMultiplier < 0.9 -> ChallengeDifficulty.EASY
			difficultyMultiplier < 1.1 -> ChallengeDifficulty.MEDIUM
			difficultyMultiplier < 1.5 -> ChallengeDifficulty.HARD
			else -> ChallengeDifficulty.VERY_HARD
		}

	open fun selectLength() {
		val max = 1.6
		val min = 0.4
		val (durationMultiplier, _) = Probability.normal(min, max)
		duration = (definition.defaultDuration * durationMultiplier).toLong()
		difficultyMultiplier *= durationMultiplier.rescale(min..max, 0.25..2.0)
	}

	fun loadResources(context: Context) {
		val resources = context.resources
		name = resources.getString(definition.nameRes)
		description = resources.getString(definition.descriptionRes)
	}

	abstract fun selectChallengeSpecificParameters()

	fun build(context: Context, startAt: Long): T {
		selectChallengeSpecificParameters()
		selectLength()
		loadResources(context)
		return buildChallenge(context, startAt)
	}

	protected abstract fun buildChallenge(context: Context, startAt: Long): T
}