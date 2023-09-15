package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.StepChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.extension.additiveInverse
import com.adsamcik.tracker.shared.base.extension.rescale
import kotlin.math.roundToInt

class StepChallengeBuilder(private val definition: StepChallengeDefinition) : ChallengeBuilder<StepChallengeInstance>(
		definition
) {

	private var stepsRequired: Int = 0

	private fun selectStepCount() {
		val min = 0.8 - 0.5 * (1 - durationMultiplierNormalized)
		val max = 1.2 + 2.0 * durationMultiplierNormalized
		val countMultiplier = normalRandom(min..max)
		stepsRequired = (definition.defaultRequiredStepCount * countMultiplier).roundToInt()
		addDifficulty(
				countMultiplier
						.additiveInverse(min..max)
						.rescale(min..max, 0.4..2.2)
		)
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

