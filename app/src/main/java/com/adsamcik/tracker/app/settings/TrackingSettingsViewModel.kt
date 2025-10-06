package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.adsamcik.tracker.shared.preferences.R as PrefR

// Contract: ViewModel for tracking settings screen
// Inputs: Context for Preferences access
// Outputs: StateFlows for all tracking-related preferences
// Errors: None (preferences default to safe values)
class TrackingSettingsViewModel(private val context: Context) : ViewModel() {
    
    private val prefs = Preferences.getPref(context)
    
    // Enable/disable tracking sources
    private val _locationEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_location_enabled_key, PrefR.string.settings_location_enabled_default)
    )
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()
    
    private val _activityEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_activity_enabled_key, PrefR.string.settings_activity_enabled_default)
    )
    val activityEnabled: StateFlow<Boolean> = _activityEnabled.asStateFlow()
    
    private val _stepsEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_steps_enabled_key, PrefR.string.settings_steps_enabled_default)
    )
    val stepsEnabled: StateFlow<Boolean> = _stepsEnabled.asStateFlow()
    
    private val _wifiEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_wifi_enabled_key, PrefR.string.settings_wifi_enabled_default)
    )
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()
    
    private val _cellEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_cell_enabled_key, PrefR.string.settings_cell_enabled_default)
    )
    val cellEnabled: StateFlow<Boolean> = _cellEnabled.asStateFlow()
    
    // WiFi sub-options
    private val _wifiNetworkEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_wifi_network_enabled_key, PrefR.string.settings_wifi_network_enabled_default)
    )
    val wifiNetworkEnabled: StateFlow<Boolean> = _wifiNetworkEnabled.asStateFlow()
    
    private val _wifiLocationCountEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_wifi_location_count_enabled_key, PrefR.string.settings_wifi_location_count_enabled_default)
    )
    val wifiLocationCountEnabled: StateFlow<Boolean> = _wifiLocationCountEnabled.asStateFlow()
    
    // Auto-tracking
    private val _autoTrackingEnabled = MutableStateFlow(
        prefs.getIntRes(PrefR.string.settings_tracking_activity_key, PrefR.string.settings_tracking_activity_default) > 0
    )
    val autoTrackingEnabled: StateFlow<Boolean> = _autoTrackingEnabled.asStateFlow()
    
    private val _transitionDetectionEnabled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_auto_tracking_transition_key, PrefR.string.settings_auto_tracking_transition_default)
    )
    val transitionDetectionEnabled: StateFlow<Boolean> = _transitionDetectionEnabled.asStateFlow()
    
    // Notification
    private val _notificationStyled = MutableStateFlow(
        prefs.getBooleanRes(PrefR.string.settings_notification_styled_key, PrefR.string.settings_notification_styled_default)
    )
    val notificationStyled: StateFlow<Boolean> = _notificationStyled.asStateFlow()
    
    // Tracking parameters
    private val _minDistance = MutableStateFlow(
        prefs.getIntRes(PrefR.string.settings_tracking_min_distance_key, 10) // Default value directly
    )
    val minDistance: StateFlow<Int> = _minDistance.asStateFlow()
    
    private val _minTime = MutableStateFlow(
        prefs.getIntRes(PrefR.string.settings_tracking_min_time_key, 2) // Default value directly
    )
    val minTime: StateFlow<Int> = _minTime.asStateFlow()
    
    private val _requiredAccuracy = MutableStateFlow(
        prefs.getIntRes(PrefR.string.settings_tracking_required_accuracy_key, 50) // Default value directly
    )
    val requiredAccuracy: StateFlow<Int> = _requiredAccuracy.asStateFlow()
    
    // Validation: at least one source must be enabled
    private val _hasValidSources = MutableStateFlow(true)
    val hasValidSources: StateFlow<Boolean> = _hasValidSources.asStateFlow()
    
    // Setters
    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_location_enabled_key, enabled) }
            _locationEnabled.value = enabled
            validateSources()
        }
    }
    
    fun setActivityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_activity_enabled_key, enabled) }
            _activityEnabled.value = enabled
            validateSources()
        }
    }
    
    fun setStepsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_steps_enabled_key, enabled) }
            _stepsEnabled.value = enabled
            validateSources()
        }
    }
    
    fun setWifiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_enabled_key, enabled) }
            _wifiEnabled.value = enabled
            validateSources()
        }
    }
    
    fun setCellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_cell_enabled_key, enabled) }
            _cellEnabled.value = enabled
            validateSources()
        }
    }
    
    fun setWifiNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_network_enabled_key, enabled) }
            _wifiNetworkEnabled.value = enabled
        }
    }
    
    fun setWifiLocationCountEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_location_count_enabled_key, enabled) }
            _wifiLocationCountEnabled.value = enabled
        }
    }
    
    fun setTransitionDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_auto_tracking_transition_key, enabled) }
            _transitionDetectionEnabled.value = enabled
        }
    }
    
    fun setNotificationStyled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_notification_styled_key, enabled) }
            _notificationStyled.value = enabled
        }
    }
    
    fun setMinDistance(distance: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(PrefR.string.settings_tracking_min_distance_key, distance) }
            _minDistance.value = distance
        }
    }
    
    fun setMinTime(time: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(PrefR.string.settings_tracking_min_time_key, time) }
            _minTime.value = time
        }
    }
    
    fun setRequiredAccuracy(accuracy: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(PrefR.string.settings_tracking_required_accuracy_key, accuracy) }
            _requiredAccuracy.value = accuracy
        }
    }
    
    private fun validateSources() {
        _hasValidSources.value = locationEnabled.value || activityEnabled.value || 
                stepsEnabled.value || wifiEnabled.value || cellEnabled.value
    }
}
