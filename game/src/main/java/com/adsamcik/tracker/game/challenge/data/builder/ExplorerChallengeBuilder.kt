package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.ExplorerChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.extension.additiveInverse
import com.adsamcik.tracker.shared.base.extension.rescale

class ExplorerChallengeBuilder(private val definition: ExplorerChallengeDefinition) :
		ChallengeBuilder<ExplorerChallengeInstance>(
				definition
		) {
	private var requiredLocationCount: Int = 0

	private fun selectLocationCount() {
		val min = 0.8 - 0.4 * (1.0 - durationMultiplierNormalized)
		val max = 1.25 + 4.75 * durationMultiplierNormalized
		val countMultiplier = normalRandom(min..max)
		requiredLocationCount = (definition.defaultLocationCount * countMultiplier).toInt()
		addDifficulty(
				countMultiplier
						.additiveInverse(min..max)
						.rescale(min..max, 0.4..2.5)
		)
	}

	override fun selectChallengeSpecificParameters() {
		selectLocationCount()
	}

	override fun buildChallenge(
			context: Context,
			entry: ChallengeEntry
	): ExplorerChallengeInstance {
		return ExplorerChallengeInstance(
				entry, definition,
				ExplorerChallengeEntity(entry.id, false, requiredLocationCount, 0)
		)
	}

	override fun persistExtra(database: ChallengeDatabase, challenge: ExplorerChallengeInstance) {
		database.explorerDao().insert(challenge.extra)
	}
}

