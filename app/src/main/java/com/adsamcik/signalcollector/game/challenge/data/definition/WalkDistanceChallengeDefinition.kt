package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.builder.WalkDistanceChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.WalkDistanceChallengeInstance

class WalkDistanceChallengeDefinition : ChallengeDefinition<WalkDistanceChallengeInstance>(R.string.challenge_walk_in_the_park_title, R.string.challenge_walk_in_the_park_description,
		Constants.DAY_IN_MILLISECONDS * 3) {

	val defaultDistanceInM: Int = 18000

	override val type: ChallengeType = ChallengeType.WalkDistance

	override fun createInstance(context: Context, startAt: Long): WalkDistanceChallengeInstance {
		return WalkDistanceChallengeBuilder(this).build(context, startAt)
	}

}