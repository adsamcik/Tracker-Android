package com.adsamcik.tracker.app.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataSettingsUiState(
    val autoCleanupEnabled: Boolean = false,
    val dataRetentionYears: Int = RetentionConfigState.DEFAULT_RETENTION_YEARS,
    val incrementalBackupsEnabled: Boolean = true,
    val smartGoalNotificationsEnabled: Boolean = true,
)

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val retentionConfigStore: RetentionConfigStore,
    private val exportPlanStore: ExportPlanStore,
    private val preferences: Preferences,
) : ViewModel() {

    private val smartGoalNotificationsKey: String
        get() = appContext.getString(R.string.settings_smart_goal_notifications_key)

    val uiState: StateFlow<DataSettingsUiState> = combine(
        retentionConfigStore.config
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load data settings", it)
                emit(RetentionConfigState())
            },
        exportPlanStore.plans
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load export plans", it)
                emit(emptyList())
            },
        preferences.observeBoolean(smartGoalNotificationsKey, true)
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load smart goal notifications", it)
                emit(true)
            },
    ) { config, plans, smartGoalNotificationsEnabled ->
        DataSettingsUiState(
            autoCleanupEnabled = config.autoCleanupEnabled,
            dataRetentionYears = config.dataRetentionYears,
            incrementalBackupsEnabled = plans.all { it.incrementalEnabled },
            smartGoalNotificationsEnabled = smartGoalNotificationsEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSettingsUiState())

    fun setAutoCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                retentionConfigStore.update {
                    copy(autoCleanupEnabled = enabled, autoPurgeEnabled = enabled)
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update auto-cleanup", it)
            }
        }
    }

    fun setDataRetentionYears(years: Int) {
        if (years < 0) return
        viewModelScope.launch {
            runCatching {
                val retentionDays = if (years == 0) 0 else years * DAYS_PER_YEAR
                retentionConfigStore.update {
                    copy(
                        dataRetentionYears = years,
                        rawDataRetentionDays = retentionDays,
                        wifiCellRetentionDays = retentionDays,
                        tripRetentionDays = retentionDays,
                        dailySummaryRetentionDays = retentionDays,
                        explorationRetentionDays = retentionDays,
                        legacySessionRetentionDays = retentionDays,
                    )
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update retention years", it)
            }
        }
    }

    fun setIncrementalBackupsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                exportPlanStore.setAllIncrementalEnabled(enabled)
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update incremental backups", it)
            }
        }
    }

    fun setSmartGoalNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                preferences.edit {
                    setBoolean(smartGoalNotificationsKey, enabled)
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update smart goal notifications", it)
            }
        }
    }

    fun resetExportWatermarks() {
        viewModelScope.launch {
            runCatching {
                exportPlanStore.resetAllWatermarks()
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to reset export watermarks", it)
            }
        }
    }

    private companion object {
        const val DAYS_PER_YEAR = 365
    }
}
