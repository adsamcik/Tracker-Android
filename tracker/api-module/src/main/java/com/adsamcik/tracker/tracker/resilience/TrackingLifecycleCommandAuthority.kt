package com.adsamcik.tracker.tracker.resilience

/** One durably ordered request to enter the tracker foreground runtime. */
data class TrackingStartCommand(
	val generation: Long,
) {
	init {
		require(generation > 0L) { "generation must be positive" }
	}
}

/** One durably ordered request to stop the tracker runtime. */
data class TrackingStopCommand(
	val generation: Long,
	val reason: TrackingStopCandidateReason,
	val requestedAtEpochMs: Long,
	val requestedBootId: String? = null,
	val requestedElapsedRealtimeNanos: Long? = null,
) {
	init {
		require(generation > 0L) { "generation must be positive" }
		require(requestedAtEpochMs >= 0L) { "requestedAtEpochMs must not be negative" }
		require((requestedBootId == null) == (requestedElapsedRealtimeNanos == null)) {
			"STOP boot identity and elapsed cutoff must either both be present or both be absent"
		}
		require(requestedBootId == null || requestedBootId.isNotBlank()) {
			"requestedBootId must not be blank"
		}
		require(requestedElapsedRealtimeNanos == null || requestedElapsedRealtimeNanos >= 0L) {
			"requestedElapsedRealtimeNanos must not be negative"
		}
	}
}

/** Compact durable state for ordering tracker starts and stops outside Room. */
data class TrackingLifecycleCommandState(
	val lastGeneration: Long = 0L,
	/** Latest START reservation. A reservation alone never supersedes a STOP. */
	val latestStartGeneration: Long = 0L,
	/** Latest START that passed foreground promotion and Room foreground acceptance. */
	val latestAcceptedStartGeneration: Long = 0L,
	val latestStopGeneration: Long = 0L,
	val handledStopGeneration: Long = 0L,
	val latestStopReason: TrackingStopCandidateReason = TrackingStopCandidateReason.UNKNOWN,
	val latestStopRequestedAtEpochMs: Long = 0L,
	val latestStopRequestedBootId: String? = null,
	val latestStopRequestedElapsedRealtimeNanos: Long? = null,
) {
	init {
		require(lastGeneration >= 0L)
		require(latestStartGeneration in 0L..lastGeneration)
		require(latestAcceptedStartGeneration in 0L..latestStartGeneration)
		require(latestStopGeneration in 0L..lastGeneration)
		require(handledStopGeneration in 0L..latestStopGeneration)
		require(latestStopRequestedAtEpochMs >= 0L)
		require(
			(latestStopRequestedBootId == null) ==
				(latestStopRequestedElapsedRealtimeNanos == null),
		)
		require(latestStopRequestedBootId == null || latestStopRequestedBootId.isNotBlank())
		require(
			latestStopRequestedElapsedRealtimeNanos == null ||
				latestStopRequestedElapsedRealtimeNanos >= 0L,
		)
	}

	fun issueStart(): Pair<TrackingLifecycleCommandState, TrackingStartCommand> {
		val next = nextGeneration()
		return copy(
			lastGeneration = next,
			latestStartGeneration = next,
		) to TrackingStartCommand(next)
	}

	fun issueStop(
		reason: TrackingStopCandidateReason,
		requestedAtEpochMs: Long,
		requestedBootId: String? = null,
		requestedElapsedRealtimeNanos: Long? = null,
	): Pair<TrackingLifecycleCommandState, TrackingStopCommand> {
		require(requestedAtEpochMs >= 0L)
		require((requestedBootId == null) == (requestedElapsedRealtimeNanos == null))
		val next = nextGeneration()
		return copy(
			lastGeneration = next,
			latestStopGeneration = next,
			latestStopReason = reason,
			latestStopRequestedAtEpochMs = requestedAtEpochMs,
			latestStopRequestedBootId = requestedBootId,
			latestStopRequestedElapsedRealtimeNanos = requestedElapsedRealtimeNanos,
		) to TrackingStopCommand(
			next,
			reason,
			requestedAtEpochMs,
			requestedBootId,
			requestedElapsedRealtimeNanos,
		)
	}

	fun resolveStart(command: TrackingStartCommand): TrackingStartCommandDisposition = when {
		command.generation > latestStartGeneration -> TrackingStartCommandDisposition.Stale
		command.generation != latestStartGeneration &&
			command.generation != latestAcceptedStartGeneration -> TrackingStartCommandDisposition.Stale
		latestStopGeneration > latestAcceptedStartGeneration &&
			handledStopGeneration < latestStopGeneration ->
			TrackingStartCommandDisposition.BlockedByStop(latestStopCommand())
		latestStopGeneration > command.generation &&
			handledStopGeneration >= latestStopGeneration -> TrackingStartCommandDisposition.Stale
		else -> TrackingStartCommandDisposition.Allowed
	}

	fun acceptStart(command: TrackingStartCommand): TrackingLifecycleCommandState {
		check(resolveStart(command) == TrackingStartCommandDisposition.Allowed) {
			"Only an allowed START can become accepted"
		}
		return if (command.generation > latestAcceptedStartGeneration) {
			copy(latestAcceptedStartGeneration = command.generation)
		} else {
			this
		}
	}

	fun isCurrent(command: TrackingStopCommand): Boolean =
		command.generation == latestStopGeneration &&
			command.generation > latestAcceptedStartGeneration &&
			command.reason == latestStopReason &&
			command.requestedAtEpochMs == latestStopRequestedAtEpochMs &&
			command.requestedBootId == latestStopRequestedBootId &&
			command.requestedElapsedRealtimeNanos == latestStopRequestedElapsedRealtimeNanos

	fun isActionable(command: TrackingStopCommand): Boolean =
		isCurrent(command) && handledStopGeneration < command.generation

	/** Reconstructs the exact current STOP only while it is still actionable. */
	fun latestUnhandledStop(): TrackingStopCommand? =
		if (
			latestStopGeneration > latestAcceptedStartGeneration &&
			handledStopGeneration < latestStopGeneration
		) {
			latestStopCommand()
		} else {
			null
		}

	fun markHandled(command: TrackingStopCommand): TrackingLifecycleCommandState =
		if (isCurrent(command) && handledStopGeneration < command.generation) {
			copy(handledStopGeneration = command.generation)
		} else {
			this
		}

	private fun latestStopCommand(): TrackingStopCommand {
		check(latestStopGeneration > 0L)
		return TrackingStopCommand(
			generation = latestStopGeneration,
			reason = latestStopReason,
			requestedAtEpochMs = latestStopRequestedAtEpochMs,
			requestedBootId = latestStopRequestedBootId,
			requestedElapsedRealtimeNanos = latestStopRequestedElapsedRealtimeNanos,
		)
	}

	private fun nextGeneration(): Long {
		check(lastGeneration < Long.MAX_VALUE) { "Tracking lifecycle command generation exhausted" }
		return lastGeneration + 1L
	}
}

