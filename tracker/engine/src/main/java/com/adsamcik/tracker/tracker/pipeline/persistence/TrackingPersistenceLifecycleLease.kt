package com.adsamcik.tracker.tracker.pipeline.persistence

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class TrackingPersistenceLifecycleMode {
	LIVE_PIPELINE,
	OFFLINE_LOCATION_RECOVERY,
	PRESSURE_WRITER_TRANSITION,
	LEGACY_SOURCE_WRITER_TRANSITION,
}

internal interface TrackingPersistenceLifecyclePermit {
	val mode: TrackingPersistenceLifecycleMode
	fun release()
}

internal interface TrackingPersistenceLifecycleLease {
	suspend fun acquireLivePipeline(): TrackingPersistenceLifecyclePermit

	/** Explicit ownership for callers that may need to retain the permit across cleanup retries. */
	suspend fun acquireOfflineLocationRecovery(): TrackingPersistenceLifecyclePermit

	/** A drain must not hold its serializer waiting for a live pipeline it will later stop. */
	suspend fun tryAcquireOfflineLocationRecovery(): TrackingPersistenceLifecyclePermit?

	suspend fun <T> withOfflineLocationRecovery(block: suspend () -> T): T

	/** Setup must not wait behind an offline cleanup permit retained for a later recovery attempt. */
	suspend fun <T : Any> tryWithOfflineLocationRecovery(block: suspend () -> T): T?

	suspend fun <T> withPressureWriterTransition(block: suspend () -> T): T

	suspend fun <T> withLegacySourceWriterTransition(block: suspend () -> T): T
}

@Singleton
internal class ExclusiveTrackingPersistenceLifecycleLease @Inject constructor() :
	TrackingPersistenceLifecycleLease {
	private val mutex = Mutex()

	override suspend fun acquireLivePipeline(): TrackingPersistenceLifecyclePermit =
		acquire(TrackingPersistenceLifecycleMode.LIVE_PIPELINE)

	override suspend fun acquireOfflineLocationRecovery(): TrackingPersistenceLifecyclePermit =
		acquire(TrackingPersistenceLifecycleMode.OFFLINE_LOCATION_RECOVERY)

	override suspend fun tryAcquireOfflineLocationRecovery(): TrackingPersistenceLifecyclePermit? {
		kotlinx.coroutines.currentCoroutineContext().ensureActive()
		val owner = Any()
		return if (mutex.tryLock(owner)) {
			Permit(TrackingPersistenceLifecycleMode.OFFLINE_LOCATION_RECOVERY, mutex, owner)
		} else {
			null
		}
	}

	override suspend fun <T> withOfflineLocationRecovery(block: suspend () -> T): T =
		withPermit(TrackingPersistenceLifecycleMode.OFFLINE_LOCATION_RECOVERY, block)

	override suspend fun <T : Any> tryWithOfflineLocationRecovery(block: suspend () -> T): T? {
		val permit = tryAcquireOfflineLocationRecovery() ?: return null
		return try { block() } finally {
			withContext(NonCancellable) { permit.release() }
		}
	}

	override suspend fun <T> withPressureWriterTransition(block: suspend () -> T): T =
		withPermit(TrackingPersistenceLifecycleMode.PRESSURE_WRITER_TRANSITION, block)

	override suspend fun <T> withLegacySourceWriterTransition(block: suspend () -> T): T =
		withPermit(TrackingPersistenceLifecycleMode.LEGACY_SOURCE_WRITER_TRANSITION, block)

	private suspend fun acquire(
		mode: TrackingPersistenceLifecycleMode,
	): TrackingPersistenceLifecyclePermit {
		val owner = Any()
		mutex.lock(owner)
		return Permit(mode, mutex, owner)
	}

	private suspend fun <T> withPermit(
		mode: TrackingPersistenceLifecycleMode,
		block: suspend () -> T,
	): T {
		val permit = acquire(mode)
		return try {
			block()
		} finally {
			withContext(NonCancellable) {
				permit.release()
			}
		}
	}

	private class Permit(
		override val mode: TrackingPersistenceLifecycleMode,
		private val mutex: Mutex,
		private val owner: Any,
	) : TrackingPersistenceLifecyclePermit {
		private var released = false

		override fun release() {
			synchronized(this) {
				if (released) {
					return
				}
				released = true
				mutex.unlock(owner)
			}
		}
	}
}
