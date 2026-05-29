package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.database.data.ChallengeDifficulty as SharedChallengeDifficulty

typealias ChallengeDifficulty = SharedChallengeDifficulty

val ChallengeDifficulty.difficultyStringRes: Int
	get() = when (this) {
		ChallengeDifficulty.VERY_EASY -> R.string.challenge_very_easy
		ChallengeDifficulty.EASY -> R.string.challenge_easy
		ChallengeDifficulty.MEDIUM -> R.string.challenge_medium
		ChallengeDifficulty.HARD -> R.string.challenge_hard
		ChallengeDifficulty.VERY_HARD -> R.string.challenge_very_hard
	}
