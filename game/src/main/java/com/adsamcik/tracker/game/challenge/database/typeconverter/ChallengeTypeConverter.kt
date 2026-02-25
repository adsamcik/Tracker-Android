package com.adsamcik.tracker.game.challenge.database.typeconverter

import androidx.room.TypeConverter
import com.adsamcik.tracker.game.challenge.data.ChallengeType

/**
 * Room TypeConverter for [ChallengeType] using enum name for forward-compatible storage.
 */
class ChallengeTypeConverter {
	@TypeConverter
	fun fromChallengeType(type: ChallengeType): String = type.name

	@TypeConverter
	fun toChallengeType(name: String): ChallengeType = ChallengeType.valueOf(name)
}
