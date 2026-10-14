package com.adsamcik.tracker.impexp.exporter

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
    override suspend fun export(destination: Uri, request: SessionBundleExportRequest): SessionBundleExportResult {
        if (startup.reconcile() !is TrackingStartupResult.Ready)
            return SessionBundleExportResult.Failure(SessionBundleExportFailure.UNAVAILABLE)
        val generation = startup.currentGeneration
        return startup.withReadyGenerationTransfer(generation) {
            withContext(dispatchers.io) {
                var success = false
                try {
                    val result = writeBundle(request, {
                        if (startup.withRetainedDataGeneration(generation) { true } != true)
                            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
                    }) { write ->
                        context.contentResolver.openOutputStream(destination, "w")?.use { output -> write(output) }
                            ?: throw SessionBundleExportException(SessionBundleExportFailure.STORAGE)
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
                    if (!success) removeUnfinished(destination)
                }
            }
        } ?: SessionBundleExportResult.Failure(SessionBundleExportFailure.UNAVAILABLE)
    }

    /** Fully prepares the file before opening a slow external destination. */
    internal suspend fun writeBundle(request: SessionBundleExportRequest, verifyAccess: () -> Unit,
        destination: suspend (suspend (OutputStream) -> Unit) -> Unit): SessionBundleExportResult {
        val snapshot = snapshot(request)
        val spools = mutableListOf<StagedSessionChild>()
        try {
            for (plan in childPlans(request, snapshot)) {
                currentCoroutineContext().ensureActive(); verifyAccess(); verify(request, snapshot)
                val spool = FileBackedExportSpool(context, "session-child-", SessionBundleManifestCodecV1.MAX_CHILD_BYTES)
                try {
                    val result = spool.write { plan.write(it) }
                    if (result is ExportResult.Error) {
                        if (plan.optional && result.isEmptySource()) { spool.close(); continue }
                        throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
                    }
                    val path = "sources/${spools.size}/${plan.name}.${plan.extension}"
                    val metadata = describeSessionChild(plan.source, spool.backingFile, plan.name)
                    spools += StagedSessionChild(plan, spool, SessionBundleChildV1(path, metadata.formatId,
                        metadata.version, spool.byteCount, spool.backingFile.sha256(), metadata.fromMs,
                        metadata.toMs, metadata.fromMs < snapshot.fromMs || metadata.toMs > snapshot.toMs))
                } catch (failure: Throwable) {
                    try { spool.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
            if (spools.isEmpty()) throw SessionBundleExportException(SessionBundleExportFailure.EMPTY)
            if (spools.map { it.plan.source }.toSet() != request.sources)
                throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
            val manifest = SessionBundleManifestV1(snapshot.organization.name, snapshot.activityLabel,
                snapshot.fromMs, snapshot.toMs, snapshot.zoneId, request.sources.map { it.name.lowercase() }.sorted(),
                spools.map { it.metadata }, emptyList(), selectedPurposes = listOf("session", "background"),
                selectedOrigins = listOf("local", "imported"))
            SessionBundleArchiveWriter().withArchive(context, manifest,
                spools.associate { child -> child.metadata.path to child.spool.backingFile }) { archive ->
                verifyChildren(request, snapshot, spools, verifyAccess)
                destination { output ->
                    verifyAccess(); verify(request, snapshot)
                    archive.copyTo(output)
                    verifyChildren(request, snapshot, spools, verifyAccess)
                }
            }
            return SessionBundleExportResult.Success(spools.any { it.metadata.wholeEntries })
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                var cleanupFailure: Exception? = null
                spools.asReversed().forEach { child ->
                    try { child.spool.close() } catch (failure: Exception) {
                        if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                    }
                }
                cleanupFailure?.let { throw it }
            }
        }
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
            if (result !is ExportResult.Success || digest.digest().hex() != child.metadata.sha256)
                throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        }
        verifyAccess(); verify(request, snapshot)
    }

    private suspend fun snapshot(request: SessionBundleExportRequest): SessionExportSnapshot = database.withTransaction {
        val state = database.sourceEvidenceStateDao().get()
            ?: throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
            database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null)
            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        val row = database.organizationAnnotationDao().visible(null, null, 1,
            selectedLocalId = (request.sessionId as? OrganizationSessionId.Annotation)?.localId,
            selectedAdapterKey = (request.sessionId as? OrganizationSessionId.Recording)?.adapterKey).singleOrNull()
            ?: throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        if (row.revision != request.expectedOrganizationRevision)
            throw SessionBundleExportException(SessionBundleExportFailure.STALE)
        val from = row.fromMs
        val to = row.toMs
        if (row.isActive || from == null || to == null || from < 0L || to < from)
            throw SessionBundleExportException(SessionBundleExportFailure.UNAVAILABLE)
        val activity = row.activityLabelId?.let { id -> NativeSessionActivity.entries.firstOrNull { it.id == id }
            ?.let { context.getString(it.nameRes) } ?: database.activityDao().getByIds(listOf(id)).singleOrNull()?.name }
            ?: row.importedActivityLabel
        SessionExportSnapshot(row, state.collectedDataEpoch, state.retainedFromMs, from, to,
            row.displayZoneId ?: java.time.ZoneId.systemDefault().id, activity)
    }

    private suspend fun verify(request: SessionBundleExportRequest, expected: SessionExportSnapshot) {
        if (snapshot(request) != expected) throw SessionBundleExportException(SessionBundleExportFailure.STALE)
    }

    private suspend fun removeUnfinished(uri: Uri) = withContext(NonCancellable + dispatchers.io) {
        val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (removed) exportPlans.forgetUnfinishedFile(uri.toString()) else {
            val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: "session.trackersession"
            exportPlans.rememberUnfinishedFile(UnfinishedExportFile(uri.toString(), name))
        }
    }

    private suspend fun childPlans(request: SessionBundleExportRequest, snapshot: SessionExportSnapshot): List<SessionChildPlan> {
        val exact = snapshot.organization.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
        val locationRequest = SessionLocationFileRequest(request.sessionId, request.expectedOrganizationRevision,
            request.expectedCollectedDataEpoch)
        val queryEnd = if (snapshot.toMs == snapshot.fromMs && snapshot.toMs < Long.MAX_VALUE) snapshot.toMs + 1L else snapshot.toMs
        val range = snapshot.fromMs..(if (snapshot.toMs == snapshot.fromMs) snapshot.toMs else snapshot.toMs - 1L)
        val plans = mutableListOf<SessionChildPlan>()
        fun add(source: HistorySource, name: String, exporter: Exporter, optional: Boolean = false,
            write: suspend (OutputStream) -> ExportResult) {
            if (source in request.sources) plans += SessionChildPlan(source, name, exporter.extension, optional, write)
        }
        add(HistorySource.LOCATION, "location", LocationFileExporter()) { output ->
            LocationFileExporter().exportSession(context, locationRequest, output, database, {}) }
        for (source in listOf(HistorySource.STEPS, HistorySource.ACTIVITY, HistorySource.PRESSURE)) {
            if (source !in request.sources) continue
            val selected = exact?.filterIsInstance<OrganizationSourceSelector.CapturedEntry>()?.filter { it.source == source }
                ?.filter { selected ->
                    val bounds = when (source) {
                        HistorySource.STEPS -> database.importedStepsDao().entry(selected.entryIdentity)?.let { it.startTimeMs to it.endTimeMs }
                        HistorySource.ACTIVITY -> database.importedActivityDao().entryRevisionsForHistory(listOf(selected.entryIdentity))
                            .maxByOrNull { it.importRevision }?.let { it.startTimeMs to it.endTimeMs }
                        HistorySource.PRESSURE -> database.importedPressureDao().selectedHistoryCandidates(listOf(selected.entryIdentity))
                            .singleOrNull()?.let { it.startTimeMs to it.endTimeMs }
                        else -> null
                    }
                    bounds?.let { (from, to) -> SourceExportDates(snapshot.fromMs, queryEnd).overlaps(from, to) } == true
                }
            if (selected == null || selected.isNotEmpty()) {
                val writer: Exporter = when (source) {
                    HistorySource.STEPS -> PortableStepsExporter()
                    HistorySource.ACTIVITY -> PortableActivityExporter()
                    else -> PortablePressureExporter()
                }
                add(source, source.name.lowercase(), writer, optional = selected == null && source == HistorySource.STEPS) { output ->
                    if (selected == null) writer.export(context, emptySequence(), output, range)
                    else when (source) {
                        HistorySource.STEPS -> (writer as PortableStepsExporter).exportSession(context,
                            ExportSelectedPortableStepsRequest(emptyList(), selected.map { PortableStepsOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                        HistorySource.ACTIVITY -> (writer as PortableActivityExporter).exportSession(context,
                            ExportSelectedPortableCapturedActivityRequest(emptyList(), selected.map { PortableActivityOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                        else -> (writer as PortablePressureExporter).exportSession(context,
                            ExportSelectedPortablePressureRequest(emptyList(), selected.map { PortablePressureOpaqueIdentity(it.entryIdentity) },
                                request.expectedCollectedDataEpoch), output)
                    }
                }
            }
        }
        if (HistorySource.STEPS in request.sources) {
            val days = exact?.filterIsInstance<OrganizationSourceSelector.BackgroundStepsDay>()?.map { it.dayIdentity }?.toSet()
                ?.let { ids -> if (ids.isEmpty()) ids else database.importedAmbientStepsDao().latestDaysForIdentities(ids.toList(), ids.size + 1)
                    .filter { SourceExportDates(snapshot.fromMs, queryEnd).overlaps(it.structuralDayStartTimeMs, it.structuralDayEndTimeMs) }
                    .mapTo(linkedSetOf()) { it.dayIdentity } }
            if (days == null) for (origin in AmbientStepsPortableOrigin.entries) {
                val writer = PortableAmbientStepsExporter(origin)
                add(HistorySource.STEPS, "steps-${origin.name.lowercase()}", writer, optional = true) { output ->
                    writer.exportSession(context, ExportPortableAmbientStepsRequest(snapshot.fromMs, queryEnd), output) }
            } else if (days.isNotEmpty()) {
                val writer = PortableAmbientStepsExporter(AmbientStepsPortableOrigin.IMPORTED)
                add(HistorySource.STEPS, "steps-imported", writer) { output -> writer.exportSession(context,
                    ExportPortableAmbientStepsRequest(snapshot.fromMs, queryEnd), output, days) }
            }
        }
        add(HistorySource.WIFI, "wifi", PortableWifiSourceExporter()) { output ->
            PortableWifiSourceExporter().exportSession(context, locationRequest, output) }
        add(HistorySource.CELL, "cell", PortableCellSourceExporter()) { output ->
            PortableCellSourceExporter().exportSession(context, locationRequest, output) }
        return plans
    }
}

internal data class SessionExportSnapshot(val organization: OrganizationSessionRow, val epoch: Long, val retainedFromMs: Long?,
    val fromMs: Long, val toMs: Long, val zoneId: String, val activityLabel: String?)
private data class SessionChildPlan(val source: HistorySource, val name: String, val extension: String,
    val optional: Boolean, val write: suspend (OutputStream) -> ExportResult)
private data class StagedSessionChild(val plan: SessionChildPlan, val spool: FileBackedExportSpool, val metadata: SessionBundleChildV1)
private class SessionBundleExportException(val reason: SessionBundleExportFailure) : IllegalStateException(reason.name)
private fun ExportResult.Error.isEmptySource() = message?.stringRes in setOf(R.string.export_error_no_portable_steps,
    R.string.export_error_no_portable_ambient_steps)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private suspend fun java.io.File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) { currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read) }
    }
    return digest.digest().hex()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SessionBundleExportModule {
    @Binds abstract fun bindSessionBundleExporter(implementation: ImportExportSessionBundleExporter): SessionBundleExporter
    @Binds abstract fun bindSourceHistoryFileExporter(implementation: ImportExportSourceHistoryFileExporter):
        com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExporter
}
