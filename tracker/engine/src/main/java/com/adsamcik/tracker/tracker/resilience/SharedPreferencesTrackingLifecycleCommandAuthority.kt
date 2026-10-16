package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.startup.TrackingCollectionResumeBoundary
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.base.time.AndroidBootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.TRACKING_TURN_OFF_EXIT_PREFIX
import com.adsamcik.tracker.tracker.api.ManualTrackingStartContinuation
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
	private val processIncarnationIdProvider: ProcessIncarnationIdProvider = ProcessIncarnationIdProvider(),
) : TrackingLifecycleCommandAuthority {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val commandGate = Mutex()
	@Volatile private var commandState = readPersistedState()
	private val acceptedStarts = MutableStateFlow(commandState.latestAcceptedStartGeneration)
	override val acceptedStartGeneration = acceptedStarts.asStateFlow()

	override val pendingCollectionResumeExitId: String?
		get() = commandState.handledUserExitId.takeIf { commandState.collectionResumeRequired }
	override val collectionResumeBoundary: TrackingCollectionResumeBoundary?
		get() = commandState.collectionResumeBoundary
	override val pendingManualStart: ManualTrackingStartContinuation?
		get() = commandState.pendingManualStart

	override suspend fun reserveManualStartIfGeneration(
		expectedGeneration: Long,
		selection: ManualTrackingStartSelection,
	): ManualTrackingStartContinuation? = commandGate.withLock {
		if (commandState.lastGeneration != expectedGeneration) {
			return@withLock null
		}
		val updated = commandState.issueManualStart(selection)
		updated.pendingManualStart.takeIf { writeState(updated) }
	}

	override suspend fun consumeManualStart(
		generation: Long,
		selection: ManualTrackingStartSelection,
	): TrackingStartCommand? = commandGate.withLock {
		if (!commandState.isPendingManualStart(generation, selection)) {
			return@withLock null
		}
		val pending = requireNotNull(commandState.pendingManualStart)
		pending.command.takeIf { writeState(commandState.copy(pendingManualStart = null)) }
	}

	override suspend fun cancelManualStart(generation: Long): Boolean = commandGate.withLock {
		if (commandState.pendingManualStart?.command?.generation != generation) {
			return@withLock false
		}
		writeState(commandState.copy(pendingManualStart = null))
	}

	override suspend fun recordUserRequestedExit(exitId: String): Boolean = commandGate.withLock {
		val updated = commandState.observeUserExit(exitId)
		updated == commandState || writeState(updated)
	}

	override suspend fun reserveTurnOff(
		exitId: String,
		requestedAtEpochMs: Long,
		requestedElapsedRealtimeNanos: Long,
		expectedStartGeneration: Long?,
		fenceSources: suspend () -> Unit,
	): TrackingTurnOffReservation? = commandGate.withLock {
		require(exitId.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX))
		require(requestedAtEpochMs >= 0L && requestedElapsedRealtimeNanos >= 0L)
		if (expectedStartGeneration != null &&
			(commandState.lastGeneration != expectedStartGeneration ||
				commandState.latestStartGeneration != expectedStartGeneration ||
				commandState.collectionResumeRequired)
		) {
			return@withLock null
		}
		withContext(NonCancellable) {
			val stopped = commandState.observeUserExit(exitId)
			val (next, command) = stopped.issueStop(
				TrackingStopCandidateReason.EXPLICIT_REQUEST,
				requestedAtEpochMs,
				bootClockDomainProvider.current(),
				requestedElapsedRealtimeNanos,
			)
			// One committed preference edit is both the Off latch and actionable STOP.
			// A crash before the Room fence is repaired from this command on startup.
			if (!writeState(next)) {
				return@withContext null
			}
			try {
				fenceSources()
			} catch (_: Exception) {
				// The post-STOP owner closure repeats this idempotent Room fence.
			}
			TrackingTurnOffReservation(exitId, command)
		}
	}

	override suspend fun withPendingTurnOff(
		exitId: String,
		closeOwners: suspend () -> Boolean,
	): Boolean? = commandGate.withLock {
		val matchesExit = exitId.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX) &&
			commandState.handledUserExitId == exitId
		if (!matchesExit || !commandState.collectionResumeRequired ||
			commandState.pendingCollectionStartGeneration != null ||
			commandState.latestUnhandledStop() != null
		) {
			return@withLock null
		}
		withContext(NonCancellable) { closeOwners() }
	}

	override suspend fun resumeCollectionAfterUserExit(expectedExitId: String): Boolean = commandGate.withLock {
		if (expectedExitId.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX)) {
			return@withLock false
		}
		if (commandState.handledUserExitId != expectedExitId) {
			return@withLock false
		}
		val updated = commandState.resumeCollection(expectedExitId, TrackingCollectionResumeBoundary(
			System.currentTimeMillis(), bootClockDomainProvider.current(), SystemClock.elapsedRealtimeNanos()))
		updated == commandState || writeState(updated)
	}

	override fun isPendingCollectionStart(command: TrackingStartCommand): Boolean =
		commandState.isPendingCollectionStart(command)

	override suspend fun prepareCollectionResumeForManualStart(
		expectedExitId: String,
		command: TrackingStartCommand,
	): Boolean = commandGate.withLock {
		val updated = commandState.prepareCollectionResumeForStart(expectedExitId, command)
		updated.isPendingCollectionStart(command) && (updated == commandState || writeState(updated))
	}

	override suspend fun cancelPendingCollectionStart(generation: Long): Boolean = commandGate.withLock {
		if (commandState.pendingCollectionStartGeneration != generation) {
			return@withLock false
		}
		val revoked = commandState.copy(pendingCollectionStartGeneration = null)
		try { writeState(revoked) } finally {
			// Revoking this process's delivery exemption is safe even when cleanup cannot save.
			commandState = revoked
		}
	}

	override suspend fun reserveStart(): TrackingStartCommand? = commandGate.withLock {
		val (updated, command) = commandState.issueStart()
		command.takeIf { writeState(updated) }
	}

	override fun latestCommandGeneration(): Long = commandState.lastGeneration

	override suspend fun reserveStartIfGeneration(
		expectedGeneration: Long,
	): TrackingStartCommand? = commandGate.withLock {
		if (commandState.lastGeneration != expectedGeneration) {
			return@withLock null
		}
		val (updated, command) = commandState.issueStart()
		command.takeIf { writeState(updated) }
	}

	override suspend fun reserveStartIfGeneration(
		expectedGeneration: Long,
		automaticTrigger: AutomaticTrackingStartTrigger,
	): TrackingStartCommand? = commandGate.withLock {
		val current = commandState
		if (current.lastGeneration != expectedGeneration || current.collectionResumeRequired) {
			return@withLock null
		}
		val bootId = bootClockDomainProvider.current()
		if (automaticTrigger.bootId != bootId) {
			return@withLock null
		}
		if (current.latestStopGeneration > 0L) {
			val stopBoot = current.latestStopRequestedBootId ?: return@withLock null
			val stopElapsed = current.latestStopRequestedElapsedRealtimeNanos ?: return@withLock null
			// Elapsed counters in distinct boots cannot be compared. Current-boot registration and
			// automation authority independently qualify a new-boot trigger; Off still blocks above.
			if (stopBoot == bootId && automaticTrigger.observedElapsedRealtimeNanos <= stopElapsed) {
				return@withLock null
			}
		}
		val (updated, command) = current.issueStart()
		command.takeIf { writeState(updated) }
	}

	override suspend fun reserveRedeliveryRecoveryStart(
		redeliveredCommand: TrackingStartCommand,
	): TrackingRedeliveryRecoveryReservation = commandGate.withLock {
		if (commandState.collectionResumeRequired) {
			return@withLock TrackingRedeliveryRecoveryReservation.Stale
		}
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

	override fun isAcceptedStart(command: TrackingStartCommand): Boolean =
		commandState.latestAcceptedStartGeneration == command.generation

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
				if (commandState.collectionResumeRequired && !commandState.isPendingCollectionStart(command)) {
					return@withLock LockedTrackingStartResult.Stale
				}
				val value = action()
				currentCoroutineContext().ensureActive()
				if (acceptWhen(value)) {
					publishAcceptedStart(command)
				}
				LockedTrackingStartResult.Executed(value)
			}
			TrackingStartCommandDisposition.Stale -> LockedTrackingStartResult.Stale
			is TrackingStartCommandDisposition.BlockedByStop ->
				LockedTrackingStartResult.BlockedByStop(disposition.stop)
		}
	}

	private suspend fun publishAcceptedStart(command: TrackingStartCommand) {
		val accepted = commandState.acceptStart(command,
			if (commandState.isPendingCollectionStart(command)) {
				TrackingCollectionResumeBoundary(
				System.currentTimeMillis(), bootClockDomainProvider.current(), SystemClock.elapsedRealtimeNanos(),
			)
			} else {
				null
			})
		if (accepted != commandState) {
			val stopped = commandState.copy(pendingCollectionStartGeneration = null)
			try {
				check(writeState(accepted)) { "Accepted START was not durably published" }
			} catch (
				// A failed preference publication restores the same stopped command before rethrowing.
				@Suppress("TooGenericExceptionCaught") failure: Exception
			) {
				// A failed commit may have updated the preference cache. Restore the same
				// stopped state, without reserving another command or touching a successor.
				try { writeState(stopped) } catch (
					// Cleanup errors are attached to the failed publication; its failure remains primary.
					@Suppress("TooGenericExceptionCaught") cleanup: Exception
				) {
					failure.addSuppressed(cleanup)
				} finally { commandState = stopped }
				throw failure
			}
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
			handledUserExitId = preferences.getString(KEY_HANDLED_USER_EXIT, null),
			collectionResumeRequired = preferences.getBoolean(KEY_COLLECTION_RESUME_REQUIRED, false),
			collectionResumeBoundary = persistedResumeBoundary(),
			pendingManualStart = persistedManualStart(),
			// A stored attempt alone never grants Android redelivery/process recovery permission.
			pendingCollectionStartGeneration = null,
		)
	}

	private fun persistedManualStart(): ManualTrackingStartContinuation? {
		if (preferences.getString(KEY_MANUAL_START_PROCESS, null) != processIncarnationIdProvider.current()) {
			return null
		}
		val generation = preferences.getLong(KEY_MANUAL_START_GENERATION, -1L)
		if (generation <= 0L || generation != preferences.getLong(KEY_LAST_GENERATION, 0L) ||
			generation != preferences.getLong(KEY_LATEST_START_GENERATION, 0L)
		) {
			return null
		}
		val selection = preferences.getString(KEY_MANUAL_START_SELECTION, null)
			?.let(ManualTrackingStartSelection::decode) ?: return null
		return ManualTrackingStartContinuation(TrackingStartCommand(generation), selection)
	}

	private fun persistedResumeBoundary(): TrackingCollectionResumeBoundary? {
		val boot = preferences.getString(KEY_RESUME_BOOT, null)?.takeIf(String::isNotBlank) ?: return null
		val wall = preferences.getLong(KEY_RESUME_WALL, -1L)
		val elapsed = preferences.getLong(KEY_RESUME_ELAPSED, -1L)
		return if (wall >= 0L && elapsed >= 0L) {
			TrackingCollectionResumeBoundary(wall, boot, elapsed)
		} else {
			null
		}
	}

	private fun persistedStopClock(): Pair<String, Long>? {
		val bootId = preferences.getString(KEY_LATEST_STOP_REQUESTED_BOOT_ID, null)
			?.takeIf(String::isNotBlank) ?: return null
		if (!preferences.contains(KEY_LATEST_STOP_REQUESTED_ELAPSED_NANOS)) {
			return null
		}
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
				.putString(KEY_HANDLED_USER_EXIT, state.handledUserExitId)
				.putBoolean(KEY_COLLECTION_RESUME_REQUIRED, state.collectionResumeRequired)
			state.pendingCollectionStartGeneration?.let { editor.putLong(KEY_PENDING_COLLECTION_START_GENERATION, it) }
				?: editor.remove(KEY_PENDING_COLLECTION_START_GENERATION)
			val pending = state.pendingManualStart
			if (pending == null) {
				editor.remove(KEY_MANUAL_START_GENERATION).remove(KEY_MANUAL_START_SELECTION).remove(KEY_MANUAL_START_PROCESS)
			} else {
				editor.putLong(KEY_MANUAL_START_GENERATION, pending.command.generation)
					.putString(KEY_MANUAL_START_SELECTION, pending.selection.encode())
					.putString(KEY_MANUAL_START_PROCESS, processIncarnationIdProvider.current())
			}
			state.collectionResumeBoundary?.let { boundary ->
				editor.putString(KEY_RESUME_BOOT, boundary.bootId)
					.putLong(KEY_RESUME_WALL, boundary.wallTimeMs)
					.putLong(KEY_RESUME_ELAPSED, boundary.elapsedRealtimeNanos)
			} ?: editor.remove(KEY_RESUME_BOOT).remove(KEY_RESUME_WALL).remove(KEY_RESUME_ELAPSED)
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
			if (committed) {
				commandState = state
				acceptedStarts.value = state.latestAcceptedStartGeneration
			}
			committed
		}

	private companion object {
		const val PREFERENCES_NAME = "tracking_lifecycle_commands"
		const val KEY_PENDING_COLLECTION_START_GENERATION = "pending_collection_start_generation"
		const val KEY_MANUAL_START_GENERATION = "manual_start_generation"
		const val KEY_MANUAL_START_SELECTION = "manual_start_selection"
		const val KEY_MANUAL_START_PROCESS = "manual_start_process"
		const val KEY_HANDLED_USER_EXIT = "handled_user_exit"
		const val KEY_COLLECTION_RESUME_REQUIRED = "collection_resume_required"
		const val KEY_RESUME_BOOT = "collection_resume_boot"
		const val KEY_RESUME_WALL = "collection_resume_wall"
		const val KEY_RESUME_ELAPSED = "collection_resume_elapsed"
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
