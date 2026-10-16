package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.impexp.backup.DataBackupArchiveReader
import com.adsamcik.tracker.impexp.backup.DataBackupInspection
import com.adsamcik.tracker.impexp.backup.DataBackupRestoreResult
import com.adsamcik.tracker.impexp.backup.DataBackupSnapshot
import com.adsamcik.tracker.impexp.backup.LogicalDataBackupImporter
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupScope
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.DefaultCollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.lifecycle.resetCollectedDataLifecycleForTests
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.Runs
import java.io.File
import java.util.Properties
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PosixAtomicFileRenameShadow::class])
class DataBackupRestoreRecoveryTest {
    @Test
    fun `restore resumes after asset failure`() = restoreScenario(false)

    @Test
    fun `restore refuses checkpoint`() = restoreScenario(true)

    // Keep the real backup, interrupted asset handoff and checkpoint/recovery assertions together with
    // their cleanup.
    @Suppress("LongMethod")
    private fun restoreScenario(rejectPreservedCheckpoint: Boolean) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        resetCollectedDataLifecycleForTests(context)
        context.deleteDatabase(ACTIVE_DATABASE_NAME)
        val archive = File(context.cacheDir, "complete-data-backup.zip")
        val basemapDirectory = File(context.filesDir, "basemap")
        val restoreRoot = File(context.noBackupFilesDir, "data-restore")
        restoreRoot.deleteRecursively(); basemapDirectory.deleteRecursively()
        context.deleteDatabase(com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME)
        val source = AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME).allowMainThreadQueries().build()
        try {
            source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
            source.locationSampleDao().insert(point(2L, 200L))
        } finally { source.close() }
        basemapDirectory.mkdirs()
        File(basemapDirectory, "custom.pmtiles").writeText("PMTiles\u0003restored")
        try {
            archive.outputStream().use { DataBackupSnapshot.write(context, it) }
        } finally {
            context.deleteDatabase(ACTIVE_DATABASE_NAME)
            basemapDirectory.deleteRecursively()
        }
        val target = AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME).allowMainThreadQueries().build()
        val points = Room.inMemoryDatabaseBuilder(context, PointsDatabase::class.java).allowMainThreadQueries().build()
        mockkObject(TrackerServiceApi)
        try {
            target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
            target.locationSampleDao().insert(point(1L, 100L))
            val legacyFile = context.getDatabasePath(com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME)
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { old ->
                old.execSQL("CREATE TABLE saved_legacy_value (value TEXT)")
                old.execSQL("INSERT INTO saved_legacy_value VALUES ('previous dataset')")
                old.version = 12
            }
            val lifecycle = DefaultCollectedDataLifecycleStore(context)
            val barrier = TrackingStartupDeletionBarrier()
            val gate = object : TrackingStartupGate {
                override val isReady get() = !barrier.isClosed
                override val currentGeneration get() = barrier.currentGeneration
                override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
            }
            val quiescer = mockk<CollectedDataWriterQuiescer>()
            coEvery { quiescer.quiesce() } just Runs
            every { quiescer.resume() } just Runs
            coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } returns TrackingTurnOffResult.COMPLETE
            val dispatchers = TestDispatchersProvider(Dispatchers.Default)
            val policies = RoomSourcePolicyRepository(target, effectiveTimeProvider = {
                SourcePolicyEffectiveTime("current-boot", 1_000L, 1_000L)
            })
            val localChoices = policies.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true,
                locationEnabled = false, activityEnabled = false, stepsEnabled = true,
                autoTrackingMode = 0, minTimeSeconds = 17, requiredAccuracyMeters = 83))
            val bootstrap = SourcePolicyAuthorityBootstrapCoordinator {
                SourcePolicyRevisionReconciliationResult.Complete(
                    (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot)
            }
            fun coordinator() = DefaultDataBackupRestore(context, lifecycle, Provider { gate }, barrier,
                Provider { quiescer }, Provider { target }, Provider { points }, dispatchers, Provider { policies }, Provider { bootstrap })
            val archiveUri = Uri.fromFile(archive)
            val exportedReader = DataBackupArchiveReader(context)
            val exportedPreview = exportedReader.inspect(archiveUri)
                .shouldBeInstanceOf<DataBackupInspection.Ready>().preview
            try {
                val ownedCopy = File(exportedReader.directory(exportedPreview.token), "databases/$ACTIVE_DATABASE_NAME")
                LogicalDataBackupImporter.normalizeOwnedCopy(context, ownedCopy)
                LogicalDataBackupImporter.validateOwnedCopy(ownedCopy, target.openHelper.writableDatabase, tracker = true)
                LogicalDataBackupImporter.maximumOriginalEpoch(ownedCopy) shouldBe 7L
            } finally { exportedReader.discard(exportedPreview.token) }
            val initial = coordinator()
            val preview = initial.inspect(archiveUri).shouldBeInstanceOf<DataBackupInspection.Ready>().preview
            val preferences = context.getSharedPreferences("user-settings-fixture", Context.MODE_PRIVATE)
            preferences.edit().putString("quality", "balanced").commit()
            // A file where the asset's parent directory belongs makes only the asset handoff fail.
            basemapDirectory.writeText("temporary obstacle")
            PosixAtomicFileRenameShadow.refusePreservedRestoreJournal = rejectPreservedCheckpoint
            initial.restore(preview) shouldBe DataBackupRestoreResult.Unavailable
            barrier.isClosed shouldBe true
            val safetyCopy = File(restoreRoot, "previous-data.zip")
            val confirmation = File(restoreRoot, "confirmed.properties")
            val pending = Properties().apply {
                if (confirmation.isFile) {
                    confirmation.inputStream().use(::load)
                }
            }
            if (rejectPreservedCheckpoint) {
                pending.getProperty("phase") shouldBe "prepared"
                safetyCopy.isFile shouldBe true
                lifecycle.snapshot().epoch shouldBe 0L
                target.locationSampleDao().countAll() shouldBe 1L
                target.locationSampleDao().byIds(listOf(1L)).single().timeMs shouldBe 100L
                legacyFile.isFile shouldBe true
                barrier.isClosed shouldBe true
                return@runTest
            }
            val checkpointEpoch = lifecycle.snapshot().epoch
            val atomicFailures = org.robolectric.shadows.ShadowLog.getLogsForTag("AtomicFile")
                .takeLast(8).map { it.msg }
            withClue("Restore checkpoint: phase=${pending.getProperty("phase")}, safetyBytes=${safetyCopy.length()}, epoch=$checkpointEpoch, atomicFailures=$atomicFailures") {
                safetyCopy.isFile shouldBe true
                confirmation.isFile shouldBe true
                pending.getProperty("phase") shouldBe "preserved"
                lifecycle.snapshot().epoch shouldBe 8L
            }
            target.locationSampleDao().byIds(listOf(2L)).single().timeMs shouldBe 200L

            basemapDirectory.delete(); basemapDirectory.mkdirs()
            coordinator().reconcilePendingRestore() shouldBe true
            barrier.isClosed shouldBe false
            lifecycle.snapshot().epoch shouldBe 8L
            target.sourceEvidenceStateDao().get()?.acceptsRetainedEpoch(7L) shouldBe true
            target.locationSampleDao().countAll() shouldBe 1L
            File(basemapDirectory, "custom.pmtiles").readText() shouldBe "PMTiles\u0003restored"
            File(restoreRoot, "confirmed.properties").exists() shouldBe false
            File(restoreRoot, "previous-data.zip").exists() shouldBe false
            preferences.getString("quality", null) shouldBe "balanced"
            val restoredChoices = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
            restoredChoices[TrackingSourceComponent.LOCATION].enabled shouldBe false
            restoredChoices[TrackingSourceComponent.LOCATION].locationMinTimeSeconds shouldBe 17
            restoredChoices[TrackingSourceComponent.LOCATION].locationRequiredAccuracyMeters shouldBe 83
            restoredChoices[TrackingSourceComponent.STEPS].enabled shouldBe true
            (restoredChoices.revision > localChoices.revision) shouldBe true
            legacyFile.exists() shouldBe false
            com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository(context).hasSourceDatabase() shouldBe false
            DatabaseBackupScope.discover(context).none { it.archivePath.contains("legacy-database") ||
                it.archivePath == "databases/${com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME}" } shouldBe true
        } finally {
            PosixAtomicFileRenameShadow.refusePreservedRestoreJournal = false
            unmockkObject(TrackerServiceApi)
            target.close(); points.close()
            context.deleteDatabase(ACTIVE_DATABASE_NAME)
            archive.delete(); basemapDirectory.deleteRecursively(); restoreRoot.deleteRecursively()
            resetCollectedDataLifecycleForTests(context)
        }
    }

    private fun point(id: Long, time: Long) = LocationSample(id = id, timeMs = time,
        elapsedRealtimeNanos = 0L, latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null,
        rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null, speedAccuracyMps = null,
        provider = "saved", quality = SampleQuality.COARSE, motionState = null, policy = null,
        bucketId = null, createdAt = time, sourceSignalId = "backup-original-$id")

}
