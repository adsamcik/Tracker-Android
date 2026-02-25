package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor

/**
 * Runtime representation of an active challenge.
 * Pairs the database entity with its processor for type-specific logic.
 * Replaces the old generic ChallengeInstance<ExtraData, Instance> hierarchy.
 */
data class ChallengeInstanceNew(
	val entity: ChallengeEntity,
	val processor: ChallengeProcessor,
) {
	val progress: Double
		get() = if (entity.requiredValue > 0.0) {
			(entity.currentValue / entity.requiredValue).coerceIn(0.0, 1.0)
		} else {
			0.0
		}

	val isCompleted: Boolean get() = entity.isCompleted

	val isExpired: Boolean get() = entity.endTime <= System.currentTimeMillis()

	fun getTitle(context: Context): String = context.getString(processor.titleRes)

	fun getDescription(context: Context): String = processor.formatDescription(context, entity)
}
