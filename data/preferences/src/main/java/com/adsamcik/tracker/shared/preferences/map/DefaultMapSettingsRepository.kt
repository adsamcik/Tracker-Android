package com.adsamcik.tracker.shared.preferences.map

import android.content.Context
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

private object MapSettingsSerializer : Serializer<MapSettingsProto> {
    override val defaultValue: MapSettingsProto = MapSettingsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): MapSettingsProto = try {
        MapSettingsProto.parseFrom(input)
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: MapSettingsProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.mapSettingsDataStore: DataStore<MapSettingsProto> by dataStore(
    fileName = "map_settings.pb",
    serializer = MapSettingsSerializer
)

/** DataStore-backed implementation of [MapSettingsRepository]. */
class DefaultMapSettingsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : MapSettingsRepository {

    override val data: Flow<MapSettingsState> = context.mapSettingsDataStore.data
        .onStart { ensureMigrated() }
        .map { it.toDomain() }

    override suspend fun setQuality(quality: Float) {
        withContext(io) {
            context.mapSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setQuality(quality)
                    .build()
            }
        }
    }

    override suspend fun setMaxHeatPoints(maxHeat: Int) {
        withContext(io) {
            context.mapSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setMaxHeatPoints(maxHeat.coerceAtLeast(1))
                    .build()
            }
        }
    }

    override suspend fun setVisitThresholdSeconds(seconds: Int) {
        withContext(io) {
            context.mapSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setVisitThresholdSeconds(seconds.coerceAtLeast(0))
                    .build()
            }
        }
    }

    override suspend fun setLegacyHeatmapEnabled(enabled: Boolean) {
        withContext(io) {
            context.mapSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setLegacyHeatmapEnabled(enabled)
                    .build()
            }
        }
    }

    override suspend fun setZoomButtonsEnabled(enabled: Boolean) {
        withContext(io) {
            context.mapSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setZoomButtonsEnabled(enabled)
                    .build()
            }
        }
    }

    private suspend fun ensureMigrated() {
        val current = context.mapSettingsDataStore.data.first()
        if (current.legacyMigrated) return

        // One-time import from legacy Preferences DataStore
        @Suppress("DEPRECATION")
        val prefs = Preferences(context)
        val legacyQuality = prefs.getFloat(MapPreferenceKeys.LEGACY_QUALITY, MapSettingsState.DEFAULT_QUALITY)
        val legacyMaxHeat = prefs.getInt(MapPreferenceKeys.LEGACY_MAX_HEAT, MapSettingsState.DEFAULT_MAX_HEAT)
        val legacyVisit = prefs.getInt(MapPreferenceKeys.LEGACY_VISIT_THRESHOLD, MapSettingsState.DEFAULT_VISIT_THRESHOLD)

        context.mapSettingsDataStore.updateData {
            MapSettingsProto.newBuilder()
                .setQuality(legacyQuality)
                .setMaxHeatPoints(legacyMaxHeat)
                .setVisitThresholdSeconds(legacyVisit)
                .setLegacyMigrated(true)
                .build()
        }
    }
}

private fun MapSettingsProto.toDomain(): MapSettingsState {
    if (!legacyMigrated && quality == 0f && maxHeatPoints == 0 && visitThresholdSeconds == 0) {
        return MapSettingsState()
    }
    return MapSettingsState(
        quality = quality.takeIf { it > 0f } ?: MapSettingsState.DEFAULT_QUALITY,
        maxHeatPoints = maxHeatPoints.takeIf { it > 0 } ?: MapSettingsState.DEFAULT_MAX_HEAT,
        visitThresholdSeconds = visitThresholdSeconds.takeIf { it > 0 } ?: MapSettingsState.DEFAULT_VISIT_THRESHOLD,
        legacyHeatmapEnabled = legacyHeatmapEnabled,
        zoomButtonsEnabled = zoomButtonsEnabled,
    )
}
