package com.adsamcik.tracker.impexp.exporter.automation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.impexp.exporter.DatabaseExporter
import com.adsamcik.tracker.impexp.exporter.EXPORT_LOG_SOURCE
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.GpxExporter
import com.adsamcik.tracker.impexp.exporter.JsonExporter
import com.adsamcik.tracker.impexp.exporter.KmlExporter
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.time.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker responsible for executing a specific export backup plan.
 * Resolves format → exporter, scope → date range, executes, and logs to export_log.
 */
class ExportPlanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val planStore = ExportPlanStore(
        appContext,
        Dispatchers.IO,
        SystemClock
    )

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

        val startedAt = SystemClock.currentTimeMillis()
        val trigger = inputData.getString(KEY_TRIGGER_REASON) ?: "unknown"
        Reporter.i(EXPORT_LOG_SOURCE, "Executing plan '${plan.name}' (trigger=$trigger)")

        return try {
            val exportResult = executePlan(plan)
            val completedAt = SystemClock.currentTimeMillis()
            logExport(plan, exportResult, startedAt, completedAt)

            when (exportResult) {
                is PlanExportResult.Success -> {
                    Reporter.i(EXPORT_LOG_SOURCE, "Plan '${plan.name}' completed: ${exportResult.fileName} (${exportResult.recordCount} records)")
                    showExportCompletedNotification(plan, exportResult)
                    Result.success()
                }
                is PlanExportResult.Failed -> {
                    Reporter.w(EXPORT_LOG_SOURCE, "Plan '${plan.name}' failed: ${exportResult.error}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Reporter.w(EXPORT_LOG_SOURCE, "Plan '${plan.name}' threw exception: ${e.message}")
            logExport(plan, PlanExportResult.Failed(e.message ?: "Unknown error"), startedAt, SystemClock.currentTimeMillis())
            Result.retry()
        }
    }

    private suspend fun executePlan(plan: ExportBackupPlan): PlanExportResult = withContext(Dispatchers.IO) {
        val exporter = resolveExporter(plan.format)
        // WorkManager Workers can't use constructor injection without HiltWorkerFactory;
        // direct DB access is acceptable here as Workers are scoped to background execution.
        val db = AppDatabase.database(applicationContext)

        // Resolve date range from scope
        val dateRange = resolveDateRange(plan.scope, db)

        // Build output file
        val exportDir = resolveExportDirectory(plan)
        exportDir.mkdirs()
        val fileName = buildFileName(plan, exporter.extension)
        val outputFile = File(exportDir, fileName)

        // Build lazy paging sequence from DB (same pattern as ImportExportComposeActivity)
        val locationSampleDao = db.locationSampleDao()
        val fromMs = dateRange?.first ?: 0L
        val toMs = dateRange?.last ?: Long.MAX_VALUE
        var recordCount = 0
        val samples = locationSampleDao.getAllBetween(fromMs, toMs)
        val locationSequence = samples.asSequence()
            .filter { it.latE7 != null && it.lonE7 != null }
            .onEach { recordCount++ }

        val result = FileOutputStream(outputFile).use { fos ->
            exporter.export(applicationContext, locationSequence, fos, dateRange)
        }

        when (result) {
            is ExportResult.Success -> PlanExportResult.Success(
                fileName = fileName,
                fileSizeBytes = outputFile.length(),
                recordCount = recordCount,
            )
            is ExportResult.Error -> {
                Reporter.w(EXPORT_LOG_SOURCE, "Export failed for ${outputFile.name}")
                outputFile.delete()
                PlanExportResult.Failed("Export returned error for ${plan.name}")
            }
        }
    }

    private fun resolveExporter(format: ExportFormat): Exporter = when (format) {
        ExportFormat.GPX -> GpxExporter()
        ExportFormat.KML -> KmlExporter()
        ExportFormat.DATABASE -> DatabaseExporter()
        ExportFormat.JSON -> JsonExporter()
    }

    private suspend fun resolveDateRange(scope: ExportScope, db: AppDatabase): LongRange? {
        return when (scope) {
            ExportScope.LastSession -> {
                val trip = db.tripDao().getRecentTrips(1).firstOrNull()
                if (trip != null) {
                    trip.startTimeMs..trip.endTimeMs
                } else {
                    null
                }
            }
            is ExportScope.RollingWindow -> {
                val now = SystemClock.currentTimeMillis()
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

    private fun resolveExportDirectory(plan: ExportBackupPlan): File {
        return when (val dest = plan.destination) {
            is ExportDestination.PrivateStorage -> {
                File(applicationContext.filesDir, dest.relativeDirectory)
            }
            is ExportDestination.DocumentTree -> {
                // Fallback to private storage — SAF write requires Activity context
                File(applicationContext.filesDir, "exports")
            }
        }
    }

    private fun buildFileName(plan: ExportBackupPlan, extension: String): String {
        val prefix = plan.destination.fileNamePrefix?.takeIf { it.isNotBlank() } ?: "tracker-export"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${prefix}_${timestamp}.$extension"
    }

    private suspend fun logExport(
        plan: ExportBackupPlan,
        result: PlanExportResult,
        startedAt: Long,
        completedAt: Long,
    ) {
        try {
            // WorkManager Workers can't use constructor injection without HiltWorkerFactory;
        // direct DB access is acceptable here as Workers are scoped to background execution.
        val db = AppDatabase.database(applicationContext)
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
            db.exportLogDao().insert(entity)
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
        private const val PAGE_SIZE = 2000
        private const val EXPORT_NOTIFICATION_ID_BASE = 904_000
    }
}

private sealed interface PlanExportResult {
    data class Success(
        val fileName: String,
        val fileSizeBytes: Long,
        val recordCount: Int,
    ) : PlanExportResult

    data class Failed(val error: String) : PlanExportResult
}
