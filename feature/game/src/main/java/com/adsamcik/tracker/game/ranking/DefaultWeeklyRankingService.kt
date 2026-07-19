package com.adsamcik.tracker.game.ranking

import android.app.Application
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingResult
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingService
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingSnapshot
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Singleton
class DefaultWeeklyRankingService @Inject constructor(
	private val application: Application,
	private val dispatchers: DispatchersProvider,
	private val clock: Clock,
) : WeeklyRankingService {
	private val pointsDao by lazy { PointsDatabase.database(application).pointsAwardedDao() }

	override fun observeCurrentWeek(): Flow<WeeklyRankingResult> = flow {
		val zone = ZoneId.systemDefault()
		val weekStart = Instant.ofEpochMilli(clock.currentTimeMillis())
			.atZone(zone)
			.toLocalDate()
			.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		val weekStartMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
		val nextWeekStartMillis = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
		val tiers = WeeklyRankingCalculator.generateTiers(weekStart)

		emitAll(
			pointsDao.countBetweenFlow(weekStartMillis, nextWeekStartMillis - 1)
				.map<Double, WeeklyRankingResult> { points ->
					val progress = WeeklyRankingCalculator.calculateRanking(points, tiers)
					WeeklyRankingResult.Success(
						WeeklyRankingSnapshot(
							weekStartEpochDay = weekStart.toEpochDay(),
							accumulatedPoints = points.coerceAtLeast(0.0),
							tiers = tiers,
							currentRank = progress.currentRank,
							nextTier = progress.nextTier,
							progressToNextRank = progress.progressToNextRank,
						),
					)
				},
		)
	}.flowOn(dispatchers.io)
		.catch { emit(WeeklyRankingResult.DataUnavailable) }
}
