package com.adsamcik.tracker.app.ui

import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel managing main navigation state and animation transitions.
 * Extracts animation logic from UI layer per evergreen guidelines (§5).
 */
class MainViewModel : ViewModel() {
    
    private val _currentRoute = MutableStateFlow(Routes.Tracker)
    val currentRoute: StateFlow<String> = _currentRoute.asStateFlow()
    
    /**
     * Update current navigation route.
     * @param route The new active route
     */
    fun setCurrentRoute(route: String) {
        _currentRoute.value = route
    }
    
    /**
     * Compute navigation bar elevation based on current route.
     * Map route gets prominent elevation to emphasize overlay effect.
     */
    fun getBarElevationDp(): Float {
        return if (_currentRoute.value == Routes.Map) 10f else 4f
    }
    
    /**
     * Compute scale animation for Stats icon based on current route.
     */
    fun getStatsScale(): Float {
        return if (_currentRoute.value == Routes.Stats) 1.1f else 0.95f
    }
    
    /**
     * Compute alpha animation for Stats icon.
     * Slightly dimmed when map is active to de-emphasize bottom nav.
     */
    fun getStatsAlpha(): Float {
        return if (_currentRoute.value == Routes.Map) 0.9f else 1f
    }
    
    /**
     * Compute scale animation for Map icon.
     * Large scale emphasizes map as primary navigation target.
     */
    fun getMapScale(): Float {
        return if (_currentRoute.value == Routes.Map) 1.25f else 1.0f
    }
    
    /**
     * Compute lift animation for Map icon.
     * Creates floating effect when map is active.
     */
    fun getMapLiftDp(): Float {
        return if (_currentRoute.value == Routes.Map) 6f else 0f
    }
    
    /**
     * Compute shadow elevation for Map icon.
     */
    fun getMapShadowDp(): Float {
        return if (_currentRoute.value == Routes.Map) 8f else 0f
    }
    
    /**
     * Compute scale animation for Game icon based on current route.
     */
    fun getGameScale(): Float {
        return if (_currentRoute.value == Routes.Game) 1.1f else 0.95f
    }
    
    /**
     * Compute alpha animation for Game icon.
     */
    fun getGameAlpha(): Float {
        return if (_currentRoute.value == Routes.Map) 0.9f else 1f
    }
}
