package com.adsamcik.signalcollector.game.challenge.data.instance

import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession

class StepChallengeInstance(data: ChallengeEntry, title: String, descriptionTemplate: String, extra: StepChallengeEntity)
	: ChallengeInstance<StepChallengeEntity>(data, title, descriptionTemplate, extra) {
	override val description: String
		get() = descriptionTemplate.format(extra.requiredStepCount)
	override val progress: Double
		get() = extra.stepCount.toDouble() / extra.requiredStepCount.toDouble()

	override fun checkCompletionConditions() = extra.stepCount >= extra.requiredStepCount

	override fun processSession(session: TrackerSession) {
		extra.stepCount += session.steps
	}
}