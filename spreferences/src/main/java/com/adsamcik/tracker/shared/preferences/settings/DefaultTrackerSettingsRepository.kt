package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.R
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

// Contract:
// Inputs: Proto DataStore file, legacy SharedPreferences for one-time migration.
// Outputs: Flow<TrackerSettings>; mutation via suspend setters.
// Failure: Corruption -> emit default settings; logged (debug). No exceptions leak outward.

// Internal logger indirection to avoid module cycle with :logger. Repository can register a lambda.
internal object TrackerSettingsInternalLogger { var logger: ((String) -> Unit)? = null }

private object TrackerSettingsSerializer : Serializer<TrackerSettingsProto> {
    override val defaultValue: TrackerSettingsProto = TrackerSettingsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): TrackerSettingsProto = try {
        TrackerSettingsProto.parseFrom(input)
    } catch (e: Exception) {
        // Corruption -> fallback to default; structured log via indirection (no module dependency).
        Log.w("TrackerSettings", "Corruption while reading settings proto – using defaults", e)
        TrackerSettingsInternalLogger.logger?.invoke("SET-CORRUPTION: fallback to defaults (${e::class.simpleName})")
        defaultValue
    }

    override suspend fun writeTo(t: TrackerSettingsProto, output: OutputStream) { t.writeTo(output) }
}

private val Context.trackerSettingsDataStore: DataStore<TrackerSettingsProto> by dataStore(
    fileName = "tracker_settings.pb",
    serializer = TrackerSettingsSerializer,
    produceMigrations = { context ->
        listOf(
            trackerSettingsSharedPreferencesMigration(context),
            trackerSettingsLegacyMarkerMigration(context)
        )
    }
)

// Visible for testing: provides a deterministic way to clear in-memory DataStore cache between tests
// so that prior test mutations (e.g., setting a non-default length system) do not leak into subsequent
// test method executions. Robolectric keeps the same application Context instance; the DataStore
// delegate caches the instance in memory, meaning simply deleting the backing file is insufficient.
// Contract: Only invoke from test sources. Safe no-op in production code paths.
internal fun resetTrackerSettingsForTests() {
    resetDataStoreDelegate(
        fileClassName = "com.adsamcik.tracker.shared.preferences.settings.DefaultTrackerSettingsRepositoryKt",
        delegateFieldName = "trackerSettingsDataStore\$delegate"
    )
}

interface TrackerSettingsKeyProvider {
    val autoUnitSwitchKey: String
    val autoUnitSwitchDefault: Boolean
    val lengthSystemKey: String
    val lengthSystemDefault: String
    val speedFormatKey: String
    val speedFormatDefault: String
}

private class ResourceTrackerSettingsKeyProvider(private val context: Context) : TrackerSettingsKeyProvider {
    override val autoUnitSwitchKey: String
        get() = context.getString(R.string.settings_statistics_auto_unit_switch_key)
    override val autoUnitSwitchDefault: Boolean
        get() = context.getString(R.string.settings_statistics_auto_unit_switch_default).toBoolean()
    override val lengthSystemKey: String
        get() = PreferenceKeys.LENGTH_SYSTEM
    override val lengthSystemDefault: String
        get() = PreferenceKeys.LENGTH_SYSTEM_DEFAULT
    override val speedFormatKey: String
        get() = PreferenceKeys.SPEED_FORMAT
    override val speedFormatDefault: String
        get() = PreferenceKeys.SPEED_FORMAT_DEFAULT
}

private fun defaultSharedPreferencesName(context: Context): String = "${context.packageName}_preferences"

private fun trackerSettingsSharedPreferencesMigration(context: Context): DataMigration<TrackerSettingsProto> {
    val keys = ResourceTrackerSettingsKeyProvider(context)
    return SharedPreferencesMigration(
        context = context,
        sharedPreferencesName = defaultSharedPreferencesName(context),
        keysToMigrate = setOf(
            keys.autoUnitSwitchKey,
            keys.lengthSystemKey,
            keys.speedFormatKey
        ),
        shouldRunMigration = { current -> !current.legacyMigrated }
    ) { prefs, current ->
        val auto = prefs.getBoolean(keys.autoUnitSwitchKey, keys.autoUnitSwitchDefault)
        val lengthStr = prefs.getString(keys.lengthSystemKey, keys.lengthSystemDefault) ?: keys.lengthSystemDefault
        val speedStr = prefs.getString(keys.speedFormatKey, keys.speedFormatDefault) ?: keys.speedFormatDefault

        current.toBuilder()
            .setAutoUnitSwitch(auto)
            .setLengthSystem(lengthStr.toLengthSystemProto())
            .setSpeedFormat(speedStr.toSpeedFormatProto())
            .setLegacyMigrated(true)
            .build()
    }
}

private fun trackerSettingsLegacyMarkerMigration(context: Context): DataMigration<TrackerSettingsProto> =
    object : DataMigration<TrackerSettingsProto> {
        private val defaults = ResourceTrackerSettingsKeyProvider(context)

        override suspend fun shouldMigrate(currentData: TrackerSettingsProto): Boolean =
            !currentData.legacyMigrated

        override suspend fun migrate(currentData: TrackerSettingsProto): TrackerSettingsProto =
            currentData.toBuilder()
                .setAutoUnitSwitch(defaults.autoUnitSwitchDefault)
                .setLengthSystem(defaults.lengthSystemDefault.toLengthSystemProto())
                .setSpeedFormat(defaults.speedFormatDefault.toSpeedFormatProto())
                .setLegacyMigrated(true)
                .build()

        override suspend fun cleanUp() = Unit
    }

