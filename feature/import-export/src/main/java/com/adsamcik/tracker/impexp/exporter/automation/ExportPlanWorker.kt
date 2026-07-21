package com.adsamcik.tracker.impexp.exporter.automation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.impexp.exporter.EXPORT_LOG_SOURCE
import com.adsamcik.tracker.impexp.exporter.CursorAwareExporter
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.pagedLocationSequence
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.time.Clock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker responsible for executing a specific export backup plan.
 * Resolves format → exporter, scope → date range, executes, and logs to export_log.
 */
@HiltWorker
class ExportPlanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val planStore: ExportPlanStore,
    private val appDatabase: AppDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) {
            Reporter.w(EXPORT_LOG_SOURCE, "ExportPlanWorker missing plan id; skipping")
            return Result.failure()
        }

        val plan = planStore.getPlan(ExportPlanId(planId))
        if (plan == null) {
            Reporter.i(EXPORT_LOG_SOURCE, "Plan $planId no longer exists; nothing to export")
            return Result.success()
        }

        if (!plan.enabled) {
            Reporter.i(EXPORT_LOG_SOURCE, "Plan '${plan.name}' is disabled; skipping")
            return Result.success()
        }

        val startedAt = clock.currentTimeMillis()
        val trigger = inputData.getString(KEY_TRIGGER_REASON) ?: "unknown"
        Reporter.i(EXPORT_LOG_SOURCE, "Executing plan '${plan.name}' (trigger=$trigger)")

        return try {
            val exportResult = executePlan(plan)
            val completedAt = clock.currentTimeMillis()
            logExport(plan, exportResult, startedAt, completedAt)

            when (exportResult) {
                is PlanExportResult.Success -> {
                    Reporter.i(EXPORT_LOG_SOURCE, "Plan '${plan.name}' completed: ${exportResult.fileName} (${exportResult.recordCount} records)")
                    // Update watermark only on successful export with actual records
                    if (exportResult.shouldAdvanceWatermark && exportResult.recordCount > 0 && exportResult.maxTimeMs > 0L) {
                        planStore.updateWatermark(
                            planId = plan.id,
                            watermarkMs = exportResult.maxTimeMs,
                            watermarkId = exportResult.maxId,
                            completedAt = completedAt,
                            recordCount = exportResult.recordCount,
                        )
                    }
                    if (exportResult.fileName.isNotEmpty()) {
                        showExportCompletedNotification(plan, exportResult)
                    }
                    Result.success()
                }
                is PlanExportResult.Failed -> {
                    // Do NOT update watermark on failure
                    Reporter.w(EXPORT_LOG_SOURCE, "Plan '${plan.name}' failed: ${exportResult.error}")
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.w(EXPORT_LOG_SOURCE, "Plan '${plan.name}' threw exception: ${e.message}")
            logExport(plan, PlanExportResult.Failed(e.message ?: "Unknown error"), startedAt, clock.currentTimeMillis())
            Result.retry()
        }
    }

    private suspend fun executePlan(plan: ExportBackupPlan): PlanExportResult = withContext(ioDispatcher) {
        val exporter = resolveExporter(plan.format)

        // Resolve date range from scope
        val scopeDateRange = resolveDateRange(plan.scope)

        // Apply incremental watermark lower bound when applicable
        val exportRange = resolveExportRange(plan, exporter, scopeDateRange)
        if (exportRange.skipBecauseEmptyIncremental) {
            return@withContext PlanExportResult.Success(
                fileName = "",
                fileSizeBytes = 0L,
                recordCount = 0,
                maxTimeMs = 0L,
                maxId = 0L,
                isDelta = true,
                shouldAdvanceWatermark = false,
            )
        }
        val dateRange = exportRange.dateRange

        val fileName = buildFileName(plan, exporter.extension, exportRange.isDelta)
        val output = when (val resolved = resolveExportOutput(plan, exporter, fileName)) {
            is ExportOutputResolution.Failed -> {
                return@withContext PlanExportResult.Failed(resolved.error)
            }
            is ExportOutputResolution.Success -> resolved.output
        }

        // Build lazy paging sequence from DB (same pattern as ImportExportComposeActivity)
        val locationSampleDao = appDatabase.locationSampleDao()
        val fromMs = dateRange?.first ?: 0L
        val toMs = dateRange?.last ?: Long.MAX_VALUE
        var recordCount = 0
        var maxTimeMs = 0L
        var maxId = 0L
        val locationSequence = pagedLocationSequence(
            locationSampleDao = locationSampleDao,
            fromMs = fromMs,
            toMs = toMs,
            pageSize = PAGE_SIZE,
            initialAfterTimeMs = exportRange.afterTimeMs,
            initialAfterId = exportRange.afterId,
        )
            .filter { it.latE7 != null && it.lonE7 != null }
            .onEach {
                recordCount++
                if (it.timeMs > maxTimeMs || (it.timeMs == maxTimeMs && it.id > maxId)) {
                    maxTimeMs = it.timeMs
                    maxId = it.id
                }
            }

        val result = try {
            val outputStream = output.openOutputStream()
            if (outputStream == null) {
                output.deletePartial()
                return@withContext PlanExportResult.Failed("Unable to open export destination")
            }
            outputStream.use { stream ->
                if (exporter is CursorAwareExporter) {
                    exporter.exportAfter(
                        applicationContext,
                        locationSequence,
                        stream,
                        dateRange,
                        exportRange.afterTimeMs,
                        exportRange.afterId,
                    )
                } else {
                    exporter.export(applicationContext, locationSequence, stream, dateRange)
                }
            }
        } catch (e: CancellationException) {
            output.deletePartial()
            throw e
        } catch (e: Exception) {
            output.deletePartial()
            throw e
        }

        when (result) {
            is ExportResult.Success -> {
                val progress = resolveExportProgress(result, recordCount, maxTimeMs, maxId)
                PlanExportResult.Success(
                    fileName = output.fileName,
                    fileSizeBytes = output.length(),
                    recordCount = progress.recordCount,
                    maxTimeMs = progress.maxTimeMs,
                    maxId = progress.maxId,
                    isDelta = exportRange.isDelta,
                    shouldAdvanceWatermark = exportRange.shouldAdvanceWatermark,
                )
            }
            is ExportResult.Error -> {
                val errorMessage = result.message?.localize(applicationContext)
                    ?: "Export returned error for ${plan.name}"
                Reporter.w(EXPORT_LOG_SOURCE, "Export failed for ${output.fileName}: $errorMessage")
                output.deletePartial()
                PlanExportResult.Failed(errorMessage)
            }
        }
    }

    private fun resolveExporter(format: ExportFormat): Exporter =
        FormatRegistry.exporterFor(format.formatId)
            ?: error("No exporter registered for format ${format.name}")

    internal data class ResolvedExportRange(
        val dateRange: LongRange?,
        val isDelta: Boolean,
        val shouldAdvanceWatermark: Boolean,
        val skipBecauseEmptyIncremental: Boolean,
        val afterTimeMs: Long? = null,
        val afterId: Long? = null,
    )

    internal fun resolveExportRange(
        plan: ExportBackupPlan,
        exporter: Exporter,
        scopeDateRange: LongRange?,
    ): ResolvedExportRange {
        val supportsIncremental = plan.incrementalEnabled &&
            exporter.canSelectDateRange &&
            plan.format != ExportFormat.DATABASE
        if (!supportsIncremental) {
            return ResolvedExportRange(
                dateRange = scopeDateRange,
                isDelta = false,
                shouldAdvanceWatermark = false,
                skipBecauseEmptyIncremental = false,
            )
        }

        if (plan.lastWatermarkMs <= 0L) {
            return ResolvedExportRange(
                dateRange = scopeDateRange,
                isDelta = false,
                shouldAdvanceWatermark = true,
                skipBecauseEmptyIncremental = false,
            )
        }

        val scopeStart = scopeDateRange?.first ?: 0L
        val scopeEnd = scopeDateRange?.last ?: Long.MAX_VALUE
        val effectiveStart = maxOf(plan.lastWatermarkMs, scopeStart)
        if (effectiveStart > scopeEnd) {
            return ResolvedExportRange(
                dateRange = null,
                isDelta = true,
                shouldAdvanceWatermark = false,
                skipBecauseEmptyIncremental = true,
            )
        }

        return ResolvedExportRange(
            dateRange = effectiveStart..scopeEnd,
            isDelta = true,
            shouldAdvanceWatermark = true,
            skipBecauseEmptyIncremental = false,
            afterTimeMs = plan.lastWatermarkMs.takeIf { scopeStart <= it },
            afterId = plan.lastWatermarkId.takeIf { scopeStart <= plan.lastWatermarkMs },
        )
    }

    internal data class ExportProgress(
        val recordCount: Int,
        val maxTimeMs: Long,
        val maxId: Long,
    )

    internal fun resolveExportProgress(
        result: ExportResult.Success,
        fallbackRecordCount: Int,
        fallbackMaxTimeMs: Long,
        fallbackMaxId: Long,
    ): ExportProgress = ExportProgress(
        recordCount = result.recordCount ?: fallbackRecordCount,
        maxTimeMs = result.maxTimeMs ?: fallbackMaxTimeMs,
        maxId = result.maxId ?: fallbackMaxId,
    )

    private suspend fun resolveDateRange(scope: ExportScope): LongRange? {
        return when (scope) {
            ExportScope.LastSession -> {
                val trip = appDatabase.tripDao().getRecentTrips(1).firstOrNull()
                if (trip != null) {
                    trip.startTimeMs..trip.endTimeMs
                } else {
                    null
                }
            }
            is ExportScope.RollingWindow -> {
                val now = clock.currentTimeMillis()
                val windowMs = scope.count.toLong() * when (scope.unit) {
                    ExportScope.WindowUnit.DAY -> 86_400_000L
                    ExportScope.WindowUnit.WEEK -> 604_800_000L
                    ExportScope.WindowUnit.MONTH -> 2_592_000_000L
                    ExportScope.WindowUnit.YEAR -> 31_536_000_000L
                }
                (now - windowMs)..now
            }
            is ExportScope.FixedWindow -> scope.startEpochMillis..scope.endEpochMillis
            ExportScope.EntireHistory -> null
        }
    }

    private fun resolveExportOutput(
        plan: ExportBackupPlan,
        exporter: Exporter,
        fileName: String,
    ): ExportOutputResolution {
        return when (val dest = plan.destination) {
            is ExportDestination.PrivateStorage -> {
                val exportDir = File(applicationContext.filesDir, dest.relativeDirectory)
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    return ExportOutputResolution.Failed("Unable to create private export directory")
                }
                ExportOutputResolution.Success(FileExportOutput(File(exportDir, fileName)))
            }
            is ExportDestination.DocumentTree -> {
                if (!hasPersistedWritePermission(dest.treeUri)) {
                    return ExportOutputResolution.Failed(
                        "Missing persisted write permission for document tree export destination"
                    )
                }
                val root = DocumentFile.fromTreeUri(applicationContext, dest.treeUri)
                    ?: return ExportOutputResolution.Failed("Unable to open document tree export destination")
                val directory = resolveDocumentDirectory(root, dest.subdirectory)
                    ?: return ExportOutputResolution.Failed("Unable to create document tree export subdirectory")
                val document = directory.createFile(exporter.mimeType, fileName)
                    ?: return ExportOutputResolution.Failed("Unable to create document tree export file")
                ExportOutputResolution.Success(DocumentFileExportOutput(applicationContext, document, fileName))
            }
        }
    }

    internal fun hasPersistedWritePermission(treeUri: Uri): Boolean =
        applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    private fun resolveDocumentDirectory(root: DocumentFile, subdirectory: String?): DocumentFile? {
        var current = root
        val segments = subdirectory
            ?.split('/', '\\')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        for (segment in segments) {
            val existing = current.findFile(segment)?.takeIf { it.isDirectory }
            current = existing ?: current.createDirectory(segment) ?: return null
        }

        return current
    }

    private fun buildFileName(plan: ExportBackupPlan, extension: String, isDelta: Boolean = false): String {
        val prefix = plan.destination.fileNamePrefix?.takeIf { it.isNotBlank() } ?: "tracker-export"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return if (isDelta && plan.lastWatermarkMs > 0L) {
            val watermarkDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(plan.lastWatermarkMs))
            "${prefix}_${timestamp}_delta-from-${watermarkDate}.$extension"
        } else {
            "${prefix}_${timestamp}.$extension"
        }
    }

    private suspend fun logExport(
        plan: ExportBackupPlan,
        result: PlanExportResult,
        startedAt: Long,
        completedAt: Long,
    ) {
        try {
            val entity = when (result) {
                is PlanExportResult.Success -> ExportLogEntity(
                    format = plan.format.name,
                    scope = plan.scope.javaClass.simpleName,
                    fileName = result.fileName,
                    fileSizeBytes = result.fileSizeBytes,
                    recordCount = result.recordCount,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    status = "SUCCESS",
                    createdAt = completedAt,
                )
                is PlanExportResult.Failed -> ExportLogEntity(
                    format = plan.format.name,
                    scope = plan.scope.javaClass.simpleName,
                    fileName = "",
                    fileSizeBytes = 0,
                    recordCount = 0,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    status = "FAILED",
                    errorMessage = result.error,
                    createdAt = completedAt,
                )
            }
            appDatabase.exportLogDao().insert(entity)
        } catch (e: Exception) {
            Reporter.w(EXPORT_LOG_SOURCE, "Failed to log export: ${e.message}")
        }
    }

    private fun showExportCompletedNotification(plan: ExportBackupPlan, result: PlanExportResult.Success) {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply {
                putExtra("navigate_to", "impexp")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            plan.id.value.toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            applicationContext.getString(com.adsamcik.tracker.shared.base.R.string.channel_other_id)
        )
            .setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
            .setContentTitle("Export complete")
            .setContentText(result.fileName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(
            EXPORT_NOTIFICATION_ID_BASE + plan.id.value.toInt(),
            notification
        )
    }

    companion object {
        const val KEY_PLAN_ID = "plan_id"
        const val KEY_TRIGGER_REASON = "trigger_reason"
        const val KEY_TRIGGER_METADATA = "trigger_metadata"
        private const val PAGE_SIZE = 5000
        private const val EXPORT_NOTIFICATION_ID_BASE = 904_000
    }
}

