package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.ChallengePersistence
import com.adsamcik.signalcollector.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.persistence.StepChallengePersistence
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

class StepChallengeInstance(data: ChallengeEntry, definition: ChallengeDefinition<StepChallengeInstance>, extra: StepChallengeEntity) : ChallengeInstance<StepChallengeEntity, StepChallengeInstance>(data, definition, extra) {

	@Suppress("unchecked_cast")
	override val persistence: ChallengePersistence<StepChallengeInstance>
		get() = StepChallengePersistence()

	override fun getDescription(context: Context): String {
		return context.getString(definition.descriptionRes, extra.requiredStepCount)
	}

	override val progress: Double
		get() = extra.stepCount.toDouble() / extra.requiredStepCount.toDouble()

	override fun checkCompletionConditions() = extra.stepCount >= extra.requiredStepCount

	override fun processSession(context: Context, session: TrackerSession) {
		extra.stepCount += session.steps
	}
}
