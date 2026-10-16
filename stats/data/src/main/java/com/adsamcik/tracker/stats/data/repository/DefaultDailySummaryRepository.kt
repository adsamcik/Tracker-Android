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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class DefaultDailySummaryRepository @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val savedLocationMetrics: SavedLocationMetricsReader,
) : DailySummaryRepository {

	override fun observeToday(): Flow<DailySummary?> {
		val today = LocalDate.now().toEpochDay()
		return combine(dailySummaryDao.getByDayFlow(today), savedLocationMetrics.observeChanges()) { entity, _ ->
			measuredDays(today, today, listOfNotNull(entity)).singleOrNull()
		}
	}

	override fun observeWeek(): Flow<List<DailySummary>> {
		val today = LocalDate.now().toEpochDay()
		val weekAgo = today - 7
		return combine(dailySummaryDao.getBetweenFlow(weekAgo, today), savedLocationMetrics.observeChanges()) { entities, _ ->
			measuredDays(weekAgo, today, entities)
		}
	}

	override fun observeBetween(fromDay: Long, toDay: Long): Flow<List<DailySummary>> {
		return combine(dailySummaryDao.getBetweenFlow(fromDay, toDay), savedLocationMetrics.observeChanges()) { entities, _ ->
			measuredDays(fromDay, toDay, entities)
		}
	}

	override suspend fun getBetween(fromDay: Long, toDay: Long): Either<StatsError, List<DailySummary>> {
		return try {
			val entities = dailySummaryDao.getBetween(fromDay, toDay)
			measuredDays(fromDay, toDay, entities).right()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load daily summaries: ${e.message}", e).left()
		}
	}

	private suspend fun measuredDays(fromDay: Long, toDay: Long,
		entities: List<com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity>): List<DailySummary> {
		require(fromDay <= toDay)
		val zone = ZoneId.systemDefault()
		val saved = entities.associateBy { it.dateEpochDay }
		val measured = try { savedLocationMetrics.readDays(fromDay, toDay, zone) }
		catch (cancelled: CancellationException) { throw cancelled }
		catch (_: Exception) { null }
		return (saved.keys + measured.orEmpty().keys).filter { it in fromDay..toDay }.sorted().mapNotNull { day ->
			measuredDay(day, saved[day], measured?.get(day), measured == null)
		}
	}

	// One fixed nullable saved/measured row projection keeps defaults and partial flags visible together.
	@Suppress("CyclomaticComplexMethod")
	private fun measuredDay(
		day: Long,
		entity: com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity?,
		location: SavedLocationDayMetrics?,
		measurementUnavailable: Boolean,
	): DailySummary? {
		return if (entity == null && (location?.pointCount ?: 0L) == 0L) {
			null
		} else {
			DailySummary(
				dayEpoch = day,
				totalDistance = DistanceM.coerced((location?.distanceMeters ?: 0.0).toFloat()),
				totalDuration = DurationMs((entity?.totalDurationMs ?: 0L).coerceAtLeast(0L)),
				tripCount = (entity?.tripCount ?: 0).coerceAtLeast(0),
				activeTrackingDuration = DurationMs((entity?.activeTrackingMs ?: 0L).coerceAtLeast(0L)),
				hasMeasuredDistance = location?.distanceMeters != null,
				distanceIsPartial = measurementUnavailable || location?.partial == true,
			)
		}
	}
}
