package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.builder.ExplorerChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeInstance

class ExplorerChallengeDefinition : ChallengeDefinition<ExplorerChallengeInstance>(
		R.string.challenge_explorer_title,
		R.string.challenge_explorer_description,
		Time.DAY_IN_MILLISECONDS * BASE_DAY_COUNT) {
	val defaultLocationCount: Int = BASE_NEW_LOCATIONS_PER_DAY * BASE_DAY_COUNT

	override val type: ChallengeType = ChallengeType.Explorer

	override fun newInstance(context: Context, startAt: Long): ExplorerChallengeInstance {
		return ExplorerChallengeBuilder(this).build(context, startAt)
	}

	companion object {
		private const val BASE_NEW_LOCATIONS_PER_DAY = 100
		private const val BASE_DAY_COUNT = 7
	}

}
