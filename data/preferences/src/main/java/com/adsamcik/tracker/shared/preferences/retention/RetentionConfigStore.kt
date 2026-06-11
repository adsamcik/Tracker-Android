package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

private object RetentionConfigSerializer : Serializer<RetentionConfigProto> {
    override val defaultValue: RetentionConfigProto = RetentionConfigProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): RetentionConfigProto {
        try {
            return RetentionConfigProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read retention config proto", e)
        }
    }

    override suspend fun writeTo(t: RetentionConfigProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.retentionConfigDataStore: DataStore<RetentionConfigProto> by dataStore(
    fileName = "retention_config.pb",
    serializer = RetentionConfigSerializer,
)

class RetentionConfigStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val dataStore = context.retentionConfigDataStore

    val config: Flow<RetentionConfigState> = flow {
        val migrated = runCatching {
            ensureDataSettingsMigrated()
        }.onFailure {
            Log.e("RetentionConfigStore", "Failed to migrate legacy data settings", it)
        }.getOrNull()
        if (migrated != null) emit(migrated.toDomain())
        emitAll(dataStore.data.map { it.toDomain() })
    }

    suspend fun update(block: RetentionConfigState.() -> RetentionConfigState) {
        withContext(ioDispatcher) {
            ensureDataSettingsMigrated()
            dataStore.updateData { current ->
                val currentState = current.toDomain()
                val newState = currentState.block()
                newState.toProto()
            }
        }
    }

    /**
     * One-time migration of auto_cleanup_enabled and data_retention_years
     * from the legacy SharedPreferences to Proto DataStore.
     */
    private suspend fun ensureDataSettingsMigrated(): RetentionConfigProto =
        withContext(ioDispatcher) {
            var clearLegacyKeys = false
            val legacyPrefs = LegacyPreferenceStore.freshSnapshot(context)
            val hasAutoCleanup = legacyPrefs.hasKey("autoCleanupOldData")
            val hasRetentionYears = legacyPrefs.hasKey("dataRetentionYears")
            val autoCleanup = if (hasAutoCleanup) {
                legacyPrefs[booleanPreferencesKey("autoCleanupOldData")] ?: false
            } else {
                false
            }
            val retentionYears = if (hasRetentionYears) {
                legacyPrefs.intOrString(
                    "dataRetentionYears",
                    RetentionConfigState.DEFAULT_RETENTION_YEARS
                ).coerceAtLeast(0)
            } else {
                RetentionConfigState.DEFAULT_RETENTION_YEARS
            }
            val migrated = dataStore.updateData { current ->
                if (current.dataSettingsLegacyMigrated &&
                    !hasAutoCleanup &&
                    !hasRetentionYears
                ) {
                    return@updateData current
                }

                clearLegacyKeys = hasAutoCleanup || hasRetentionYears
                val builder = current.toBuilder()
                    .setAutoCleanupEnabled(autoCleanup)
                    .setDataRetentionYears(retentionYears)
                    .setDataSettingsLegacyMigrated(true)

                if (current.initialized || hasAutoCleanup || hasRetentionYears) {
                    val currentState = current.toDomain()
                    builder
                        .setRawDataRetentionDays(currentState.rawDataRetentionDays)
                        .setWifiCellRetentionDays(currentState.wifiCellRetentionDays)
                        .setTripRetentionDays(currentState.tripRetentionDays)
                        .setDailySummaryRetentionDays(currentState.dailySummaryRetentionDays)
                        .setExplorationRetentionDays(currentState.explorationRetentionDays)
                        .setAutoPurgeEnabled(currentState.autoPurgeEnabled)
                        .setExportBeforePurge(currentState.exportBeforePurge)
                        .setLegacySessionRetentionDays(currentState.legacySessionRetentionDays)
                        .setInitialized(true)
                }

                builder.build()
            }
            if (clearLegacyKeys) {
                runCatching {
                    Preferences(context).editSuspend {
                        remove("autoCleanupOldData")
                        remove("dataRetentionYears")
                    }
                }.onFailure {
                    Log.w("RetentionConfigStore", "Failed to clear migrated legacy data settings", it)
                }
            }
            migrated
        }
}

suspend fun resetRetentionConfigForTests(context: Context) {
    context.retentionConfigDataStore.updateData {
        RetentionConfigProto.getDefaultInstance()
    }
}

private fun RetentionConfigProto.toDomain(): RetentionConfigState {
    if (!initialized) return RetentionConfigState()
    return RetentionConfigState(
        rawDataRetentionDays = rawDataRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        wifiCellRetentionDays = wifiCellRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        tripRetentionDays = tripRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        dailySummaryRetentionDays = dailySummaryRetentionDays.withDefaultIfNegative(
            RetentionConfigState.DEFAULT_DAILY_SUMMARY_DAYS
        ),
        explorationRetentionDays = explorationRetentionDays.coerceAtLeast(0),
        autoPurgeEnabled = autoPurgeEnabled,
        exportBeforePurge = exportBeforePurge,
        legacySessionRetentionDays = legacySessionRetentionDays.withDefaultIfNegative(
            RetentionConfigState.DEFAULT_RAW_DAYS
        ),
        autoCleanupEnabled = autoCleanupEnabled,
        dataRetentionYears = dataRetentionYears.withDefaultIfNegative(RetentionConfigState.DEFAULT_RETENTION_YEARS),
    )
}

private fun RetentionConfigState.toProto(): RetentionConfigProto =
    RetentionConfigProto.newBuilder()
        .setRawDataRetentionDays(rawDataRetentionDays)
        .setWifiCellRetentionDays(wifiCellRetentionDays)
        .setTripRetentionDays(tripRetentionDays)
        .setDailySummaryRetentionDays(dailySummaryRetentionDays)
        .setExplorationRetentionDays(explorationRetentionDays)
        .setAutoPurgeEnabled(autoPurgeEnabled)
        .setExportBeforePurge(exportBeforePurge)
        .setLegacySessionRetentionDays(legacySessionRetentionDays)
        .setAutoCleanupEnabled(autoCleanupEnabled)
        .setDataRetentionYears(dataRetentionYears)
        .setInitialized(true)
        .setDataSettingsLegacyMigrated(true)
        .build()

private fun Int.withDefaultIfNegative(default: Int): Int = if (this < 0) default else this

private fun androidx.datastore.preferences.core.Preferences.hasKey(key: String): Boolean =
    asMap().keys.any { it.name == key }

private fun androidx.datastore.preferences.core.Preferences.intOrString(key: String, default: Int): Int {
    return when (val value = asMap().entries.firstOrNull { it.key.name == key }?.value) {
        is Int -> value
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}
