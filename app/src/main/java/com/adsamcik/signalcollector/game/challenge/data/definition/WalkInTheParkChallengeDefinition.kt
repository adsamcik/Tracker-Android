package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.game.challenge.builder.WalkInTheParkChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.WalkInTheParkChallengeInstance

class WalkInTheParkChallengeDefinition : ChallengeDefinition<WalkInTheParkChallengeInstance>(R.string.challenge_walk_in_the_park_title, R.string.challenge_walk_in_the_park_description,
		Constants.DAY_IN_MILLISECONDS * 3) {

	val defaultDistanceInM: Int = 18000

	override val name: String
		get() = "WalkInThePark"

	override fun createInstance(context: Context, startAt: Long): WalkInTheParkChallengeInstance {
		return WalkInTheParkChallengeBuilder(this).build(context, startAt)
	}

}