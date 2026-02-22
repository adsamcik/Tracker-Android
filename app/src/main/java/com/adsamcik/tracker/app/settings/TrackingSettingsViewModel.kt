package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.app.settings.data.TrackingPresetSettings
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.adsamcik.tracker.shared.preferences.R as PrefR

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = Preferences.getPref(context)
    
    // Preset tracking
    private val _currentPreset = MutableStateFlow<TrackingPolicyPreset?>(TrackingPolicyPreset.DEFAULT)
    val currentPreset: StateFlow<TrackingPolicyPreset?> = _currentPreset.asStateFlow()
    
    private val _currentBatteryImpact = MutableStateFlow(BatteryImpact.MODERATE)
    val currentBatteryImpact: StateFlow<BatteryImpact> = _currentBatteryImpact.asStateFlow()
    
    // Enable/disable tracking sources
    private val _locationEnabled = MutableStateFlow(true)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()
    
    private val _activityEnabled = MutableStateFlow(true)
    val activityEnabled: StateFlow<Boolean> = _activityEnabled.asStateFlow()
    
    private val _stepsEnabled = MutableStateFlow(true)
    val stepsEnabled: StateFlow<Boolean> = _stepsEnabled.asStateFlow()
    
    private val _wifiEnabled = MutableStateFlow(true)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()
    
    private val _cellEnabled = MutableStateFlow(true)
    val cellEnabled: StateFlow<Boolean> = _cellEnabled.asStateFlow()
    
    // WiFi sub-options
    private val _wifiNetworkEnabled = MutableStateFlow(true)
    val wifiNetworkEnabled: StateFlow<Boolean> = _wifiNetworkEnabled.asStateFlow()
    
    private val _wifiLocationCountEnabled = MutableStateFlow(true)
    val wifiLocationCountEnabled: StateFlow<Boolean> = _wifiLocationCountEnabled.asStateFlow()
    
    // Auto-tracking
    private val _autoTrackingEnabled = MutableStateFlow(false)
    val autoTrackingEnabled: StateFlow<Boolean> = _autoTrackingEnabled.asStateFlow()
    
    private val _transitionDetectionEnabled = MutableStateFlow(true)
    val transitionDetectionEnabled: StateFlow<Boolean> = _transitionDetectionEnabled.asStateFlow()
    
    // Notification
    private val _notificationStyled = MutableStateFlow(true)
    val notificationStyled: StateFlow<Boolean> = _notificationStyled.asStateFlow()
    
    // Tracking parameters
    private val _minDistance = MutableStateFlow(10)
    val minDistance: StateFlow<Int> = _minDistance.asStateFlow()
    
    private val _minTime = MutableStateFlow(2)
    val minTime: StateFlow<Int> = _minTime.asStateFlow()
    
    private val _requiredAccuracy = MutableStateFlow(50)
    val requiredAccuracy: StateFlow<Int> = _requiredAccuracy.asStateFlow()
    
    // Validation: at least one source must be enabled
    private val _hasValidSources = MutableStateFlow(true)
    val hasValidSources: StateFlow<Boolean> = _hasValidSources.asStateFlow()
    
    init {
        // Observe preset changes
        viewModelScope.launch {
            prefs.observeString("tracking_preset", TrackingPolicyPreset.DEFAULT.name).collect { presetName ->
                _currentPreset.value = TrackingPolicyPreset.values().firstOrNull { it.name == presetName }
                    ?: TrackingPolicyPreset.DEFAULT
            }
        }
        // Observe tracking source preferences
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_location_enabled_key, PrefR.string.settings_location_enabled_default)
                .collect { _locationEnabled.value = it; validateSources() }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_activity_enabled_key, PrefR.string.settings_activity_enabled_default)
                .collect { _activityEnabled.value = it; validateSources() }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_steps_enabled_key, PrefR.string.settings_steps_enabled_default)
                .collect { _stepsEnabled.value = it; validateSources() }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_wifi_enabled_key, PrefR.string.settings_wifi_enabled_default)
                .collect { _wifiEnabled.value = it; validateSources() }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_cell_enabled_key, PrefR.string.settings_cell_enabled_default)
                .collect { _cellEnabled.value = it; validateSources() }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_wifi_network_enabled_key, PrefR.string.settings_wifi_network_enabled_default)
                .collect { _wifiNetworkEnabled.value = it }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_wifi_location_count_enabled_key, PrefR.string.settings_wifi_location_count_enabled_default)
                .collect { _wifiLocationCountEnabled.value = it }
        }
        viewModelScope.launch {
            PreferenceFlows.intFromString(context, PrefR.string.settings_tracking_activity_key, PrefR.string.settings_tracking_activity_default)
                .map { it > 0 }
                .collect { _autoTrackingEnabled.value = it }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_auto_tracking_transition_key, PrefR.string.settings_auto_tracking_transition_default)
                .collect { _transitionDetectionEnabled.value = it }
        }
        viewModelScope.launch {
            PreferenceFlows.boolean(context, PrefR.string.settings_notification_styled_key, PrefR.string.settings_notification_styled_default)
                .collect { _notificationStyled.value = it }
        }
        // Observe tracking parameters using raw key + default since these use IntegerRes
        viewModelScope.launch {
            val key = context.getString(PrefR.string.settings_tracking_min_distance_key)
            prefs.observeInt(key, 10).collect { _minDistance.value = it }
        }
        viewModelScope.launch {
            val key = context.getString(PrefR.string.settings_tracking_min_time_key)
            prefs.observeInt(key, 2).collect { _minTime.value = it }
        }
        viewModelScope.launch {
            val key = context.getString(PrefR.string.settings_tracking_required_accuracy_key)
            prefs.observeInt(key, 50).collect { _requiredAccuracy.value = it }
        }
    }
    
    // Setters
    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_location_enabled_key, enabled) }
            _locationEnabled.value = enabled
            validateSources()
            markCustomPreset()
        }
    }
    
    fun setActivityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_activity_enabled_key, enabled) }
            _activityEnabled.value = enabled
            validateSources()
            markCustomPreset()
        }
    }
    
    fun setStepsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_steps_enabled_key, enabled) }
            _stepsEnabled.value = enabled
            validateSources()
            markCustomPreset()
        }
    }
    
    fun setWifiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_enabled_key, enabled) }
            _wifiEnabled.value = enabled
            validateSources()
            markCustomPreset()
        }
    }
    
    fun setCellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_cell_enabled_key, enabled) }
            _cellEnabled.value = enabled
            validateSources()
            markCustomPreset()
        }
    }
    
    fun setWifiNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_network_enabled_key, enabled) }
            _wifiNetworkEnabled.value = enabled
            markCustomPreset()
        }
    }
    
    fun setWifiLocationCountEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(PrefR.string.settings_wifi_location_count_enabled_key, enabled) }
            _wifiLocationCountEnabled.value = enabled
            markCustomPreset()
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
            markCustomPreset()
        }
    }
    
    fun setMinTime(time: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(PrefR.string.settings_tracking_min_time_key, time) }
            _minTime.value = time
            markCustomPreset()
        }
    }
    
    fun setRequiredAccuracy(accuracy: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(PrefR.string.settings_tracking_required_accuracy_key, accuracy) }
            _requiredAccuracy.value = accuracy
            markCustomPreset()
        }
    }
    
    private fun validateSources() {
        _hasValidSources.value = locationEnabled.value || activityEnabled.value || 
                stepsEnabled.value || wifiEnabled.value || cellEnabled.value
    }
    
    // Preset management
    fun applyPreset(preset: TrackingPolicyPreset) {
        viewModelScope.launch {
            val config = preset.settings
            
            // Apply all settings
            prefs.edit {
                setBoolean(PrefR.string.settings_location_enabled_key, config.locationEnabled)
                setBoolean(PrefR.string.settings_activity_enabled_key, config.activityEnabled)
                setBoolean(PrefR.string.settings_steps_enabled_key, config.stepsEnabled)
                setBoolean(PrefR.string.settings_wifi_enabled_key, config.wifiEnabled)
                setBoolean(PrefR.string.settings_wifi_network_enabled_key, config.wifiEnabled) // Network enabled with wifi
                setBoolean(PrefR.string.settings_wifi_location_count_enabled_key, config.wifiLocationCountEnabled)
                setBoolean(PrefR.string.settings_cell_enabled_key, config.cellEnabled)
                setBoolean(PrefR.string.settings_auto_tracking_transition_key, config.useTransitionDetection)
                setInt(PrefR.string.settings_tracking_min_distance_key, config.minDistanceMeters)
                setInt(PrefR.string.settings_tracking_min_time_key, config.minTimeSeconds)
                setInt(PrefR.string.settings_tracking_required_accuracy_key, config.requiredAccuracyMeters)
                setString("tracking_preset", preset.name)
            }
            
            // Update state flows
            _locationEnabled.value = config.locationEnabled
            _activityEnabled.value = config.activityEnabled
            _stepsEnabled.value = config.stepsEnabled
            _wifiEnabled.value = config.wifiEnabled
            _wifiNetworkEnabled.value = config.wifiEnabled
            _wifiLocationCountEnabled.value = config.wifiLocationCountEnabled
            _cellEnabled.value = config.cellEnabled
            _transitionDetectionEnabled.value = config.useTransitionDetection
            _minDistance.value = config.minDistanceMeters
            _minTime.value = config.minTimeSeconds
            _requiredAccuracy.value = config.requiredAccuracyMeters
            _currentPreset.value = preset
            _currentBatteryImpact.value = preset.batteryImpact
            
            validateSources()
        }
    }
    
    /**
     * Mark preset as custom when user manually changes advanced settings.
     */
    private fun markCustomPreset() {
        if (_currentPreset.value != null) {
            viewModelScope.launch {
                prefs.edit { setString("tracking_preset", "CUSTOM") }
                _currentPreset.value = null // null indicates custom
                recalculateBatteryImpact()
            }
        }
    }
    
    /**
     * Recalculate battery impact based on current settings.
     */
    private fun recalculateBatteryImpact() {
        val currentSettings = TrackingPresetSettings(
            locationEnabled = _locationEnabled.value,
            requirePreciseLocation = context.hasPreciseLocationPermission,
            minDistanceMeters = _minDistance.value,
            minTimeSeconds = _minTime.value,
            requiredAccuracyMeters = _requiredAccuracy.value,
            activityEnabled = _activityEnabled.value,
            stepsEnabled = _stepsEnabled.value,
            wifiEnabled = _wifiEnabled.value,
            wifiLocationCountEnabled = _wifiLocationCountEnabled.value,
            cellEnabled = _cellEnabled.value,
            useTransitionDetection = _transitionDetectionEnabled.value
        )
        _currentBatteryImpact.value = currentSettings.calculateBatteryImpact()
    }
    
    init {
        // Calculate initial battery impact
        recalculateBatteryImpact()
    }
}
