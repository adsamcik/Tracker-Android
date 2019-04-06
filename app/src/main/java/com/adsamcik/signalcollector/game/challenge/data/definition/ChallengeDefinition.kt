package com.adsamcik.signalcollector.game.challenge.data.definition

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.game.challenge.data.instance.Challenge
import kotlin.reflect.KClass

/**
 * Definition containing basic data for each challenge
 */
abstract class ChallengeDefinition(@StringRes val nameRes: Int,
                                   @StringRes val descriptionRes: Int,
                                   val defaultDuration: Long) {

	abstract val type: KClass<*>

	abstract fun createInstance(context: Context, startAt: Long): Challenge

}