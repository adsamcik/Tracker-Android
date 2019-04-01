package com.adsamcik.signalcollector.game.challenge

import androidx.annotation.IntDef

/**
 * Object containing annotation class and it's [IntDef] for ChallengeDifficulty
 */
object ChallengeDifficulties {
	const val UNKNOWN: Int = -1
	const val VERY_EASY: Int = 0
	const val EASY: Int = 1
	const val MEDIUM: Int = 2
	const val HARD: Int = 3
	const val VERY_HARD: Int = 4

	@IntDef(UNKNOWN, VERY_EASY, EASY, MEDIUM, HARD, VERY_HARD)
	@Retention(AnnotationRetention.SOURCE)
	annotation class ChallengeDifficulty
}
