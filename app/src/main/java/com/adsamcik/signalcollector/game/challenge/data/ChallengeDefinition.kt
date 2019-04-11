package com.adsamcik.signalcollector.game.challenge.data

import android.content.Context
import androidx.annotation.StringRes

/**
 * Definition containing basic data for each challenge
 */
abstract class ChallengeDefinition<ChallengeInstanceType : ChallengeInstance<*>>(
		@StringRes val titleRes: Int,
		@StringRes val descriptionRes: Int,
		val defaultDuration: Long) {

	abstract val type: ChallengeType

	abstract fun createInstance(context: Context, startAt: Long): ChallengeInstanceType

}