package com.adsamcik.tracker.activity.api.registration

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/**
 * Process-local admission and drain barrier for one physical Activity registration identity.
 *
 * The first entry for an unseen identity opens its local generation. A fence atomically closes that
 * identity to later callbacks and waits for every already-admitted permit to complete. A drained
 * non-terminal fence may be reopened explicitly when the same provider generation continues for a
 * control-only purpose. Tombstones are retained for this process's lifetime so a retired generation
 * can never become implicitly open again.
 *
 * This barrier does not replace durable admission checks. Process death terminates in-flight
 * callback work and this process-local state with it; Room owns admission state across processes.
 */
@Singleton
class ActivityCallbackAdmissionBarrier @Inject constructor() {
	private val monitor = Any()
	private val generations = mutableMapOf<ActivityRegistrationIdentity, GenerationState>()

	/** Returns a permit only while this exact registration identity is open. */
	internal fun tryEnter(identity: ActivityRegistrationIdentity): ActivityCallbackAdmissionPermit? =
		synchronized(monitor) {
			val generation = generations.getOrPut(identity) {
				GenerationState(phase = Phase.OPEN)
			}
			if (generation.phase != Phase.OPEN) return@synchronized null
			generation.inFlight += 1
			ActivityCallbackAdmissionPermit { complete(identity) }
		}

	/** Closes this identity to later entries and waits for all pre-fence permits to complete. */
	internal suspend fun fenceAndAwait(identity: ActivityRegistrationIdentity) {
		closeAndAwait(identity, terminal = false)
	}

	/**
	 * Reopens a non-terminal fence after it has drained.
	 *
	 * This is intentionally exact-identity only, for same-generation control-only continuation.
	 */
	internal fun reopen(identity: ActivityRegistrationIdentity): Boolean = synchronized(monitor) {
		val generation = generations[identity] ?: return@synchronized false
		if (generation.phase != Phase.FENCED || generation.inFlight != 0) {
			return@synchronized false
		}
		generation.phase = Phase.OPEN
		generation.drained = null
		true
	}

	/** Permanently closes this exact identity and waits for its admitted callbacks to drain. */
	internal suspend fun tombstoneAndAwait(identity: ActivityRegistrationIdentity) {
		closeAndAwait(identity, terminal = true)
	}

	private suspend fun closeAndAwait(
		identity: ActivityRegistrationIdentity,
		terminal: Boolean,
	) {
		val drained = synchronized(monitor) {
			val generation = generations.getOrPut(identity) {
				GenerationState(phase = if (terminal) Phase.TOMBSTONED else Phase.FENCED)
			}
			if (terminal) {
				generation.phase = Phase.TOMBSTONED
			} else if (generation.phase == Phase.OPEN) {
				generation.phase = Phase.FENCED
			}
			if (generation.inFlight == 0) {
				null
			} else {
				generation.drained ?: CompletableDeferred<Unit>().also { generation.drained = it }
			}
		}
		drained?.await()
	}

	private fun complete(identity: ActivityRegistrationIdentity) {
		val drained = synchronized(monitor) {
			val generation = checkNotNull(generations[identity])
			check(generation.inFlight > 0) { "Activity callback permit count underflow" }
			generation.inFlight -= 1
			if (generation.inFlight == 0 && generation.phase != Phase.OPEN) {
				generation.drained
			} else {
				null
			}
		}
		drained?.complete(Unit)
	}

	private data class GenerationState(
		var phase: Phase,
		var inFlight: Int = 0,
		var drained: CompletableDeferred<Unit>? = null,
	)

	private enum class Phase {
		OPEN,
		FENCED,
		TOMBSTONED,
	}
}

/** One explicitly completed callback admission. Completion is safe to repeat. */
internal class ActivityCallbackAdmissionPermit(
	private val onComplete: () -> Unit,
) {
	private val completed = AtomicBoolean(false)

	fun complete() {
		if (completed.compareAndSet(false, true)) onComplete()
	}
}
