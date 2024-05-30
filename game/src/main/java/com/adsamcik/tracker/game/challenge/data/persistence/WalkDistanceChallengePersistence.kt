package com.adsamcik.tracker.game.challenge.data.persistence

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengePersistence
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.instance.WalkDistanceChallengeInstance

internal class WalkDistanceChallengePersistence : ChallengePersistence<WalkDistanceChallengeInstance> {
	override fun load(context: Context, entryId: Long): WalkDistanceChallengeInstance {
		val database = getDatabase(context)
		val entry = database.entryDao().get(entryId)
		val entity = database.walkDistanceDao().getByEntry(entryId)
		val definition = WalkDistanceChallengeDefinition()
		return WalkDistanceChallengeInstance(
				entry,
				definition,
				entity
		)
	}

	override fun persist(context: Context, instance: WalkDistanceChallengeInstance) {
		val database = getDatabase(context)
		database.entryDao().update(instance.data)
		database.walkDistanceDao().update(instance.extra)
	}

}
