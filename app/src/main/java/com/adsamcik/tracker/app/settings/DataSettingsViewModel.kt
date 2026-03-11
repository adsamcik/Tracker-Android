package com.adsamcik.tracker.app.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataSettingsUiState(
    val autoCleanupEnabled: Boolean = false,
    val dataRetentionYears: Int = RetentionConfigState.DEFAULT_RETENTION_YEARS,
)

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    private val retentionConfigStore: RetentionConfigStore,
) : ViewModel() {

    val uiState: StateFlow<DataSettingsUiState> = retentionConfigStore.config
        .catch {
            Log.e("DataSettingsViewModel", "Failed to load data settings", it)
            emit(RetentionConfigState())
        }
        .map { config ->
            DataSettingsUiState(
                autoCleanupEnabled = config.autoCleanupEnabled,
                dataRetentionYears = config.dataRetentionYears,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSettingsUiState())

    fun setAutoCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                retentionConfigStore.update { copy(autoCleanupEnabled = enabled) }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update auto-cleanup", it)
            }
        }
    }

    fun setDataRetentionYears(years: Int) {
        if (years <= 0) return
        viewModelScope.launch {
            runCatching {
                retentionConfigStore.update { copy(dataRetentionYears = years) }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update retention years", it)
            }
        }
    }
}
