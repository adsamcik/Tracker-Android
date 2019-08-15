package com.adsamcik.tracker.game.challenge.database.typeconverter

import androidx.room.TypeConverter
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType

class ChallengeDifficultyTypeConverter {
	@TypeConverter
	fun toInt(difficulty: ChallengeDifficulty) = difficulty.ordinal

	@TypeConverter
	fun difficultyToEnum(ordinal: Int): ChallengeDifficulty = ChallengeDifficulty.values()[ordinal]

	@TypeConverter
	fun toInt(type: ChallengeType) = type.ordinal

	@TypeConverter
	fun typeToEnum(ordinal: Int): ChallengeType = ChallengeType.values()[ordinal]
}
