package com.adsamcik.tracker.tracker.pipeline.persistence

import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import kotlinx.coroutines.withContext

/**
 * Durable write-ahead buffer for [TrackingSignal]s.
 *
 * Signals are first staged in a fast in-memory list (via [stage]).
 * Periodically, [checkpoint] serializes them to the `pending_signal`
 * Room table so they survive process death. [drainBatch] reads and
 * deletes entries for replay into the destination tables.
 *
 * Thread safety:
 * - [stage] and [checkpoint] synchronize on [stagingLock].
 * - [drainBatch], [hasPendingEntries], and [clear] are pure DAO
 *   calls and do not touch the staging list.
 */
internal class DurableSignalBuffer(
	private val pendingSignalDao: PendingSignalDao,
	private val dispatchers: DispatchersProvider,
) {
	private val stagingLock = Any()
	private val staging = mutableListOf<TrackingSignal>()
	private var sessionId: Long = 0L

	fun setSessionId(id: Long) {
		sessionId = id
	}

	/**
	 * Stage a signal in the in-memory buffer. Fast, no I/O.
	 * Called from [PersistenceProcessor.onSignal] (non-suspend).
	 */
	fun stage(signal: TrackingSignal) {
		synchronized(stagingLock) {
			staging.add(signal)
		}
	}

	/** Number of signals currently in the in-memory staging area. */
	val stagingSize: Int get() = synchronized(stagingLock) { staging.size }

	/**
	 * Write all staged signals to the Room WAL table.
	 * After this returns, the signals are durable on disk.
	 */
	suspend fun checkpoint() {
		val signals: List<TrackingSignal>
		synchronized(stagingLock) {
			if (staging.isEmpty()) return
			signals = staging.toList()
			staging.clear()
		}

		withContext(dispatchers.io) {
			val now = Time.nowMillis
			val entities = signals.map { signal ->
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(signal),
					createdAt = now,
				)
			}
			pendingSignalDao.insertAll(entities)
		}
	}

	/**
	 * Read and atomically delete the oldest batch of WAL entries.
	 * Returns deserialized [TrackingSignal]s. Corrupted entries
	 * are silently skipped (logged).
	 */
	suspend fun drainBatch(limit: Int = DRAIN_BATCH_SIZE): List<TrackingSignal> =
		withContext(dispatchers.io) {
			val entities = pendingSignalDao.getOldest(sessionId, limit)
			if (entities.isEmpty()) return@withContext emptyList()

			val signals = entities.mapNotNull { entity ->
				SignalSerializer.deserialize(entity.signalJson).also {
					if (it == null) {
						Log.w(TAG, "Skipped corrupted WAL entry id=${entity.id}")
					}
				}
			}
			pendingSignalDao.deleteByIds(entities.map { it.id })
			signals
		}

	/** True when there are no more WAL entries for this session. */
	suspend fun hasPendingEntries(): Boolean = withContext(dispatchers.io) {
		pendingSignalDao.countForSession(sessionId) > 0
	}

	/** Delete all WAL entries for the current session. */
	suspend fun clear() = withContext(dispatchers.io) {
		pendingSignalDao.deleteBySession(sessionId)
	}

	companion object {
		private const val TAG = "DurableSignalBuffer"
		internal const val DRAIN_BATCH_SIZE = 100
	}
}
