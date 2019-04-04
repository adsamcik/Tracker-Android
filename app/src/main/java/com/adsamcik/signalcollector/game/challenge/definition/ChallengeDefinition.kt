package com.adsamcik.signalcollector.game.challenge.definition

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.Challenge

interface ChallengeDefinition {
	@get:StringRes
	val nameRes: Int

	@get:StringRes
	val descriptionRes: Int

	fun createInstance(context: Context, difficulty: ChallengeDifficulty): Challenge
}