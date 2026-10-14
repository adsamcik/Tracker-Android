package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.impexp.backup.DataBackupArchiveReader
import com.adsamcik.tracker.impexp.backup.DataBackupInspection
import com.adsamcik.tracker.impexp.backup.DataBackupPreview
import com.adsamcik.tracker.impexp.backup.DataBackupRestore
import com.adsamcik.tracker.impexp.backup.DataBackupRestoreResult
import com.adsamcik.tracker.impexp.backup.DataBackupSnapshot
import com.adsamcik.tracker.impexp.backup.LogicalDataBackupImporter
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupScope
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Confirmed logical replacement, using the existing stop, writer and storage boundaries. */
@Singleton
class DefaultDataBackupRestore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lifecycle: CollectedDataLifecycleStore,
    private val startup: Provider<TrackingStartupGate>,
    private val barrier: TrackingStartupDeletionBarrier,
    private val quiescer: Provider<CollectedDataWriterQuiescer>,
    private val database: Provider<AppDatabase>,
    private val points: Provider<PointsDatabase>,
    private val dispatchers: DispatchersProvider,
    private val sourcePolicies: Provider<SourcePolicyRepository>,
    private val policyBootstrap: Provider<SourcePolicyAuthorityBootstrapCoordinator>,
) : DataBackupRestore {
    private val reader = DataBackupArchiveReader(context)
    private val mutex = Mutex()
    @Volatile private var activeRestore: Job? = null
    @Volatile private var deletionCleanupActive = false
    private var initialPreviewCleanupAttempted = false
    private val root get() = File(context.noBackupFilesDir, "data-restore").also {
        require(it.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
    }
    private val journal get() = AtomicFile(File(root, "confirmed.properties"))

    override suspend fun inspect(uri: Uri): DataBackupInspection = withContext(dispatchers.io) {
        val gate = startup.get()
        gate.reconcile()
        val generation = gate.currentGeneration
        val epoch = lifecycle.snapshot().epoch
        gate.withReadyGenerationTransfer(generation) {
            when (val result = reader.inspect(uri)) {
                is DataBackupInspection.Ready -> try {
                    val copy = File(reader.directory(result.preview.token), "databases/$ACTIVE_DATABASE_NAME")
                    LogicalDataBackupImporter.normalizeOwnedCopy(context, copy)
                    LogicalDataBackupImporter.validateOwnedCopy(copy, database.get().openHelper.writableDatabase, tracker = true)
                    val pointsCopy = File(reader.directory(result.preview.token), "databases/${DatabaseBackupScope.POINTS_DATABASE_NAME}")
                    if (pointsCopy.isFile) {
                        val ownedPoints = PointsDatabase.ownedCopyBuilder(context, pointsCopy.path).build()
                        try { ownedPoints.openHelper.writableDatabase } finally { ownedPoints.close() }
                    }
                    if (pointsCopy.isFile) LogicalDataBackupImporter.validateOwnedCopy(pointsCopy, points.get().openHelper.writableDatabase, tracker = false)
                    if (lifecycle.snapshot().epoch != epoch) {
                        reader.discard(result.preview.token)
                        DataBackupInspection.Invalid
                    } else result.copy(preview = result.preview.copy(originalDataEpoch = epoch))
                } catch (cancelled: CancellationException) {
                    reader.discard(result.preview.token)
                    throw cancelled
                } catch (_: Exception) {
                    reader.discard(result.preview.token)
                    DataBackupInspection.Invalid
                }
                else -> result
            }
        } ?: DataBackupInspection.Invalid
    }

    override suspend fun discardPreview(token: String) = withContext(dispatchers.io) {
        mutex.withLock {
            if (readJournal()?.token != token) reader.discard(token)
        }
    }

    override suspend fun restore(preview: DataBackupPreview): DataBackupRestoreResult = withContext(dispatchers.io) {
        val job = currentCoroutineContext().job
        try {
        mutex.withLock {
            activeRestore = job
            try {
                if (deletionCleanupActive || readJournal() != null || !startup.get().isReady || deletionIsPending() ||
                    preview.originalDataEpoch < 0L || lifecycle.snapshot().epoch != preview.originalDataEpoch) {
                    return@withLock DataBackupRestoreResult.Unavailable
                }
                val directory = reader.directory(preview.token)
                val source = File(directory, "databases/$ACTIVE_DATABASE_NAME")
                if (!source.isFile) return@withLock DataBackupRestoreResult.Unavailable
                val targetEpoch = Math.addExact(maxOf(preview.originalDataEpoch,
                    LogicalDataBackupImporter.maximumOriginalEpoch(source)), 1L)
                val operation = RestoreJournal(preview.token, preview.originalDataEpoch, targetEpoch, "prepared")
                writeJournal(operation)
                // The durable confirmation is published before any mutation. Cancellation leaves
                // an owned input and journal; startup completes the same import, not a new one.
                apply(operation)
                DataBackupRestoreResult.Restored
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DataBackupRestoreResult.Unavailable }
        }
        } finally { if (activeRestore === job) activeRestore = null }
    }

    override suspend fun reconcilePendingRestore(): Boolean = withContext(dispatchers.io) {
        val job = currentCoroutineContext().job
        try {
        mutex.withLock {
            activeRestore = job
            val pending = try { readJournal() } catch (_: Exception) { return@withLock false }
                ?: run {
                    if (!initialPreviewCleanupAttempted) {
                        initialPreviewCleanupAttempted = true
                        // Unconfirmed previews have no authority across a process restart. Failed
                        // cleanup stays in the known owned folder for Clear all to retry.
                        try { reader.clearAllOwned() } catch (_: Exception) { }
                    }
                    if (!deletionIsPending()) deletionCleanupActive = false
                    return@withLock true
                }
            try { apply(pending); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        } finally { if (activeRestore === job) activeRestore = null }
    }

    override suspend fun cancelAndClearOwnedData() = withContext(dispatchers.io) {
        deletionCleanupActive = true
        activeRestore?.takeUnless { it === currentCoroutineContext().job }?.cancelAndJoin()
        mutex.withLock {
            reader.clearAllOwned()
            val preserved = File(context.filesDir, "data-backup/legacy-database")
            require(preserved.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
            if (preserved.exists() && !preserved.delete()) throw IOException("Preserved imported data could not be removed")
            com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository(context).refreshRestoredData()
        }
    }

    private fun deletionIsPending(): Boolean = listOf("", ".tmp", ".clearing").any {
        File(context.noBackupFilesDir, "collected-data-deletion-pending$it").exists()
    }

    private suspend fun apply(initial: RestoreJournal) {
        if (initial.phase == "complete") {
            finishCompleted(initial)
            return
        }
        var operation = initial
        val directory = reader.directory(operation.token)
        val source = File(directory, "databases/$ACTIVE_DATABASE_NAME")
        require(source.isFile)
        val localEpoch = lifecycle.snapshot().epoch
        require(localEpoch == operation.originalEpoch || localEpoch == operation.targetEpoch)
        if (policyBootstrap.get().reconcileAuthorityForRetentionBootstrap() !is
            SourcePolicyRevisionReconciliationResult.Complete) {
            throw IOException("Current tracking choices could not be preserved")
        }
        if (TrackerServiceApi.turnOffTrackingAndAwait(context) != TrackingTurnOffResult.COMPLETE) {
            throw IOException("Tracking could not stop for data restore")
        }
        barrier.beginCloseAdmission()
        val writers = quiescer.get()
        writers.quiesce()
        barrier.awaitQuiescence()
        try {
            val safety = File(root, "previous-data.zip")
            if (operation.phase == "prepared") {
                val temporary = File(root, "previous-data.zip.tmp")
                try {
                    temporary.outputStream().use { output ->
                        DataBackupSnapshot.write(context, output)
                        output.fd.sync()
                    }
                    check(temporary.renameTo(safety)) { "The previous data could not be preserved" }
                } finally { temporary.delete() }
                operation = operation.copy(phase = "preserved")
                writeJournal(operation)
            }
            val floor = LogicalDataBackupImporter.backedUpRetentionFloor(source)
            lifecycle.beginDataRestore(operation.token, operation.targetEpoch, floor)
            LogicalDataBackupImporter.replaceTrackerRows(database.get(), source, operation.targetEpoch,
                System.currentTimeMillis(), sourcePolicies.get())
            val pointsSource = File(directory, "databases/${DatabaseBackupScope.POINTS_DATABASE_NAME}")
            val pointsDatabase = points.get()
            if (pointsSource.isFile) {
                LogicalDataBackupImporter.replaceAuxiliaryRows(pointsSource, pointsDatabase.openHelper.writableDatabase)
                pointsDatabase.invalidationTracker.refreshAsync()
            } else pointsDatabase.clearAllTables()
            for (path in DatabaseBackupScope.allowedPaths.filter { it.startsWith("files/") }) {
                val staged = File(directory, path)
                val destination = DatabaseBackupScope.destination(context, path)
                if (staged.isFile) replaceOwnedFile(staged, destination)
                else if (destination.exists() && !destination.delete()) throw IOException("Previous imported file could not be removed")
            }
            // The preserved legacy source is durable user data, not a replacement Room database.
            val legacy = File(directory, "databases/$LEGACY_DATABASE_NAME")
            if (legacy.isFile) replaceOwnedFile(legacy, File(context.filesDir, "data-backup/legacy-database"))
            val legacyRepository = com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository(context)
            legacyRepository.removeReleasedSourceAfterConfirmedRestore()
            legacyRepository.refreshRestoredData()
            com.adsamcik.tracker.shared.base.data.ImportedDataFilesRevision.changed()
            writeJournal(operation.copy(phase = "complete"))
            finishCompleted(operation)
        } catch (failure: Throwable) {
            // A partly imported dataset remains behind the closed barrier and durable journal.
            // The next startup retries from the same validated input, retaining the safety copy.
            throw failure
        }
    }

    private suspend fun finishCompleted(operation: RestoreJournal) {
        reader.discard(operation.token)
        val safety = File(root, "previous-data.zip")
        if (safety.exists() && !safety.delete()) throw IOException("Restore safety copy could not be removed")
        journal.delete()
        check(!journal.baseFile.exists()) { "Restore completion could not be saved" }
        barrier.reopen()
        quiescer.get().resume()
    }

    private suspend fun replaceOwnedFile(source: File, destination: File) {
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    private fun readJournal(): RestoreJournal? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return null
        val values = Properties().apply { journal.openRead().use(::load) }
        val token = values.getProperty("token") ?: throw IOException("Restore journal is incomplete")
        require(UUID.fromString(token).toString() == token)
        val original = values.getProperty("originalEpoch").toLong()
        val target = values.getProperty("targetEpoch").toLong()
        val phase = values.getProperty("phase")
        require(original >= 0L && target > original && phase in setOf("prepared", "preserved", "complete"))
        return RestoreJournal(token, original, target, phase)
    }

    private fun writeJournal(operation: RestoreJournal) {
        check(root.mkdirs() || root.isDirectory)
        val output = journal.startWrite()
        try {
            Properties().apply {
                setProperty("token", operation.token); setProperty("originalEpoch", operation.originalEpoch.toString())
                setProperty("targetEpoch", operation.targetEpoch.toString()); setProperty("phase", operation.phase)
            }.store(output, null)
            journal.finishWrite(output)
        } catch (failure: Throwable) { journal.failWrite(output); throw failure }
    }

    private data class RestoreJournal(val token: String, val originalEpoch: Long, val targetEpoch: Long, val phase: String)
}