sealed interface TrackingStartCommandDisposition {
	data object Allowed : TrackingStartCommandDisposition
	data object Stale : TrackingStartCommandDisposition
	data class BlockedByStop(val stop: TrackingStopCommand) : TrackingStartCommandDisposition
}

/** Atomic result of replacing an accepted Android redelivery with a new recovery command. */
sealed interface TrackingRedeliveryRecoveryReservation {
	data class Reserved(val command: TrackingStartCommand) : TrackingRedeliveryRecoveryReservation
	data object Stale : TrackingRedeliveryRecoveryReservation
	data class BlockedByStop(val stop: TrackingStopCommand) : TrackingRedeliveryRecoveryReservation
}

/** Result of an operation performed while the start/stop generation lock is held. */
sealed interface LockedTrackingStartResult<out T> {
	data class Executed<T>(val value: T) : LockedTrackingStartResult<T>
	data object Stale : LockedTrackingStartResult<Nothing>
	data class BlockedByStop(val stop: TrackingStopCommand) : LockedTrackingStartResult<Nothing>
}

/** Whether exact STOP work reached the durable boundary required to consume its command. */
sealed interface TrackingStopActionResult<out T> {
	data class Terminal<T>(val value: T) : TrackingStopActionResult<T>
	data class StillActionable<T>(val value: T) : TrackingStopActionResult<T>
}

/** Result of STOP work performed while the durable ordering gate is held. */
sealed interface LockedTrackingStopResult<out T> {
	data class Handled<T>(val value: T) : LockedTrackingStopResult<T>
	data class StillActionable<T>(val value: T) : LockedTrackingStopResult<T>
	data object Superseded : LockedTrackingStopResult<Nothing>
}

/**
 * Tracker-specific durable command fence. Implementations must commit a reservation before
 * returning it; Android service delivery always happens after that boundary.
 */
interface TrackingLifecycleCommandAuthority {
	suspend fun reserveStart(): TrackingStartCommand?

