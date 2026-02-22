package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = Preferences.getPref(context)
    
    // Auto-cleanup old data
    private val _autoCleanupEnabled = MutableStateFlow(false)
    val autoCleanupEnabled: StateFlow<Boolean> = _autoCleanupEnabled.asStateFlow()
    
    // Data retention years (stored as string in preferences)
    private val _dataRetentionYears = MutableStateFlow("1")
    val dataRetentionYears: StateFlow<String> = _dataRetentionYears.asStateFlow()
    
    init {
        viewModelScope.launch {
            PreferenceFlows.boolean(
                context,
                R.string.settings_auto_cleanup_old_data_key,
                R.string.settings_auto_cleanup_old_data_default
            ).collect { _autoCleanupEnabled.value = it }
        }
        viewModelScope.launch {
            PreferenceFlows.string(
                context,
                R.string.settings_data_retention_years_key,
                R.string.settings_data_retention_years_default
            ).collect { _dataRetentionYears.value = it }
        }
    }
    
    fun setAutoCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit { setBoolean(R.string.settings_auto_cleanup_old_data_key, enabled) }
            _autoCleanupEnabled.value = enabled
        }
    }
    
    fun setDataRetentionYears(years: String) {
        viewModelScope.launch {
            prefs.edit { setString(R.string.settings_data_retention_years_key, years) }
            _dataRetentionYears.value = years
        }
    }
}
