package com.adsamcik.tracker.shared.preferences.settings

import kotlinx.coroutines.flow.Flow
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat

/** Public immutable model exposed to the app (decoupled from proto). */
data class TrackerSettingsState(
    val autoUnitSwitch: Boolean,
    val lengthSystem: LengthSystem,
    val speedFormat: SpeedFormat,
) {
    companion object {
        /** Default settings used as initial value before async load completes. */
        val DEFAULT = TrackerSettingsState(
            autoUnitSwitch = false,
            lengthSystem = LengthSystem.Metric,
            speedFormat = SpeedFormat.Hour,
        )
    }
}

/** Repository boundary for tracker settings. */
interface TrackerSettingsRepository {
    /** Continuous stream of current settings; never completes. */
    val data: Flow<TrackerSettingsState>

    /** Toggle automatic unit switching. */
    suspend fun setAutoUnitSwitch(enabled: Boolean)

    /** Set preferred length system. */
    suspend fun setLengthSystem(system: LengthSystem)

    /** Set preferred speed format. */
    suspend fun setSpeedFormat(format: SpeedFormat)
}
