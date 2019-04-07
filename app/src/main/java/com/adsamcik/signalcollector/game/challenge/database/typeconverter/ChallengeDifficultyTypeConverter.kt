package com.adsamcik.signalcollector.game.challenge.database.typeconverter

import androidx.room.TypeConverter
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty

class ChallengeDifficultyTypeConverter {
	@TypeConverter
	fun toInt(difficulty: ChallengeDifficulty) = difficulty.ordinal

	@TypeConverter
	fun toEnum(ordinal: Int): ChallengeDifficulty = ChallengeDifficulty.values()[ordinal]
}