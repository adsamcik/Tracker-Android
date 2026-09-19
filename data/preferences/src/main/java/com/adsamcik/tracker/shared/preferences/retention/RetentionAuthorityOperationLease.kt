package com.adsamcik.tracker.shared.preferences.retention

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes collected-data lifecycle transitions with retention bootstrap, reads, and writes.
 *
 * Lock order is: any already-owned startup/configuration authority, this lease, DataStore, then
 * Room. Room transactions must use only Room state and CAS; they must never call back into
 * DataStore or acquire this lease. A timed-out DataStore acknowledgement remains registered in the
 * application-owned completion scope, and every later lease entrant fails closed behind it. Floor
 * settlement additionally writes its durable Room journal before starting the DataStore flight so
 * a replacement process can resume the same identity.
 */
class RetentionAuthorityOperationLease(
	internal val ownedSuspensionTimeoutMs: Long = DEFAULT_OWNED_SUSPENSION_TIMEOUT_MS,
	private val completionScope: CoroutineScope =
		CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
	private val mutex = Mutex()
	private val dataStoreFlightMonitor = Any()
	private var dataStoreFlight: RetentionAuthorityDataStoreFlight? = null

	init {
		require(ownedSuspensionTimeoutMs > 0L)
	}

	internal suspend fun <T> withOperation(operation: suspend () -> T): T =
		mutex.withLock {
			awaitOutstandingDataStoreFlight()
			operation()
		}

	suspend fun <T> withPermit(
		cancellationShielded: Boolean = false,
		operation: suspend (RetentionAuthorityOperationPermit) -> T,
	): T = mutex.withLock {
		awaitOutstandingDataStoreFlight()
		val invoke = suspend {
			coroutineScope {
				val permit = RetentionAuthorityOperationPermit(
					owner = this@RetentionAuthorityOperationLease,
					ownerJob = checkNotNull(currentCoroutineContext()[Job]) {
						"Retention authority operation requires a coroutine Job"
					},
				)
				try {
					operation(permit)
				} finally {
					permit.close()
				}
			}
		}
		if (cancellationShielded) {
			withContext(NonCancellable) { invoke() }
		} else {
			invoke()
		}
	}

	internal suspend fun requireOwned(permit: RetentionAuthorityOperationPermit) {
		permit.requireActive(this)
	}

	internal suspend fun <T> commitDataStoreMutation(
		permit: RetentionAuthorityOperationPermit,
		operationIdentity: String,
		operation: suspend () -> T,
	): T {
		permit.requireActive(this)
		require(operationIdentity.isNotBlank() && operationIdentity.length <= MAX_OPERATION_ID_LENGTH)
		val flight = synchronized(dataStoreFlightMonitor) {
			check(dataStoreFlight == null) {
				"Retention authority already owns an unacknowledged DataStore mutation"
			}
			RetentionAuthorityDataStoreFlight(
				operationIdentity = operationIdentity,
				task = completionScope.async(start = CoroutineStart.UNDISPATCHED) {
					runCatching { operation() as Any? }
				},
			).also { dataStoreFlight = it }
		}
		val outcome = awaitDataStoreFlight(flight)
		permit.requireActive(this)
		@Suppress("UNCHECKED_CAST")
		return outcome.getOrThrow() as T
	}

	private suspend fun awaitOutstandingDataStoreFlight() {
		val flight = synchronized(dataStoreFlightMonitor) { dataStoreFlight } ?: return
		awaitDataStoreFlight(flight).getOrThrow()
	}

	private suspend fun awaitDataStoreFlight(
		flight: RetentionAuthorityDataStoreFlight,
	): Result<Any?> {
		val outcome = withTimeoutOrNull(ownedSuspensionTimeoutMs) {
			flight.task.await()
		} ?: if (flight.task.isCompleted) {
			flight.task.await()
		} else {
			throw RetentionAuthorityDataStoreCommitUnknownException(flight.operationIdentity)
		}
		synchronized(dataStoreFlightMonitor) {
			if (dataStoreFlight === flight) dataStoreFlight = null
		}
		return outcome
	}

	private companion object {
		const val DEFAULT_OWNED_SUSPENSION_TIMEOUT_MS = 30_000L
		const val MAX_OPERATION_ID_LENGTH = 512
	}
}

