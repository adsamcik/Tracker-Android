package com.adsamcik.tracker.shared.preferences.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultMapSettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear DataStore files between tests
        val dsDir = context.filesDir.resolve("datastore")
        if (dsDir.exists()) {
            dsDir.listFiles()?.forEach { file -> file.delete() }
        }
        resetMapSettingsForTests()
        LegacyPreferenceStore.resetForTests()
    }

    @Test
    fun default_values_are_applied() = runTest {
        val repo = DefaultMapSettingsRepository(context, Dispatchers.IO)
        val state = repo.data.first()
        assertEquals(MapSettingsState.DEFAULT_QUALITY, state.quality)
        assertEquals(MapSettingsState.DEFAULT_MAX_HEAT, state.maxHeatPoints)
        assertEquals(MapSettingsState.DEFAULT_VISIT_THRESHOLD, state.visitThresholdSeconds)
    }

    @Test
    fun setQuality_persists_and_reads_back() = runTest {
        val repo = DefaultMapSettingsRepository(context, Dispatchers.IO)
        // Ensure we consume the initial emission first
        repo.data.first()
        repo.setQuality(2.5f)
        val state = repo.data.first()
        assertEquals(2.5f, state.quality)
    }

    @Test
    fun setMaxHeatPoints_persists_positive_value() = runTest {
        val repo = DefaultMapSettingsRepository(context, Dispatchers.IO)
        repo.data.first()
        repo.setMaxHeatPoints(50)
        val state = repo.data.first()
        assertEquals(50, state.maxHeatPoints)
    }

    @Test
    fun setVisitThresholdSeconds_persists_positive_value() = runTest {
        val repo = DefaultMapSettingsRepository(context, Dispatchers.IO)
        repo.data.first()
        repo.setVisitThresholdSeconds(120)
        val state = repo.data.first()
        assertEquals(120, state.visitThresholdSeconds)
    }
}

private fun resetMapSettingsForTests() {
    try {
        val fileClassName = "com.adsamcik.tracker.shared.preferences.map.DefaultMapSettingsRepositoryKt"
        val delegateFieldName = "mapSettingsDataStore\$delegate"
        val fileClass = Class.forName(fileClassName)
        val delegateField = fileClass.getDeclaredField(delegateFieldName).apply { isAccessible = true }
        val delegate = delegateField.get(null)
        val instanceField = delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        instanceField.set(delegate, null)
    } catch (_: Exception) {
        // Ignore if field doesn't exist or can't be reset
    }
}
