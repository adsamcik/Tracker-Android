package com.adsamcik.tracker.game.challenge.data.persistence

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengePersistence
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.instance.StepChallengeInstance

class StepChallengePersistence : ChallengePersistence<StepChallengeInstance> {
	override fun load(context: Context, entryId: Long): StepChallengeInstance {
		val database = getDatabase(context)
		val entry = database.entryDao.get(entryId)
		val entity = database.stepDao.getByEntry(entryId)
		val definition = StepChallengeDefinition()
		return StepChallengeInstance(
				entry,
				definition,
				entity
		)
	}

	override fun persist(context: Context, instance: StepChallengeInstance) {
		val database = getDatabase(context)
		database.entryDao.update(instance.data)
		database.stepDao.update(instance.extra)
	}

}
