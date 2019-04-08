package com.adsamcik.signalcollector.game.challenge.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkInTheParkChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.WalkInTheParkChallengeInstance
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.extra.WalkInTheParkChallengeEntry
import com.adsamcik.signalcollector.misc.extension.additiveInverse
import com.adsamcik.signalcollector.misc.extension.rescale

class WalkInTheParkChallengeBuilder(private val definition: WalkInTheParkChallengeDefinition) : ChallengeBuilder<WalkInTheParkChallengeInstance>(definition) {

	private var distanceRequired: Int = 0

	private fun selectRequiredDistance() {
		val min = 0.8 - 0.4 * (1 - durationMultiplierNormalized)
		val max = 1.0 + 2.3 * durationMultiplierNormalized
		val countMultiplier = normalRandom(min..max)
		distanceRequired = (definition.defaultDistanceInM * countMultiplier).toInt()
		this.difficultyMultiplier *= countMultiplier.additiveInverse(min..max).rescale(min..max, 0.4..2.2)
	}

	override fun selectChallengeSpecificParameters() {
		selectRequiredDistance()
	}

	override fun buildChallenge(context: Context, entry: ChallengeEntry): WalkInTheParkChallengeInstance {
		return WalkInTheParkChallengeInstance(entry, title, description, WalkInTheParkChallengeEntry(entry.id, false, distanceRequired, 0))
	}

}