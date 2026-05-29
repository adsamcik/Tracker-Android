package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
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
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of GameRepository using existing game managers and DAOs.
 * Manages initialization of game components and provides reactive data streams.
 */
@Singleton
class DefaultGameRepository @Inject constructor(
    private val application: Application,
    @ApplicationScope private val scope: CoroutineScope,
    private val sessionChannel: TrackerSessionChannel,
    private val dispatchers: DispatchersProvider,
    private val challengeManager: ChallengeManager,
    private val challengeDb: AppDatabase,
) : GameRepository {
    
    private val pointsDao by lazy { PointsDatabase.database(application).pointsAwardedDao() }
    
    init {
        // Initialize game managers (idempotent)
        GoalTracker.initialize(application, sessionChannel)
        // ChallengeManager.initialize launches into its own scope and reports failures
        // internally via tryWithResultAndReport. Wrapping the launch in runCatching only
        // catches synchronous DI throws, which would also fail Hilt graph creation upstream.
        challengeManager.initialize(application)
    }
    
    private fun startOfDay(now: Long): Long {
        return Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    
    override fun getPointsToday(): Flow<Int> {
        return flow {
            emitAll(pointsDao.countBetweenFlow(startOfDay(Time.nowMillis), Time.nowMillis))
        }.map { it.toInt() }.flowOn(dispatchers.io)
    }
    
    override fun getStepsSummary(): StateFlow<StepsSummaryData?> {
        return combine(
            GoalTracker.stepsDay,
            GoalTracker.goalDay,
            GoalTracker.stepsWeek,
            GoalTracker.goalWeek
        ) { stepsDay, goalDay, stepsWeek, goalWeek ->
            StepsSummaryData(
                stepsToday = stepsDay,
                stepsWeek = stepsWeek,
                goalDay = goalDay,
                goalWeek = goalWeek
            )
        }.stateIn(scope, SharingStarted.Lazily, null)
    }
    
    override fun getActiveChallenges(): StateFlow<List<ChallengeData>> {
        return challengeManager.activeChallenges
            .map { list ->
                val now = Time.nowMillis
                list.map { inst ->
                    ChallengeData(
                        id = inst.entity.id,
                        title = inst.getTitle(application),
                        description = inst.getDescription(application),
                        progress = inst.progress.toFloat(),
                        difficulty = inst.entity.difficulty.name,
                        timeRemainingMs = (inst.entity.endTime - now).coerceAtLeast(0L),
                        endTimeMs = inst.entity.endTime,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Lazily, emptyList())
    }

    override fun getPlayerProfile(): Flow<PlayerProfileUi?> {
        return challengeDb.playerProfileDao().observe()
            .map { entity ->
                entity?.let {
                    PlayerProfileUi(
                        level = it.level,
                        totalXp = it.totalXp,
                        xpIntoCurrentLevel = it.xpIntoCurrentLevel,
                        xpForNextLevel = it.xpForNextLevel,
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    override fun getStreak(): Flow<StreakUi?> {
        return challengeDb.challengeStreakDao().observe()
            .map { entity ->
                entity?.let {
                    StreakUi(
                        currentCount = it.currentCount,
                        bestCount = it.bestCount,
                        freezeCount = it.freezeCount,
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    override fun getTrophySummary(): Flow<TrophySummaryUi> {
        return combine(
            challengeDb.challengeHistoryDao().observeCompletedCount(),
            challengeDb.challengeHistoryDao().observeMedalCount("GOLD"),
            challengeDb.challengeHistoryDao().observeMedalCount("SILVER"),
            challengeDb.challengeHistoryDao().observeMedalCount("BRONZE"),
        ) { completed, gold, silver, bronze ->
            TrophySummaryUi(
                totalCompleted = completed,
                goldCount = gold,
                silverCount = silver,
                bronzeCount = bronze,
            )
        }.flowOn(dispatchers.io)
    }

    override fun getChallengeHistory(): Flow<List<TrophyItemUi>> {
        return challengeDb.challengeHistoryDao().observeAll()
            .map { list ->
                list.map { entity ->
                    TrophyItemUi(
                        id = entity.id,
                        challengeType = entity.challengeType,
                        difficulty = entity.difficulty,
                        medal = entity.medal,
                        completedAt = entity.completedAt,
                        xpAwarded = entity.xpAwarded,
                        progressValue = entity.progressValue,
                        targetValue = entity.targetValue,
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    override fun getPersonalRecords(): Flow<List<PersonalRecordUi>> {
        return challengeDb.challengePersonalRecordDao().observeAll()
            .map { list ->
                list.map { entity ->
                    PersonalRecordUi(
                        challengeType = entity.challengeType,
                        metric = entity.metric,
                        value = entity.value,
                        achievedAt = entity.achievedAt,
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    override fun getLifetimeStats(): Flow<LifetimeStatsUi> {
        return challengeDb.challengeHistoryDao().observeLifetimeStats().map { row ->
            LifetimeStatsUi(
                totalChallenges = row.totalChallenges,
                completedCount = row.completedCount,
                completionRate = if (row.totalChallenges > 0) {
                    row.completedCount.toFloat() / row.totalChallenges
                } else 0f,
                goldCount = row.goldCount,
                silverCount = row.silverCount,
                bronzeCount = row.bronzeCount,
                totalXpEarned = row.totalXpEarned,
            )
        }.flowOn(dispatchers.io)
    }
}
