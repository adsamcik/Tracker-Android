package com.adsamcik.tracker.game.repository

import android.app.Application
import androidx.lifecycle.asFlow
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.ui.compose.ChallengeUi
import com.adsamcik.tracker.game.ui.compose.StepsSummaryUi
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Default implementation of GameRepository using existing game managers and DAOs.
 * Manages initialization of game components and provides reactive data streams.
 */
class DefaultGameRepository(
    private val application: Application,
    private val scope: CoroutineScope
) : GameRepository {
    
    private val pointsDao = PointsDatabase.database(application).pointsAwardedDao()
    
    init {
        // Initialize game managers (idempotent)
        GoalTracker.initialize(application)
        ChallengeManager.initialize(application)
    }
    
    private fun startOfDay(now: Long): Long = (now / 86_400_000L) * 86_400_000L
    
    override fun getPointsToday(): Flow<Int> {
        return pointsDao.countBetweenLive(startOfDay(Time.nowMillis), Time.nowMillis).asFlow()
            .map { it ?: 0 }
    }
    
    override fun getStepsSummary(): StateFlow<StepsSummaryData?> {
        return combine(
            GoalTracker.stepsDay.asFlow(),
            GoalTracker.goalDay.asFlow(),
            GoalTracker.stepsWeek.asFlow(),
            GoalTracker.goalWeek.asFlow()
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
        return ChallengeManager.activeChallenges.asFlow()
            .map { list ->
                list.map { inst ->
                    ChallengeData(
                        id = inst.data.id,
                        title = inst.getTitle(application),
                        description = inst.getDescription(application),
                        progress = inst.progress.toFloat()
                    )
                }
            }
            .stateIn(scope, SharingStarted.Lazily, emptyList())
    }
}