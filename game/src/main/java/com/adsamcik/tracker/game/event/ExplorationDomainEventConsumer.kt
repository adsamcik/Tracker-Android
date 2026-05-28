package com.adsamcik.tracker.game.event

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.game.GAME_LOG_SOURCE
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
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
) {
	private val streakTracker = ExplorationStreakTracker()
	// Single-flight guard: this consumer is a @Singleton and processUnconsumed() can be
	// called concurrently from session-end + WorkManager catch-ups. Without a per-consumer
	// mutex, both callers fetch the same batch, both run handlers (double-counting visits +
	// duplicate streak/dirty marks), then both ack the same timestamp.
	private val processMutex = Mutex()

	/** Process any unconsumed CellDiscovered events. */
	suspend fun processUnconsumed() = processMutex.withLock {
		val database = AppDatabase.database(context)
		val cellDao = database.explorationCellDao()
		val streakDao = database.explorationStreakDao()

		while (true) {
			val batch = domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
			if (batch.isEmpty()) return@withLock

			// One transaction per BATCH instead of per event. During backlog/replay
			// (100 cell events × 100 BEGIN+COMMIT+fsync = 100-400 ms of write I/O)
			// this drops to ONE transaction. Atomicity per event is still preserved
			// because all cell+streak writes inside the loop roll back together if
			// any fail. We collect dirty/log effects and apply them outside the
			// transaction (mark-after-commit contract).
			val dirtyTables = mutableSetOf<String>()
			val newCellLogCount = mutableListOf<Int>() // captured event.level for new cells
			database.withTransaction {
				batch.forEach { unconsumed ->
					val effects = handleEvent(
						event = unconsumed.event,
						cellDao = cellDao,
						streakDao = streakDao,
					)
					dirtyTables += effects.dirty
					if (effects.newCellLevel != null) newCellLogCount += effects.newCellLevel
				}
			}

			if (dirtyTables.isNotEmpty()) dirtyTracker.markDirty(dirtyTables)
			newCellLogCount.forEach { level ->
				Logger.log(
					LogData(
						message = "Cell discovered: level=$level isNew=true",
						source = GAME_LOG_SOURCE,
					),
				)
			}

			// Ack the LAST event by (timestamp, id) so a future event sharing the same
			// timestamp as our boundary doesn't get silently skipped by the next fetch.
			val last = batch.last()
			domainEventRepository.markBatchConsumed(
				consumerId = CONSUMER_ID,
				upToTimestamp = last.event.timestampMs,
				upToEventId = last.persistedId,
			)
		}
	}

	/** Side effects to apply AFTER the transaction commits (mark-after-commit). */
	private data class EventEffects(
		val dirty: Set<String>,
		val newCellLevel: Int?,
	)

	private suspend fun handleEvent(
		event: DomainEvent,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	): EventEffects = when (event) {
		is DomainEvent.CellDiscovered -> onCellDiscovered(event, cellDao, streakDao)
		else -> EventEffects(dirty = emptySet(), newCellLevel = null)
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
				newCellLevel = event.level,
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
			newCellLevel = null,
		)
	}

	companion object {
		const val CONSUMER_ID = "exploration-module"
	}
}
