package com.adsamcik.signalcollector.game.challenge.data.definition

import androidx.annotation.StringRes

/**
 * Definition containing basic data for each challenge
 */
open class ChallengeDefinition(@StringRes val nameRes: Int,
                               @StringRes val descriptionRes: Int,
                               val defaultDuration: Long)