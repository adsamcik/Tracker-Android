package com.adsamcik.tracker.game.challenge.data.definition

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.builder.ExplorerChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.instance.ExplorerChallengeInstance

class ExplorerChallengeDefinition : ChallengeDefinition<ExplorerChallengeInstance>(
		R.string.challenge_explorer_title,
		R.string.challenge_explorer_description,
		Time.DAY_IN_MILLISECONDS * BASE_DAY_COUNT
) {
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
