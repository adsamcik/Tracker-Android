package com.adsamcik.tracker.app.ui

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
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
    
    // Store route as a type-safe destination object for type safety
    private val _currentRoute = MutableStateFlow<Any>(Dashboard)
    val currentRoute: StateFlow<Any> = _currentRoute.asStateFlow()
    
    /**
     * Update current navigation route.
     * @param route The new active route
     */
    fun setCurrentRoute(route: Any) {
        _currentRoute.value = route
    }

    fun recordUnrecognizedDestination() {
        Tracebox.log.warn(TrackerTraceboxTemplates.NAVIGATION_DESTINATION_REJECTED)
    }
    
    /**
     * Compute navigation bar elevation based on current route.
     */
    fun getBarElevationDp(): Float {
        return 4f
    }
}
