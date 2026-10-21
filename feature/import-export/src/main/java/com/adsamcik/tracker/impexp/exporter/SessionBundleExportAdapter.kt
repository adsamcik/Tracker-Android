package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExporter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExporter
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportFailure
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportRequest
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportResult
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.automation.UnfinishedExportFile
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.AllDaySensorPortableRows
import com.adsamcik.tracker.impexp.portable.AllDaySensorSourceFile
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One selected session action, using the existing typed files and shared transfer lifetime. */
internal class ImportExportSessionBundleExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dispatchers: DispatchersProvider,
    private val startup: TrackingStartupGate,
    private val exportPlans: ExportPlanStore,
) : SessionBundleExporter {
    override suspend fun export(destination: String, request: SessionBundleExportRequest): SessionBundleExportResult {
        if (startup.reconcile() !is TrackingStartupResult.Ready) {
            return SessionBundleExportResult.Failure(SessionBundleExportFailure.UNAVAILABLE)
        }
        val destinationUri = Uri.parse(destination)
        val generation = startup.currentGeneration
        return startup.withReadyGenerationTransfer(generation) {
            withContext(dispatchers.io) {
                var success = false
                try {
                    val result = writeBundle(request, {
                        if (startup.withRetainedDataGeneration(generation) { true } != true) {
                            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
                        }
                    }) { write ->
                        context.writeSessionBundleDestination(destinationUri, write)
                    }
                    success = true
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SessionBundleExportException) {
                    SessionBundleExportResult.Failure(failure.reason)
                } catch (_: Exception) {
                    SessionBundleExportResult.Failure(SessionBundleExportFailure.STORAGE)
                } finally {
                    if (!success) {
                        removeUnfinished(destinationUri)
                    }
                }
            }
        } ?: SessionBundleExportResult.Failure(SessionBundleExportFailure.UNAVAILABLE)
    }

    /** Fully prepares the file before opening a slow external destination. */
    internal suspend fun writeBundle(request: SessionBundleExportRequest, verifyAccess: () -> Unit,
        destination: suspend (suspend (OutputStream) -> Unit) -> Unit): SessionBundleExportResult {
        val snapshot = snapshot(request)
        val spools = mutableListOf<StagedSessionChild>()
        var primaryFailure: Throwable? = null
        var cleanupFailure: Exception? = null
        val result = try {
            stageChildren(request, snapshot, verifyAccess, spools)
            val manifest = sessionManifest(request, snapshot, spools)
            SessionBundleArchiveWriter().withArchive(context, manifest,
                spools.associate { child -> child.metadata.path to child.spool.backingFile }) { archive ->
                verifyChildren(request, snapshot, spools, verifyAccess)
                destination { output ->
                    verifyAccess(); verify(request, snapshot)
                    archive.copyTo(output)
                    verifyChildren(request, snapshot, spools, verifyAccess)
                }
            }
            SessionBundleExportResult.Success(spools.any { it.metadata.wholeEntries })
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Preserve the exact producer/destination failure for later suppressed cleanup failures.
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                spools.asReversed().forEach { child ->
                    // Concrete close has no callback; aggregate both filesystem failure families.
                    try { child.spool.close() } catch (failure: IOException) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure
                        } else {
                            cleanupFailure?.addSuppressed(failure)
                        }
                    } catch (failure: SecurityException) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure
                        } else {
                            cleanupFailure?.addSuppressed(failure)
                        }
                    }
                }
                primaryFailure?.let { failure ->
                    cleanupFailure?.let(failure::addSuppressed)
                }
            }
        }
        cleanupFailure?.let { throw it }
        return result
    }

    private suspend fun stageChildren(
        request: SessionBundleExportRequest,
        snapshot: SessionExportSnapshot,
        verifyAccess: () -> Unit,
        spools: MutableList<StagedSessionChild>,
    ) {
        for (plan in childPlans(request, snapshot)) {
            currentCoroutineContext().ensureActive(); verifyAccess(); verify(request, snapshot)
            val spool = FileBackedExportSpool(context, "session-child-",
                SessionBundleManifestCodecV1.MAX_CHILD_BYTES)
            try {
                val result = spool.write { plan.write(it) }
                if (result is ExportResult.Error) {
                    if (plan.optional && result.isEmptySource()) { spool.close(); continue }
                    throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
                }
                val path = "sources/${spools.size}/${plan.name}.${plan.extension}"
                val metadata = describeSessionChild(context, plan.source, spool.backingFile, plan.name)
                spools += StagedSessionChild(plan, spool, SessionBundleChildV1(path, metadata.formatId,
                    metadata.version, spool.byteCount, spool.backingFile.sha256(), metadata.fromMs,
                    metadata.toMs, metadata.fromMs < snapshot.fromMs || metadata.toMs > snapshot.toMs))
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                // This unpublished spool is otherwise unreachable even if the producer throws Error.
                // Concrete spool close performs only canonical-path and file deletion operations.
                try { spool.close() } catch (cleanup: IOException) {
                    failure.addSuppressed(cleanup)
                } catch (cleanup: SecurityException) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }

    private fun sessionManifest(
        request: SessionBundleExportRequest,
        snapshot: SessionExportSnapshot,
        spools: List<StagedSessionChild>,
    ): SessionBundleManifestV1 {
        if (spools.isEmpty()) {
            throw SessionBundleExportException(SessionBundleExportFailure.EMPTY)
        }
        if (spools.map { it.plan.source }.toSet() != request.sources) {
            throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        }
        return SessionBundleManifestV1(snapshot.organization.name, snapshot.activityLabel,
            snapshot.fromMs, snapshot.toMs, snapshot.zoneId, request.sources.map { it.name.lowercase() }.sorted(),
            spools.map { it.metadata }, emptyList(), selectedPurposes = listOf("session", "background"),
            selectedOrigins = listOf("local", "imported"))
    }

    private suspend fun verifyChildren(request: SessionBundleExportRequest, snapshot: SessionExportSnapshot,
        children: List<StagedSessionChild>, verifyAccess: () -> Unit) {
        verifyAccess(); verify(request, snapshot)
        for (child in children) {
            currentCoroutineContext().ensureActive(); verifyAccess()
            val digest = MessageDigest.getInstance("SHA-256")
            val discard = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }
            val result = child.plan.write(DigestOutputStream(discard, digest))
            if (result !is ExportResult.Success || digest.digest().hex() != child.metadata.sha256) {
                throw SessionBundleExportException(SessionBundleExportFailure.STALE)
            }
        }
        verifyAccess(); verify(request, snapshot)
    }

    private suspend fun snapshot(request: SessionBundleExportRequest): SessionExportSnapshot =
        database.withTransaction {
        val state = database.sourceEvidenceStateDao().get()
            ?: throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
            database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        }
        val row = readSnapshotOrganization(request)
        val from = row.fromMs
        val to = row.toMs
        if (row.isActive || from == null || to == null) {
            throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        }
        if (from < 0L || to < from) {
            throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        }
        val activity = snapshotActivityLabel(row)
        SessionExportSnapshot(row, state.collectedDataEpoch, state.retainedFromMs, from, to,
            row.displayZoneId ?: java.time.ZoneId.systemDefault().id, activity)
    }

    private suspend fun readSnapshotOrganization(request: SessionBundleExportRequest): OrganizationSessionRow {
        val row = database.organizationAnnotationDao().visible(null, null, 1,
            selectedLocalId = (request.sessionId as? OrganizationSessionId.Annotation)?.localId,
            selectedAdapterKey = (request.sessionId as? OrganizationSessionId.Recording)?.adapterKey).singleOrNull()
            ?: throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        if (row.revision != request.expectedOrganizationRevision) {
            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        }
        return row
    }

    private suspend fun snapshotActivityLabel(row: OrganizationSessionRow): String? =
        row.activityLabelId?.let { id -> NativeSessionActivity.entries.firstOrNull { it.id == id }
            ?.let { context.getString(it.nameRes) } ?:
                database.activityDao().getByIds(listOf(id)).singleOrNull()?.name }
            ?: row.importedActivityLabel

    private suspend fun verify(request: SessionBundleExportRequest, expected: SessionExportSnapshot) {
        if (snapshot(request) != expected) {
            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        }
    }

    private suspend fun removeUnfinished(uri: Uri) = withContext(NonCancellable + dispatchers.io) {
        val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (removed) {
            exportPlans.forgetUnfinishedFile(uri.toString())
        } else {
            val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?:
                "session.trackersession"
            exportPlans.rememberUnfinishedFile(UnfinishedExportFile(uri.toString(), name))
        }
    }

    private suspend fun childPlans(
        request: SessionBundleExportRequest,
        snapshot: SessionExportSnapshot,
    ): List<SessionChildPlan> {
        val exact = snapshot.organization.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
        val locationRequest = SessionLocationFileRequest(request.sessionId, request.expectedOrganizationRevision,
            request.expectedCollectedDataEpoch)
        val queryEnd = if (snapshot.toMs == snapshot.fromMs && snapshot.toMs < Long.MAX_VALUE) {
            snapshot.toMs + 1L
        } else {
            snapshot.toMs
        }
        val range = snapshot.fromMs..(if (snapshot.toMs == snapshot.fromMs) {
            snapshot.toMs
        } else {
            snapshot.toMs - 1L
        })
        val plans = mutableListOf<SessionChildPlan>()
        fun add(source: HistorySource, name: String, exporter: Exporter,
            write: suspend (OutputStream) -> ExportResult) {
            if (source in request.sources) {
                plans += SessionChildPlan(source, name, exporter.extension, false, write)
            }
        }
        add(HistorySource.LOCATION, "location", LocationFileExporter()) { output ->
            LocationFileExporter().exportSession(context, locationRequest, output, database,
                maximumBytes = SessionBundleManifestCodecV1.MAX_CHILD_BYTES, verifyCollectedDataAccess = {}) }
        addCapturedPlans(request, snapshot, exact, queryEnd, range, plans)
        if (HistorySource.STEPS in request.sources) {
            addBackgroundStepsPlans(plans, request, exact, snapshot, queryEnd)
        }
        add(HistorySource.WIFI, "wifi", PortableWifiSourceExporter()) { output ->
            PortableWifiSourceExporter().exportSession(context, locationRequest, output) }
        add(HistorySource.CELL, "cell", PortableCellSourceExporter()) { output ->
            PortableCellSourceExporter().exportSession(context, locationRequest, output) }
        return plans
    }

    private suspend fun addCapturedPlans(
        request: SessionBundleExportRequest, snapshot: SessionExportSnapshot,
        exact: List<OrganizationSourceSelector>?, queryEnd: Long, range: LongRange,
        plans: MutableList<SessionChildPlan>,
    ) {
        for (source in listOf(HistorySource.STEPS, HistorySource.ACTIVITY, HistorySource.PRESSURE)) {
            if (source !in request.sources) {
                continue
            }
            val selected = selectedCapturedEntries(source, exact, snapshot, queryEnd)
            val observations = exact.orEmpty().filterIsInstance<OrganizationSourceSelector.SensorObservations>()
                .filter { it.source == source }.flatMap { it.identities }.distinct()
            if (observations.isNotEmpty()) {
                plans += sensorObservationPlan(source, selected.orEmpty(), observations, request, range)
                continue
            }
            if (selected == null || selected.isNotEmpty()) {
                capturedChildPlan(source, selected, request, range)?.let { plans += it }
            }
        }
    }

    private fun sensorObservationPlan(source: HistorySource,
        captured: List<OrganizationSourceSelector.CapturedEntry>, observations: List<String>,
        request: SessionBundleExportRequest, range: LongRange): SessionChildPlan {
        val name = source.name.lowercase()
        val extension = if (source == HistorySource.ACTIVITY) { "trackeractivity" } else { "trackerpressure" }
        return SessionChildPlan(source, name, extension, optional = false) { output ->
            FileBackedExportSpool(context, "sensor-sessions-", AllDaySensorSourceFile.MAX_FILE_BYTES).use { sessions ->
                val result = sessions.write { staged ->
                    if (captured.isEmpty()) {
                        ExportResult.Success(0)
                    } else {
                        requireNotNull(capturedChildPlan(source, captured, request, range)).write(staged)
                    }
                }
                if (result !is ExportResult.Success) {
                    return@use result
                }
                val count = sessions.backingFile.takeIf { sessions.byteCount > 0 }?.inputStream().use { input ->
                    AllDaySensorSourceFile.write(output, source.name, input) { emit ->
                        AllDaySensorPortableRows.forEachSelected(database, source.name, observations, range,
                            request.expectedCollectedDataEpoch, emit)
                    }
                }
                ExportResult.Success(count + (result.recordCount ?: 0))
            }
        }
    }

    private suspend fun selectedCapturedEntries(
        source: HistorySource,
        exact: List<OrganizationSourceSelector>?,
        snapshot: SessionExportSnapshot,
        queryEnd: Long,
    ): List<OrganizationSourceSelector.CapturedEntry>? =
        exact?.filterIsInstance<OrganizationSourceSelector.CapturedEntry>()?.filter { it.source == source }
            ?.filter { selected ->
                val bounds = when (source) {
                    HistorySource.STEPS -> database.importedStepsDao().entry(selected.entryIdentity)
                        ?.let { it.startTimeMs to it.endTimeMs }
                    HistorySource.ACTIVITY ->
                        database.importedActivityDao().entryRevisionsForHistory(listOf(selected.entryIdentity))
                            .maxByOrNull { it.importRevision }?.let { it.startTimeMs to it.endTimeMs }
                    HistorySource.PRESSURE ->
                        database.importedPressureDao().selectedHistoryCandidates(listOf(selected.entryIdentity))
                            .singleOrNull()?.let { it.startTimeMs to it.endTimeMs }
                    else -> null
                }
                bounds?.let { (from, to) -> SourceExportDates(snapshot.fromMs, queryEnd).overlaps(from, to) } == true
            }

    private fun capturedChildPlan(
        source: HistorySource,
        selected: List<OrganizationSourceSelector.CapturedEntry>?,
        request: SessionBundleExportRequest,
        range: LongRange,
    ): SessionChildPlan? {
        val writer: Exporter = when (source) {
            HistorySource.STEPS -> PortableStepsExporter()
            HistorySource.ACTIVITY -> PortableActivityExporter()
            else -> PortablePressureExporter()
        }
        val name = source.name.lowercase()
        val optional = selected == null && source == HistorySource.STEPS
        return if (source in request.sources) {
            SessionChildPlan(source, name, writer.extension, optional) { output ->
                if (selected == null) {
                    writer.export(context, emptySequence(), output, range)
                } else {
                    when (source) {
                        HistorySource.STEPS -> (writer as PortableStepsExporter).exportSession(context,
                            ExportSelectedPortableStepsRequest(emptyList(),
                                selected.map { PortableStepsOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                        HistorySource.ACTIVITY -> (writer as PortableActivityExporter).exportSession(context,
                            ExportSelectedPortableCapturedActivityRequest(emptyList(),
                                selected.map { PortableActivityOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                        else -> (writer as PortablePressureExporter).exportSession(context,
                            ExportSelectedPortablePressureRequest(emptyList(),
                                selected.map { PortablePressureOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                    }
                }
            }
        } else {
            null
        }
    }

    private suspend fun addBackgroundStepsPlans(
        plans: MutableList<SessionChildPlan>,
        request: SessionBundleExportRequest,
        exact: List<OrganizationSourceSelector>?,
        snapshot: SessionExportSnapshot,
        queryEnd: Long,
    ) {
        val days = selectedBackgroundStepsDays(database, exact, snapshot, queryEnd)
        if (days == null) {
            for (origin in AmbientStepsPortableOrigin.entries) {
                val writer = PortableAmbientStepsExporter(origin)
                val name = "steps-${origin.name.lowercase()}"
                if (HistorySource.STEPS in request.sources) {
                    plans += SessionChildPlan(HistorySource.STEPS, name, writer.extension, optional = true) { output ->
                        writer.exportSession(context,
                            ExportPortableAmbientStepsRequest(snapshot.fromMs, queryEnd), output)
                    }
                }
            }
        } else if (days.isNotEmpty()) {
            val writer = PortableAmbientStepsExporter(AmbientStepsPortableOrigin.IMPORTED)
            if (HistorySource.STEPS in request.sources) {
                plans += SessionChildPlan(HistorySource.STEPS, "steps-imported", writer.extension,
                    optional = false) { output ->
                    writer.exportSession(context,
                        ExportPortableAmbientStepsRequest(snapshot.fromMs, queryEnd), output, days)
                }
            }
        }
    }

}

private suspend fun selectedBackgroundStepsDays(
    database: AppDatabase, exact: List<OrganizationSourceSelector>?,
    snapshot: SessionExportSnapshot,
    queryEnd: Long,
): Set<String>? = exact?.filterIsInstance<OrganizationSourceSelector.BackgroundStepsDay>()
    ?.map { it.dayIdentity }
    ?.toSet()
    ?.let { ids ->
        if (ids.isEmpty()) {
            ids
        } else {
            database.importedAmbientStepsDao().latestDaysForIdentities(ids.toList(), ids.size + 1)
                .filter { SourceExportDates(snapshot.fromMs, queryEnd)
                    .overlaps(it.structuralDayStartTimeMs, it.structuralDayEndTimeMs) }
                .mapTo(linkedSetOf()) { it.dayIdentity }
        }
    }


internal data class SessionExportSnapshot(val organization: OrganizationSessionRow, val epoch: Long,
    val retainedFromMs: Long?,
    val fromMs: Long, val toMs: Long, val zoneId: String, val activityLabel: String?)
private data class SessionChildPlan(val source: HistorySource, val name: String, val extension: String,
    val optional: Boolean, val write: suspend (OutputStream) -> ExportResult)
private data class StagedSessionChild(val plan: SessionChildPlan, val spool: FileBackedExportSpool,
    val metadata: SessionBundleChildV1)
private suspend fun Context.writeSessionBundleDestination(
    uri: Uri, write: suspend (OutputStream) -> Unit,
) {
    contentResolver.openOutputStream(uri, "w")?.use { output -> write(output) }
        ?: throw SessionBundleExportException(SessionBundleExportFailure.STORAGE)
}

private class SessionBundleExportException(val reason: SessionBundleExportFailure) : IllegalStateException(reason.name)
private fun ExportResult.Error.isEmptySource() = message?.stringRes in setOf(R.string.export_error_no_portable_steps,
    R.string.export_error_no_portable_ambient_steps)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private suspend fun java.io.File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) { currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) {
            break
        }
            if (read > 0) {
                digest.update(buffer, 0, read)
            } }
    }
    return digest.digest().hex()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SessionBundleExportModule {
    @Binds abstract fun bindSessionBundleExporter(
        implementation: ImportExportSessionBundleExporter
    ): SessionBundleExporter
    @Binds abstract fun bindSourceHistoryFileExporter(implementation: ImportExportSourceHistoryFileExporter):
        SourceHistoryFileExporter
}
