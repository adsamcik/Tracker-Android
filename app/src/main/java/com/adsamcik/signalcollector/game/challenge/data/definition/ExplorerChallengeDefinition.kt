package com.adsamcik.signalcollector.game.challenge.data.definition

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants

class ExplorerChallengeDefinition : ChallengeDefinition(R.string.challenge_explorer_title, R.string.challenge_explorer_description, 3 * Constants.DAY_IN_MILLISECONDS) {
	val defaultLocationCount: Int = 500
}