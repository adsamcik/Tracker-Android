package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.ExplorerChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.Time

class ExplorerChallengeBuilder(private val definition: ExplorerChallengeDefinition) :
	ChallengeBuilder<ExplorerChallengeInstance>(definition) {

	private var requiredLocationCount: Int = 0

	private fun selectLocationCount() {
		val minMultiplier = 0.5
		val maxMultiplier = 2.0

		// Adjust min and max multipliers slightly based on duration
		val adjustment = 0.3 * (durationMultiplierNormalized - 0.5)
		val adjustedMin = minMultiplier + adjustment
		val adjustedMax = maxMultiplier + adjustment

		val countMultiplier = normalRandom(adjustedMin..adjustedMax)
		requiredLocationCount = (definition.defaultLocationCount * countMultiplier).toInt()

		// Calculate rate (locations per day)
		val durationDays = duration.toDouble() / Time.DAY_IN_MILLISECONDS
		val rate = requiredLocationCount / durationDays

		// Normalize rate against default rate
		val defaultRate = definition.defaultLocationCount / (definition.defaultDuration.toDouble() / Time.DAY_IN_MILLISECONDS)
		val difficultyValue = rate / defaultRate

		addDifficulty(difficultyValue)
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


