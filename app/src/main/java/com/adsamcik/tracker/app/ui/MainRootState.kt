package com.adsamcik.tracker.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for MainRoot managing navigation and animation state.
 * Extracted from UI layer per architecture guidelines (copilot-instructions.md §5).
 */
class MainRootViewModel : ViewModel() {
    
    private val _isMapExpanded = MutableStateFlow(false)
    val isMapExpanded: StateFlow<Boolean> = _isMapExpanded.asStateFlow()
    
    private val _currentPrimaryRoute = MutableStateFlow(Routes.Stats)
    val currentPrimaryRoute: StateFlow<String> = _currentPrimaryRoute.asStateFlow()
    
    private val _lastNonMapRoute = MutableStateFlow(Routes.Stats)
    val lastNonMapRoute: StateFlow<String> = _lastNonMapRoute.asStateFlow()
    
    fun setMapExpanded(expanded: Boolean) {
        viewModelScope.launch {
            _isMapExpanded.value = expanded
        }
    }
    
    fun setCurrentRoute(route: String) {
        viewModelScope.launch {
            _currentPrimaryRoute.value = route
            if (route != Routes.Map) {
                _lastNonMapRoute.value = route
            }
        }
    }
    
    fun toggleMapExpanded() {
        viewModelScope.launch {
            _isMapExpanded.value = !_isMapExpanded.value
        }
    }
    
    fun collapseMap() {
        viewModelScope.launch {
            _isMapExpanded.value = false
        }
    }
    
    fun expandMap() {
        viewModelScope.launch {
            _isMapExpanded.value = true
        }
    }
    
    /**
     * Initialize state based on start destination.
     */
    fun initializeWithDestination(destination: String) {
        viewModelScope.launch {
            when (destination) {
                Routes.Map -> {
                    _isMapExpanded.value = true
                    _currentPrimaryRoute.value = Routes.Stats
                    _lastNonMapRoute.value = Routes.Stats
                }
                Routes.Game, Routes.Debug, Routes.Settings -> {
                    _isMapExpanded.value = false
                    _currentPrimaryRoute.value = destination
                    _lastNonMapRoute.value = destination
                }
                else -> {
                    _isMapExpanded.value = false
                    _currentPrimaryRoute.value = Routes.Stats
                    _lastNonMapRoute.value = Routes.Stats
                }
            }
        }
    }
    
    /**
     * Calculate effective route for display (Map when expanded, else primary route).
     */
    fun getEffectiveRoute(isExpanded: Boolean, primaryRoute: String): String {
        return if (isExpanded) Routes.Map else primaryRoute
    }
    
    /**
     * Determine if back should be handled (not on Stats when collapsed).
     */
    fun shouldHandleBack(isExpanded: Boolean, currentRoute: String): Boolean {
        return isExpanded || currentRoute != Routes.Stats
    }
    
    /**
     * Handle back press logic.
     * Returns true if back was handled, false if should use default behavior.
     */
    fun handleBack(): Boolean {
        return when {
            _isMapExpanded.value -> {
                _isMapExpanded.value = false
                true
            }
            _currentPrimaryRoute.value != Routes.Stats -> {
                _currentPrimaryRoute.value = Routes.Stats
                true
            }
            else -> false
        }
    }
}
