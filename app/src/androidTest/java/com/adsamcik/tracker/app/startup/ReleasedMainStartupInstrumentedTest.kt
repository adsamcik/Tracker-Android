package com.adsamcik.tracker.app.startup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.di.FiveSourceTrackingHistoryEntryPoint
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.hasCompletedLegacyImport
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the closed Location-only v12 fixture copied into fresh Debug BEFORE Application startup. */
@RunWith(AndroidJUnit4::class)
class ReleasedMainStartupInstrumentedTest {
    @Test(timeout = 120_000L)
    fun releasedLocationOnlyImportReachesFullReadyAndRemainsVisibleThroughSharedHistory() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("releasedMainFixture") == "location-only-v12") {
            "Preseed the Location-only released v12 database before launching this selected test"
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        check(application.packageName == "com.adsamcik.tracker.debug")
        assertTrue(application.getDatabasePath(LEGACY_DATABASE_NAME).isFile)
        val entry = EntryPointAccessors.fromApplication(application, FiveSourceTrackingHistoryEntryPoint::class.java)
        val startup = application.awaitStartupReconciliation()
        assertTrue("Full production startup failed: $startup", startup is TrackingStartupResult.Ready)
        assertTrue(entry.startupGate().isReady)
        val database = entry.database()
        val lifecycle = entry.collectedDataLifecycleStore().snapshot()
        val guard = checkNotNull(database.sourceEvidenceStateDao().get())
        assertEquals(lifecycle.epoch, guard.collectedDataEpoch)
        assertEquals(lifecycle.retainedFromMs, guard.retainedFromMs)
        val raw = database.openHelper.writableDatabase
        assertTrue(hasCompletedLegacyImport(raw))
        raw.query("SELECT COUNT(*) FROM location_sample WHERE id=101").use { row ->
            assertTrue(row.moveToFirst()); assertEquals(1L, row.getLong(0))
        }
        raw.query("SELECT COUNT(*) FROM source_projection_registration").use { row ->
            assertTrue(row.moveToFirst()); assertTrue(row.getLong(0) >= 4L)
        }
        val read = entry.history().readRange(HistoryRangeRequest(HistoryRange(
            1_700_100_000_000L, 1_700_100_000_001L, "UTC", sources = setOf(HistorySource.LOCATION),
            mode = HistoryBrowseMode.OBSERVATIONS), limit = 2))
        check(read is HistoryRangeRead.Page) { "Released Location history unavailable: $read" }
        val point = read.rows.mapNotNull { (it.measurement as? HistoryMeasurement.Location)?.point }.single()
        assertEquals(101L, point.sampleId)
        assertEquals(1_700_100_000_000L, point.timeMs)
        assertEquals(501_000_000, point.latitudeE7)
        assertEquals(142_000_000, point.longitudeE7)
    }
}
