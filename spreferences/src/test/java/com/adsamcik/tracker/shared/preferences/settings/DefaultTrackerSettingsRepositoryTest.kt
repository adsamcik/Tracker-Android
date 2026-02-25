package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackerSettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test to isolate migration behavior
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        // Remove persisted DataStore between tests to avoid leakage of previous values
        val dsDir = context.filesDir.resolve("datastore")
        if (dsDir.exists()) {
            dsDir.listFiles()?.forEach { file -> file.delete() }
        }
        // Also clear the in-memory DataStore cache (file deletion alone is insufficient because the
        // delegated DataStore instance is retained across tests). Using runBlocking since @Before
        // cannot be suspend.
        kotlinx.coroutines.runBlocking { resetTrackerSettingsForTests(context) }
    }

    private class FakeKeys : TrackerSettingsKeyProvider {
        override val autoUnitSwitchKey: String = "statisticsAutoUnitSwitch"
        override val autoUnitSwitchDefault: Boolean = true // matches string resource default
        override val lengthSystemKey: String = "lengthSystem"
        override val lengthSystemDefault: String = "Metric"
        override val speedFormatKey: String = "speedFormat"
        override val speedFormatDefault: String = "Hour"
    }

    @Test
    fun default_isTrue_andDefaultsLengthSpeed() = runTest {
        val repo = DefaultTrackerSettingsRepository(context, Dispatchers.IO, FakeKeys())
        val value = repo.data.first()
        assertEquals(true, value.autoUnitSwitch)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.LengthSystem.Metric, value.lengthSystem)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Hour, value.speedFormat)
    }

    @Test
    fun migrates_legacyPreference_onFirstCollect() = runTest {
        // Arrange legacy value true
    val key = FakeKeys().autoUnitSwitchKey
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(key, true)
            .putString(FakeKeys().lengthSystemKey, "Imperial")
            .putString(FakeKeys().speedFormatKey, "Minute")
            .commit()
    val repo = DefaultTrackerSettingsRepository(context, Dispatchers.IO, FakeKeys())

        // Act
        val value = repo.data.first()

        // Assert
        assertEquals(true, value.autoUnitSwitch)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.LengthSystem.Imperial, value.lengthSystem)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Minute, value.speedFormat)
    }

    @Test
    fun set_updatesPersistedValue() = runTest {
    val repo = DefaultTrackerSettingsRepository(context, Dispatchers.IO, FakeKeys())
        repo.setAutoUnitSwitch(true)
        repo.setLengthSystem(com.adsamcik.tracker.shared.preferences.type.LengthSystem.Sailing)
        repo.setSpeedFormat(com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Second)
        val afterSet = repo.data.first()
        assertEquals(true, afterSet.autoUnitSwitch)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.LengthSystem.Sailing, afterSet.lengthSystem)
        assertEquals(com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Second, afterSet.speedFormat)
    }

    @Test
    fun concurrent_migration_does_not_corrupt_state() = runTest {
        // Arrange: seed legacy SharedPreferences with non-default values
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(FakeKeys().autoUnitSwitchKey, true)
            .putString(FakeKeys().lengthSystemKey, "Imperial")
            .putString(FakeKeys().speedFormatKey, "Minute")
            .commit()

        val repo = DefaultTrackerSettingsRepository(context, Dispatchers.IO, FakeKeys())

        // Act: launch many concurrent reads that all trigger ensureMigrated via onStart
        val results = (1..20).map {
            async(Dispatchers.IO) { repo.data.first() }
        }.awaitAll()

        // Assert: every result must reflect the migrated legacy values – no corruption
        results.forEach { value ->
            assertEquals(true, value.autoUnitSwitch)
            assertEquals(
                com.adsamcik.tracker.shared.preferences.type.LengthSystem.Imperial,
                value.lengthSystem
            )
            assertEquals(
                com.adsamcik.tracker.shared.preferences.type.SpeedFormat.Minute,
                value.speedFormat
            )
        }
    }
}
