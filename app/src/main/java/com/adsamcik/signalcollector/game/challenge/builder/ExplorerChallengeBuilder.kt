package com.adsamcik.signalcollector.game.challenge.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.Challenge
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.progress.ExplorerChallengeProgressData
import com.adsamcik.signalcollector.misc.Probability
import com.adsamcik.signalcollector.misc.extension.additiveInverse
import com.adsamcik.signalcollector.misc.extension.rescale

class ExplorerChallengeBuilder(val definition: ExplorerChallengeDefinition) : ChallengeBuilder(definition) {
	private var requiredLocationCount: Int = 0

	private fun selectLocationCount() {
		val min = 0.2
		val max = 5.0
		val (countMultiplier, _) = Probability.normal(min, max)
		requiredLocationCount = (definition.defaultLocationCount * countMultiplier).toInt()
		this.difficultyMultiplier *= countMultiplier.additiveInverse(min..max).rescale(min..max, 0.25..2.0)
	}

	override fun selectChallengeSpecificParameters() {
		selectLocationCount()
	}

	override fun buildChallenge(context: Context, startAt: Long): Challenge {
		return ExplorerChallengeEntity(context, name, description, difficulty, startAt, startAt + duration, requiredLocationCount, ExplorerChallengeProgressData())
	}

}