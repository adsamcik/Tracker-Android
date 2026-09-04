package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.CellBounds
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.OrderedAltitudeSampleRow
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.data.geo.CountryBoundaryLookup
import com.adsamcik.tracker.shared.model.SegmentSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class DefaultAchievementMetricsProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val exportLogDao: ExportLogDao,
	private val miniGameScoreDao: MiniGameScoreDao,
	private val locationSampleDao: LocationSampleDao,
	private val countryLookup: CountryBoundaryLookup,
	private val achievementProgressDao: AchievementProgressDao,
) : AchievementMetricsProvider {
	override suspend fun collect(): MetricSnapshot {
		val zoneId = ZoneId.systemDefault()
		val today = LocalDate.now(zoneId)
		val weekStartEpochDay = today.minusDays(6).toEpochDay()
		val weekStartMs = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
		val weekEndMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
		val activeDays = dailySummaryDao.getActiveEpochDays()
		val firstActivityMs = listOfNotNull(dailySummaryDao.minCreatedAt(), sessionSegmentDao.minStartTime()).minOrNull()
		val seasonBitmasks = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_CELL_LEVEL)
		val combinedSeasons = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		val hasWalk = sessionSegmentDao.countByActivities(SessionActivityIds.ON_FOOT.toList()) > 0
		val hasCycle = sessionSegmentDao.countByActivities(SessionActivityIds.CYCLING.toList()) > 0
		val hasDrive = sessionSegmentDao.countByActivities(SessionActivityIds.DRIVING.toList()) > 0
		val weekActiveDays = dailySummaryDao.countActiveDaysSinceEpochDay(weekStartEpochDay)
		val countriesVisited = explorationCellDao.getCellCenters(EXPLORATION_CELL_LEVEL)
			.mapNotNullTo(HashSet()) { countryLookup.countryOf(it.latE7 / E7, it.lonE7 / E7) }
			.size
		val progressRows = achievementProgressDao.getAll()
		val unlockedTierByMetric = progressRows
			.mapNotNull { row ->
				MetricKey.fromStorageKey(row.metricKey)
					?.takeIf(AchievementMetricQualification::isTrustedPersistedProgress)
					?.let { it to row.lastTierIndex }
			}
			.toMap()
		val achievementsUnlocked = AchievementCatalog.definitions.count { definition ->
			AchievementMetricQualification.isTrustedPersistedProgress(definition.metric) &&
				(unlockedTierByMetric[definition.metric] ?: -1) >= definition.tierIndex
		}
		val categoriesCompleted = AchievementCategory.entries.count { category ->
			val defs = AchievementCatalog.byCategory(category)
				.filterNot { it.metric in AchievementMetricQualification.derivedMetaMetrics }
			defs.isNotEmpty() && defs.all {
				AchievementMetricQualification.isTrustedPersistedProgress(it.metric)
			} &&
				defs.all { def -> (unlockedTierByMetric[def.metric] ?: -1) >= def.tierIndex }
		}

		// STEPS_TOTAL, BEST_DAILY_STEPS, PERFECT_WEEKS, and GOAL_STREAK_DAYS remain
		// deliberately absent. daily_summary is a projection rather than source qualification, and
		// legacy GOAL XP timestamps do not prove the exact captured day or its calendar authority.
		// Lifetime/best-day re-enable only after one coherent retained-fact decision; streak metrics
		// additionally require exact qualified goal-day provenance and atomic award revalidation.
		// PLAYER_LEVEL, BEST_DAY_XP, and XP_SOURCES_USED are also absent because legacy XP storage
		// cannot separate raw Steps-derived awards from independently qualified XP.
		return MetricSnapshot.from(
			mapOf(
				MetricKey.DISTANCE_TOTAL_M to dailySummaryDao.sumTotalDistance().toDouble(),
				MetricKey.ACTIVE_DAYS_TOTAL to activeDays.size.toDouble(),
				MetricKey.SESSIONS_TOTAL to dailySummaryDao.sumTotalTrips().toDouble(),
				MetricKey.CELLS_DISTINCT_LIFETIME to explorationCellDao.countAtLevelLong(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.MAX_SESSION_DISTANCE_M to sessionSegmentDao.maxSegmentDistance().toDouble(),
				MetricKey.MAX_SESSION_DURATION_MS to sessionSegmentDao.maxSegmentDurationMs().toDouble(),
				MetricKey.MAX_SPEED_MPS to sessionSegmentDao.maxAverageSpeedMps(),
				MetricKey.STREAK_DAYS_CURRENT to (explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.currentCount ?: 0).toDouble(),
				MetricKey.STREAK_DAYS_MAX to (explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.bestCount ?: 0).toDouble(),
				MetricKey.COUNTRIES_VISITED to countriesVisited.toDouble(),
				MetricKey.ACTIVITY_TYPES_USED to sessionSegmentDao.countDistinctActivities().toDouble(),
				MetricKey.MONTHS_ACTIVE to dailySummaryDao.countDistinctMonths().toDouble(),
				MetricKey.HOURS_OF_DAY_TRACKED to sessionSegmentDao.countDistinctStartHours().toDouble(),
				MetricKey.DAYS_OF_WEEK_TRACKED to dailySummaryDao.countDistinctWeekdays().toDouble(),
				MetricKey.APP_AGE_DAYS to appAgeDays(firstActivityMs, zoneId).toDouble(),
				MetricKey.COMEBACK_GAP_DAYS to maxActiveDayGap(activeDays).toDouble(),
				MetricKey.CALENDAR_NEW_YEAR to activeDays.hasMonthDay(1, 1).toFlag(),
				MetricKey.CALENDAR_LEAP_DAY to activeDays.hasMonthDay(2, 29).toFlag(),
				MetricKey.CALENDAR_SUMMER_SOLSTICE to activeDays.hasMonthDay(6, 21).toFlag(),
				MetricKey.CALENDAR_WINTER_SOLSTICE to activeDays.hasMonthDay(12, 21).toFlag(),
				MetricKey.WEEK_DISTANCE_M to dailySummaryDao.sumDistanceSinceEpochDay(weekStartEpochDay).toDouble(),
				MetricKey.WEEK_ACTIVE_DAYS to weekActiveDays.toDouble(),
				MetricKey.WEEK_ALL_DAYS_TRACKED to (weekActiveDays >= 7).toFlag(),
				MetricKey.WEEK_ACTIVITY_TYPES to sessionSegmentDao.countDistinctActivitiesBetween(weekStartMs, weekEndMs).toDouble(),
				MetricKey.LIFETIME_WALK_CYCLE_DRIVE to (hasWalk && hasCycle && hasDrive).toFlag(),
				MetricKey.ACTIVE_MINUTES_TOTAL to (dailySummaryDao.sumTotalDurationMs() / MILLIS_PER_MINUTE).toDouble(),
				MetricKey.DISTANCE_ON_FOOT_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.ON_FOOT.toList()).toDouble(),
				MetricKey.CYCLING_DISTANCE_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.CYCLING.toList()).toDouble(),
				MetricKey.VEHICLE_DISTANCE_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.IN_VEHICLE.toList()).toDouble(),
				MetricKey.ON_FOOT_ACTIVE_DAYS to sessionSegmentDao.countDistinctDaysByActivities(SessionActivityIds.ON_FOOT.toList()).toDouble(),
				MetricKey.CYCLING_ACTIVE_DAYS to sessionSegmentDao.countDistinctDaysByActivities(SessionActivityIds.CYCLING.toList()).toDouble(),
				MetricKey.VEHICLE_ACTIVE_DAYS to sessionSegmentDao.countDistinctDaysByActivities(SessionActivityIds.IN_VEHICLE.toList()).toDouble(),
				MetricKey.BEST_DAY_DISTANCE_M to dailySummaryDao.maxDailyDistance().toDouble(),
				MetricKey.EXPORTS_TOTAL to exportLogDao.countTotal().toDouble(),
				MetricKey.SEASONS_EXPLORED to Integer.bitCount(combinedSeasons).toDouble(),
				MetricKey.NIGHT_SESSIONS_TOTAL to sessionSegmentDao.countSessionsStartingBetweenHours(NIGHT_START_HOUR, NIGHT_END_HOUR).toDouble(),
				MetricKey.DAWN_SESSIONS_TOTAL to sessionSegmentDao.countSessionsStartingBetweenHours(DAWN_START_HOUR, DAWN_END_HOUR).toDouble(),
				MetricKey.USER_CREATED_SESSIONS to sessionSegmentDao.countBySource(SegmentSource.USER_CREATED).toDouble(),
				MetricKey.MAX_CYCLE_SESSION_M to sessionSegmentDao.maxDistanceByActivities(SessionActivityIds.CYCLING.toList()).toDouble(),
				MetricKey.TRIATHLON_DAYS to sessionSegmentDao.countTriathlonDays(
					SessionActivityIds.ON_FOOT.toList(),
					SessionActivityIds.CYCLING.toList(),
					SessionActivityIds.DRIVING.toList(),
				).toDouble(),
				MetricKey.CELL_ALL_SEASONS to explorationCellDao.countAllSeasonsCells(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.MAX_CELL_VISITS to explorationCellDao.maxVisitCount(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.CELLS_THOROUGH to explorationCellDao.countByQuality(EXPLORATION_CELL_LEVEL, QUALITY_THOROUGHLY_EXPLORED).toDouble(),
				MetricKey.MAX_CELL_SPAN_M to spanMeters(explorationCellDao.getCellBounds(EXPLORATION_CELL_LEVEL)),
				MetricKey.MAX_CELLS_IN_DAY to explorationCellDao.maxCellsDiscoveredInDay(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.MAX_CELL_REVISIT_GAP_DAYS to explorationCellDao.maxRevisitGapDays(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.PERFECT_MONTHS to countPerfectMonths(activeDays).toDouble(),
				MetricKey.EXPORT_FORMATS to exportLogDao.countDistinctFormats().toDouble(),
				MetricKey.MINIGAMES_PLAYED to miniGameScoreDao.countTotal().toDouble(),
				MetricKey.TOTAL_ASCENT_M to totalAscent(locationSampleDao.getAltitudeSamplesOrdered()),
				MetricKey.ACHIEVEMENTS_UNLOCKED to achievementsUnlocked.toDouble(),
				MetricKey.CATEGORIES_COMPLETED to categoriesCompleted.toDouble(),
			)
		)
	}

	private fun appAgeDays(firstActivityMs: Long?, zoneId: ZoneId): Long {
		if (firstActivityMs == null || firstActivityMs <= 0L) return 0L
		val first = Instant.ofEpochMilli(firstActivityMs).atZone(zoneId).toLocalDate()
		return ChronoUnit.DAYS.between(first, LocalDate.now(zoneId)).coerceAtLeast(0L)
	}

	private fun maxActiveDayGap(activeDays: List<Long>): Long {
		if (activeDays.size < 2) return 0L
		var previous = activeDays.first()
		var maxGap = 0L
		for (index in 1 until activeDays.size) {
			val current = activeDays[index]
			maxGap = maxOf(maxGap, current - previous - 1)
			previous = current
		}
		return maxGap
	}

	private fun List<Long>.hasMonthDay(month: Int, day: Int): Boolean = any { epochDay ->
		LocalDate.ofEpochDay(epochDay).let { it.monthValue == month && it.dayOfMonth == day }
	}

	private fun Boolean.toFlag(): Double = if (this) 1.0 else 0.0

	/**
	 * Counts calendar months in which EVERY day was an active tracking day.
	 * [activeDays] is the distinct, sorted list of active epoch-days.
	 */
	private fun countPerfectMonths(activeDays: List<Long>): Long {
		if (activeDays.isEmpty()) return 0L
		return activeDays
			.groupBy { LocalDate.ofEpochDay(it).withDayOfMonth(1) }
			.count { (firstOfMonth, days) -> days.size >= firstOfMonth.lengthOfMonth() }
			.toLong()
	}

	/**
	 * Total elevation gain (meters) = sum of positive consecutive altitude deltas within one
	 * identified datum and monotonic clock domain.
	 * Deltas below [ASCENT_MIN_DELTA_M] are ignored as barometric/GPS jitter and
	 * deltas above [ASCENT_MAX_DELTA_M] (between adjacent samples) as glitches.
	 * Computed over the retained `location_sample` history (re-evaluation triggered
	 * by `daily_summary` writes at session end).
	 */
	private fun totalAscent(altitudes: List<OrderedAltitudeSampleRow>): Double {
		if (altitudes.size < 2) return 0.0
		var ascent = 0.0
		var previous: OrderedAltitudeSampleRow? = null
		altitudes.forEach { current ->
			if (!current.altitudeM.isFinite() ||
				current.altitudeDatum == com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY
			) {
				previous = null
				return@forEach
			}
			val prior = previous
			val sameClockDomain = current.clockDomainId?.takeIf(String::isNotBlank) != null &&
				current.clockDomainId == prior?.clockDomainId
			if (prior != null && sameClockDomain &&
				current.altitudeDatum.isContinuousWith(prior.altitudeDatum)
			) {
				val delta = current.altitudeM.toDouble() - prior.altitudeM.toDouble()
				if (delta in ASCENT_MIN_DELTA_M..ASCENT_MAX_DELTA_M) ascent += delta
			}
			// An unknown/mismatched datum or clock begins a new segment rather than bridging it.
			previous = current
		}
		return ascent
	}

	/**
	 * Great-circle distance (meters) across the diagonal of the discovered-cell
	 * bounding box — a cheap proxy for "furthest two cells apart". Returns 0 when
	 * fewer than two cells exist.
	 */
	private fun spanMeters(bounds: CellBounds?): Double {
		val minLat = bounds?.minLatE7 ?: return 0.0
		val maxLat = bounds.maxLatE7 ?: return 0.0
		val minLon = bounds.minLonE7 ?: return 0.0
		val maxLon = bounds.maxLonE7 ?: return 0.0
		return haversineMeters(minLat / E7, minLon / E7, maxLat / E7, maxLon / E7)
	}

	private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = sin(dLat / 2) * sin(dLat / 2) +
			cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
		return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
	}

	companion object {
		private const val EXPLORATION_CELL_LEVEL = 14
		private const val DAILY_STREAK_TYPE = "DAILY_DISCOVERY"
		private const val MILLIS_PER_MINUTE = 60_000L
		private const val QUALITY_THOROUGHLY_EXPLORED = 4
		private const val NIGHT_START_HOUR = 0
		private const val NIGHT_END_HOUR = 5
		private const val DAWN_START_HOUR = 5
		private const val DAWN_END_HOUR = 8
		private const val EARTH_RADIUS_M = 6_371_000.0
		private const val E7 = 1e7
		private const val ASCENT_MIN_DELTA_M = 0.5
		private const val ASCENT_MAX_DELTA_M = 50.0
	}
}
