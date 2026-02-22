package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatisticsSettingsViewModel @Inject constructor(
    private val settingsRepository: TrackerSettingsRepository
) : ViewModel() {

    private val _autoUnitSwitch = MutableStateFlow(false)
    val autoUnitSwitch: StateFlow<Boolean> = _autoUnitSwitch.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.data.collect { _autoUnitSwitch.value = it.autoUnitSwitch }
        }
    }

    fun setAutoUnitSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUnitSwitch(enabled)
        }
    }
}
