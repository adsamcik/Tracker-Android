package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observes [ExportPlanStore] and keeps WorkManager in sync so that each enabled plan runs on
 * schedule. After-session plans are triggered manually via [triggerAfterSessionPlans].
 */
class ExportAutomationController(
    private val context: Context,
    private val planStore: ExportPlanStore,
    private val dispatchers: DispatchersProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context)
    private val schedulingConstraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    private var latestPlans: List<ExportBackupPlan> = emptyList()

    init {
        observePlans()
    }

    private fun observePlans() {
        planStore.plans
            .onEach { plans ->
                latestPlans = plans
                synchronizeIntervalPlans(plans)
            }
            .launchIn(scope)
    }

    private suspend fun cancelWork(workName: String) = withContext(dispatchers.io) {
        workManager.cancelUniqueWork(workName)
    }

    private fun synchronizeIntervalPlans(plans: List<ExportBackupPlan>) {
        scope.launch(dispatchers.io) {
            plans.forEach { plan ->
                val workName = plan.workName()
                if (!plan.enabled) {
                    cancelWork(workName)
                    return@forEach
                }
                when (val cadence = plan.cadence) {
                    ExportCadence.AfterSession -> cancelWork(workName)
                    is ExportCadence.Interval -> scheduleIntervalPlan(plan, cadence)
                }
            }
        }
    }

    private fun scheduleIntervalPlan(plan: ExportBackupPlan, cadence: ExportCadence.Interval) {
        val repeatDuration = ExportPlanScheduling.repeatDuration(cadence)
        val initialDelay = ExportPlanScheduling.initialDelay(cadence, clock)
            .coerceAtLeast(Duration.ZERO)
            .coerceAtMost(repeatDuration)

        val data = workDataOf(
            ExportPlanWorker.KEY_PLAN_ID to plan.id.value,
            ExportPlanWorker.KEY_TRIGGER_REASON to TriggerReason.SCHEDULED.name
        )

        val request = PeriodicWorkRequestBuilder<ExportPlanWorker>(repeatDuration)
            .setConstraints(schedulingConstraints)
            .setInitialDelay(initialDelay)
            .setInputData(data)
            .addTag(WORK_TAG_PLAN)
            .build()

        workManager.enqueueUniquePeriodicWork(
            plan.workName(),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Trigger all after-session plans immediately. */
    fun triggerAfterSessionPlans(trigger: AfterSessionTrigger = AfterSessionTrigger.Generic) {
        val plans = latestPlans
        if (plans.isEmpty()) return
        scope.launch(dispatchers.io) {
            plans.filter { it.enabled && it.cadence is ExportCadence.AfterSession }
                .forEach { plan -> enqueueOneTime(plan, TriggerReason.AFTER_SESSION, trigger.tag()) }
        }
    }

    private fun enqueueOneTime(
        plan: ExportBackupPlan,
        reason: TriggerReason,
        metadata: String?
    ) {
        val inputData = Data.Builder()
            .putLong(ExportPlanWorker.KEY_PLAN_ID, plan.id.value)
            .putString(ExportPlanWorker.KEY_TRIGGER_REASON, reason.name)
            .apply { metadata?.let { putString(ExportPlanWorker.KEY_TRIGGER_METADATA, it) } }
            .build()
        val request = OneTimeWorkRequestBuilder<ExportPlanWorker>()
            .setConstraints(schedulingConstraints)
            .setInputData(inputData)
            .addTag(WORK_TAG_PLAN)
            .build()
        workManager.enqueue(request)
    }

    private fun AfterSessionTrigger.tag(): String? = when (this) {
        AfterSessionTrigger.Generic -> null
        is AfterSessionTrigger.Session -> "session:${sessionId ?: -1}"
    }

    enum class TriggerReason { SCHEDULED, AFTER_SESSION }

    sealed interface AfterSessionTrigger {
        data object Generic : AfterSessionTrigger
        data class Session(val sessionId: Long?, val startedAtMillis: Long, val endedAtMillis: Long) : AfterSessionTrigger
    }

    companion object {
        private const val WORK_TAG_PLAN = "export.plan"
    }
}

private fun ExportBackupPlan.workName(): String = "export-plan-${id.value}"

internal object ExportPlanScheduling {
    private val zone: ZoneId
        get() = ZoneId.systemDefault()

    fun repeatDuration(cadence: ExportCadence.Interval): Duration {
        val days: Long = when (cadence.unit) {
            ExportCadence.IntervalUnit.DAY -> cadence.every.toLong().coerceAtLeast(1L)
            ExportCadence.IntervalUnit.WEEK -> (7L * cadence.every).coerceAtLeast(7L)
            ExportCadence.IntervalUnit.MONTH -> (30L * cadence.every).coerceAtLeast(30L)
            ExportCadence.IntervalUnit.YEAR -> (365L * cadence.every).coerceAtLeast(365L)
            ExportCadence.IntervalUnit.CUSTOM_DAYS -> cadence.every.toLong().coerceAtLeast(1L)
        }
        return Duration.of(days, ChronoUnit.DAYS)
    }

    fun initialDelay(
        cadence: ExportCadence.Interval,
        clock: Clock,
        nowMillis: Long = clock.currentTimeMillis(),
        zoneId: ZoneId = zone
    ): Duration {
        val atTime = cadence.atTime ?: return Duration.ZERO
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val todayTarget = now.withHour(atTime.hour).withMinute(atTime.minute).withSecond(0).withNano(0)
        val firstRun = if (todayTarget.isAfter(now)) {
            todayTarget
        } else {
            todayTarget.plusSeconds(repeatDuration(cadence).seconds)
        }
        return Duration.between(now, firstRun)
    }
}