private sealed interface PlanExportResult {
    data class Success(
        val fileName: String,
        val fileSizeBytes: Long,
        val recordCount: Int,
        val maxTimeMs: Long = 0L,
        val maxId: Long = 0L,
        val isDelta: Boolean = false,
        val shouldAdvanceWatermark: Boolean = false,
    ) : PlanExportResult

    data class Failed(val error: String) : PlanExportResult
}

private sealed interface ExportOutputResolution {
    data class Success(val output: ExportOutput) : ExportOutputResolution
    data class Failed(val error: String) : ExportOutputResolution
}

private interface ExportOutput {
    val fileName: String
    fun openOutputStream(): OutputStream?
    fun length(): Long
    fun deletePartial()
}

private data class FileExportOutput(private val file: File) : ExportOutput {
    override val fileName: String = file.name

    override fun openOutputStream(): OutputStream = FileOutputStream(file)

    override fun length(): Long = file.length()

    override fun deletePartial() {
        if (file.exists() && !file.delete()) {
            Reporter.w(EXPORT_LOG_SOURCE, "Failed to delete partial export $fileName")
        }
    }
}

private data class DocumentFileExportOutput(
    private val context: Context,
    private val document: DocumentFile,
    override val fileName: String,
) : ExportOutput {
    override fun openOutputStream(): OutputStream? = document.openOutputStream(context, append = false)

    override fun length(): Long = document.length()

    override fun deletePartial() {
        if (!document.delete()) {
            Reporter.w(EXPORT_LOG_SOURCE, "Failed to delete partial document export")
        }
    }
}
