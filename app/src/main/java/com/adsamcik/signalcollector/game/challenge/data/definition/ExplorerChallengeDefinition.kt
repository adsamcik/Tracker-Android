package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.game.challenge.builder.ExplorerChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeInstance

class ExplorerChallengeDefinition : ChallengeDefinition<ExplorerChallengeInstance>(
		R.string.challenge_explorer_title,
		R.string.challenge_explorer_description,
		3 * Constants.DAY_IN_MILLISECONDS) {
	val defaultLocationCount: Int = 500

	override val name: String
		get() = "Explorer"

	override fun createInstance(context: Context, startAt: Long): ExplorerChallengeInstance {
		return ExplorerChallengeBuilder(this).build(context, startAt)
	}

}