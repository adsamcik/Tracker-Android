package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.WalkDistanceChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.additiveInverse
import com.adsamcik.tracker.shared.base.extension.rescale

internal class WalkDistanceChallengeBuilder(private val definition: WalkDistanceChallengeDefinition) :
	ChallengeBuilder<WalkDistanceChallengeInstance>(definition) {

	private var distanceRequired: Float = 0f

	private fun selectRequiredDistance() {
		val minMultiplier = 0.5
		val maxMultiplier = 2.0

		// Adjust min and max multipliers slightly based on duration
		val adjustment = 0.3 * (durationMultiplierNormalized - 0.5)
		val adjustedMin = minMultiplier + adjustment
		val adjustedMax = maxMultiplier + adjustment

		val countMultiplier = normalRandom(adjustedMin..adjustedMax)
		distanceRequired = (definition.defaultDistanceInM * countMultiplier).toFloat()

		// Calculate rate (meters per day)
		val durationDays = duration.toDouble() / Time.DAY_IN_MILLISECONDS
		val rate = distanceRequired / durationDays

		// Normalize rate against default rate
		val defaultRate = definition.defaultDistanceInM / (definition.defaultDuration.toDouble() / Time.DAY_IN_MILLISECONDS)
		val difficultyValue = rate / defaultRate

		addDifficulty(difficultyValue)
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
		database.walkDistanceDao().insert(challenge.extra)
	}
}


