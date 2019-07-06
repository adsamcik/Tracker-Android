package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.builder.StepChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.instance.StepChallengeInstance

class StepChallengeDefinition : ChallengeDefinition<StepChallengeInstance>(R.string.challenge_step_title, R.string.challenge_step_description, 2 * Time.DAY_IN_MILLISECONDS) {
	override val type: ChallengeType = ChallengeType.Step

	val defaultRequiredStepCount = 20000

	override val minDurationMultiplier: Double = 0.5
	override val maxDurationMultiplier: Double = 4.0

	override fun newInstance(context: Context, startAt: Long): StepChallengeInstance {
		return StepChallengeBuilder(this).build(context, startAt)
	}
}