package com.adsamcik.signalcollector.game.challenge.data.persistence

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.ChallengePersistence
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.WalkDistanceChallengeInstance

class WalkDistanceChallengePersistence : ChallengePersistence<WalkDistanceChallengeInstance> {
	override fun load(context: Context, entryId: Long): WalkDistanceChallengeInstance {
		val database = getDatabase(context)
		val resources = context.resources
		val entry = database.entryDao.get(entryId)
		val entity = database.walkDistanceDao.getByEntry(entryId)
		val definition = WalkDistanceChallengeDefinition()
		return WalkDistanceChallengeInstance(context,
				entry,
				resources.getString(definition.titleRes),
				resources.getString(definition.descriptionRes),
				entity
		)
	}

	override fun persist(context: Context, instance: WalkDistanceChallengeInstance) {
		val database = getDatabase(context)
		database.entryDao.update(instance.data)
		database.walkDistanceDao.update(instance.extra)
	}

}