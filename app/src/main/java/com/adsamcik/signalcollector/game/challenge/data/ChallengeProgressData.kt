package com.adsamcik.signalcollector.game.challenge.data

import com.adsamcik.signalcollector.tracker.data.Location

data class ChallengeProgressData(val completed: Boolean,
                                 val integerData: Collection<Int> = listOf(),
                                 val floatingPointData: Collection<Double> = listOf(),
                                 val location: Location? = null)