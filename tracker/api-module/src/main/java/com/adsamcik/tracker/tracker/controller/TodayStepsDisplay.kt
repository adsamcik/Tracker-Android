package com.adsamcik.tracker.tracker.controller

import android.content.Context
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.R
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** UI-only daily read: no session requirement, provider request, stored total or idle timer. */
class TodayStepsDisplay(
    private val repository: StepsNumericSummaryRepository,
    private val clock: Clock,
) {
    private val refreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Rebind the displayed date and reschedule midnight after a clock or time-zone change. */
    fun refresh() { refreshes.tryEmit(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<StepsDailyDisplay> = refreshes.onStart { emit(Unit) }
        .flatMapLatest {
            flow {
                while (true) {
                    val now = clock.currentTimeMillis()
                    val zone = ZoneId.systemDefault()
                    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                    emit(StepsNumericSummaryRequest(date.toEpochDay(), date.toEpochDay(), zone.id))
                    val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    delay((nextMidnight - now).coerceAtLeast(1L))
                }
            }.distinctUntilChanged().flatMapLatest { request ->
                repository.observeDisplay(request)
                    .map { display ->
                        if (display is StepsDailyDisplay.Snapshot &&
                            display.days.singleOrNull()?.epochDay != request.firstEpochDay
                        ) StepsDailyDisplay.StorageUnavailable else display
                    }
                    .onStart { emit(StepsDailyDisplay.Materializing) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        emit(StepsDailyDisplay.StorageUnavailable)
                    }
            }
        }
}

/** Daily presentation is independent of strict goals and of the active session's own count. */
fun StepsDailyDisplay.showTodaySteps(participating: Boolean): Boolean = participating ||
    (this as? StepsDailyDisplay.Snapshot)?.days?.singleOrNull()?.value.let { value ->
        value is StepsDailyDisplayValue.Complete || value is StepsDailyDisplayValue.Recorded ||
            (value as? StepsDailyDisplayValue.Unavailable)?.reason ==
                StepsNumericUnverifiableReason.PARTIAL_CAPTURE
    }

fun StepsDailyDisplay.formatTodaySteps(context: Context, format: (Long) -> String): String = when (this) {
    is StepsDailyDisplay.Snapshot -> when (val value = days.singleOrNull()?.value) {
        is StepsDailyDisplayValue.Complete -> format(value.count)
        is StepsDailyDisplayValue.Recorded -> context.getString(R.string.tracker_steps_today_recorded, format(value.count))
        StepsDailyDisplayValue.Materializing -> context.getString(R.string.tracker_steps_today_waiting)
        is StepsDailyDisplayValue.Unavailable -> context.getString(when (value.reason) {
            StepsNumericUnverifiableReason.NOT_CAPTURED -> R.string.tracker_steps_today_missing
            StepsNumericUnverifiableReason.PARTIAL_CAPTURE -> R.string.tracker_measurement_partial
            else -> R.string.tracker_steps_value_unavailable
        })
        null -> context.getString(R.string.tracker_steps_value_unavailable)
    }
    StepsDailyDisplay.Materializing -> context.getString(R.string.tracker_steps_today_waiting)
    StepsDailyDisplay.StorageUnavailable -> context.getString(R.string.tracker_steps_value_unavailable)
}
