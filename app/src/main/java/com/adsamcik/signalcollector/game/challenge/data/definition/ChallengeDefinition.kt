package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.game.challenge.data.instance.ChallengeInstance

/**
 * Definition containing basic data for each challenge
 */
abstract class ChallengeDefinition<ChallengeType : ChallengeInstance<*>>(
		@StringRes val titleRes: Int,
		@StringRes val descriptionRes: Int,
		val defaultDuration: Long) {

	abstract val name: String

	abstract fun createInstance(context: Context, startAt: Long): ChallengeType

}