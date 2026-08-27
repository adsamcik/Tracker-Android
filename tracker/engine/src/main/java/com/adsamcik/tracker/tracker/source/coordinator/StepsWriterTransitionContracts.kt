package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.time.Clock

/** Outcome of an explicit Steps destination-writer transition. */
sealed interface StepsWriterTransitionResult {
	/** Transition phase associated with this outcome. */
	val phase: StepsWriterTransitionPhase

	/** A transition committed a new durable authority generation. */
	data class Applied(
		override val phase: StepsWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long,
		val cutoffOrdinal: Long?,
	) : StepsWriterTransitionResult

	/** The requested durable authority generation was already installed. */
	data class AlreadyApplied(
		override val phase: StepsWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long,
		val cutoffOrdinal: Long?,
	) : StepsWriterTransitionResult

	/** A durable precondition prevented the requested transition. */
	data class Blocked(
		override val phase: StepsWriterTransitionPhase,
		val blocker: StepsWriterTransitionBlocker,
	) : StepsWriterTransitionResult

	/** Process startup authority was unavailable for the requested transition. */
	data class StartupUnavailable(
		override val phase: StepsWriterTransitionPhase,
	) : StepsWriterTransitionResult

	/** Another lifecycle coordinator currently owns the shared transition lease. */
	data class Busy(
		override val phase: StepsWriterTransitionPhase,
	) : StepsWriterTransitionResult
}

/** Explicit phase of a Steps destination-writer transition. */
enum class StepsWriterTransitionPhase {
	ACTIVATE_CANDIDATE,
	BEGIN_CANDIDATE_ROLLBACK,
	COMPLETE_CANDIDATE_ROLLBACK,
}

/** Durable or process-local precondition that prevented a Steps writer transition. */
enum class StepsWriterTransitionBlocker {
	ROLLOUT_MISSING_OR_UNREADABLE,
	ROLLOUT_REVISION_CHANGED,
	ROLLOUT_NOT_CONTAINED,
	LIFECYCLE_NOT_IDLE,
	SERVICE_RUN_NOT_FINALIZED,
	LIFECYCLE_ACTION_NOT_TERMINAL,
	LEGACY_STEPS_WRITER_NOT_QUIESCENT,
	STEPS_COMMAND_PENDING,
	CAPTURE_CALLBACK_BARRIER_OPEN,
	DESTINATION_OWNER_MISSING,
	DESTINATION_OWNER_CONFLICT,
	DESTINATION_OWNER_CHANGED,
	BINDING_NOT_EXECUTABLE,
	ACTIVE_LANE_MISSING,
	ACTIVE_LANE_CONFLICT,
	ACTIVE_LANE_CHANGED,
	MULTIPLE_ACTIVE_LANES,
	GLOBAL_WRITER_COLLISION,
	SHADOW_CURSOR_BEHIND,
	ROLLBACK_CURSOR_BEHIND,
	SESSION_LEASE_LOST,
	REVISION_EXHAUSTED,
	OWNER_GENERATION_EXHAUSTED,
	DELETION_ROWS_REMAIN,
	POSTCONDITION_FAILED,
}

internal enum class StepsWriterTransitionCheckpoint {
	AFTER_LANE_PROMOTION,
	AFTER_OWNER_PROMOTION,
	AFTER_CANONICAL_ROLLOUT_SAVE,
	AFTER_CONTAINED_ROLLOUT_SAVE,
	AFTER_ROLLBACK_CUTOFF,
	AFTER_CANDIDATE_LANE_RETIREMENT,
	AFTER_LEGACY_OWNER_RESTORE,
	AFTER_DELETION_LANE_REARM,
	AFTER_DELETION_OWNER_REARM,
	AFTER_DELETION_ROLLOUT_REARM,
}

internal fun interface StepsWriterTransitionFaultInjector {
	fun checkpoint(checkpoint: StepsWriterTransitionCheckpoint)

	companion object {
		val NONE = StepsWriterTransitionFaultInjector { }
	}
}

internal object StepsWriterDestination {
	const val SESSION_LEASE = "tracking-session-coordinator"
	const val SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
	const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
	const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
	const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
}

internal object FixedStepsWriterTransitionClock : Clock {
	override fun currentTimeMillis(): Long = 1_000L

	override fun elapsedRealtimeNanos(): Long = 1_000L
}

internal class StepsWriterTransitionBlockedException(
	val blocker: StepsWriterTransitionBlocker,
) : RuntimeException(blocker.name)

internal fun blocked(blocker: StepsWriterTransitionBlocker): Nothing {
	throw StepsWriterTransitionBlockedException(blocker)
}

internal fun nextRolloutRevision(current: Long): Long {
	if (current == Long.MAX_VALUE) {
		blocked(StepsWriterTransitionBlocker.REVISION_EXHAUSTED)
	}
	return current + 1L
}

internal fun nextOwnerGeneration(current: Long): Long {
	if (current == Long.MAX_VALUE) {
		blocked(StepsWriterTransitionBlocker.OWNER_GENERATION_EXHAUSTED)
	}
	return current + 1L
}

internal fun successfulRevisionAfter(expected: Long): Long? = if (expected == Long.MAX_VALUE) {
	null
} else {
	expected + 1L
}
