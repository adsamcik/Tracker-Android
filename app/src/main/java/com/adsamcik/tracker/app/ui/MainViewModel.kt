package com.adsamcik.tracker.app.ui

import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * ViewModel managing main navigation state.
 * Extracts navigation logic from UI layer per evergreen guidelines (§5).
 */
@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {
    
    // Store route as AppRoute for type safety
    private val _currentRoute = MutableStateFlow<AppRoute>(Dashboard)
    val currentRoute: StateFlow<AppRoute> = _currentRoute.asStateFlow()
    
    /**
     * Update current navigation route.
     * @param route The new active route
     */
    fun setCurrentRoute(route: AppRoute) {
        _currentRoute.value = route
    }
    
    /**
     * Compute navigation bar elevation based on current route.
     */
    fun getBarElevationDp(): Float {
        return 4f
    }
}