class RetentionAuthorityOperationPermit internal constructor(
	internal val owner: RetentionAuthorityOperationLease,
	private val ownerJob: Job,
) {
	private val active = AtomicBoolean(true)
	private val operationMonitor = Any()
	private var inFlightOperation: RetentionAuthorityInFlightOperation? = null

	suspend fun validate() {
		requireActive()
	}

	/**
	 * Owns one complete Room suspension from call entry through transaction return.
	 *
	 * The Room operation is awaited as a registered lease flight, so Room may use its own coroutine
	 * without receiving an inheritable authorization context or the lexical permit.
	 */
	suspend fun <T> commitRoomMutation(
		operation: suspend () -> T,
	): T = runOwnedSuspension(RetentionAuthorityOwnedSuspensionKind.ROOM, operation)

	internal suspend fun <T> commitDataStoreMutation(
		operationIdentity: String,
		operation: suspend () -> T,
	): T = owner.commitDataStoreMutation(this, operationIdentity, operation)

	internal suspend fun <T> commitDataStoreMutation(
		expectedOwner: RetentionAuthorityOperationLease,
		operationIdentity: String,
		operation: suspend () -> T,
	): T {
		require(owner === expectedOwner) {
			"Retention authority DataStore bridge belongs to another lifecycle boundary"
		}
		return commitDataStoreMutation(operationIdentity, operation)
	}

	internal suspend fun requireActive(expectedOwner: RetentionAuthorityOperationLease = owner) {
		require(owner === expectedOwner) {
			"Retention authority operation permit belongs to another lifecycle boundary"
		}
		require(active.get()) {
			"Retention authority operation permit escaped its active lexical scope"
		}
		require(currentCoroutineContext()[Job] === ownerJob) {
			"Retention authority operation permit transferred outside its exact owner coroutine"
		}
	}

	internal fun close() {
		synchronized(operationMonitor) {
			check(inFlightOperation == null) {
				"Retention authority operation ended with a privileged suspension in flight"
			}
			active.set(false)
		}
	}

	private suspend fun <T> runOwnedSuspension(
		kind: RetentionAuthorityOwnedSuspensionKind,
		operation: suspend () -> T,
	): T {
		requireActive()
		val registered = RetentionAuthorityInFlightOperation(kind)
		synchronized(operationMonitor) {
			require(active.get()) {
				"Retention authority operation permit escaped its active lexical scope"
			}
			check(inFlightOperation == null) {
				"Retention authority operation already owns a privileged suspension"
			}
			inFlightOperation = registered
		}
		return try {
			val result = try {
				withTimeout(owner.ownedSuspensionTimeoutMs) {
					operation()
				}
			} catch (_: TimeoutCancellationException) {
				throw RetentionAuthorityOwnedSuspensionTimeoutException(kind)
			}
			synchronized(operationMonitor) {
				check(active.get() && inFlightOperation === registered && registered.active.get()) {
					"Retention authority privileged suspension lost lease ownership before commit"
				}
			}
			requireActive()
			result
		} finally {
			synchronized(operationMonitor) {
				if (inFlightOperation === registered) inFlightOperation = null
				registered.active.set(false)
			}
		}
	}
}

private class RetentionAuthorityInFlightOperation(
	val kind: RetentionAuthorityOwnedSuspensionKind,
) {
	val active = AtomicBoolean(true)
}

private data class RetentionAuthorityDataStoreFlight(
	val operationIdentity: String,
	val task: Deferred<Result<Any?>>,
)

internal enum class RetentionAuthorityOwnedSuspensionKind {
	ROOM,
}

internal class RetentionAuthorityOwnedSuspensionTimeoutException(
	kind: RetentionAuthorityOwnedSuspensionKind,
) : IllegalStateException("Timed out waiting for lease-owned ${kind.name.lowercase()} commit")

class RetentionAuthorityDataStoreCommitUnknownException(
	val operationIdentity: String,
) : IllegalStateException(
	"DataStore mutation acknowledgement remains unknown for $operationIdentity",
)
