package com.adsamcik.tracker.game.event

import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.api.event.DomainEvent
import java.time.Instant
import java.time.ZoneId

internal class ExplorationStreakTracker {
	suspend fun onCellDiscovered(
		streakDao: ExplorationStreakDao,
		event: DomainEvent.CellDiscovered,
		wasNewCell: Boolean,
	) {
		if (!wasNewCell || event.level != EXPLORATION_LEVEL) return

		val updatedAt = event.timestampMs.raw
		val epochDay = Instant.ofEpochMilli(updatedAt)
			.atZone(ZoneId.systemDefault())
			.toLocalDate()
			.toEpochDay()
		val current = streakDao.getByType(STREAK_TYPE_DAILY)

		when {
			current == null -> {
				streakDao.upsert(
					ExplorationStreakEntity(
						type = STREAK_TYPE_DAILY,
						currentCount = 1,
						bestCount = 1,
						lastIncrementDay = epochDay,
						updatedAt = updatedAt,
					),
				)
			}

			epochDay <= current.lastIncrementDay -> Unit

			epochDay == current.lastIncrementDay + 1L -> {
				streakDao.incrementStreak(
					type = STREAK_TYPE_DAILY,
					epochDay = epochDay,
					updatedAt = updatedAt,
				)
			}

			else -> {
				streakDao.upsert(
					current.copy(
						currentCount = 1,
						bestCount = maxOf(current.bestCount, 1),
						lastIncrementDay = epochDay,
						updatedAt = updatedAt,
					),
				)
			}
		}
	}

	companion object {
		internal const val EXPLORATION_LEVEL = 14
		internal const val STREAK_TYPE_DAILY = "DAILY_DISCOVERY"
	}
}
