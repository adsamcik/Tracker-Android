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

			batch.forEach { unconsumed ->
				handleEvent(unconsumed.event, database, cellDao, streakDao)
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

	private suspend fun handleEvent(
		event: DomainEvent,
		database: AppDatabase,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	) {
		when (event) {
			is DomainEvent.CellDiscovered -> onCellDiscovered(event, database, cellDao, streakDao)
			else -> Unit
		}
	}

	private suspend fun onCellDiscovered(
		event: DomainEvent.CellDiscovered,
		database: AppDatabase,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	) {
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

		// Wrap cell + streak handling in one transaction so a crash between them can't
		// leave the cell committed without the streak update. On redelivery after such
		// a crash, the previously-committed cell row has firstDiscoveredAt == event.ts,
		// which we use to detect "this event discovered this cell" regardless of which
		// side committed first. ExplorationStreakTracker is already day-idempotent
		// (epochDay <= lastIncrementDay → no-op), so re-calling it after a successful
		// prior streak update is also safe.
		var wasNewCell = false
		database.withTransaction {
			val insertedId = cellDao.insert(entity)
			val isFreshInsert = insertedId != -1L
			val isRecoveredFirstDiscovery = !isFreshInsert &&
				cellDao.getByToken(event.cellToken)?.firstDiscoveredAt == now
			wasNewCell = isFreshInsert || isRecoveredFirstDiscovery

			if (wasNewCell) {
				// Update streak inside the SAME transaction so crash now would roll back
				// the cell insert too (clean retry on redelivery).
				streakTracker.onCellDiscovered(streakDao, event, wasNewCell = true)
			} else {
				// Cell already exists and not a recovery — just bump visit metadata.
				cellDao.updateVisit(
					token = event.cellToken,
					quality = event.quality,
					lastVisitedAt = now,
					seasonBit = event.seasonBit,
				)
			}
		}

		// Mark tables dirty + log AFTER the transaction commits, per the contract
		// in MetricDirtyTracker (mark-after-commit). Logging is outside the transaction
		// so a slow log can't lengthen the write lock window.
		if (wasNewCell) {
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_EXPLORATION_CELL, MetricKeys.TABLE_EXPLORATION_STREAK)
			)
			Logger.log(
				LogData(
					message = "Cell discovered: level=${event.level} isNew=true",
					source = GAME_LOG_SOURCE,
				),
			)
		} else {
			dirtyTracker.markDirty(MetricKeys.TABLE_EXPLORATION_CELL)
		}
	}

	companion object {
		const val CONSUMER_ID = "exploration-module"
	}
}
