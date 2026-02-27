package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Encapsulates all type-specific challenge logic.
 * One implementation per [ChallengeType]. Registered via Hilt multibinding.
 *
 * Adding a new challenge type requires:
 * 1. Add enum entry to [ChallengeType]
 * 2. Implement this interface
 * 3. Add @Binds @IntoMap binding in ChallengeProcessorModule
 */
interface ChallengeProcessor {
	/** The challenge type this processor handles. */
	val type: ChallengeType

	/** String resource for the challenge title. */
	@get:StringRes
	val titleRes: Int

	/** Format the challenge description using entity data. */
	fun formatDescription(context: Context, entity: ChallengeEntity): String

	/**
	 * Extract progress delta from a completed tracking session.
	 * Called on a background thread.
	 *
	 * @return The amount to add to [ChallengeEntity.currentValue]
	 */
	suspend fun extractProgress(context: Context, session: TrackerSession): Double

	/**
	 * Process a challenge entity using a completed tracking session.
	 * Default behavior is additive based on [extractProgress].
	 */
	fun processEntity(
		context: Context,
		entity: ChallengeEntity,
		session: TrackerSession,
	): ChallengeEntity {
		val delta = extractProgress(context, session)
		if (delta <= 0.0) return entity
		val updatedValue = entity.currentValue + delta
		return entity.copy(
			currentValue = updatedValue,
			isCompleted = updatedValue >= entity.requiredValue,
		)
	}

	/** Default required value for this challenge type at base difficulty. */
	val defaultRequiredValue: Double

	/** Default duration in milliseconds. */
	val defaultDurationMs: Long

	/** Minimum duration multiplier for random generation. */
	val minDurationMultiplier: Double get() = 0.25

	/** Maximum duration multiplier for random generation. */
	val maxDurationMultiplier: Double get() = 3.0
}
