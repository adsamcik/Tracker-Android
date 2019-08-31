package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.common.extension.additiveInverse
import com.adsamcik.tracker.common.extension.rescale
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.WalkDistanceChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry

class WalkDistanceChallengeBuilder(private val definition: WalkDistanceChallengeDefinition) :
		ChallengeBuilder<WalkDistanceChallengeInstance>(
				definition
		) {

	private var distanceRequired: Float = 0f

	private fun selectRequiredDistance() {
		val min = 0.8 - 0.4 * (1 - durationMultiplierNormalized)
		val max = 1.0 + 2.3 * durationMultiplierNormalized
		val countMultiplier = normalRandom(min..max)
		distanceRequired = (definition.defaultDistanceInM * countMultiplier).toFloat()
		this.difficultyMultiplier = (difficultyMultiplier + countMultiplier.additiveInverse(min..max)
				.rescale(min..max, 0.4..2.2)) / 2.0
	}

	override fun selectChallengeSpecificParameters() {
		selectRequiredDistance()
	}

	override fun buildChallenge(
			context: Context,
			entry: ChallengeEntry
	): WalkDistanceChallengeInstance {
		return WalkDistanceChallengeInstance(
				entry, definition,
				WalkDistanceChallengeEntity(entry.id, false, distanceRequired, 0f)
		)
	}

	override fun persistExtra(
			database: ChallengeDatabase,
			challenge: WalkDistanceChallengeInstance
	) {
		database.walkDistanceDao.insert(challenge.extra)
	}
}

