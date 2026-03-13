package com.adsamcik.tracker.shared.preferences.tracking

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import com.adsamcik.tracker.shared.preferences.R as PrefR

private object TrackingParamsSerializer : Serializer<TrackingParamsProto> {
    override val defaultValue: TrackingParamsProto = TrackingParamsProto.newBuilder()
        .setLocationEnabled(true)
        .setActivityEnabled(true)
        .setStepsEnabled(true)
        .setWifiEnabled(false)
        .setCellEnabled(false)
        .setWifiNetworkEnabled(false)
        .setWifiLocationCountEnabled(false)
        .setAutoTrackingMode(1)
        .setTransitionDetectionEnabled(true)
        .setNotificationStyled(true)
        .setMinDistanceMeters(TrackingParamsState.DEFAULT_MIN_DISTANCE)
        .setMinTimeSeconds(TrackingParamsState.DEFAULT_MIN_TIME)
        .setRequiredAccuracyMeters(TrackingParamsState.DEFAULT_REQUIRED_ACCURACY)
        .setPresetName(TrackingParamsState.DEFAULT_PRESET)
        .setSkiDetectionEnabled(false)
        .setLegacyMigrated(false)
        .build()

    override suspend fun readFrom(input: InputStream): TrackingParamsProto = try {
        TrackingParamsProto.parseFrom(input)
    } catch (e: Exception) {
        Log.w("TrackingParams", "Corruption reading tracking params proto – using defaults", e)
        defaultValue
    }

