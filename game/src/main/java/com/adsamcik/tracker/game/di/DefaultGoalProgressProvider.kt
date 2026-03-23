package com.adsamcik.tracker.game.di

import android.content.Context
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Default implementation of GoalProgressProvider.
 * 
 * Wraps GameRepository.getStepsSummary() and combines with gamification preference
 * to provide GoalProgress StateFlow for dashboard display.
 * 
 * @param context Application context for accessing preferences
 * @param gameRepository Repository for accessing step/goal data
 * @param scope CoroutineScope for StateFlow sharing (application-scoped)
 */
class DefaultGoalProgressProvider(
    context: Context,
    private val gameRepository: GameRepository,
    scope: CoroutineScope
) : GoalProgressProvider {
    
    private val prefs = Preferences(context)
    private val challengeEnabledKey = GamePreferenceKeys.CHALLENGE_ENABLED
    
    // Sync read for initial value; acceptable at construction time
    @Suppress("DEPRECATION")
    private val initialGamificationEnabled = prefs.getBoolean(
        challengeEnabledKey,
        GamePreferenceKeys.CHALLENGE_ENABLED_DEFAULT,
    )
    
    override val goalProgressFlow: StateFlow<GoalProgress> = gameRepository.getStepsSummary()
        .map { stepsSummary ->
            // Sync read inside Flow map: acceptable for infrequent updates; avoids combineLatest complexity
            @Suppress("DEPRECATION")
            val gamificationEnabled = prefs.getBoolean(
                challengeEnabledKey,
                GamePreferenceKeys.CHALLENGE_ENABLED_DEFAULT,
            )
            GoalProgress(
                stepsToday = stepsSummary?.stepsToday ?: 0,
                goalSteps = stepsSummary?.goalDay ?: 0,
                gamificationEnabled = gamificationEnabled
            )
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = GoalProgress(
                stepsToday = 0,
                goalSteps = 0,
                gamificationEnabled = initialGamificationEnabled
            )
        )
}
