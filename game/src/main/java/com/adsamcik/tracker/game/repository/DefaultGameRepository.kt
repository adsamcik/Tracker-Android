package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.challenge.ChallengeManager
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
}