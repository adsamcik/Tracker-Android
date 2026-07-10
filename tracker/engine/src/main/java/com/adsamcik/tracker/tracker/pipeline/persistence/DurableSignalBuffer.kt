package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Durable write-ahead buffer for [TrackingSignal]s.
 *
 * Signals are first staged in a fast in-memory list (via [stage]).
 * [checkpoint] serializes them to the `pending_signal` Room table so they
 * survive process death, returning the generated row IDs. [peekBatch] reads
 * (but does NOT delete) the oldest entries so callers can persist them to the
 * destination tables and only then acknowledge the exact IDs — in a single
 * transaction — via [PendingSignalDao.deleteByIds].
 *
 * Thread safety:
 * - [stage], [stagingSize] and [checkpoint]'s staging mutation synchronize on
 *   [stagingLock].
 * - [peekBatch], [hasPendingEntries] and [clear] are DAO reads/writes and do
 *   not touch the staging list.
 */
@Singleton
class DurableSignalBuffer @Inject constructor(
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
	 * Durably write all currently-staged signals to the Room WAL table.
	 *
	 * The staged signals are **only** removed from the in-memory buffer after
	 * the insert succeeds. If the insert throws (including cancellation), the
	 * staging list is left untouched so the signals can be retried on the next
	 * checkpoint. Returns the generated WAL row IDs (empty when nothing was
	 * staged).
	 */
	suspend fun checkpoint(onCommitted: (List<Long>) -> Unit = {}): List<Long> {
		val signals: List<TrackingSignal> = synchronized(stagingLock) {
			if (staging.isEmpty()) return emptyList()
			staging.toList()
		}

		return withContext(NonCancellable + dispatchers.io) {
			val now = Time.nowMillis
			val entities = signals.map { signal ->
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(signal),
					createdAt = now,
				)
			}
			val ids = pendingSignalDao.insertAll(entities)

			// Keep the WAL commit, staging removal, and caller ID registration in
			// one non-cancellable section. This closes the post-commit cancellation
			// window that could otherwise checkpoint the same signals twice.
			synchronized(stagingLock) {
				val count = signals.size
				if (count >= staging.size) {
					staging.clear()
				} else {
					repeat(count) { staging.removeAt(0) }
				}
			}
			onCommitted(ids)

			ids
		}
	}

	/**
	 * Read the oldest batch of WAL entries **across all sessions** without
	 * deleting them.
	 *
	 * Recovery is intentionally session-agnostic: a restarted process mints a
	 * new session id, so filtering by the current session would strand rows
	 * written under the previous one. Each returned [PeekedSignal] pairs the row
	 * [PeekedSignal.id] with the deserialized [PeekedSignal.signal] (null when
	 * the row is corrupted). The caller acknowledges the exact IDs (via
	 * [PendingSignalDao.deleteByIds]) once the destination writes commit, so a
	 * failure before that leaves the rows available for retry.
	 */
	suspend fun peekBatch(limit: Int = RECOVERY_BATCH_SIZE): List<PeekedSignal> =
		withContext(dispatchers.io) {
			val entities = pendingSignalDao.getOldestAcrossSessions(limit)
			entities.map { entity ->
				PeekedSignal(
					id = entity.id,
					signal = SignalSerializer.deserialize(entity.signalJson),
				)
			}
		}

	/** True when there are still WAL entries for **any** session (recovery scope). */
	suspend fun hasPendingEntries(): Boolean = withContext(dispatchers.io) {
		pendingSignalDao.countAll() > 0
	}

	/** Delete all WAL entries for the current session. */
	suspend fun clear() = withContext(dispatchers.io) {
		pendingSignalDao.deleteBySession(sessionId)
	}

	/**
	 * A single peeked WAL row: its primary key plus the deserialized signal,
	 * or `null` [signal] when the stored JSON could not be parsed (corruption).
	 */
	data class PeekedSignal(
		val id: Long,
		val signal: TrackingSignal?,
	)

	companion object {
		internal const val RECOVERY_BATCH_SIZE = 100
	}
}