	/**
	 * Atomically validates the command carried by an Android redelivery and, only while it remains
	 * allowed, reserves a distinct command for the replacement service run. Implementations must
	 * not split validation and reservation: a STOP committed between those operations would let a
	 * passive system redelivery revive a session the user already stopped.
	 */
	suspend fun reserveRedeliveryRecoveryStart(
		redeliveredCommand: TrackingStartCommand,
	): TrackingRedeliveryRecoveryReservation

	suspend fun reserveStop(
		reason: TrackingStopCandidateReason,
		requestedAtEpochMs: Long,
	): TrackingStopCommand?

	/** Reserves a STOP with a factual monotonic cutoff in the current boot domain. */
	suspend fun reserveStop(
		reason: TrackingStopCandidateReason,
		requestedAtEpochMs: Long,
		requestedElapsedRealtimeNanos: Long,
	): TrackingStopCommand? = reserveStop(reason, requestedAtEpochMs)

	/** Lock-free immutable snapshot read for short process-local routing decisions only. */
	fun resolveStart(command: TrackingStartCommand): TrackingStartCommandDisposition

	/**
	 * Validates [command] and invokes [action] while holding the same coroutine gate used by command
	 * mutations. Android enqueue and foreground acceptance use this boundary so STOP cannot be
	 * reserved inside either critical transition.
	 */
	suspend fun <T> withCurrentStart(
		command: TrackingStartCommand,
		action: suspend () -> T,
	): LockedTrackingStartResult<T>

	/**
	 * Variant that durably accepts [command], before releasing the ordering gate, only when
	 * [acceptWhen] recognizes the action's foreground-and-Room terminal success.
	 */
	suspend fun <T> withCurrentStart(
		command: TrackingStartCommand,
		acceptWhen: (T) -> Boolean,
		action: suspend () -> T,
	): LockedTrackingStartResult<T> = withCurrentStart(command, action)

	/**
	 * Runs one short control-plane handoff only while [expectedStopGeneration] is still the latest
	 * handled STOP generation. Durable implementations must hold the same ordering gate used by
	 * [reserveStop] for the full [action], so a STOP is ordered wholly before or wholly after the
	 * handoff rather than being reserved midway through its side effects.
	 *
	 * The default preserves lightweight test authorities; production durable authorities must
	 * override it with real reservation linearization.
	 */
	suspend fun runWithHandledStopGeneration(
		expectedStopGeneration: Long,
		action: suspend () -> Unit,
	): Boolean {
		if (latestStopGeneration() != expectedStopGeneration || latestUnhandledStop() != null) {
			return false
		}
		action()
		return true
	}

	fun isStopCurrent(command: TrackingStopCommand): Boolean

	fun isStopActionable(command: TrackingStopCommand): Boolean

	/**
	 * Lock-free snapshot of the latest durable STOP generation, including a STOP that has already
	 * been handled. Process-local readiness caches use this to prove that recovery happened after
	 * every STOP rather than merely observing that no STOP is currently actionable.
	 */
	fun latestStopGeneration(): Long = latestUnhandledStop()?.generation ?: 0L

	/**
	 * Returns the exact latest STOP reconstructed from durable command state, or `null` when it was
	 * handled or a newer START superseded it.
	 */
	fun latestUnhandledStop(): TrackingStopCommand? = null

	/**
	 * Validates [command] and invokes [action] while holding the same ordering gate used by START
	 * and STOP reservations. Durable implementations must override this so a newer START cannot be
	 * reserved halfway through exact inactive-session finalization.
	 *
	 * A [TrackingStopActionResult.Terminal] action is durably marked handled before the ordering gate
	 * is released. A failed, cancelled, or [TrackingStopActionResult.StillActionable] action leaves
	 * the command available to the next startup reconciliation.
	 */
	suspend fun <T> completeActionableStop(
		command: TrackingStopCommand,
		action: suspend () -> TrackingStopActionResult<T>,
	): LockedTrackingStopResult<T> {
		if (!isStopActionable(command)) return LockedTrackingStopResult.Superseded
		return when (val result = action()) {
			is TrackingStopActionResult.StillActionable ->
				LockedTrackingStopResult.StillActionable(result.value)
			is TrackingStopActionResult.Terminal -> {
				check(markStopHandled(command)) { "Terminal STOP was not durably marked handled" }
				LockedTrackingStopResult.Handled(result.value)
			}
		}
	}

	suspend fun markStopHandled(command: TrackingStopCommand): Boolean
}
