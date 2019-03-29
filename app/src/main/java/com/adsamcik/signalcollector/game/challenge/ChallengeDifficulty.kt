package com.adsamcik.signalcollector.game.challenge

import androidx.annotation.IntDef

/**
 * Object containing annotation class and it's [IntDef] for ChallengeDifficulty
 */
object ChallengeDifficulties {
    const val UNKNOWN = -1
    const val VERY_EASY = 0
    const val EASY = 1
    const val MEDIUM = 2
    const val HARD = 3
    const val VERY_HARD = 4

    @IntDef(UNKNOWN, VERY_EASY, EASY, MEDIUM, HARD, VERY_HARD)
    @Retention(AnnotationRetention.SOURCE)
    annotation class ChallengeDifficulty
}
