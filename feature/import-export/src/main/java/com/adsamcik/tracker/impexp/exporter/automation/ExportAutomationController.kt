package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.adsamcik.tracker.impexp.importer.ImportAdmissionFence
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
    private val workManager: WorkManager = WorkManager.getInstance(context),
) {
    // Imports and schedules share the same short enqueue fence; transfer jobs belong to startup.
    private val admissionFence = ImportAdmissionFence()
    private val schedulingConstraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    @Volatile
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

    private fun synchronizeIntervalPlans(plans: List<ExportBackupPlan>) {
        val generation = admissionFence.capture() ?: return
        scope.launch(dispatchers.io) {
            plans.forEach { plan ->
                val workName = plan.workName()
                if (!plan.enabled) {
                    admissionFence.enqueueIfCurrent(generation) { workManager.cancelUniqueWork(workName) }
                    return@forEach
                }
                when (val cadence = plan.cadence) {
                    ExportCadence.AfterSession -> admissionFence.enqueueIfCurrent(generation) {
                        workManager.cancelUniqueWork(workName)
                    }
                    is ExportCadence.Interval -> scheduleIntervalPlan(plan, cadence, generation)
                }
            }
        }
    }

    private fun scheduleIntervalPlan(plan: ExportBackupPlan, cadence: ExportCadence.Interval, generation: Long) {
        val repeatDuration = ExportPlanScheduling.repeatDuration(cadence)
        val initialDelay = ExportPlanScheduling.initialDelay(cadence, clock)
            .coerceAtLeast(Duration.ZERO)
            .coerceAtMost(repeatDuration)

        val data = workDataOf(
            ExportPlanWorker.KEY_PLAN_ID to plan.id.value
        )

        val request = PeriodicWorkRequestBuilder<ExportPlanWorker>(repeatDuration)
            .setConstraints(schedulingConstraints)
            .setInitialDelay(initialDelay)
            .setInputData(data)
            .addTag(WORK_TAG_PLAN)
            .build()

        admissionFence.enqueueIfCurrent(generation) {
            workManager.enqueueUniquePeriodicWork(
                plan.workName(), ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }
    }

    /** Trigger all after-session plans immediately. */
    fun triggerAfterSessionPlans() {
        val generation = admissionFence.capture() ?: return
        val plans = latestPlans
        if (plans.isEmpty()) return
        scope.launch(dispatchers.io) {
            plans.filter { it.enabled && it.cadence is ExportCadence.AfterSession }
                .forEach { enqueueOneTime(it, generation) }
        }
    }

    /** Fences new scheduled enqueues and waits for WorkManager cancellation before collected-data cleanup proceeds. */
    suspend fun pauseForDataDeletion() {
        admissionFence.pause { Unit }
        withContext(dispatchers.io) { workManager.cancelAllWorkByTag(WORK_TAG_PLAN).await() }
    }

    fun resumeAfterDataDeletion() {
        admissionFence.resume()
        synchronizeIntervalPlans(latestPlans)
    }

    private fun enqueueOneTime(plan: ExportBackupPlan, generation: Long) {
        val inputData = workDataOf(ExportPlanWorker.KEY_PLAN_ID to plan.id.value)
        val request = OneTimeWorkRequestBuilder<ExportPlanWorker>()
            .setConstraints(schedulingConstraints)
            .setInputData(inputData)
            .addTag(WORK_TAG_PLAN)
            .build()
        // Queue a follow-up trigger without cancelling an in-flight export; replace only
        // a failed/cancelled prerequisite chain so future sessions are not suppressed.
        admissionFence.enqueueIfCurrent(generation) {
            workManager.enqueueUniqueWork(plan.workName(), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
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
