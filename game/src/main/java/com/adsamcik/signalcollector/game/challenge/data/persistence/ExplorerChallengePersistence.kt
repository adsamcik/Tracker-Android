package com.adsamcik.signalcollector.game.challenge.data.persistence

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.ChallengePersistence
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeInstance

class ExplorerChallengePersistence : ChallengePersistence<ExplorerChallengeInstance> {
	override fun persist(context: Context, instance: ExplorerChallengeInstance) {
		val database = getDatabase(context)
		database.entryDao.update(instance.data)
		database.explorerDao.update(instance.extra)
	}

	override fun load(context: Context, entryId: Long): ExplorerChallengeInstance {
		val database = getDatabase(context)
		val resources = context.resources
		val entry = database.entryDao.get(entryId)
		val entity = database.explorerDao.getByEntry(entryId)
		val definition = ExplorerChallengeDefinition()
		return ExplorerChallengeInstance(context,
				entry,
				resources.getString(definition.titleRes),
				resources.getString(definition.descriptionRes),
				entity
		)
	}
}