package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.builder.StepChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.StepChallengeInstance

class StepChallengeDefinition : ChallengeDefinition<StepChallengeInstance>(R.string.challenge_step_title, R.string.challenge_step_description, Constants.DAY_IN_MILLISECONDS) {
	override val type: ChallengeType
		get() = ChallengeType.Step

	val defaultRequiredStepCount = 10000

	override val minDurationMultiplier: Double get() = 0.8

	override fun createInstance(context: Context, startAt: Long): StepChallengeInstance {
		return StepChallengeBuilder(this).build(context, startAt)
	}
}