private fun resetDataStoreDelegate(fileClassName: String, delegateFieldName: String) {
    val fileClass = Class.forName(fileClassName)
    val delegateField = fileClass.getDeclaredField(delegateFieldName).apply { isAccessible = true }
    val delegate = delegateField.get(null)
    val instanceField = delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
    instanceField.set(delegate, null)
}

class DefaultTrackerSettingsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val keys: TrackerSettingsKeyProvider = ResourceTrackerSettingsKeyProvider(context),
    private val log: (String) -> Unit = {}
) : TrackerSettingsRepository {

    init {
        // Register logger once if not yet present.
        if (TrackerSettingsInternalLogger.logger == null) {
            TrackerSettingsInternalLogger.logger = log
        }
    }

    override val data: Flow<TrackerSettingsState> = context.trackerSettingsDataStore.data
        .map { proto ->
            TrackerSettingsState(
                autoUnitSwitch = proto.autoUnitSwitch,
                lengthSystem = proto.lengthSystem.toDomainLength(),
                speedFormat = proto.speedFormat.toDomainSpeed(),
            )
        }

    override suspend fun setAutoUnitSwitch(enabled: Boolean) {
        withContext(io) {
            val current = context.trackerSettingsDataStore.data.first()
            val updated = current.toBuilder()
                .setAutoUnitSwitch(enabled)
                .build()
            context.trackerSettingsDataStore.updateData { updated }
        }
    }

    override suspend fun setLengthSystem(system: com.adsamcik.tracker.shared.preferences.type.LengthSystem) {
        withContext(io) {
            val current = context.trackerSettingsDataStore.data.first()
            val updated = current.toBuilder()
                .setLengthSystem(system.toProto())
                .build()
            context.trackerSettingsDataStore.updateData { updated }
        }
    }

    override suspend fun setSpeedFormat(format: com.adsamcik.tracker.shared.preferences.type.SpeedFormat) {
        withContext(io) {
            val current = context.trackerSettingsDataStore.data.first()
            val updated = current.toBuilder()
                .setSpeedFormat(format.toProto())
                .build()
            context.trackerSettingsDataStore.updateData { updated }
        }
    }
}

// Mapping helpers (proto -> domain)
private fun TrackerSettingsProto.LengthSystemProto?.toDomainLength(): com.adsamcik.tracker.shared.preferences.type.LengthSystem = when (this) {
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_IMPERIAL -> com.adsamcik.tracker.shared.preferences.type.LengthSystem.Imperial
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_ANCIENT_ROMAN -> com.adsamcik.tracker.shared.preferences.type.LengthSystem.AncientRoman
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_SAILING -> com.adsamcik.tracker.shared.preferences.type.LengthSystem.Sailing
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_FLYING -> com.adsamcik.tracker.shared.preferences.type.LengthSystem.Flying
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_METRIC,
    TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_UNSPECIFIED,
    TrackerSettingsProto.LengthSystemProto.UNRECOGNIZED,
    null -> com.adsamcik.tracker.shared.preferences.type.LengthSystem.Metric
}

private fun TrackerSettingsProto.SpeedFormatProto?.toDomainSpeed(): com.adsamcik.tracker.shared.preferences.type.SpeedFormat = when (this) {
    TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_SECOND -> com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Second
    TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_MINUTE -> com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Minute
    TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_HOUR,
    TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_UNSPECIFIED,
    TrackerSettingsProto.SpeedFormatProto.UNRECOGNIZED,
    null -> com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Hour
}

// Mapping helpers (domain -> proto)
private fun com.adsamcik.tracker.shared.preferences.type.LengthSystem.toProto(): TrackerSettingsProto.LengthSystemProto = when (this) {
    com.adsamcik.tracker.shared.preferences.type.LengthSystem.Metric -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_METRIC
    com.adsamcik.tracker.shared.preferences.type.LengthSystem.Imperial -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_IMPERIAL
    com.adsamcik.tracker.shared.preferences.type.LengthSystem.AncientRoman -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_ANCIENT_ROMAN
    com.adsamcik.tracker.shared.preferences.type.LengthSystem.Sailing -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_SAILING
    com.adsamcik.tracker.shared.preferences.type.LengthSystem.Flying -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_FLYING
}

private fun com.adsamcik.tracker.shared.preferences.type.SpeedFormat.toProto(): TrackerSettingsProto.SpeedFormatProto = when (this) {
    com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Second -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_SECOND
    com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Minute -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_MINUTE
    com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Hour -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_HOUR
}

// Legacy string value mapping
private fun String.toLengthSystemProto(): TrackerSettingsProto.LengthSystemProto = when (this) {
    "Metric" -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_METRIC
    "Imperial" -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_IMPERIAL
    "AncientRoman" -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_ANCIENT_ROMAN
    "Sailing" -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_SAILING
    "Flying" -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_FLYING
    else -> TrackerSettingsProto.LengthSystemProto.LENGTH_SYSTEM_METRIC
}

private fun String.toSpeedFormatProto(): TrackerSettingsProto.SpeedFormatProto = when (this) {
    "Second" -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_SECOND
    "Minute" -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_MINUTE
    "Hour" -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_HOUR
    else -> TrackerSettingsProto.SpeedFormatProto.SPEED_FORMAT_HOUR
}
