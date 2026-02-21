package com.adsamcik.tracker.game.challenge.database.typeconverter

import androidx.room.TypeConverter
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty

/**
 * String-based TypeConverter for [ChallengeDifficulty].
 * Uses enum name for forward-compatible storage (not ordinal).
 * Used by the new unified challenge table.
 */
class ChallengeDifficultyStringTypeConverter {
	@TypeConverter
	fun fromDifficulty(difficulty: ChallengeDifficulty): String = difficulty.name

	@TypeConverter
	fun toDifficulty(name: String): ChallengeDifficulty = ChallengeDifficulty.valueOf(name)
}
