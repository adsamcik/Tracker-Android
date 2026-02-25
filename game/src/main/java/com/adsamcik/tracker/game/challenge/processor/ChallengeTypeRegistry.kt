package com.adsamcik.tracker.game.challenge.processor

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of all challenge processors, populated via Hilt multibinding.
 * Provides type-safe lookup and iteration.
 */
@Singleton
class ChallengeTypeRegistry @Inject constructor(
	private val processors: Map<ChallengeType, @JvmSuppressWildcards ChallengeProcessor>
) {
	/** Get processor for a specific type. */
	fun get(type: ChallengeType): ChallengeProcessor =
		processors[type] ?: error("No ChallengeProcessor registered for $type")

	/** All registered processors. */
	val all: Collection<ChallengeProcessor> get() = processors.values

	/** All registered challenge types. */
	val types: Set<ChallengeType> get() = processors.keys
}
