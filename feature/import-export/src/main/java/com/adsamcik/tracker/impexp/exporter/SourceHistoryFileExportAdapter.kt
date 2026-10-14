package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExporter
import com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExportResult
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.automation.UnfinishedExportFile
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses existing exact typed writers and the shared retained-data transfer, with one private file. */
internal class ImportExportSourceHistoryFileExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dispatchers: DispatchersProvider,
    private val startup: TrackingStartupGate,
    private val plans: ExportPlanStore,
) : SourceHistoryFileExporter {
    override suspend fun export(destination: Uri, selection: SourceHistoryDetailSelection): SourceHistoryFileExportResult {
        if (startup.reconcile() !is TrackingStartupResult.Ready) return SourceHistoryFileExportResult.UNAVAILABLE
        val generation = startup.currentGeneration
        return startup.withReadyGenerationTransfer(generation) {
            withContext(dispatchers.io) {
                var saved = false
                try {
                    writeFile(selection, {
                        check(startup.withRetainedDataGeneration(generation) { true } == true)
                    }) { write ->
                        context.contentResolver.openOutputStream(destination, "w")?.use { write(it) }
                            ?: throw java.io.IOException("Could not open export destination")
                    }
                    saved = true
                    SourceHistoryFileExportResult.SAVED
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalStateException) { SourceHistoryFileExportResult.STALE }
                catch (_: Exception) { SourceHistoryFileExportResult.STORAGE }
                finally { if (!saved) removeUnfinished(destination) }
            }
        } ?: SourceHistoryFileExportResult.UNAVAILABLE
    }

    internal suspend fun writeFile(selection: SourceHistoryDetailSelection, verifyAccess: () -> Unit,
        destination: suspend (suspend (OutputStream) -> Unit) -> Unit) {
        val epoch = requireNotNull(selection.readSnapshot).collectedDataEpoch
        val target = selection.actionTarget
        suspend fun verify() {
            currentCoroutineContext().ensureActive(); verifyAccess()
            database.withTransaction {
                check(database.sourceEvidenceStateDao().get()?.collectedDataEpoch == epoch)
                check(database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() == null)
                if (target is TrackingHistoryActionTarget.ImportedPressure) {
                    check(database.importedPressureDao().selectedHistoryCandidates(listOf(target.identity.value))
                        .singleOrNull()?.importRevision == target.importRevision)
                }
                val activity = (target as? TrackingHistoryActionTarget.Activity)?.selection as? ActivityHistorySelection.Imported
                if (activity != null) {
                    val selected = activity.selected
                    val header = database.importedActivityDao().entryRevisionsForHistory(listOf(selected.identity.value))
                        .maxByOrNull { it.importRevision }
                    check(header?.importRevision == selected.importRevision && header.contentChecksum == selected.contentChecksum.value)
                }
            }
        }
        verify()
        val write = writer(target, epoch)
        FileBackedExportSpool(context, "source-selection-", 64L * 1024L * 1024L).use { file ->
            val initial = MessageDigest.getInstance("SHA-256")
            check(file.write { output -> write(DigestOutputStream(output, initial)) }.isSuccess)
            val expected = initial.digest()
            suspend fun recheck() {
                verify()
                val current = MessageDigest.getInstance("SHA-256")
                val discard = object : OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                }
                check(write(DigestOutputStream(discard, current)).isSuccess && expected.contentEquals(current.digest()))
                verify()
            }
            recheck()
            destination { output -> verify(); file.copyTo(output); recheck() }
        }
    }

    private suspend fun writer(target: TrackingHistoryActionTarget, epoch: Long): suspend (OutputStream) -> ExportResult = when (target) {
        is TrackingHistoryActionTarget.CapturedSource -> if (target.source == HistorySource.STEPS) {
            { output -> PortableStepsExporter().exportSession(context, ExportSelectedPortableStepsRequest(
                target.segmentIds, emptyList(), epoch), output) }
        } else {
            { output -> PortablePressureExporter().exportSession(context, ExportSelectedPortablePressureRequest(
                target.segmentIds, emptyList(), epoch), output) }
        }
        is TrackingHistoryActionTarget.ImportedPressure -> {
            { output -> PortablePressureExporter().exportSession(context, ExportSelectedPortablePressureRequest(
                emptyList(), listOf(PortablePressureOpaqueIdentity(target.identity.value)), epoch), output) }
        }
        is TrackingHistoryActionTarget.Activity -> {
            val selected = when (val value = target.selection) {
                is ActivityHistorySelection.Local -> ExportSelectedPortableCapturedActivityRequest(
                    listOf(resolveActivitySegment(value.key)), emptyList(), epoch)
                is ActivityHistorySelection.Imported -> ExportSelectedPortableCapturedActivityRequest(
                    emptyList(), listOf(PortableActivityOpaqueIdentity(value.selected.identity.value)), epoch)
            }
            val action: suspend (OutputStream) -> ExportResult = { output -> PortableActivityExporter().exportSession(context, selected, output) }
            action
        }
        is TrackingHistoryActionTarget.Wifi -> { output -> PortableWifiSourceExporter().writeSelection(context, target.selection, output) }
        is TrackingHistoryActionTarget.Cell -> { output -> PortableCellSourceExporter().writeSelection(context, target.selection, output) }
        else -> error("This source selection cannot be exported")
    }

    private suspend fun resolveActivitySegment(key: ActivityHistoryEntryKey): Long = database.withTransaction {
        var beforeTime: Long? = null
        var beforeId: Long? = null
        var visited = 0
        while (visited < ActivityCapturedPortableFormatV1.MAX_ENTRIES) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(100, ActivityCapturedPortableFormatV1.MAX_ENTRIES - visited)
            val page = database.activityCapturedFactDao().logicalHistoryCandidatePage(limit, beforeTime, beforeId,
                SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            page.firstOrNull { it.segment.logicalTrackingId?.let { id -> ActivityHistoryEntryKey("activity-logical:$id") == key } == true }
                ?.let { return@withTransaction it.segment.id }
            if (page.size < limit) break
            visited += page.size
            beforeTime = page.last().logicalRecencyStartMs; beforeId = page.last().logicalRecencySegmentId
        }
        error("Selected Activity recording is unavailable")
    }

    private suspend fun removeUnfinished(uri: Uri) = withContext(NonCancellable + dispatchers.io) {
        if (runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false))
            plans.forgetUnfinishedFile(uri.toString())
        else plans.rememberUnfinishedFile(UnfinishedExportFile(uri.toString(),
            runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: "recording"))
    }
}
