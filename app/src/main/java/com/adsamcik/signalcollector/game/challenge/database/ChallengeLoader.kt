package com.adsamcik.signalcollector.game.challenge.database

import android.content.Context
import android.content.res.Resources
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.instance.StepChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.instance.WalkDistanceChallengeInstance
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

object ChallengeLoader {
	fun loadChallenge(context: Context, resources: Resources, database: ChallengeDatabase, entry: ChallengeEntry): ChallengeInstance<*> {
		return when (entry.type) {
			ChallengeType.Explorer -> loadExplorerChallenge(context, resources, database, entry)
			ChallengeType.WalkDistance -> loadWalkDistanceChallenge(context, resources, database, entry)
			ChallengeType.Step -> loadStepChallenge(context, resources, database, entry)
		}
	}

	private fun loadExplorerChallenge(context: Context, resources: Resources, database: ChallengeDatabase, entry: ChallengeEntry): ExplorerChallengeInstance {
		val entity = database.explorerDao.getByEntry(entry.id)
		val definition = ExplorerChallengeDefinition()
		return ExplorerChallengeInstance(context,
				entry,
				resources.getString(definition.titleRes),
				resources.getString(definition.descriptionRes),
				entity
		)
	}

	private fun loadWalkDistanceChallenge(context: Context, resources: Resources, database: ChallengeDatabase, entry: ChallengeEntry): WalkDistanceChallengeInstance {
		val entity = database.walkDistanceDao.getByEntry(entry.id)
		val definition = WalkDistanceChallengeDefinition()
		return WalkDistanceChallengeInstance(context,
				entry,
				resources.getString(definition.titleRes),
				resources.getString(definition.descriptionRes),
				entity
		)
	}

	private fun loadStepChallenge(context: Context, resources: Resources, database: ChallengeDatabase, entry: ChallengeEntry): StepChallengeInstance {
		val entity = database.stepDao.getByEntry(entry.id)
		val definition = StepChallengeDefinition()
		return StepChallengeInstance(
				entry,
				resources.getString(definition.titleRes),
				resources.getString(definition.descriptionRes),
				entity
		)
	}
}