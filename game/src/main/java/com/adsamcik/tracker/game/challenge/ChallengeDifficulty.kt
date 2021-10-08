package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.R

enum class ChallengeDifficulty {
	VERY_EASY {
		override val difficultyStringRes: Int = R.string.challenge_very_easy
	},
	EASY {
		override val difficultyStringRes: Int = R.string.challenge_easy
	},
	MEDIUM {
		override val difficultyStringRes: Int = R.string.challenge_medium
	},
	HARD {
		override val difficultyStringRes: Int = R.string.challenge_hard
	},
	VERY_HARD {
		override val difficultyStringRes: Int = R.string.challenge_very_hard
	};

	abstract val difficultyStringRes: Int
}

