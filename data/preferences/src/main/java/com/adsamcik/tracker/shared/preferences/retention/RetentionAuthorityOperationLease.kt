package com.adsamcik.tracker.shared.preferences.retention

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes collected-data lifecycle transitions with retention bootstrap, reads, and writes.
 *
 * Lock order is: any already-owned startup/configuration authority, this lease, DataStore, then
 * Room. Room transactions must use only Room state and CAS; they must never call back into
 * DataStore or acquire this lease.
 */
class RetentionAuthorityOperationLease {
	private val mutex = Mutex()

	suspend fun <T> withOperation(operation: suspend () -> T): T =
		mutex.withLock { operation() }

	suspend fun <T> withPermit(
		cancellationShielded: Boolean = false,
		operation: suspend (RetentionAuthorityOperationPermit) -> T,
	): T = mutex.withLock {
		val capability = Any()
		val context = RetentionAuthorityOperationContext(capability)
		val invoke = suspend {
			val permit = RetentionAuthorityOperationPermit(
				owner = this@RetentionAuthorityOperationLease,
				ownerJob = checkNotNull(currentCoroutineContext()[Job]) {
					"Retention authority operation requires a coroutine Job"
				},
				operationContext = context,
				capability = capability,
			)
			try {
				operation(permit)
			} finally {
				permit.invalidate()
			}
		}
		if (cancellationShielded) {
			withContext(NonCancellable + context) { invoke() }
		} else {
			withContext(context) { invoke() }
		}
	}

	internal suspend fun requireOwned(permit: RetentionAuthorityOperationPermit) {
		permit.requireActive(this)
	}
}

class RetentionAuthorityOperationPermit internal constructor(
	internal val owner: RetentionAuthorityOperationLease,
	// This rejects inherited operation context in child coroutines; capability identity is separate.
	private val ownerJob: Job,
	private val operationContext: RetentionAuthorityOperationContext,
	private val capability: Any,
) {
	private val active = AtomicBoolean(true)
	private val awaitedOperation = AtomicReference<RetentionAuthorityAwaitContext?>(null)

	suspend fun validate() {
		requireActive()
	}

	/**
	 * Runs one awaited library operation without transferring the permit to the library coroutine.
	 *
	 * Entry and return remain bound to the exact lexical owner coroutine. While the operation is
	 * suspended, Room/DataStore-created coroutines authenticate with the private awaited capability
	 * instead of a Job identity. Only one such operation may be active for this permit.
	 */
	suspend fun <T> awaitOwned(
		operation: suspend () -> T,
	): T {
		requireOwnerEntry()
		val awaited = RetentionAuthorityAwaitContext(capability)
		check(awaitedOperation.compareAndSet(null, awaited)) {
			"Retention authority operation already owns an awaited library call"
		}
		return try {
			val invoke = suspend {
				requireAwaited(awaited)
				operation().also { requireAwaited(awaited) }
			}
			val result = withContext(awaited) { invoke() }
			requireOwnerEntry()
			result
		} finally {
			awaited.invalidate()
			awaitedOperation.compareAndSet(awaited, null)
		}
	}

	internal suspend fun requireActive(expectedOwner: RetentionAuthorityOperationLease = owner) {
		require(owner === expectedOwner) {
			"Retention authority operation permit belongs to another lifecycle boundary"
		}
		val current = currentCoroutineContext()
		require(
			current[RetentionAuthorityOperationContext] === operationContext &&
				operationContext.owns(capability),
		) {
			"Retention authority operation permit lost its private operation capability"
		}
		require(active.get()) {
			"Retention authority operation permit escaped its active lexical scope"
		}
		val awaited = current[RetentionAuthorityAwaitContext]
		if (awaited == null) {
			require(current[Job] === ownerJob) {
				"Retention authority operation permit transferred outside its exact owner coroutine"
			}
		} else {
			requireAwaited(awaited)
		}
	}

	internal fun invalidate() {
		active.set(false)
		awaitedOperation.getAndSet(null)?.invalidate()
	}

	private suspend fun requireOwnerEntry() {
		requireActive(owner)
		require(currentCoroutineContext()[RetentionAuthorityAwaitContext] == null) {
			"Retention authority awaited operations cannot be nested or transferred"
		}
	}

	private suspend fun requireAwaited(expected: RetentionAuthorityAwaitContext) {
		val current = currentCoroutineContext()
		require(active.get()) {
			"Retention authority operation permit escaped its active lexical scope"
		}
		require(
			current[RetentionAuthorityOperationContext] === operationContext &&
				operationContext.owns(capability) &&
				current[RetentionAuthorityAwaitContext] === expected &&
				expected.capability === capability &&
				expected.isActive &&
				awaitedOperation.get() === expected,
		) {
			"Retention authority awaited capability is no longer owned by this operation"
		}
	}
}

internal class RetentionAuthorityOperationContext(
	private val capability: Any,
) : AbstractCoroutineContextElement(Key) {
	fun owns(candidate: Any): Boolean = capability === candidate

	companion object Key : CoroutineContext.Key<RetentionAuthorityOperationContext>
}

private class RetentionAuthorityAwaitContext(
	val capability: Any,
) : AbstractCoroutineContextElement(Key) {
	private val active = AtomicBoolean(true)

	val isActive: Boolean
		get() = active.get()

	fun invalidate() {
		active.set(false)
	}

	companion object Key : CoroutineContext.Key<RetentionAuthorityAwaitContext>
}
