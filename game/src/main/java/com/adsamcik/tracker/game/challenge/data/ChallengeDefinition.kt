package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import androidx.annotation.StringRes

/**
 * Definition containing basic data for each challenge
 */
abstract class ChallengeDefinition<ChallengeInstanceType : ChallengeInstance<*, *>>(
		@StringRes val titleRes: Int,
		@StringRes val descriptionRes: Int,
		val defaultDuration: Long
) {

	abstract val type: ChallengeType

	open val maxDurationMultiplier: Double = MAX_DURATION_MULTIPLIER
	open val minDurationMultiplier: Double = MIN_DURATION_MULTIPLIER

	abstract fun newInstance(context: Context, startAt: Long): ChallengeInstanceType

	companion object {
		const val MAX_DURATION_MULTIPLIER: Double = 3.0
		const val MIN_DURATION_MULTIPLIER: Double = 0.25
	}
}

