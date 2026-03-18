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
    fun `migration keeps string mode, legacy ski flag, and wifi default`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PreferenceKeys.TRACKING_ACTIVITY_MODE, "2")
            .putBoolean(PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED, true)
            .commit()

        val repo = DefaultTrackingParamsRepository(context, Dispatchers.IO)
        val state = repo.data.first()

        assertEquals(2, state.autoTrackingMode)
        assertTrue(state.skiDetectionEnabled)
        assertFalse(state.wifiEnabled)
    }
}
