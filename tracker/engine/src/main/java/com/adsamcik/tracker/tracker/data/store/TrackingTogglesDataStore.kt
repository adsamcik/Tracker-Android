package com.adsamcik.tracker.tracker.data.store

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsProto
import com.adsamcik.tracker.tracker.proto.TrackingTogglesProto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Serializer for [TrackingTogglesProto] used by DataStore.
 */
internal object TrackingTogglesSerializer : Serializer<TrackingTogglesProto> {
    override val defaultValue: TrackingTogglesProto = TrackingTogglesProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): TrackingTogglesProto = try {
        TrackingTogglesProto.parseFrom(input)
    } catch (e: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: TrackingTogglesProto, output: OutputStream) {
        t.writeTo(output)
    }
}

/**
 * DataStore extension for tracking toggles.
 * Each toggle is a boolean preference keyed by its string resource name.
 */
internal val Context.trackingTogglesProtoDataStore: DataStore<TrackingTogglesProto> by dataStore(
    fileName = "tracking_toggles.pb",
    serializer = TrackingTogglesSerializer,
    produceMigrations = { context ->
        listOf(TrackingParamsToTogglesMigration(context.applicationContext))
    }
)

/**
 * DataStore writer for dashboard tracking source toggles.
 */
class TrackingTogglesDataStore @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatchers: DispatchersProvider,
) {
    private val dataStore = context.trackingTogglesProtoDataStore

    val data: Flow<TrackingTogglesProto> = dataStore.data

    suspend fun setSource(name: String, enabled: Boolean) {
        withContext(dispatchers.io) {
            dataStore.updateData { current ->
                current.toBuilder()
                    .putToggles(name, enabled)
                    .build()
            }
        }
    }

    suspend fun setAll(map: Map<String, Boolean>) {
        if (map.isEmpty()) return
        withContext(dispatchers.io) {
            dataStore.updateData { current ->
                current.toBuilder()
                    .putAllToggles(map)
                    .build()
            }
        }
    }
}

private class TrackingParamsToTogglesMigration(
    private val context: Context,
) : DataMigration<TrackingTogglesProto> {
    override suspend fun shouldMigrate(currentData: TrackingTogglesProto): Boolean {
        return currentData.togglesCount == 0 && readTrackingParams() != null
    }

    override suspend fun migrate(currentData: TrackingTogglesProto): TrackingTogglesProto {
        if (currentData.togglesCount != 0) return currentData
        val params = readTrackingParams() ?: return currentData
        return currentData.toBuilder()
            .putAllToggles(params.toSourceToggleMap())
            .build()
    }

    override suspend fun cleanUp() = Unit

    private fun readTrackingParams(): TrackingParamsProto? {
        val file = File(context.filesDir, "datastore/tracking_params.pb")
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { TrackingParamsProto.parseFrom(it) }
        }.getOrNull()
    }
}

@Suppress("DEPRECATION")
private fun TrackingParamsProto.toSourceToggleMap(): Map<String, Boolean> = mapOf(
    PreferenceKeys.LOCATION_ENABLED to locationEnabled,
    PreferenceKeys.ACTIVITY_ENABLED to activityEnabled,
    PreferenceKeys.STEPS_ENABLED to stepsEnabled,
    PreferenceKeys.WIFI_ENABLED to (wifiEnabled || wifiNetworkEnabled || wifiLocationCountEnabled),
    PreferenceKeys.CELL_ENABLED to cellEnabled,
    PreferenceKeys.BAROMETER_ENABLED to if (hasBarometerEnabled()) {
        barometerEnabled
    } else {
        PreferenceKeys.BAROMETER_ENABLED_DEFAULT
    },
)
