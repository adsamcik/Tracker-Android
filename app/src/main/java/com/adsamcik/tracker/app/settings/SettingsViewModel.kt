package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: TrackerSettingsRepository) : ViewModel() {
    val settings: StateFlow<TrackerSettingsState> = repo.data
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TrackerSettingsState(
                autoUnitSwitch = false,
                lengthSystem = LengthSystem.Metric,
                speedFormat = SpeedFormat.Hour
            )
        )

    fun setAutoUnitSwitch(enabled: Boolean) {
        viewModelScope.launch { repo.setAutoUnitSwitch(enabled) }
    }
}
