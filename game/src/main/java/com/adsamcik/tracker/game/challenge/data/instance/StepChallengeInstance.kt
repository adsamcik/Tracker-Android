package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.ChallengePersistence
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.persistence.StepChallengePersistence
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.data.TrackerSession

class StepChallengeInstance(
		data: ChallengeEntry,
		definition: ChallengeDefinition<StepChallengeInstance>,
		extra: StepChallengeEntity
) : ChallengeInstance<StepChallengeEntity, StepChallengeInstance>(
		data, definition, extra
) {

	@Suppress("unchecked_cast")
	override val persistence: ChallengePersistence<StepChallengeInstance>
		get() = StepChallengePersistence()

	override fun getDescription(context: Context): String {
		return context.getString(definition.descriptionRes, extra.requiredStepCount)
	}

	override val progress: Double
		get() = extra.stepCount.toDouble() / extra.requiredStepCount.toDouble()

	override fun checkCompletionConditions(): Boolean = extra.stepCount >= extra.requiredStepCount

	override fun processSession(context: Context, session: TrackerSession) {
		extra.stepCount += session.steps
	}
}

