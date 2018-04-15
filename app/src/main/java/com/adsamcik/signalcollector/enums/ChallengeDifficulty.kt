package com.adsamcik.signalcollector.enums

import android.support.annotation.IntDef

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
