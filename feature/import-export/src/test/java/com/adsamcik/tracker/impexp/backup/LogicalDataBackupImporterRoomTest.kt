package com.adsamcik.tracker.impexp.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceOverrides
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicalDataBackupImporterRoomTest {
    @Test
    fun `logical restore keeps original point identity and values but fences the previous dataset`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logical-restore-source"
        context.deleteDatabase(name)
        val copy = context.getDatabasePath(name)
        try {
            val source = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 3L,
                    collectedDataEpoch = 4L, restoredDataEpochs = "1,2", deletedSourceEventHighWaterOrdinal = 6L))
                source.locationSampleDao().insert(point(41L, 1_000L, 500_000_000))
            } finally { source.close() }
            val target = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
            try {
                target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 8L, revision = 20L))
                target.locationSampleDao().insert(point(99L, 2_000L, 510_000_000))
                val policies = policies(target)

                LogicalDataBackupImporter.replaceTrackerRows(target, copy, freshEpoch = 9L, nowMs = 3_000L, sourcePolicies = policies)

                target.locationSampleDao().byIds(listOf(41L)).single() shouldBe point(41L, 1_000L, 500_000_000)
                target.locationSampleDao().byIds(listOf(99L)).isEmpty() shouldBe true
                val state = requireNotNull(target.sourceEvidenceStateDao().get())
                state.collectedDataEpoch shouldBe 9L
                state.restoredDataEpochs shouldBe "1,2,4"
                state.deletedSourceEventHighWaterOrdinal shouldBe 6L
                state.revision shouldBe 21L
                state.acceptsRetainedEpoch(4L) shouldBe true
                state.acceptsRetainedEpoch(8L) shouldBe false

                // An interrupted restore retries from the same owned input, adding no copies.
                LogicalDataBackupImporter.replaceTrackerRows(target, copy, freshEpoch = 9L, nowMs = 4_000L, sourcePolicies = policies)
                target.locationSampleDao().countAll() shouldBe 1L
            } finally { target.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `a backup missing a data table leaves existing rows and installed triggers intact`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logical-restore-invalid-source"
        context.deleteDatabase(name)
        try {
            val source = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 1L))
                source.openHelper.writableDatabase.execSQL("DROP TABLE minigame_score")
            } finally { source.close() }
            val target = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
            try {
                target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 2L))
                target.locationSampleDao().insert(point(7L, 8_000L, 510_000_000))
                val policies = policies(target)
                val triggersBefore = target.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'")
                    .use { it.moveToFirst(); it.getLong(0) }
                shouldThrow<java.io.IOException> {
                    LogicalDataBackupImporter.replaceTrackerRows(target, context.getDatabasePath(name), 3L, 9_000L, policies)
                }
                target.locationSampleDao().byIds(listOf(7L)).single().timeMs shouldBe 8_000L
                target.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 2L
                target.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'")
                    .use { it.moveToFirst(); it.getLong(0) } shouldBe triggersBefore
            } finally { target.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `a fresh epoch cannot equal one of the restored original epochs`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logical-restore-epoch-source"
        context.deleteDatabase(name)
        try {
            val source = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 11L))
            } finally { source.close() }
            val target = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
            try {
                target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 1L))
                shouldThrow<IllegalArgumentException> {
                    LogicalDataBackupImporter.replaceTrackerRows(target, context.getDatabasePath(name), 11L, 12L, policies(target))
                }
                target.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
            } finally { target.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `restore keeps local source choices and quality while preserving the backup policy history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logical-restore-policy-source"
        context.deleteDatabase(name)
        val copy = context.getDatabasePath(name)
        try {
            val source = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
            val archivedSettings = TrackingParamsState(legacySettingsMigrationCompleted = true,
                locationEnabled = true, stepsEnabled = false, wifiEnabled = true,
                ambientWifiEnabled = false, autoTrackingMode = 2, balanceProfileVersion = 1,
                overallBalanceLevel = 5, locationProfileVersion = 1, locationBalanceLevel = 5)
            val archivedPolicies: List<com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity>
            val archivedConsent: List<com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity>
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 4L))
                val repository = RoomSourcePolicyRepository(source, effectiveTimeProvider = {
                    SourcePolicyEffectiveTime("archive-boot", 100L, 100L)
                })
                val snapshot = repository.bootstrapFromLegacy(archivedSettings)
                archivedPolicies = source.sourcePolicyDao().policiesAtRevision(snapshot.revision)
                archivedConsent = source.sourcePolicyDao().consentHistory(
                    TrackingSourceComponent.WIFI.stableCode, SourcePurpose.SESSION_CAPTURE.stableName)
                source.locationSampleDao().insert(point(41L, 1_000L, 500_000_000))
            } finally { source.close() }
            val target = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
            try {
                target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 8L))
                val repository = RoomSourcePolicyRepository(target, effectiveTimeProvider = {
                    SourcePolicyEffectiveTime("current-boot", 2_000L, 2_000L)
                })
                val localSettings = archivedSettings.copy(locationEnabled = false, stepsEnabled = true,
                    wifiEnabled = false, ambientWifiEnabled = true, autoTrackingMode = 0,
                    overallBalanceLevel = 2, locationBalanceLevel = 2,
                    sourceBalanceOverrides = SourceBalanceOverrides(wifi = 4),
                    minTimeSeconds = 17, minDistanceMeters = 43, requiredAccuracyMeters = 83,
                    backgroundWifiNotificationEnabled = true, ambientLocationEnabled = true,
                    locationBackgroundMode = com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode.ADAPTIVE,
                    locationMotionAssistanceEnabled = true)
                val first = repository.bootstrapFromLegacy(localSettings)
                val local = repository.replaceCaptureSettings(first.revision,
                    localSettings.copy(minTimeSeconds = 19), "LOCAL_CUSTOM")
                val localFingerprint = target.sourcePolicyDao().authority()?.legacySettingsFingerprint
                val localConsentEpoch = requireNotNull(target.sourcePolicyDao().maximumConsentEpoch())

                LogicalDataBackupImporter.replaceTrackerRows(target, copy, 9L, 3_000L, repository)

                val restored = (repository.currentState() as SourcePolicyAuthorityState.Active).snapshot
                (restored.revision > local.revision) shouldBe true
                TrackingSourceComponent.entries.forEach { component ->
                    val before = local[component]
                    val after = restored[component]
                    after.enabled shouldBe before.enabled
                    after.qos shouldBe before.qos
                    after.balanceLevel shouldBe before.balanceLevel
                    after.overallBalanceLevel shouldBe before.overallBalanceLevel
                    after.sourceBalanceOverride shouldBe before.sourceBalanceOverride
                    after.backgroundNotificationHostEnabled shouldBe before.backgroundNotificationHostEnabled
                    after.locationBackgroundMode shouldBe before.locationBackgroundMode
                    after.locationMotionAssistanceEnabled shouldBe before.locationMotionAssistanceEnabled
                    SourcePurpose.entries.forEach { purpose ->
                        val consent = requireNotNull(target.sourcePolicyDao().latestConsentEpoch(
                            component.stableCode, purpose.stableName))
                        (consent.epoch > localConsentEpoch) shouldBe true
                    }
                    (after.ambientConsentEpoch != null) shouldBe (before.ambientConsentEpoch != null)
                    (after.controlConsentEpoch != null) shouldBe (before.controlConsentEpoch != null)
                }
                restored[TrackingSourceComponent.LOCATION].locationMinTimeSeconds shouldBe 19
                restored[TrackingSourceComponent.LOCATION].locationRequiredAccuracyMeters shouldBe 83
                target.sourcePolicyDao().authority()?.legacySettingsFingerprint shouldBe localFingerprint
                target.sourcePolicyDao().policiesAtRevision(archivedPolicies.first().policyRevision) shouldBe archivedPolicies
                archivedConsent.forEach { consent ->
                    target.sourcePolicyDao().consentEpoch(consent.sourceKind, consent.purpose, consent.epoch) shouldBe consent
                }
                target.locationSampleDao().byIds(listOf(41L)).single().timeMs shouldBe 1_000L

                LogicalDataBackupImporter.replaceTrackerRows(target, copy, 9L, 4_000L, repository)
                val retried = (repository.currentState() as SourcePolicyAuthorityState.Active).snapshot
                retried[TrackingSourceComponent.LOCATION].enabled shouldBe false
                retried[TrackingSourceComponent.LOCATION].locationMinTimeSeconds shouldBe 19
                retried[TrackingSourceComponent.WIFI].ambientPersistenceEligible shouldBe true
                (retried.revision > restored.revision) shouldBe true
                target.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 9L
            } finally { target.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `failed local policy publication rolls back the complete row replacement`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logical-restore-policy-failed-source"
        context.deleteDatabase(name)
        try {
            val source = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 1L))
                source.locationSampleDao().insert(point(41L, 1_000L, 500_000_000))
            } finally { source.close() }
            val target = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
            try {
                target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 2L))
                target.locationSampleDao().insert(point(7L, 8_000L, 510_000_000))
                val repository = policies(target)
                val local = (repository.currentState() as SourcePolicyAuthorityState.Active).snapshot
                val rejecting = object : SourcePolicyRepository by repository {
                    override suspend fun reissueAfterDataRestore(localChoices: SourcePolicySnapshot,
                        legacySettingsFingerprint: String?, previousConsentEpoch: Long): SourcePolicySnapshot =
                        throw java.io.IOException("Policy publication failed")
                }
                shouldThrow<java.io.IOException> {
                    LogicalDataBackupImporter.replaceTrackerRows(target, context.getDatabasePath(name), 3L, 9_000L, rejecting)
                }
                target.locationSampleDao().byIds(listOf(7L)).single().timeMs shouldBe 8_000L
                target.locationSampleDao().byIds(listOf(41L)).isEmpty() shouldBe true
                target.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 2L
                repository.currentState() shouldBe SourcePolicyAuthorityState.Active(local)
            } finally { target.close() }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun policies(database: AppDatabase): RoomSourcePolicyRepository =
        RoomSourcePolicyRepository(database, effectiveTimeProvider = {
            SourcePolicyEffectiveTime("current-boot", 1_000L, 1_000L)
        }).also { it.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true)) }

    private fun point(id: Long, time: Long, latitude: Int) = LocationSample(id = id, timeMs = time,
        elapsedRealtimeNanos = 0L, latE7 = latitude, lonE7 = 140_000_000, altitudeM = null,
        rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null, speedAccuracyMps = null,
        provider = "saved", quality = SampleQuality.COARSE, motionState = null, policy = null,
        bucketId = null, createdAt = time, sourceSignalId = "original-$id", legacyLat = latitude / 10_000_000.0)
}
