package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.AndroidBootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small durable command store; mutations are cancellable while waiting and complete on IO once held. */
@Singleton
class SharedPreferencesTrackingLifecycleCommandAuthority @Inject constructor(
	@ApplicationContext context: Context,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val bootClockDomainProvider: BootClockDomainProvider =
		AndroidBootClockDomainProvider(context),
) : TrackingLifecycleCommandAuthority {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val commandGate = Mutex()
	@Volatile private var commandState = readPersistedState()

	override suspend fun reserveStart(): TrackingStartCommand? = commandGate.withLock {
		val (updated, command) = commandState.issueStart()
		command.takeIf { writeState(updated) }
	}

	override suspend fun reserveRedeliveryRecoveryStart(
		redeliveredCommand: TrackingStartCommand,
	): TrackingRedeliveryRecoveryReservation = commandGate.withLock {
		when (val disposition = commandState.resolveStart(redeliveredCommand)) {
			TrackingStartCommandDisposition.Allowed -> {
				val (updated, recoveryCommand) = commandState.issueStart()
				if (writeState(updated)) {
					TrackingRedeliveryRecoveryReservation.Reserved(recoveryCommand)
				} else {
					TrackingRedeliveryRecoveryReservation.Stale
				}
			}
			TrackingStartCommandDisposition.Stale -> TrackingRedeliveryRecoveryReservation.Stale
			is TrackingStartCommandDisposition.BlockedByStop ->
				TrackingRedeliveryRecoveryReservation.BlockedByStop(disposition.stop)
		}
	}

	override suspend fun reserveStop(
		reason: TrackingStopCandidateReason,
		requestedAtEpochMs: Long,
	): TrackingStopCommand? = commandGate.withLock {
		val (updated, command) = commandState.issueStop(
			reason = reason,
			requestedAtEpochMs = requestedAtEpochMs,
		)
		command.takeIf { writeState(updated) }
	}

	override suspend fun reserveStop(
		reason: TrackingStopCandidateReason,
		requestedAtEpochMs: Long,
		requestedElapsedRealtimeNanos: Long,
	): TrackingStopCommand? = commandGate.withLock {
		val (updated, command) = commandState.issueStop(
			reason = reason,
			requestedAtEpochMs = requestedAtEpochMs,
			requestedBootId = bootClockDomainProvider.current(),
			requestedElapsedRealtimeNanos = requestedElapsedRealtimeNanos,
		)
		command.takeIf { writeState(updated) }
	}

	override fun resolveStart(command: TrackingStartCommand): TrackingStartCommandDisposition =
		commandState.resolveStart(command)

	override suspend fun <T> withCurrentStart(
		command: TrackingStartCommand,
		action: suspend () -> T,
	): LockedTrackingStartResult<T> = withCurrentStart(command, { false }, action)

	override suspend fun <T> withCurrentStart(
		command: TrackingStartCommand,
		acceptWhen: (T) -> Boolean,
		action: suspend () -> T,
	): LockedTrackingStartResult<T> = commandGate.withLock {
		when (val disposition = commandState.resolveStart(command)) {
			TrackingStartCommandDisposition.Allowed -> {
				val value = action()
				if (acceptWhen(value)) {
					val accepted = commandState.acceptStart(command)
					if (accepted != commandState) {
						check(writeState(accepted)) { "Accepted START was not durably published" }
					}
				}
				LockedTrackingStartResult.Executed(value)
			}
			TrackingStartCommandDisposition.Stale -> LockedTrackingStartResult.Stale
			is TrackingStartCommandDisposition.BlockedByStop ->
				LockedTrackingStartResult.BlockedByStop(disposition.stop)
		}
	}

	override suspend fun runWithHandledStopGeneration(
		expectedStopGeneration: Long,
		action: suspend () -> Unit,
	): Boolean = commandGate.withLock {
		if (commandState.latestStopGeneration != expectedStopGeneration ||
			commandState.latestUnhandledStop() != null
		) {
			false
		} else {
			action()
			true
		}
	}

	override fun isStopCurrent(command: TrackingStopCommand): Boolean =
		commandState.isCurrent(command)

	override fun isStopActionable(command: TrackingStopCommand): Boolean =
		commandState.isActionable(command)

	override fun latestStopGeneration(): Long = commandState.latestStopGeneration

	override fun latestUnhandledStop(): TrackingStopCommand? =
		commandState.latestUnhandledStop()

	override suspend fun <T> completeActionableStop(
		command: TrackingStopCommand,
		action: suspend () -> TrackingStopActionResult<T>,
	): LockedTrackingStopResult<T> = commandGate.withLock {
		if (!commandState.isActionable(command)) {
			return@withLock LockedTrackingStopResult.Superseded
		}
		when (val result = action()) {
			is TrackingStopActionResult.StillActionable ->
				LockedTrackingStopResult.StillActionable(result.value)
			is TrackingStopActionResult.Terminal -> {
				val updated = commandState.markHandled(command)
				check(updated != commandState && writeState(updated)) {
					"Terminal STOP was not durably marked handled"
				}
				LockedTrackingStopResult.Handled(result.value)
			}
		}
	}

	override suspend fun markStopHandled(command: TrackingStopCommand): Boolean = commandGate.withLock {
		val current = commandState
		val updated = current.markHandled(command)
		updated != current && writeState(updated)
	}

	private fun readPersistedState(): TrackingLifecycleCommandState {
		val stopClock = persistedStopClock()
		return TrackingLifecycleCommandState(
			lastGeneration = preferences.getLong(KEY_LAST_GENERATION, 0L),
			latestStartGeneration = preferences.getLong(KEY_LATEST_START_GENERATION, 0L),
			latestAcceptedStartGeneration =
				preferences.getLong(KEY_LATEST_ACCEPTED_START_GENERATION, 0L),
			latestStopGeneration = preferences.getLong(KEY_LATEST_STOP_GENERATION, 0L),
			handledStopGeneration = preferences.getLong(KEY_HANDLED_STOP_GENERATION, 0L),
			latestStopReason = preferences.getString(KEY_LATEST_STOP_REASON, null)
				?.let { name -> TrackingStopCandidateReason.entries.firstOrNull { it.name == name } }
				?: TrackingStopCandidateReason.UNKNOWN,
			latestStopRequestedAtEpochMs =
				preferences.getLong(KEY_LATEST_STOP_REQUESTED_AT_MS, 0L),
			latestStopRequestedBootId = stopClock?.first,
			latestStopRequestedElapsedRealtimeNanos = stopClock?.second,
		)
	}

	private fun persistedStopClock(): Pair<String, Long>? {
		val bootId = preferences.getString(KEY_LATEST_STOP_REQUESTED_BOOT_ID, null)
			?.takeIf(String::isNotBlank) ?: return null
		if (!preferences.contains(KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS)) return null
		val elapsedRealtimeNanos =
			preferences.getLong(KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS, -1L)
		return elapsedRealtimeNanos.takeIf { it >= 0L }?.let { bootId to it }
	}

	private suspend fun writeState(state: TrackingLifecycleCommandState): Boolean =
		withContext(NonCancellable + ioDispatcher) {
			val editor = preferences.edit()
				.putLong(KEY_LAST_GENERATION, state.lastGeneration)
				.putLong(KEY_LATEST_START_GENERATION, state.latestStartGeneration)
				.putLong(
					KEY_LATEST_ACCEPTED_START_GENERATION,
					state.latestAcceptedStartGeneration,
				)
				.putLong(KEY_LATEST_STOP_GENERATION, state.latestStopGeneration)
				.putLong(KEY_HANDLED_STOP_GENERATION, state.handledStopGeneration)
				.putString(KEY_LATEST_STOP_REASON, state.latestStopReason.name)
				.putLong(KEY_LATEST_STOP_REQUESTED_AT_MS, state.latestStopRequestedAtEpochMs)
			if (state.latestStopRequestedBootId == null) {
				editor.remove(KEY_LATEST_STOP_REQUESTED_BOOT_ID)
					.remove(KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS)
			} else {
				editor.putString(
					KEY_LATEST_STOP_REQUESTED_BOOT_ID,
					state.latestStopRequestedBootId,
				).putLong(
					KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS,
					requireNotNull(state.latestStopRequestedElapsedRealtimeNanos),
				)
			}
			val committed = editor.commit()
			if (committed) commandState = state
			committed
		}

	private companion object {
		const val PREFERENCES_NAME = "tracking_lifecycle_commands"
		const val KEY_LAST_GENERATION = "last_generation"
		const val KEY_LATEST_START_GENERATION = "latest_start_generation"
		const val KEY_LATEST_ACCEPTED_START_GENERATION = "latest_accepted_start_generation"
		const val KEY_LATEST_STOP_GENERATION = "latest_stop_generation"
		const val KEY_HANDLED_STOP_GENERATION = "handled_stop_generation"
		const val KEY_LATEST_STOP_REASON = "latest_stop_reason"
		const val KEY_LATEST_STOP_REQUESTED_AT_MS = "latest_stop_requested_at_ms"
		const val KEY_LATEST_STOP_REQUESTED_BOOT_ID = "latest_stop_requested_boot_id"
		const val KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS =
			"latest_stop_requested_elapsed_realtime_nanos"
	}
}
