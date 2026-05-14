package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import android.util.Log
import com.adsamcik.tracker.shared.preferences.Preferences
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

    val config: Flow<RetentionConfigState> = dataStore.data
        .onStart {
            runCatching {
                ensureDataSettingsMigrated()
            }.onFailure {
                Log.e("RetentionConfigStore", "Failed to migrate legacy data settings", it)
            }
        }
        .map { it.toDomain() }

    suspend fun update(block: RetentionConfigState.() -> RetentionConfigState) {
        withContext(ioDispatcher) {
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
    private suspend fun ensureDataSettingsMigrated() {
        withContext(ioDispatcher) {
            dataStore.updateData { current ->
                if (current.dataSettingsLegacyMigrated) return@updateData current

                val prefs = Preferences(context)
                val autoCleanup = runCatching {
                    prefs.getBooleanSync("autoCleanupOldData", DEFAULT_AUTO_CLEANUP_ENABLED)
                }.getOrDefault(DEFAULT_AUTO_CLEANUP_ENABLED)
                val retentionYears = runCatching {
                    prefs.getStringSync("dataRetentionYears")?.toIntOrNull()
                        ?: prefs.getIntSync(
                            "dataRetentionYears",
                            RetentionConfigState.DEFAULT_RETENTION_YEARS
                        )
                }.getOrDefault(RetentionConfigState.DEFAULT_RETENTION_YEARS).coerceAtLeast(1)

                current.toBuilder()
                    .setAutoCleanupEnabled(autoCleanup)
                    .setDataRetentionYears(retentionYears)
                    .setDataSettingsLegacyMigrated(true)
                    .build()
            }
        }
    }
}

suspend fun resetRetentionConfigForTests(context: Context) {
    context.retentionConfigDataStore.updateData {
        RetentionConfigProto.getDefaultInstance()
    }
}

private fun RetentionConfigProto.toDomain(): RetentionConfigState {
    if (!initialized) return RetentionConfigState(autoCleanupEnabled = DEFAULT_AUTO_CLEANUP_ENABLED)
    return RetentionConfigState(
        rawDataRetentionDays = rawDataRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        wifiCellRetentionDays = wifiCellRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        tripRetentionDays = tripRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        dailySummaryRetentionDays = dailySummaryRetentionDays.withDefault(RetentionConfigState.DEFAULT_DAILY_SUMMARY_DAYS),
        explorationRetentionDays = explorationRetentionDays.coerceAtLeast(0),
        autoPurgeEnabled = autoPurgeEnabled,
        exportBeforePurge = exportBeforePurge,
        legacySessionRetentionDays = legacySessionRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        autoCleanupEnabled = autoCleanupEnabled,
        dataRetentionYears = dataRetentionYears.withDefault(RetentionConfigState.DEFAULT_RETENTION_YEARS),
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
        .build()

private fun Int.withDefault(default: Int): Int = if (this <= 0) default else this

private const val DEFAULT_AUTO_CLEANUP_ENABLED = true
