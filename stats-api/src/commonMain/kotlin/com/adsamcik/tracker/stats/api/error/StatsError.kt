package com.adsamcik.tracker.stats.api.error

/**
 * Typed error hierarchy for stats operations.
 * Used with Arrow's Either<StatsError, T> for compile-time exhaustive error handling.
 */
sealed interface StatsError {
	val message: String

	data class DatabaseError(
		override val message: String,
		val cause: Throwable? = null,
	) : StatsError

	data class ProcessorError(
		override val message: String,
		val processorId: String,
	) : StatsError

	data class CheckpointError(
		override val message: String,
	) : StatsError

	data class ValidationError(
		override val message: String,
	) : StatsError

	data class NotFound(
		override val message: String,
		val entityType: String,
		val id: String,
	) : StatsError
}
