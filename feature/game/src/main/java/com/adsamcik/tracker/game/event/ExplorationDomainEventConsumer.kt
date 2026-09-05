package com.adsamcik.tracker.game.event

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes [DomainEvent.CellDiscovered] events and persists them as
 * [ExplorationCellEntity] rows via [ExplorationCellDao].
 *
 * This bridges the gap between the exploration processor (which discovers cells
 * during tracking) and the exploration UI/map overlay (which reads from the
 * exploration_cell table).
 *
 * Uses consumer-offset tracking for crash-safe, ordered delivery.
 *
 * On every write to `exploration_cell` or `exploration_streak`, marks the
 * corresponding table dirty in [dirtyTracker] so the achievement processor's
 * flush short-circuit doesn't skip a flush where exploration data genuinely
 * changed.
 */
@Singleton
class ExplorationDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
	private val dirtyTracker: MetricDirtyTracker,
	private val trackingStartupGate: TrackingStartupGate,
) {
	private val streakTracker = ExplorationStreakTracker()
	// Single-flight guard: this consumer is a @Singleton and processUnconsumed() can be
	// called concurrently from session-end + WorkManager catch-ups. Without a per-consumer
	// mutex, both callers fetch the same batch, both run handlers (double-counting visits +
	// duplicate streak/dirty marks), then both ack the same timestamp.
	private val processMutex = Mutex()

	/** Process any unconsumed CellDiscovered events. */
	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val expectedGeneration = trackingStartupGate.currentGeneration
			val batch = loadAcceptedBatch(expectedGeneration) ?: return@withLock
			if (batch.isEmpty()) return@withLock
			if (!applyAcceptedBatch(batch, expectedGeneration)) return@withLock
		}
	}

	private suspend fun loadAcceptedBatch(expectedGeneration: Long): List<UnconsumedEvent>? {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) return null
		return trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		}
	}

	private suspend fun applyAcceptedBatch(
		batch: List<UnconsumedEvent>,
		expectedGeneration: Long,
	): Boolean = trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
		val database = AppDatabase.database(context)
		val cellDao = database.explorationCellDao()
		val streakDao = database.explorationStreakDao()
		val dirtyTables = mutableSetOf<String>()
		val last = batch.last()

		// One transaction per BATCH instead of per event. During backlog/replay
		// (100 cell events × 100 BEGIN+COMMIT+fsync = 100-400 ms of write I/O)
		// this drops to ONE transaction. Cell/streak writes and cursor acknowledgement
		// commit or roll back together, and the startup-generation lease makes deletion
		// wait for the whole accepted batch before clearing it.
		database.withTransaction {
			batch.forEach { unconsumed ->
				val effects = handleEvent(
					event = unconsumed.event,
					cellDao = cellDao,
					streakDao = streakDao,
				)
				dirtyTables += effects.dirty
			}
			// Ack the LAST event by (timestamp, id) so a future event sharing the same
			// timestamp as our boundary doesn't get silently skipped by the next fetch.
			domainEventRepository.markBatchConsumed(
				consumerId = CONSUMER_ID,
				upToTimestamp = last.event.timestampMs,
				upToEventId = last.persistedId,
			)
		}

		// Keep the in-memory dirty handoff in the same deletion-linearized lease. It is
		// still mark-after-commit, and this accepted generation cannot hand off after deletion.
		if (dirtyTables.isNotEmpty()) dirtyTracker.markDirty(dirtyTables)
		true
	} ?: false

	/** Side effects to apply AFTER the transaction commits (mark-after-commit). */
	private data class EventEffects(
		val dirty: Set<String>,
	)

	private suspend fun handleEvent(
		event: DomainEvent,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	): EventEffects = when (event) {
		is DomainEvent.CellDiscovered -> onCellDiscovered(event, cellDao, streakDao)
		else -> EventEffects(dirty = emptySet())
	}

	private suspend fun onCellDiscovered(
		event: DomainEvent.CellDiscovered,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	): EventEffects {
		val now = event.timestampMs.raw
		val entity = ExplorationCellEntity(
			cellToken = event.cellToken,
			level = event.level,
			quality = event.quality,
			firstDiscoveredAt = now,
			lastVisitedAt = now,
			visitCount = 1,
			seasonBitmask = event.seasonBit,
			centerLatE7 = event.centerLatE7,
			centerLonE7 = event.centerLonE7,
			createdAt = now,
		)

		// Runs inside the caller's batched transaction. Recovery detection (cell row
		// committed by a prior crashed attempt) uses firstDiscoveredAt == event.ts so
		// the streak update still happens on redelivery. ExplorationStreakTracker
		// is day-idempotent so re-calling it is safe.
		val insertedId = cellDao.insert(entity)
		val isFreshInsert = insertedId != -1L
		val isRecoveredFirstDiscovery = !isFreshInsert &&
			cellDao.getByToken(event.cellToken)?.firstDiscoveredAt == now
		val wasNewCell = isFreshInsert || isRecoveredFirstDiscovery

		if (wasNewCell) {
			streakTracker.onCellDiscovered(streakDao, event, wasNewCell = true)
			return EventEffects(
				dirty = setOf(MetricKeys.TABLE_EXPLORATION_CELL, MetricKeys.TABLE_EXPLORATION_STREAK),
			)
		}

		// Cell already exists and not a recovery — just bump visit metadata.
		cellDao.updateVisit(
			token = event.cellToken,
			quality = event.quality,
			lastVisitedAt = now,
			seasonBit = event.seasonBit,
		)
		return EventEffects(
			dirty = setOf(MetricKeys.TABLE_EXPLORATION_CELL),
		)
	}

	companion object {
		const val CONSUMER_ID = "exploration-module"
	}
}
