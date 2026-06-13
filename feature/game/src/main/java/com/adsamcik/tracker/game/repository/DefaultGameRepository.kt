package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Singleton
class DefaultGameRepository @Inject constructor(
	private val application: Application,
	@ApplicationScope private val scope: CoroutineScope,
	private val sessionChannel: TrackerSessionChannel,
	private val dispatchers: DispatchersProvider,
	private val database: AppDatabase,
	private val progressionRepository: PlayerProgressionRepository,
) : GameRepository {
	private val pointsDao by lazy { PointsDatabase.database(application).pointsAwardedDao() }

	init { GoalTracker.initialize(application, sessionChannel) }

	private fun startOfDay(now: Long): Long = Instant.ofEpochMilli(now)
		.atZone(ZoneId.systemDefault())
		.toLocalDate()
		.atStartOfDay(ZoneId.systemDefault())
		.toInstant()
		.toEpochMilli()

	override fun getPointsToday(): Flow<Int> = flow {
		emitAll(pointsDao.countBetweenFlow(startOfDay(Time.nowMillis), Time.nowMillis))
	}.map { it.toInt() }.flowOn(dispatchers.io)

	override fun getStepsSummary(): StateFlow<StepsSummaryData?> = combine(
		GoalTracker.stepsDay,
		GoalTracker.goalDay,
		GoalTracker.stepsWeek,
		GoalTracker.goalWeek,
	) { stepsDay, goalDay, stepsWeek, goalWeek ->
		StepsSummaryData(stepsDay, stepsWeek, goalDay, goalWeek)
	}.stateIn(scope, SharingStarted.Lazily, null)

	override fun getPlayerProfile(): Flow<PlayerProfileUi?> = database.playerProfileDao().observe()
		.map { entity -> entity?.let { PlayerProfileUi(it.level, it.totalXp, it.xpIntoCurrentLevel, it.xpForNextLevel) } }
		.flowOn(dispatchers.io)

	override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) {
		if (xp <= 0) return
		withContext(dispatchers.io) {
			pointsDao.insert(
				PointsAwarded(
					earnedAtMs,
					Points(xp.toDouble()),
					AwardSource(MINIGAME_AWARD_SOURCE_PREFIX + gameId),
				),
			)
		}
		// Also feed the leveling ledger so mini-game play raises the player level
		// (and thus unlocks higher-tier games). Separate store from the points
		// ledger above; idempotent on the run timestamp.
		progressionRepository.awardMiniGameXp(points = xp, earnedAtMs = earnedAtMs)
	}

	private companion object {
		// Mini-games share a per-game source label so the points ledger can
		// distinguish them from regular tracking sessions while still flowing
		// through countBetweenFlow into the "points earned today" counter.
		private const val MINIGAME_AWARD_SOURCE_PREFIX = "minigame:"
	}
}

