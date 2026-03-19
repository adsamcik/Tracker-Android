package com.adsamcik.tracker.shared.preferences.tracking

import android.content.Context
import android.util.Log
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
        .setWifiNetworkEnabled(PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT)
        .setWifiLocationCountEnabled(PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT)
        .setAutoTrackingMode(PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT)
        .setTransitionDetectionEnabled(PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT)
        .setNotificationStyled(PreferenceKeys.NOTIFICATION_STYLED_DEFAULT)
        .setMinDistanceMeters(PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT)
        .setMinTimeSeconds(PreferenceKeys.TRACKING_MIN_TIME_DEFAULT)
        .setRequiredAccuracyMeters(PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT)
        .setPresetName(TrackingParamsState.DEFAULT_PRESET)
        .setSkiDetectionEnabled(PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED_DEFAULT)
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

            // Read directly from SharedPreferences — the legacy source.
            val sp = PreferenceManager.getDefaultSharedPreferences(context)

            fun spBool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
            fun spInt(key: String, def: Int): Int = sp.getInt(key, def)
            fun spIntFromString(key: String, def: Int): Int =
                sp.getString(key, null)?.toIntOrNull() ?: def

            val locationEnabled = spBool(PreferenceKeys.LOCATION_ENABLED, PreferenceKeys.LOCATION_ENABLED_DEFAULT)
            val activityEnabled = spBool(PreferenceKeys.ACTIVITY_ENABLED, PreferenceKeys.ACTIVITY_ENABLED_DEFAULT)
            val stepsEnabled = spBool(PreferenceKeys.STEPS_ENABLED, PreferenceKeys.STEPS_ENABLED_DEFAULT)
            val wifiEnabled = spBool(PreferenceKeys.WIFI_ENABLED, PreferenceKeys.WIFI_ENABLED_DEFAULT)
            val cellEnabled = spBool(PreferenceKeys.CELL_ENABLED, PreferenceKeys.CELL_ENABLED_DEFAULT)
            val wifiNetworkEnabled = spBool(PreferenceKeys.WIFI_NETWORK_ENABLED, PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT)
            val wifiLocationCountEnabled = spBool(
                PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
                PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT
            )
            val autoTrackingMode = spIntFromString(
                PreferenceKeys.TRACKING_ACTIVITY_MODE,
                PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT
            )
            val transitionEnabled = spBool(
                PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED,
                PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT
            )
            val notificationStyled = spBool(
                PreferenceKeys.NOTIFICATION_STYLED,
                PreferenceKeys.NOTIFICATION_STYLED_DEFAULT
            )
            val minDistance = spInt(PreferenceKeys.TRACKING_MIN_DISTANCE, PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT)
            val minTime = spInt(PreferenceKeys.TRACKING_MIN_TIME, PreferenceKeys.TRACKING_MIN_TIME_DEFAULT)
            val requiredAccuracy = spInt(
                PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
                PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
            )
            val preset = sp.getString("tracking_preset", TrackingParamsState.DEFAULT_PRESET)
                ?: TrackingParamsState.DEFAULT_PRESET
            val skiDetectionEnabled = spBool(
                PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED,
                PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED_DEFAULT
            )

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
                    .setSkiDetectionEnabled(skiDetectionEnabled)
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
