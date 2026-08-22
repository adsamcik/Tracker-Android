package com.adsamcik.tracker.shared.preferences.tracking

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.preference.PreferenceManager
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

private object TrackingParamsSerializer : Serializer<TrackingParamsProto> {
    override val defaultValue: TrackingParamsProto = TrackingParamsProto.newBuilder()
        .setLocationEnabled(PreferenceKeys.LOCATION_ENABLED_DEFAULT)
        .setActivityEnabled(PreferenceKeys.ACTIVITY_ENABLED_DEFAULT)
        .setStepsEnabled(PreferenceKeys.STEPS_ENABLED_DEFAULT)
        .setWifiEnabled(PreferenceKeys.WIFI_ENABLED_DEFAULT)
        .setCellEnabled(PreferenceKeys.CELL_ENABLED_DEFAULT)
        .setBarometerEnabled(PreferenceKeys.BAROMETER_ENABLED_DEFAULT)
        .setAutoTrackingMode(PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT)
        .setTransitionDetectionEnabled(PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT)
        .setNotificationStyled(PreferenceKeys.NOTIFICATION_STYLED_DEFAULT)
        .setMinDistanceMeters(PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT)
        .setMinTimeSeconds(PreferenceKeys.TRACKING_MIN_TIME_DEFAULT)
        .setRequiredAccuracyMeters(PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT)
        .setPresetName(TrackingParamsState.DEFAULT_PRESET)
		.setLocationFrequency(SourceCollectionFrequency.BALANCED.stableCode)
		.setActivityFrequency(SourceCollectionFrequency.BALANCED.stableCode)
		.setStepsFrequency(SourceCollectionFrequency.BALANCED.stableCode)
		.setPressureFrequency(SourceCollectionFrequency.BALANCED.stableCode)
		.setWifiFrequency(SourceCollectionFrequency.OFF.stableCode)
		.setCellFrequency(SourceCollectionFrequency.OFF.stableCode)
		.setSourceSettingsVersion(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION)
        .setLegacyMigrated(false)
        .build()

    override suspend fun readFrom(input: InputStream): TrackingParamsProto =
        TrackingParamsProto.parseFrom(input)

    override suspend fun writeTo(t: TrackingParamsProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.trackingParamsDataStore: DataStore<TrackingParamsProto> by dataStore(
    fileName = "tracking_params.pb",
    serializer = TrackingParamsSerializer
)

internal fun resetTrackingParamsForTests() {
    val fileClass = Class.forName("com.adsamcik.tracker.shared.preferences.tracking.DefaultTrackingParamsRepositoryKt")
    val delegateField = fileClass.getDeclaredField("trackingParamsDataStore\$delegate").apply { isAccessible = true }
    val delegate = delegateField.get(null)
    val instanceField = delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
    instanceField.set(delegate, null)
}

/** DataStore-backed implementation of [TrackingParamsRepository]. */
class DefaultTrackingParamsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : TrackingParamsRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val data: Flow<TrackingParamsState> = context.trackingParamsDataStore.data
        .onStart { ensureMigrated() }
        .map { it.toDomain() }
		.transformLatest { state ->
			emit(state)
			while (!state.legacySettingsMigrationCompleted) {
				delay(MIGRATION_RETRY_DELAY_MS)
				if (ensureMigrated()) return@transformLatest
			}
		}

    override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
        withContext(io) {
            ensureMigrated()
            context.trackingParamsDataStore.updateData { current ->
                val newState = current.toDomain().block()
                newState.toProto()
            }
        }
    }

