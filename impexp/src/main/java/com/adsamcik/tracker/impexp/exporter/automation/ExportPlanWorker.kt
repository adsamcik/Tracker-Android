package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.impexp.exporter.EXPORT_LOG_SOURCE
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.time.SystemClock
import kotlinx.coroutines.Dispatchers

/**
 * Worker responsible for executing a specific export backup plan. Actual export execution will be
 * wired in a follow-up change; for now the worker simply resolves the plan snapshot so scheduling
 * can be verified end-to-end.
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
        val trigger = inputData.getString(KEY_TRIGGER_REASON)
        val metadata = inputData.getString(KEY_TRIGGER_METADATA)
        val plan = planStore.getPlan(ExportPlanId(planId))
        if (plan == null) {
            Reporter.i(EXPORT_LOG_SOURCE, "Plan $planId no longer exists; nothing to export")
            return Result.success()
        }
        Reporter.i(
            EXPORT_LOG_SOURCE,
            "Scheduled export plan '${plan.name}' (trigger=$trigger, meta=$metadata) queued; execution implementation pending"
        )
        // Actual export execution will be added after ExportManager wiring supports automation.
        return Result.success()
    }

    companion object {
        const val KEY_PLAN_ID = "plan_id"
        const val KEY_TRIGGER_REASON = "trigger_reason"
        const val KEY_TRIGGER_METADATA = "trigger_metadata"
    }
}
