package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedAmbientRadioGapReaderTest {
    private lateinit var database: AppDatabase

    @Before fun setup() = runTest {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure()
        for (source in listOf(5, 6)) {
            database.sourcePolicyDao().insertPolicies(listOf(policy(source)))
            database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(source,
                SourceBrokerPurpose.AMBIENT_PRODUCT, 0, true, true, 1, "boot", 0, 0, "USER")))
        }
        database.ambientWifiFactDao().insertRetentionAuthority(AmbientWifiRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        database.ambientCellFactDao().insertRetentionAuthority(AmbientCellRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        database.ambientWifiFactDao().insertGap(wifi())
        database.ambientCellFactDao().insertGap(cell())
    }
    @After fun cleanup() = database.close()

    @Test fun `saved acquisition gaps keep their historical purpose after recording is turned off`() = runTest {
        for (source in listOf(5, 6)) database.sourcePolicyDao().insertPolicies(listOf(policy(source).copy(
            policyRevision = 2, enabled = false, ambientPersistenceEligible = false)))
        assertEquals(listOf(wifi()), readWifi().rows)
        assertEquals(listOf(cell()), readCell().rows)
        assertTrue(readWifi().gaps.isEmpty())
        assertTrue(readCell().gaps.isEmpty())
        assertNull(database.sourceBrokerDao().registration(5, 1))
    }

    @Test fun `native gap deletion does not erase another source gap or revive after a full clear`() = runTest {
        database.ambientWifiFactDao().insertReplayFootprints(listOf(AmbientWifiFactIntegrity.createReplayFootprint(
            AmbientWifiReplayFootprintEntity.KIND_LOCAL_GAP, wifi().gapId, 0, 0, 1, 3_000)))
        assertTrue(readWifi().rows.isEmpty())
        assertEquals(setOf(RadioContextReadGapReason.RETENTION_BOUNDARY), readWifi().gaps)
        assertEquals(1, readCell().rows.size)
        database.sourceEvidenceStateDao().updateLifecycle(1, null, 3_001)
        assertTrue(readCell().rows.isEmpty())
        assertEquals(1, database.ambientCellFactDao().gapsOverWindow(0, 3_000, 10).size)
    }

    @Test fun `missing native gap grant is unavailable while retention filters stale gaps`() = runTest {
        database.openHelper.writableDatabase.execSQL("DELETE FROM source_consent_epoch WHERE source_kind = 5")
        assertTrue(readWifi().rows.isEmpty())
        assertEquals(setOf(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE), readWifi().gaps)
        database.sourceEvidenceStateDao().updateLifecycle(0, 1_501, 3_000)
        assertTrue(readCell().rows.isEmpty())
        assertEquals(setOf(RadioContextReadGapReason.RETENTION_BOUNDARY), readCell().gaps)
    }

    private suspend fun readWifi() = database.withTransaction {
        RetainedAmbientRadioReader(database).qualifyWifiGapsInTransaction(
            database.ambientWifiFactDao().gapsOverWindow(0, 3_000, 10), 10)
    }
    private suspend fun readCell() = database.withTransaction {
        RetainedAmbientRadioReader(database).qualifyCellGapsInTransaction(
            database.ambientCellFactDao().gapsOverWindow(0, 3_000, 10), 10)
    }
    private fun policy(source: Int) = SourcePolicyEntity(1, source, true, 1, null, null, null,
        false, false, true, null, null, 0, "boot", 0, 0, "USER")
    private fun wifi() = AmbientWifiFactIntegrity.createGap("a".repeat(64),
        AmbientWifiGapEntity.REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE, 1_500, 1_501, "UTC", 0,
        1, 0, "retention", 1, 0, 0, 2_000)
    private fun cell() = AmbientCellFactIntegrity.createGap("b".repeat(64),
        AmbientCellGapEntity.REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE, 1_500, 1_501, "UTC", 0,
        1, 0, "retention", 1, 0, 0, 2_000)
}
