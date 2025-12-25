package com.adsamcik.tracker.game.di

import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Default implementation of DailyPointsProvider.
 * 
 * Wraps GameRepository.getPointsToday() as a StateFlow for Compose consumption.
 * Provides reactive updates when points are awarded during tracking sessions.
 * 
 * Lifecycle: Application-scoped singleton (wired in AppGraph)
 */
class DefaultDailyPointsProvider(
    gameRepository: GameRepository,
    scope: CoroutineScope
) : DailyPointsProvider {
    
    override val pointsTodayFlow: StateFlow<Int> = gameRepository
        .getPointsToday()
        .stateIn(scope, SharingStarted.Lazily, 0)
}
