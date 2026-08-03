package com.adsamcik.tracker.shared.preferences.tracking

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
