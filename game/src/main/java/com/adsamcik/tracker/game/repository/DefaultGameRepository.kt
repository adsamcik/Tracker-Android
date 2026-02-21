package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val challengeManager: ChallengeManager
) : GameRepository {
    
    private val pointsDao by lazy { PointsDatabase.database(application).pointsAwardedDao() }
    private val challengeDb by lazy { ChallengeDatabase.database(application) }
    
    init {
        // Initialize game managers (idempotent)
        GoalTracker.initialize(application)
        challengeManager.initialize(application)
    }
    
    private fun startOfDay(now: Long): Long = (now / 86_400_000L) * 86_400_000L
    
    override fun getPointsToday(): Flow<Int> {
        return flow {
            emitAll(pointsDao.countBetweenFlow(startOfDay(Time.nowMillis), Time.nowMillis))
        }.flowOn(Dispatchers.IO)
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
                list.map { inst ->
                    ChallengeData(
                        id = inst.entity.id,
                        title = inst.getTitle(application),
                        description = inst.getDescription(application),
                        progress = inst.progress.toFloat()
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
            .flowOn(Dispatchers.IO)
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
            .flowOn(Dispatchers.IO)
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
        }.flowOn(Dispatchers.IO)
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
            .flowOn(Dispatchers.IO)
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
            .flowOn(Dispatchers.IO)
    }

    override fun getLifetimeStats(): Flow<LifetimeStatsUi> {
        return combine(
            challengeDb.challengeHistoryDao().observeAll(),
            challengeDb.challengeHistoryDao().observeCompletedCount(),
            challengeDb.challengeHistoryDao().observeMedalCount("GOLD"),
            challengeDb.challengeHistoryDao().observeMedalCount("SILVER"),
            challengeDb.challengeHistoryDao().observeMedalCount("BRONZE"),
        ) { allHistory, completed, gold, silver, bronze ->
            val total = allHistory.size
            val totalXp = allHistory.sumOf { it.xpAwarded.toLong() }
            LifetimeStatsUi(
                totalChallenges = total,
                completedCount = completed,
                completionRate = if (total > 0) completed.toFloat() / total else 0f,
                goldCount = gold,
                silverCount = silver,
                bronzeCount = bronze,
                totalXpEarned = totalXp,
            )
        }.flowOn(Dispatchers.IO)
    }
}
