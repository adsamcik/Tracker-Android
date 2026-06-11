package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class DefaultDailySummaryRepository @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
) : DailySummaryRepository {

	override fun observeToday(): Flow<DailySummary?> {
		val today = LocalDate.now().toEpochDay()
		return dailySummaryDao.getByDayFlow(today).map { entity ->
			entity?.toDailySummary()
		}
	}

	override fun observeWeek(): Flow<List<DailySummary>> {
		val today = LocalDate.now().toEpochDay()
		val weekAgo = today - 7
		return dailySummaryDao.getBetweenFlow(weekAgo, today).map { entities ->
			entities.map { it.toDailySummary() }
		}
	}

	override fun observeBetween(fromDay: Long, toDay: Long): Flow<List<DailySummary>> {
		return dailySummaryDao.getBetweenFlow(fromDay, toDay).map { entities ->
			entities.map { it.toDailySummary() }
		}
	}

	override suspend fun getBetween(fromDay: Long, toDay: Long): Either<StatsError, List<DailySummary>> {
		return try {
			val entities = dailySummaryDao.getBetween(fromDay, toDay)
			entities.map { it.toDailySummary() }.right()
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load daily summaries: ${e.message}", e).left()
		}
	}

	private fun com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity.toDailySummary(): DailySummary {
		return DailySummary(
			dayEpoch = dateEpochDay,
			totalDistance = DistanceM.coerced(totalDistanceM),
			totalSteps = StepCount.coerced(totalSteps),
			totalDuration = DurationMs(totalDurationMs.coerceAtLeast(0L)),
			tripCount = tripCount,
			activeTrackingDuration = DurationMs(activeTrackingMs.coerceAtLeast(0L)),
		)
	}
}
