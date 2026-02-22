package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class StatisticsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = Preferences.getPref(context)

    private val _autoUnitSwitch = MutableStateFlow(true)
    val autoUnitSwitch: StateFlow<Boolean> = _autoUnitSwitch.asStateFlow()

    init {
        viewModelScope.launch {
            PreferenceFlows.boolean(
                context,
                com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_key,
                com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_default
            ).collect { _autoUnitSwitch.value = it }
        }
    }

    fun setAutoUnitSwitch(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit {
                setBoolean(
                    com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_key,
                    enabled
                )
            }
            _autoUnitSwitch.value = enabled
        }
    }
}
