package com.adsamcik.tracker.shared.preferences.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Repository boundary for tracking parameter settings. */
interface TrackingParamsRepository {
    /** Quality adoption preserves source participation, legacy vectors, limits and purpose consent. */
    suspend fun adoptBalanceProfile(level: Int = SourceBalanceSettings.DEFAULT_LEVEL) {
        require(level in 1..5)
        update {
            val overrides = if (balanceProfileVersion == null && locationBalanceLevel != null) {
                sourceBalanceOverrides.copy(location = locationBalanceLevel)
            } else sourceBalanceOverrides
            copy(balanceProfileVersion = SourceBalanceSettings.PROFILE_VERSION, overallBalanceLevel = level,
                sourceBalanceOverrides = overrides, locationProfileVersion = LocationAdaptiveSettings.PROFILE_VERSION,
                locationBalanceLevel = overrides.location ?: level)
        }
    }

    /** Null inherits the overall preference. Changing quality never grants participation. */
    suspend fun setSourceBalanceOverride(source: TrackingSourceComponent, level: Int?) {
        require(level == null || level in 1..5)
        update {
            require(balanceProfileVersion != null) { "Adopt an overall balance before setting overrides" }
            val overrides = sourceBalanceOverrides.with(source, level)
            copy(sourceBalanceOverrides = overrides, locationBalanceLevel = overrides.location ?: overallBalanceLevel)
        }
    }

	/** Explicit adoption changes profile metadata without rewriting historical numeric controls. */
	suspend fun adoptLocationProfile(level: Int = LocationAdaptiveSettings.DEFAULT_BALANCE_LEVEL) {
		require(level in LocationAdaptiveSettings.BALANCE_LEVELS)
		update {
			copy(locationProfileVersion = LocationAdaptiveSettings.PROFILE_VERSION, locationBalanceLevel = level,
				sourceBalanceOverrides = if (balanceProfileVersion != null) sourceBalanceOverrides.copy(location = level) else sourceBalanceOverrides)
		}
	}

	/** Save the disclosed all-day intent before any platform permission prompt. */
	suspend fun enableAdaptiveBackgroundLocation() {
		update {
			copy(
				ambientLocationEnabled = true,
				locationProfileVersion = LocationAdaptiveSettings.PROFILE_VERSION,
				locationBalanceLevel = locationBalanceLevel ?: LocationAdaptiveSettings.DEFAULT_BALANCE_LEVEL,
				locationBackgroundMode = LocationBackgroundMode.ADAPTIVE,
			)
		}
	}

	suspend fun setLocationBackgroundMode(mode: LocationBackgroundMode) {
		update {
			copy(
				locationBackgroundMode = mode,
				locationProfileVersion = if (mode == LocationBackgroundMode.ADAPTIVE) {
					LocationAdaptiveSettings.PROFILE_VERSION
				} else locationProfileVersion,
				locationBalanceLevel = if (mode == LocationBackgroundMode.ADAPTIVE) {
					locationBalanceLevel ?: LocationAdaptiveSettings.DEFAULT_BALANCE_LEVEL
				} else locationBalanceLevel,
			)
		}
	}

	suspend fun setLocationMotionAssistanceEnabled(enabled: Boolean) {
		update { copy(locationMotionAssistanceEnabled = enabled) }
	}

    /** Continuous stream of current tracking params; never completes. */
    val data: Flow<TrackingParamsState>

    /** Bulk-update all params at once (used by preset application). */
    suspend fun update(block: TrackingParamsState.() -> TrackingParamsState)

    /** Update a single toggle or parameter. */
    suspend fun setLocationEnabled(enabled: Boolean)
    suspend fun setActivityEnabled(enabled: Boolean)
    suspend fun setStepsEnabled(enabled: Boolean)
    suspend fun setWifiEnabled(enabled: Boolean)
    suspend fun setCellEnabled(enabled: Boolean)
    suspend fun setBarometerEnabled(enabled: Boolean)
	/** Compatibility entry point; Steps have one all-day choice. */
	suspend fun setAmbientStepsEnabled(enabled: Boolean) {
		setStepsEnabled(enabled)
	}
	/** Changes only sessionless Ambient Location consent; live session Location remains independent. */
	suspend fun setAmbientLocationEnabled(enabled: Boolean) {
		update { copy(ambientLocationEnabled = enabled) }
	}
	/** Changes only sessionless Ambient Wi-Fi consent; live session Wi-Fi remains independent. */
	suspend fun setAmbientWifiEnabled(enabled: Boolean) {
		update { copy(ambientWifiEnabled = enabled) }
	}
	/** Changes only sessionless Ambient Cell consent; live session Cell remains independent. */
	suspend fun setAmbientCellEnabled(enabled: Boolean) {
		update { copy(ambientCellEnabled = enabled) }
	}
	/** Hosting changes execution only, without granting recording consent or active scan budget. */
	suspend fun setBackgroundWifiNotificationEnabled(enabled: Boolean) {
		update { copy(backgroundWifiNotificationEnabled = enabled) }
	}
	suspend fun setBackgroundCellNotificationEnabled(enabled: Boolean) {
		update { copy(backgroundCellNotificationEnabled = enabled) }
	}
    suspend fun setTransitionDetectionEnabled(enabled: Boolean)
    suspend fun setNotificationStyled(enabled: Boolean)
    suspend fun setMinDistanceMeters(meters: Int)
    suspend fun setMinTimeSeconds(seconds: Int)
    suspend fun setRequiredAccuracyMeters(meters: Int)
    suspend fun setPreset(preset: TrackingPreset)

	/**
	 * Returns settings only when their legacy migration durably completed. Policy bootstrap must
	 * use this boundary instead of treating serializer defaults as verified user configuration.
	 */
	suspend fun verifiedSnapshotForPolicyBootstrap(): TrackingParamsState = data.first().also { state ->
		if (!state.legacySettingsMigrationCompleted) {
			throw LegacySourceSettingsUnavailableException()
		}
	}
	suspend fun setSourceFrequency(component: TrackingSourceComponent, frequency: SourceCollectionFrequency) {
		update {
			require(balanceProfileVersion == null) { "Legacy frequency editing requires a legacy/custom configuration" }
			val current = sourceCollectionSettings
			val next = when (component) {
				TrackingSourceComponent.LOCATION -> current.copy(location = frequency)
				TrackingSourceComponent.ACTIVITY -> current.copy(activity = frequency)
				TrackingSourceComponent.STEPS -> current.copy(steps = frequency)
				TrackingSourceComponent.PRESSURE -> current.copy(pressure = frequency)
				TrackingSourceComponent.WIFI -> current.copy(wifi = frequency)
				TrackingSourceComponent.CELL -> current.copy(cell = frequency)
			}
			copy(sourceCollectionSettings = next)
		}
	}

	suspend fun setAdvancedSourceControlsEnabled(enabled: Boolean) {
		update { copy(advancedSourceControlsEnabled = enabled) }
	}
}
