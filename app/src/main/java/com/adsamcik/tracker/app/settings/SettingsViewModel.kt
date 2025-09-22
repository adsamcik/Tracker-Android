package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: TrackerSettingsRepository) : ViewModel() {
    val settings: StateFlow<TrackerSettingsState> = repo.data
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerSettingsState(autoUnitSwitch = false))

    fun setAutoUnitSwitch(enabled: Boolean) {
        viewModelScope.launch { repo.setAutoUnitSwitch(enabled) }
    }
}
