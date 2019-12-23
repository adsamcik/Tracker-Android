package com.adsamcik.tracker.game.challenge.data.definition

import android.content.Context
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.builder.WalkDistanceChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.instance.WalkDistanceChallengeInstance

internal class WalkDistanceChallengeDefinition : ChallengeDefinition<WalkDistanceChallengeInstance>(
		R.string.challenge_walk_in_the_park_title, R.string.challenge_walk_in_the_park_description,
		BASE_DAY_COUNT * Time.DAY_IN_MILLISECONDS
) {

	val defaultDistanceInM: Int = BASE_DAY_COUNT * BASE_DISTANCE_PER_DAY

	override val type: ChallengeType = ChallengeType.WalkDistance

	override fun newInstance(context: Context, startAt: Long): WalkDistanceChallengeInstance {
		return WalkDistanceChallengeBuilder(this).build(context, startAt)
	}

	companion object {
		private const val BASE_DISTANCE_PER_DAY = 5000
		private const val BASE_DAY_COUNT = 2
	}

}

