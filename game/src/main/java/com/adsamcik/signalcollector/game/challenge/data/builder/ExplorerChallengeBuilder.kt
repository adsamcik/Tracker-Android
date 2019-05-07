package com.adsamcik.signalcollector.game.challenge.data.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.ChallengeBuilder
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.instance.ExplorerChallengeInstance
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.common.misc.extension.additiveInverse
import com.adsamcik.signalcollector.common.misc.extension.rescale

class ExplorerChallengeBuilder(private val definition: ExplorerChallengeDefinition) : ChallengeBuilder<ExplorerChallengeInstance>(definition) {
	private var requiredLocationCount: Int = 0

	private fun selectLocationCount() {
		val min = 0.8 - 0.4 * (1.0 - durationMultiplierNormalized)
		val max = 1.25 + 4.75 * durationMultiplierNormalized
		val countMultiplier = normalRandom(min..max)
		requiredLocationCount = (definition.defaultLocationCount * countMultiplier).toInt()
		this.difficultyMultiplier *= countMultiplier.additiveInverse(min..max).rescale(min..max, 0.4..2.5)
	}

	override fun selectChallengeSpecificParameters() {
		selectLocationCount()
	}

	override fun buildChallenge(context: Context, entry: ChallengeEntry): ExplorerChallengeInstance {
		return ExplorerChallengeInstance(context, entry, title, description, ExplorerChallengeEntity(entry.id, false, requiredLocationCount, 0))
	}

	override fun persistExtra(database: ChallengeDatabase, challenge: ExplorerChallengeInstance) {
		database.explorerDao.insert(challenge.extra)
	}
}