    override suspend fun setLocationEnabled(enabled: Boolean) = update {
		copy(
			locationEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				location = sourceCollectionSettings.location.forEnabled(enabled),
			),
		)
	}
    override suspend fun setActivityEnabled(enabled: Boolean) = update {
		copy(
			activityEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				activity = sourceCollectionSettings.activity.forEnabled(enabled),
			),
		)
	}
    override suspend fun setStepsEnabled(enabled: Boolean) = update {
		copy(
			stepsEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				steps = sourceCollectionSettings.steps.forEnabled(enabled),
			),
		)
	}
    override suspend fun setWifiEnabled(enabled: Boolean) = update {
		copy(
			wifiEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				wifi = sourceCollectionSettings.wifi.forEnabled(enabled),
			),
		)
	}
    override suspend fun setCellEnabled(enabled: Boolean) = update {
		copy(
			cellEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				cell = sourceCollectionSettings.cell.forEnabled(enabled),
			),
		)
	}
    override suspend fun setBarometerEnabled(enabled: Boolean) = update {
		copy(
			barometerEnabled = enabled,
			sourceCollectionSettings = sourceCollectionSettings.copy(
				pressure = sourceCollectionSettings.pressure.forEnabled(enabled),
			),
		)
	}
    override suspend fun setTransitionDetectionEnabled(enabled: Boolean) = updateField { setTransitionDetectionEnabled(enabled) }
    override suspend fun setNotificationStyled(enabled: Boolean) = updateField { setNotificationStyled(enabled) }
    override suspend fun setMinDistanceMeters(meters: Int) = updateField { setMinDistanceMeters(meters.coerceAtLeast(1)) }
    override suspend fun setMinTimeSeconds(seconds: Int) = updateField { setMinTimeSeconds(seconds.coerceAtLeast(1)) }
    override suspend fun setRequiredAccuracyMeters(meters: Int) = updateField { setRequiredAccuracyMeters(meters.coerceAtLeast(1)) }
    override suspend fun setPreset(preset: TrackingPreset) = updateField { setPresetName(preset.name) }

	override suspend fun setSourceFrequency(
		component: TrackingSourceComponent,
		frequency: SourceCollectionFrequency,
	) = update {
		val next = when (component) {
			TrackingSourceComponent.LOCATION -> sourceCollectionSettings.copy(location = frequency)
			TrackingSourceComponent.ACTIVITY -> sourceCollectionSettings.copy(activity = frequency)
			TrackingSourceComponent.STEPS -> sourceCollectionSettings.copy(steps = frequency)
			TrackingSourceComponent.PRESSURE -> sourceCollectionSettings.copy(pressure = frequency)
			TrackingSourceComponent.WIFI -> sourceCollectionSettings.copy(wifi = frequency)
			TrackingSourceComponent.CELL -> sourceCollectionSettings.copy(cell = frequency)
		}
		copy(
			locationEnabled = if (component == TrackingSourceComponent.LOCATION) frequency != SourceCollectionFrequency.OFF else locationEnabled,
			activityEnabled = if (component == TrackingSourceComponent.ACTIVITY) frequency != SourceCollectionFrequency.OFF else activityEnabled,
			stepsEnabled = if (component == TrackingSourceComponent.STEPS) frequency != SourceCollectionFrequency.OFF else stepsEnabled,
			barometerEnabled = if (component == TrackingSourceComponent.PRESSURE) frequency != SourceCollectionFrequency.OFF else barometerEnabled,
			wifiEnabled = if (component == TrackingSourceComponent.WIFI) frequency != SourceCollectionFrequency.OFF else wifiEnabled,
			cellEnabled = if (component == TrackingSourceComponent.CELL) frequency != SourceCollectionFrequency.OFF else cellEnabled,
			sourceCollectionSettings = next,
			sourceSettingsVersion = TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION,
		)
	}

	override suspend fun setAdvancedSourceControlsEnabled(enabled: Boolean) = update {
		copy(advancedSourceControlsEnabled = enabled)
	}

    @Suppress("DEPRECATION")
    private suspend fun updateField(block: TrackingParamsProto.Builder.() -> TrackingParamsProto.Builder) {
        withContext(io) {
            ensureMigrated()
            context.trackingParamsDataStore.updateData { current ->
                val wifiEnabled = current.wifiEnabled ||
                    current.wifiNetworkEnabled ||
                    current.wifiLocationCountEnabled
                current.toBuilder()
                    .setWifiEnabled(wifiEnabled)
                    .clearWifiNetworkEnabled()
                    .clearWifiLocationCountEnabled()
                    .block()
                    .setLegacyMigrated(true)
                    .build()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun ensureMigrated(): Boolean = withContext(io) {
        try {
            val current = context.trackingParamsDataStore.data.first()
            if (current.legacyMigrated) {
                if (current.sourceSettingsVersion < TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION) {
                    context.trackingParamsDataStore.updateData { stored ->
                        stored.withCurrentSourceSettings()
                    }
                }
				return@withContext true
            }

            // Read directly from SharedPreferences — the legacy source.
            val sp = PreferenceManager.getDefaultSharedPreferences(context)

            // Read each field independently. SharedPreferences throws ClassCastException when an
            // older or damaged install contains a value with the wrong type; one bad field must
            // not discard every other valid tracking preference during upgrade.
            fun spBool(key: String, def: Boolean): Boolean = when (val value = sp.all[key]) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull() ?: def
                is Number -> value.toInt() != 0
                else -> def
            }
            fun spInt(key: String, def: Int): Int = when (val value = sp.all[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: def
                else -> def
            }
            fun spString(key: String, def: String): String = when (val value = sp.all[key]) {
                is String -> value
                else -> def
            }

            val locationEnabled = spBool(PreferenceKeys.LOCATION_ENABLED, PreferenceKeys.LOCATION_ENABLED_DEFAULT)
            val activityEnabled = spBool(PreferenceKeys.ACTIVITY_ENABLED, PreferenceKeys.ACTIVITY_ENABLED_DEFAULT)
            val stepsEnabled = spBool(PreferenceKeys.STEPS_ENABLED, PreferenceKeys.STEPS_ENABLED_DEFAULT)
            val wifiEnabled = spBool(PreferenceKeys.WIFI_ENABLED, PreferenceKeys.WIFI_ENABLED_DEFAULT) ||
                spBool(PreferenceKeys.WIFI_NETWORK_ENABLED, PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT) ||
                spBool(
                    PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
                    PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT,
                )
            val cellEnabled = spBool(PreferenceKeys.CELL_ENABLED, PreferenceKeys.CELL_ENABLED_DEFAULT)
            val barometerEnabled = spBool(
                PreferenceKeys.BAROMETER_ENABLED,
                PreferenceKeys.BAROMETER_ENABLED_DEFAULT,
            )
            val autoTrackingMode = spInt(
                PreferenceKeys.TRACKING_ACTIVITY_MODE,
                PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT
            ).takeIf { it in 0..2 } ?: PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT
            val transitionEnabled = spBool(
                PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED,
                PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT
            )
            val notificationStyled = spBool(
                PreferenceKeys.NOTIFICATION_STYLED,
                PreferenceKeys.NOTIFICATION_STYLED_DEFAULT
            )
            val minDistance = spInt(
                PreferenceKeys.TRACKING_MIN_DISTANCE,
                PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT,
            ).takeIf { it > 0 } ?: PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT
            val minTime = spInt(
                PreferenceKeys.TRACKING_MIN_TIME,
                PreferenceKeys.TRACKING_MIN_TIME_DEFAULT,
            ).takeIf { it > 0 } ?: PreferenceKeys.TRACKING_MIN_TIME_DEFAULT
            val requiredAccuracy = spInt(
                PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
                PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
            ).takeIf { it > 0 } ?: PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
            val preset = TrackingPreset.fromName(
                spString("tracking_preset", TrackingParamsState.DEFAULT_PRESET),
            ).name
            context.trackingParamsDataStore.updateData {
                TrackingParamsProto.newBuilder()
                    .setLocationEnabled(locationEnabled)
                    .setActivityEnabled(activityEnabled)
                    .setStepsEnabled(stepsEnabled)
                    .setWifiEnabled(wifiEnabled)
                    .setCellEnabled(cellEnabled)
                    .setBarometerEnabled(barometerEnabled)
                    .setAutoTrackingMode(autoTrackingMode)
                    .setTransitionDetectionEnabled(transitionEnabled)
                    .setNotificationStyled(notificationStyled)
                    .setMinDistanceMeters(minDistance)
                    .setMinTimeSeconds(minTime)
                    .setRequiredAccuracyMeters(requiredAccuracy)
                    .setPresetName(preset)
                    .setLegacyMigrated(true)
                    .build()
                    .withCurrentSourceSettings()
            }
			true
		} catch (_: Exception) {
			currentCoroutineContext().ensureActive()
			// Leave migration markers untouched so a transient I/O or semantic failure can be
			// retried. Never replace unreadable retained intent with enable-by-default values.
			false
        }
    }

	private companion object {
		const val MIGRATION_RETRY_DELAY_MS = 250L
	}
}

@Suppress("DEPRECATION")
private fun TrackingParamsProto.toDomain(): TrackingParamsState {
	if (!legacyMigrated) {
		return TrackingParamsState(legacySettingsMigrationCompleted = false)
	}
    val preset = TrackingPreset.fromName(presetName)
    val usePresetCadence = preset == TrackingPreset.HIGH_ACCURACY &&
        minDistanceMeters == 5 &&
        minTimeSeconds == 1 &&
        requiredAccuracyMeters == 100
    return TrackingParamsState(
        locationEnabled = locationEnabled,
        activityEnabled = activityEnabled,
        stepsEnabled = stepsEnabled,
        // Versions before the unified Wi-Fi source toggle stored output choices separately.
        // Folding them here also repairs already-migrated DataStore files without another marker.
        wifiEnabled = wifiEnabled || wifiNetworkEnabled || wifiLocationCountEnabled,
        cellEnabled = cellEnabled,
        barometerEnabled = if (hasBarometerEnabled()) {
            barometerEnabled
        } else {
            PreferenceKeys.BAROMETER_ENABLED_DEFAULT
        },
        autoTrackingMode = autoTrackingMode,
        transitionDetectionEnabled = transitionDetectionEnabled,
        notificationStyled = notificationStyled,
        minDistanceMeters = if (usePresetCadence) preset.minDistanceMeters
            else minDistanceMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_DISTANCE,
        minTimeSeconds = if (usePresetCadence) preset.minTimeSeconds
            else minTimeSeconds.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_TIME,
        requiredAccuracyMeters = if (usePresetCadence) preset.requiredAccuracyMeters
            else requiredAccuracyMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_REQUIRED_ACCURACY,
        presetName = preset.name,
		sourceCollectionSettings = SourceCollectionSettings(
			location = frequencyOrLegacy(hasLocationFrequency(), locationFrequency, locationEnabled),
			activity = frequencyOrLegacy(hasActivityFrequency(), activityFrequency, activityEnabled),
			steps = frequencyOrLegacy(hasStepsFrequency(), stepsFrequency, stepsEnabled),
			pressure = frequencyOrLegacy(hasPressureFrequency(), pressureFrequency, if (hasBarometerEnabled()) barometerEnabled else true),
			wifi = frequencyOrLegacy(hasWifiFrequency(), wifiFrequency, wifiEnabled || wifiNetworkEnabled || wifiLocationCountEnabled),
			cell = frequencyOrLegacy(hasCellFrequency(), cellFrequency, cellEnabled),
		),
		advancedSourceControlsEnabled = advancedSourceControlsEnabled,
		sourceSettingsVersion = sourceSettingsVersion.takeIf { it > 0 }
			?: TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION,
		legacySettingsMigrationCompleted = true,
    )
}

private fun TrackingParamsState.toProto(): TrackingParamsProto =
    TrackingParamsProto.newBuilder()
        .setLocationEnabled(locationEnabled)
        .setActivityEnabled(activityEnabled)
        .setStepsEnabled(stepsEnabled)
        .setWifiEnabled(wifiEnabled)
        .setCellEnabled(cellEnabled)
        .setBarometerEnabled(barometerEnabled)
        .setAutoTrackingMode(autoTrackingMode)
        .setTransitionDetectionEnabled(transitionDetectionEnabled)
        .setNotificationStyled(notificationStyled)
        .setMinDistanceMeters(minDistanceMeters)
        .setMinTimeSeconds(minTimeSeconds)
        .setRequiredAccuracyMeters(requiredAccuracyMeters)
        .setPresetName(presetName)
		.setLocationFrequency(sourceCollectionSettings.location.stableCode)
		.setActivityFrequency(sourceCollectionSettings.activity.stableCode)
		.setStepsFrequency(sourceCollectionSettings.steps.stableCode)
		.setPressureFrequency(sourceCollectionSettings.pressure.stableCode)
		.setWifiFrequency(sourceCollectionSettings.wifi.stableCode)
		.setCellFrequency(sourceCollectionSettings.cell.stableCode)
		.setAdvancedSourceControlsEnabled(advancedSourceControlsEnabled)
		.setSourceSettingsVersion(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION)
        .setLegacyMigrated(true)
        .build()

private fun frequencyOrLegacy(
	hasSemanticValue: Boolean,
	semanticValue: Int,
	legacyEnabled: Boolean,
): SourceCollectionFrequency = if (hasSemanticValue) {
	SourceCollectionFrequency.fromStableCode(semanticValue)
} else if (legacyEnabled) {
	SourceCollectionFrequency.BALANCED
} else {
	SourceCollectionFrequency.OFF
}

@Suppress("DEPRECATION")
private fun TrackingParamsProto.withCurrentSourceSettings(): TrackingParamsProto {
	if (sourceSettingsVersion >= TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION) return this

	val effectiveWifiEnabled = wifiEnabled || wifiNetworkEnabled || wifiLocationCountEnabled
	val effectiveBarometerEnabled = if (hasBarometerEnabled()) {
		barometerEnabled
	} else {
		PreferenceKeys.BAROMETER_ENABLED_DEFAULT
	}
	val normalizedPreset = TrackingPreset.fromName(presetName)
	val usesLegacyHighAccuracyCadence = normalizedPreset == TrackingPreset.HIGH_ACCURACY &&
		minDistanceMeters == 5 && minTimeSeconds == 1 && requiredAccuracyMeters == 100
	val normalizedMinDistance = if (usesLegacyHighAccuracyCadence) {
		normalizedPreset.minDistanceMeters
	} else {
		minDistanceMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_DISTANCE
	}
	val normalizedMinTime = if (usesLegacyHighAccuracyCadence) {
		normalizedPreset.minTimeSeconds
	} else {
		minTimeSeconds.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_TIME
	}
	val normalizedRequiredAccuracy = if (usesLegacyHighAccuracyCadence) {
		normalizedPreset.requiredAccuracyMeters
	} else {
		requiredAccuracyMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_REQUIRED_ACCURACY
	}
	fun migratedFrequency(
		hasSemanticValue: Boolean,
		semanticValue: Int,
		legacyEnabled: Boolean,
	): Int = frequencyOrLegacy(hasSemanticValue, semanticValue, legacyEnabled).stableCode

	return toBuilder()
		.setWifiEnabled(effectiveWifiEnabled)
		.clearWifiNetworkEnabled()
		.clearWifiLocationCountEnabled()
		.setBarometerEnabled(effectiveBarometerEnabled)
		.setAutoTrackingMode(
			autoTrackingMode.takeIf { it in 0..2 } ?: PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT,
		)
		.setMinDistanceMeters(normalizedMinDistance)
		.setMinTimeSeconds(normalizedMinTime)
		.setRequiredAccuracyMeters(normalizedRequiredAccuracy)
		.setPresetName(normalizedPreset.name)
		.setLocationFrequency(migratedFrequency(hasLocationFrequency(), locationFrequency, locationEnabled))
		.setActivityFrequency(migratedFrequency(hasActivityFrequency(), activityFrequency, activityEnabled))
		.setStepsFrequency(migratedFrequency(hasStepsFrequency(), stepsFrequency, stepsEnabled))
		.setPressureFrequency(
			migratedFrequency(
				hasPressureFrequency(),
				pressureFrequency,
				effectiveBarometerEnabled,
			),
		)
		.setWifiFrequency(migratedFrequency(hasWifiFrequency(), wifiFrequency, effectiveWifiEnabled))
		.setCellFrequency(migratedFrequency(hasCellFrequency(), cellFrequency, cellEnabled))
		.setSourceSettingsVersion(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION)
		.setLegacyMigrated(true)
		.build()
}

private fun SourceCollectionFrequency.forEnabled(enabled: Boolean): SourceCollectionFrequency = when {
	!enabled -> SourceCollectionFrequency.OFF
	this == SourceCollectionFrequency.OFF -> SourceCollectionFrequency.BALANCED
	else -> this
}
