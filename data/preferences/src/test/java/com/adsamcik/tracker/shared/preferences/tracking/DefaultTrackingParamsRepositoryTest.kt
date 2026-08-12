package com.adsamcik.tracker.shared.preferences.tracking

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackingParamsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()

        val dsDir = context.filesDir.resolve("datastore")
        if (dsDir.exists()) {
            dsDir.listFiles()?.forEach { file -> file.delete() }
        }

        resetTrackingParamsForTests()
        LegacyPreferenceStore.resetForTests()
    }

    @Test
    fun `migration keeps string mode and wifi default`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PreferenceKeys.TRACKING_ACTIVITY_MODE, "2")
            .commit()

        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)
        val state = repo.data.first()

        assertEquals(2, state.autoTrackingMode)
        assertFalse(state.wifiEnabled)
    }

    @Test
	fun `migration folds legacy wifi outputs and imports barometer setting`() = runTest {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(PreferenceKeys.WIFI_ENABLED, false)
			.putBoolean(PreferenceKeys.WIFI_NETWORK_ENABLED, true)
			.putBoolean(PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED, true)
			.putBoolean(PreferenceKeys.CELL_ENABLED, true)
			.putBoolean(PreferenceKeys.BAROMETER_ENABLED, false)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 42)
            .putInt(PreferenceKeys.TRACKING_MIN_TIME, 9)
            .putInt(PreferenceKeys.TRACKING_REQUIRED_ACCURACY, 25)
            .putBoolean(PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED, false)
            .putString("tracking_preset", TrackingPreset.HIGH_ACCURACY.name)
            .commit()

        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)
        val state = repo.data.first()

		assertTrue(state.wifiEnabled)
		assertTrue(state.cellEnabled)
		assertFalse(state.barometerEnabled)
        assertEquals(42, state.minDistanceMeters)
        assertEquals(9, state.minTimeSeconds)
        assertEquals(25, state.requiredAccuracyMeters)
        assertFalse(state.transitionDetectionEnabled)
		assertEquals(TrackingPreset.HIGH_ACCURACY, state.preset)
	}

	@Test
	@Suppress("DEPRECATION")
	fun `legacy wifi output is normalized on write and can be disabled`() = runTest {
		val dataStoreFile = context.filesDir.resolve("datastore/tracking_params.pb")
		dataStoreFile.parentFile?.mkdirs()
		val proto = TrackingParamsProto.newBuilder()
			.setLegacyMigrated(true)
			.setWifiEnabled(false)
			.setWifiNetworkEnabled(true)
			.build()
		dataStoreFile.outputStream().use(proto::writeTo)

		val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)
		val state = repo.data.first()

		assertTrue(state.wifiEnabled)
		// Files created before the optional field retain the historical enabled default.
		assertTrue(state.barometerEnabled)

		repo.setBarometerEnabled(false)
		assertTrue(repo.data.first().wifiEnabled)

		repo.setWifiEnabled(false)
		assertFalse(repo.data.first().wifiEnabled)
	}

    @Test
    fun `active write performs legacy migration before updating repository`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 77)
            .commit()

        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)
        repo.setWifiEnabled(true)

        val state = repo.data.first()
        assertEquals(77, state.minDistanceMeters)
        assertTrue(state.wifiEnabled)
    }

    @Test
    fun `shared preferences upgrade maps every legacy source to an explicit frequency`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceKeys.LOCATION_ENABLED, false)
            .putBoolean(PreferenceKeys.ACTIVITY_ENABLED, true)
            .putBoolean(PreferenceKeys.STEPS_ENABLED, false)
            .putBoolean(PreferenceKeys.WIFI_ENABLED, false)
            .putBoolean(PreferenceKeys.WIFI_NETWORK_ENABLED, true)
            .putBoolean(PreferenceKeys.CELL_ENABLED, true)
            .putBoolean(PreferenceKeys.BAROMETER_ENABLED, false)
            .putString(PreferenceKeys.TRACKING_ACTIVITY_MODE, "2")
            .putBoolean(PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED, false)
            .putBoolean(PreferenceKeys.NOTIFICATION_STYLED, false)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 37)
            .putInt(PreferenceKeys.TRACKING_MIN_TIME, 11)
            .putInt(PreferenceKeys.TRACKING_REQUIRED_ACCURACY, 24)
            .putString("tracking_preset", "BATTERY_SAVER")
            .commit()

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.location)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.activity)
        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.steps)
        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.pressure)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.wifi)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.cell)
        assertEquals(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION, state.sourceSettingsVersion)
        assertEquals(TrackingPreset.POWER_SAVE, state.preset)
        assertEquals(2, state.autoTrackingMode)
        assertFalse(state.transitionDetectionEnabled)
        assertFalse(state.notificationStyled)
        assertEquals(37, state.minDistanceMeters)
        assertEquals(11, state.minTimeSeconds)
        assertEquals(24, state.requiredAccuracyMeters)

        val persisted = readTrackingProto()
        assertTrue(persisted.hasLocationFrequency())
        assertTrue(persisted.hasActivityFrequency())
        assertTrue(persisted.hasStepsFrequency())
        assertTrue(persisted.hasPressureFrequency())
        assertTrue(persisted.hasWifiFrequency())
        assertTrue(persisted.hasCellFrequency())
        assertEquals(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION, persisted.sourceSettingsVersion)
    }

    @Test
    fun `malformed legacy field falls back independently without discarding valid settings`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PreferenceKeys.LOCATION_ENABLED, "damaged")
            .putBoolean(PreferenceKeys.ACTIVITY_ENABLED, false)
            .putString(PreferenceKeys.TRACKING_ACTIVITY_MODE, "not-a-mode")
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 73)
            .putString("tracking_preset", "unknown-preset")
            .commit()

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(PreferenceKeys.LOCATION_ENABLED_DEFAULT, state.locationEnabled)
        assertFalse(state.activityEnabled)
        assertEquals(PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT, state.autoTrackingMode)
        assertEquals(73, state.minDistanceMeters)
        assertEquals(TrackingPreset.DEFAULT, state.preset)
        assertTrue(readTrackingProto().legacyMigrated)
    }

    @Test
    fun `legacy numeric values are sanitized to supported runtime bounds`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PreferenceKeys.TRACKING_ACTIVITY_MODE, 99)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, -5)
            .putInt(PreferenceKeys.TRACKING_MIN_TIME, 0)
            .putInt(PreferenceKeys.TRACKING_REQUIRED_ACCURACY, -1)
            .commit()

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT, state.autoTrackingMode)
        assertEquals(PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT, state.minDistanceMeters)
        assertEquals(PreferenceKeys.TRACKING_MIN_TIME_DEFAULT, state.minTimeSeconds)
        assertEquals(PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT, state.requiredAccuracyMeters)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `exact pre source settings datastore is upgraded in place without losing values`() = runTest {
        writeTrackingProto(
            TrackingParamsProto.newBuilder()
                .setLegacyMigrated(true)
                .setLocationEnabled(false)
                .setActivityEnabled(true)
                .setStepsEnabled(false)
                .setWifiEnabled(false)
                .setWifiNetworkEnabled(true)
                .setCellEnabled(true)
                .setBarometerEnabled(false)
                .setAutoTrackingMode(2)
                .setTransitionDetectionEnabled(false)
                .setNotificationStyled(false)
                .setMinDistanceMeters(41)
                .setMinTimeSeconds(7)
                .setRequiredAccuracyMeters(22)
                .setPresetName("BATTERY_SAVER")
                .build(),
        )

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertFalse(state.locationEnabled)
        assertTrue(state.activityEnabled)
        assertFalse(state.stepsEnabled)
        assertTrue(state.wifiEnabled)
        assertTrue(state.cellEnabled)
        assertFalse(state.barometerEnabled)
        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.location)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.activity)
        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.steps)
        assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.pressure)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.wifi)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.cell)
        assertEquals(TrackingPreset.POWER_SAVE, state.preset)
        assertEquals(41, state.minDistanceMeters)
        assertEquals(7, state.minTimeSeconds)
        assertEquals(22, state.requiredAccuracyMeters)

        val persisted = readTrackingProto()
        assertEquals(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION, persisted.sourceSettingsVersion)
        assertTrue(persisted.hasLocationFrequency())
        assertTrue(persisted.hasPressureFrequency())
        assertFalse(persisted.wifiNetworkEnabled)
        assertFalse(persisted.wifiLocationCountEnabled)
        assertTrue(persisted.wifiEnabled)
        assertEquals(TrackingPreset.POWER_SAVE.name, persisted.presetName)
    }

    @Test
    fun `pre rework high accuracy cadence is upgraded to the current preset contract`() = runTest {
        writeTrackingProto(
            TrackingParamsProto.newBuilder()
                .setLegacyMigrated(true)
                .setLocationEnabled(true)
                .setPresetName(TrackingPreset.HIGH_ACCURACY.name)
                .setMinDistanceMeters(5)
                .setMinTimeSeconds(1)
                .setRequiredAccuracyMeters(100)
                .build(),
        )

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(TrackingPreset.HIGH_ACCURACY.minDistanceMeters, state.minDistanceMeters)
        assertEquals(TrackingPreset.HIGH_ACCURACY.minTimeSeconds, state.minTimeSeconds)
        assertEquals(TrackingPreset.HIGH_ACCURACY.requiredAccuracyMeters, state.requiredAccuracyMeters)
        val persisted = readTrackingProto()
        assertEquals(TrackingPreset.HIGH_ACCURACY.minDistanceMeters, persisted.minDistanceMeters)
        assertEquals(TrackingPreset.HIGH_ACCURACY.minTimeSeconds, persisted.minTimeSeconds)
        assertEquals(TrackingPreset.HIGH_ACCURACY.requiredAccuracyMeters, persisted.requiredAccuracyMeters)
    }

    @Test
    fun `partially migrated datastore preserves explicit frequencies and repairs missing ones`() = runTest {
        writeTrackingProto(
            TrackingParamsProto.newBuilder()
                .setLegacyMigrated(true)
                .setLocationEnabled(false)
                .setActivityEnabled(false)
                .setLocationFrequency(SourceCollectionFrequency.RESPONSIVE.stableCode)
                .setActivityFrequency(999)
                .build(),
        )

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(SourceCollectionFrequency.RESPONSIVE, state.sourceCollectionSettings.location)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.activity)
        assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.pressure)
        val persisted = readTrackingProto()
        assertEquals(SourceCollectionFrequency.RESPONSIVE.stableCode, persisted.locationFrequency)
        assertEquals(SourceCollectionFrequency.BALANCED.stableCode, persisted.activityFrequency)
        assertEquals(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION, persisted.sourceSettingsVersion)
    }

    @Test
    fun `completed migration is idempotent and ignores later legacy preference changes`() = runTest {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .putBoolean(PreferenceKeys.LOCATION_ENABLED, false)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 64)
            .commit()
        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)

        val migrated = repo.data.first()
        preferences.edit()
            .putBoolean(PreferenceKeys.LOCATION_ENABLED, true)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 12)
            .commit()
        val reread = repo.data.first()

        assertFalse(migrated.locationEnabled)
        assertFalse(reread.locationEnabled)
        assertEquals(64, reread.minDistanceMeters)
        assertNotEquals(12, reread.minDistanceMeters)
    }

    @Test
    fun `concurrent first collectors observe one complete atomic migration`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceKeys.LOCATION_ENABLED, false)
            .putBoolean(PreferenceKeys.CELL_ENABLED, true)
            .putInt(PreferenceKeys.TRACKING_MIN_DISTANCE, 58)
            .commit()
        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)

        val states = coroutineScope {
            List(8) { async { repo.data.first() } }.awaitAll()
        }

        states.forEach { state ->
            assertFalse(state.locationEnabled)
            assertTrue(state.cellEnabled)
            assertEquals(58, state.minDistanceMeters)
            assertEquals(SourceCollectionFrequency.OFF, state.sourceCollectionSettings.location)
            assertEquals(SourceCollectionFrequency.BALANCED, state.sourceCollectionSettings.cell)
        }
        assertEquals(TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION, readTrackingProto().sourceSettingsVersion)
    }

    @Test
    fun `future semantic settings version is not downgraded or rewritten`() = runTest {
        writeTrackingProto(
            TrackingParamsProto.newBuilder()
                .setLegacyMigrated(true)
                .setLocationEnabled(true)
                .setLocationFrequency(SourceCollectionFrequency.RESPONSIVE.stableCode)
                .setSourceSettingsVersion(99)
                .build(),
        )

        val state = DefaultTrackingParamsRepository(context, Dispatchers.IO).data.first()

        assertEquals(SourceCollectionFrequency.RESPONSIVE, state.sourceCollectionSettings.location)
        assertEquals(99, state.sourceSettingsVersion)
        assertEquals(99, readTrackingProto().sourceSettingsVersion)
    }

    private fun writeTrackingProto(proto: TrackingParamsProto) {
        val file = context.filesDir.resolve("datastore/tracking_params.pb")
        file.parentFile?.mkdirs()
        file.outputStream().use(proto::writeTo)
    }

    private fun readTrackingProto(): TrackingParamsProto {
        val file = context.filesDir.resolve("datastore/tracking_params.pb")
        return file.inputStream().use(TrackingParamsProto::parseFrom)
    }
}
