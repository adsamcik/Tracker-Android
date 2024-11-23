package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.StepChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.additiveInverse
import com.adsamcik.tracker.shared.base.extension.rescale
import kotlin.math.roundToInt

class StepChallengeBuilder(private val definition: StepChallengeDefinition) : ChallengeBuilder<StepChallengeInstance>(
	definition
) {

	private var stepsRequired: Int = 0

	private fun selectStepCount() {
		val minMultiplier = 0.5
		val maxMultiplier = 2.0

		// Adjust min and max multipliers slightly based on duration
		val adjustment = 0.3 * (durationMultiplierNormalized - 0.5)
		val adjustedMin = minMultiplier + adjustment
		val adjustedMax = maxMultiplier + adjustment

		val countMultiplier = normalRandom(adjustedMin..adjustedMax)
		stepsRequired = (definition.defaultRequiredStepCount * countMultiplier).roundToInt()

		// Calculate rate (steps per day)
		val durationDays = duration.toDouble() / Time.DAY_IN_MILLISECONDS
		val rate = stepsRequired / durationDays

		// Normalize rate against default rate
		val defaultRate = definition.defaultRequiredStepCount / (definition.defaultDuration.toDouble() / Time.DAY_IN_MILLISECONDS)
		val difficultyValue = rate / defaultRate

		addDifficulty(difficultyValue)
	}

	override fun selectChallengeSpecificParameters() {
		selectStepCount()
	}

	override fun buildChallenge(context: Context, entry: ChallengeEntry): StepChallengeInstance {
		return StepChallengeInstance(
			entry,
			definition,
			StepChallengeEntity(entry.id, false, stepsRequired, 0)
		)
	}

	override fun persistExtra(database: ChallengeDatabase, challenge: StepChallengeInstance) {
		database.stepDao().insert(challenge.extra)
	}
}


