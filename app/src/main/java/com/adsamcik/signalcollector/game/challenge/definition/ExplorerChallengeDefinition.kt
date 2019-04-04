package com.adsamcik.signalcollector.game.challenge.definition

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.Challenge
import com.adsamcik.signalcollector.game.challenge.data.ExplorerChallenge

class ExplorerChallengeDefinition : ChallengeDefinition {
	override val nameRes: Int = R.string.challenge_explorer_title
	override val descriptionRes: Int = R.string.challenge_explorer_description

	override fun createInstance(context: Context, difficulty: ChallengeDifficulty): Challenge {
		val resources = context.resources
		return ExplorerChallenge(
				name = resources.getString(nameRes),
				description = resources.getString(descriptionRes),
				difficulty = difficulty
		)
	}
}