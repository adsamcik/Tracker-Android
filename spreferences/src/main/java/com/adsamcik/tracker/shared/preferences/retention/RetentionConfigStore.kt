package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val dataStore = context.retentionConfigDataStore

    val config: Flow<RetentionConfigState> = dataStore.data.map { it.toDomain() }

    suspend fun update(block: RetentionConfigState.() -> RetentionConfigState) {
        withContext(ioDispatcher) {
            dataStore.updateData { current ->
                val currentState = current.toDomain()
                val newState = currentState.block()
                newState.toProto()
            }
        }
    }
}

private fun RetentionConfigProto.toDomain(): RetentionConfigState {
    if (!initialized) return RetentionConfigState()
    return RetentionConfigState(
        rawDataRetentionDays = rawDataRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        wifiCellRetentionDays = wifiCellRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        tripRetentionDays = tripRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
        dailySummaryRetentionDays = dailySummaryRetentionDays.withDefault(RetentionConfigState.DEFAULT_DAILY_SUMMARY_DAYS),
        explorationRetentionDays = explorationRetentionDays.coerceAtLeast(0),
        autoPurgeEnabled = autoPurgeEnabled,
        exportBeforePurge = exportBeforePurge,
        legacySessionRetentionDays = legacySessionRetentionDays.withDefault(RetentionConfigState.DEFAULT_RAW_DAYS),
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
        .setInitialized(true)
        .build()

private fun Int.withDefault(default: Int): Int = if (this == 0) default else coerceAtLeast(0)
