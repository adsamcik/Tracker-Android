package com.adsamcik.tracker.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.adsamcik.tracker.app.ui.MainViewModel
import com.adsamcik.tracker.game.repository.DefaultGameRepository
import com.adsamcik.tracker.game.ui.compose.GameViewModel
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.statistics.repository.DefaultSessionRepository
import com.adsamcik.tracker.statistics.viewmodel.StatsViewModel
import com.adsamcik.tracker.shared.preferences.settings.DefaultTrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.app.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Application composition root for dependency injection.
 * Provides ViewModels with their required dependencies and manages application-scoped services.
 */
class AppGraph(
    val dispatchers: DispatchersProvider,
    val appScope: CoroutineScope,
) {
    
    // Late-initialized application instance (set during Application.onCreate)
    lateinit var application: Application
        private set
    
    fun initialize(app: Application) {
        application = app
    }
    
    // Repositories (application-scoped, lazy-initialized)
    private val sessionRepository by lazy { DefaultSessionRepository(application, dispatchers) }
    private val gameRepository by lazy { DefaultGameRepository(application, appScope) }
    private val trackerSettingsRepository: TrackerSettingsRepository by lazy {
        DefaultTrackerSettingsRepository(application, dispatchers.io)
    }
    
    /**
     * ViewModelFactory that provides ViewModels with injected dependencies.
     */
    class ViewModelFactory(private val appGraph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when (modelClass) {
                MainViewModel::class.java -> MainViewModel() as T
                StatsViewModel::class.java -> StatsViewModel(appGraph.sessionRepository) as T
                GameViewModel::class.java -> GameViewModel(appGraph.gameRepository) as T
                SettingsViewModel::class.java -> SettingsViewModel(appGraph.trackerSettingsRepository) as T
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
    
    val viewModelFactory by lazy { ViewModelFactory(this) }
}