    override suspend fun writeTo(t: TrackingParamsProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.trackingParamsDataStore: DataStore<TrackingParamsProto> by dataStore(
    fileName = "tracking_params.pb",
    serializer = TrackingParamsSerializer
)

/** DataStore-backed implementation of [TrackingParamsRepository]. */
class DefaultTrackingParamsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : TrackingParamsRepository {

    override val data: Flow<TrackingParamsState> = context.trackingParamsDataStore.data
        .onStart { ensureMigrated() }
        .map { it.toDomain() }

    override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
        withContext(io) {
            context.trackingParamsDataStore.updateData { current ->
                val newState = current.toDomain().block()
                newState.toProto()
            }
        }
    }

    override suspend fun setLocationEnabled(enabled: Boolean) = updateField { setLocationEnabled(enabled) }
    override suspend fun setActivityEnabled(enabled: Boolean) = updateField { setActivityEnabled(enabled) }
    override suspend fun setStepsEnabled(enabled: Boolean) = updateField { setStepsEnabled(enabled) }
    override suspend fun setWifiEnabled(enabled: Boolean) = updateField { setWifiEnabled(enabled) }
    override suspend fun setCellEnabled(enabled: Boolean) = updateField { setCellEnabled(enabled) }
    override suspend fun setWifiNetworkEnabled(enabled: Boolean) = updateField { setWifiNetworkEnabled(enabled) }
    override suspend fun setWifiLocationCountEnabled(enabled: Boolean) = updateField { setWifiLocationCountEnabled(enabled) }
    override suspend fun setTransitionDetectionEnabled(enabled: Boolean) = updateField { setTransitionDetectionEnabled(enabled) }
    override suspend fun setNotificationStyled(enabled: Boolean) = updateField { setNotificationStyled(enabled) }
    override suspend fun setMinDistanceMeters(meters: Int) = updateField { setMinDistanceMeters(meters.coerceAtLeast(1)) }
    override suspend fun setMinTimeSeconds(seconds: Int) = updateField { setMinTimeSeconds(seconds.coerceAtLeast(1)) }
    override suspend fun setRequiredAccuracyMeters(meters: Int) = updateField { setRequiredAccuracyMeters(meters.coerceAtLeast(1)) }
    override suspend fun setPreset(preset: TrackingPreset) = updateField { setPresetName(preset.name) }
    override suspend fun setSkiDetectionEnabled(enabled: Boolean) = updateField { setSkiDetectionEnabled(enabled) }

    private suspend fun updateField(block: TrackingParamsProto.Builder.() -> TrackingParamsProto.Builder) {
        withContext(io) {
            context.trackingParamsDataStore.updateData { current ->
                current.toBuilder().block().build()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun ensureMigrated() = withContext(io) {
        try {
            val current = context.trackingParamsDataStore.data.first()
            if (current.legacyMigrated) return@withContext

            val prefs = Preferences(context)

            val locationEnabled = prefs.getBooleanRes(PrefR.string.settings_location_enabled_key, PrefR.string.settings_location_enabled_default)
            val activityEnabled = prefs.getBooleanRes(PrefR.string.settings_activity_enabled_key, PrefR.string.settings_activity_enabled_default)
            val stepsEnabled = prefs.getBooleanRes(PrefR.string.settings_steps_enabled_key, PrefR.string.settings_steps_enabled_default)
            val wifiEnabled = prefs.getBoolean(context.getString(PrefR.string.settings_wifi_enabled_key), true)
            val cellEnabled = prefs.getBooleanRes(PrefR.string.settings_cell_enabled_key, PrefR.string.settings_cell_enabled_default)
            val wifiNetworkEnabled = prefs.getBooleanRes(PrefR.string.settings_wifi_network_enabled_key, PrefR.string.settings_wifi_network_enabled_default)
            val wifiLocationCountEnabled = prefs.getBooleanRes(PrefR.string.settings_wifi_location_count_enabled_key, PrefR.string.settings_wifi_location_count_enabled_default)
            val autoTrackingMode = prefs.getIntResString(PrefR.string.settings_tracking_activity_key, PrefR.string.settings_tracking_activity_default)
            val transitionEnabled = prefs.getBooleanRes(PrefR.string.settings_auto_tracking_transition_key, PrefR.string.settings_auto_tracking_transition_default)
            val notificationStyled = prefs.getBooleanRes(PrefR.string.settings_notification_styled_key, PrefR.string.settings_notification_styled_default)
            val minDistance = prefs.getInt(context.getString(PrefR.string.settings_tracking_min_distance_key), TrackingParamsState.DEFAULT_MIN_DISTANCE)
            val minTime = prefs.getInt(context.getString(PrefR.string.settings_tracking_min_time_key), TrackingParamsState.DEFAULT_MIN_TIME)
            val requiredAccuracy = prefs.getInt(context.getString(PrefR.string.settings_tracking_required_accuracy_key), TrackingParamsState.DEFAULT_REQUIRED_ACCURACY)
            val preset = prefs.getString("tracking_preset", TrackingParamsState.DEFAULT_PRESET)

            context.trackingParamsDataStore.updateData {
                TrackingParamsProto.newBuilder()
                    .setLocationEnabled(locationEnabled)
                    .setActivityEnabled(activityEnabled)
                    .setStepsEnabled(stepsEnabled)
                    .setWifiEnabled(wifiEnabled)
                    .setCellEnabled(cellEnabled)
                    .setWifiNetworkEnabled(wifiNetworkEnabled)
                    .setWifiLocationCountEnabled(wifiLocationCountEnabled)
                    .setAutoTrackingMode(autoTrackingMode)
                    .setTransitionDetectionEnabled(transitionEnabled)
                    .setNotificationStyled(notificationStyled)
                    .setMinDistanceMeters(minDistance)
                    .setMinTimeSeconds(minTime)
                    .setRequiredAccuracyMeters(requiredAccuracy)
                    .setPresetName(preset)
                    .setLegacyMigrated(true)
                    .build()
            }
        } catch (e: Exception) {
            Log.w("TrackingParams", "Legacy migration failed – using defaults", e)
            try {
                context.trackingParamsDataStore.updateData { current ->
                    current.toBuilder().setLegacyMigrated(true).build()
                }
            } catch (e2: Exception) {
                Log.e("TrackingParams", "Failed to mark migration complete", e2)
            }
        }
    }
}

private fun TrackingParamsProto.toDomain(): TrackingParamsState {
    if (!legacyMigrated) return TrackingParamsState()
    return TrackingParamsState(
        locationEnabled = locationEnabled,
        activityEnabled = activityEnabled,
        stepsEnabled = stepsEnabled,
        wifiEnabled = wifiEnabled,
        cellEnabled = cellEnabled,
        wifiNetworkEnabled = wifiNetworkEnabled,
        wifiLocationCountEnabled = wifiLocationCountEnabled,
        autoTrackingMode = autoTrackingMode,
        transitionDetectionEnabled = transitionDetectionEnabled,
        notificationStyled = notificationStyled,
        minDistanceMeters = minDistanceMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_DISTANCE,
        minTimeSeconds = minTimeSeconds.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_MIN_TIME,
        requiredAccuracyMeters = requiredAccuracyMeters.takeIf { it > 0 } ?: TrackingParamsState.DEFAULT_REQUIRED_ACCURACY,
        presetName = TrackingPreset.fromName(presetName).name,
        skiDetectionEnabled = skiDetectionEnabled,
    )
}

private fun TrackingParamsState.toProto(): TrackingParamsProto =
    TrackingParamsProto.newBuilder()
        .setLocationEnabled(locationEnabled)
        .setActivityEnabled(activityEnabled)
        .setStepsEnabled(stepsEnabled)
        .setWifiEnabled(wifiEnabled)
        .setCellEnabled(cellEnabled)
        .setWifiNetworkEnabled(wifiNetworkEnabled)
        .setWifiLocationCountEnabled(wifiLocationCountEnabled)
        .setAutoTrackingMode(autoTrackingMode)
        .setTransitionDetectionEnabled(transitionDetectionEnabled)
        .setNotificationStyled(notificationStyled)
        .setMinDistanceMeters(minDistanceMeters)
        .setMinTimeSeconds(minTimeSeconds)
        .setRequiredAccuracyMeters(requiredAccuracyMeters)
        .setPresetName(presetName)
        .setSkiDetectionEnabled(skiDetectionEnabled)
        .setLegacyMigrated(true)
        .build()
