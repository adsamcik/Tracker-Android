package com.adsamcik.tracker.game.event

import android.content.Context
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
 * corresponding table dirty in [dirtyTracker] so the unified rule signal processor
 * can short-circuit idle flushes (p6-3).
 */
@Singleton
class ExplorationDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
	private val dirtyTracker: MetricDirtyTracker,
) {
	private val streakTracker = ExplorationStreakTracker()

	/** Process any unconsumed CellDiscovered events. */
	suspend fun processUnconsumed() {
		val database = AppDatabase.database(context)
		val cellDao = database.explorationCellDao()
		val streakDao = database.explorationStreakDao()

		while (true) {
			val events = domainEventRepository.getUnconsumedBatch(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
			if (events.isEmpty()) return

			val latestTimestamp = events.maxByOrNull { it.timestampMs.raw }?.timestampMs ?: return
			events.forEach { event ->
				handleEvent(event, cellDao, streakDao)
			}
			domainEventRepository.markConsumed(CONSUMER_ID, latestTimestamp)
		}
	}

	private suspend fun handleEvent(
		event: DomainEvent,
		cellDao: ExplorationCellDao,
		streakDao: ExplorationStreakDao,
	) {
		when (event) {
			is DomainEvent.CellDiscovered -> onCellDiscovered(event, cellDao, streakDao)
			else -> Unit
		}
	}

	private suspend fun onCellDiscovered(
		event: DomainEvent.CellDiscovered,
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

		val insertedId = cellDao.insert(entity)
		if (insertedId != -1L) {
			streakTracker.onCellDiscovered(streakDao, event, wasNewCell = true)
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_EXPLORATION_CELL, MetricKeys.TABLE_EXPLORATION_STREAK)
			)
			// Don't log the cell token in release builds — it is a coarse geographic
			// identifier derived from the user's location and the project's rule is to
			// never expose coordinates (or anything derived from them) in release logs.
			// Level + isNew is enough for diagnostics.
			Logger.log(
				LogData(
					message = "Cell discovered: level=${event.level} isNew=true",
					source = GAME_LOG_SOURCE,
				),
			)
		} else {
			// Cell already exists — update visit metadata
			cellDao.updateVisit(
				token = event.cellToken,
				quality = event.quality,
				lastVisitedAt = now,
				seasonBit = event.seasonBit,
			)
			dirtyTracker.markDirty(MetricKeys.TABLE_EXPLORATION_CELL)
		}
	}

	companion object {
		const val CONSUMER_ID = "exploration-module"
	}
}
