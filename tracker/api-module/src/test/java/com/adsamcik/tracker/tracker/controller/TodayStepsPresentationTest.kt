package com.adsamcik.tracker.tracker.controller

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayStepsPresentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `complete zero is numeric and a subtotal remains explicitly recorded`() {
        assertEquals("0", display(StepsDailyDisplayValue.Complete(0L)).formatTodaySteps(context) { "$it" })
        val recorded = display(StepsDailyDisplayValue.Recorded(12L, setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE)))
        assertEquals(context.getString(R.string.tracker_steps_today_recorded, "12"),
            recorded.formatTodaySteps(context) { "$it" })
        assertTrue(recorded.showTodaySteps(false))
    }

    @Test fun `waiting missing and storage failures never invent a number`() {
        assertEquals(context.getString(R.string.tracker_steps_today_waiting),
            StepsDailyDisplay.Materializing.formatTodaySteps(context) { error("No count") })
        val missing = display(StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.NOT_CAPTURED))
        assertEquals(context.getString(R.string.tracker_steps_today_missing),
            missing.formatTodaySteps(context) { error("No count") })
        assertEquals(context.getString(R.string.tracker_steps_value_unavailable),
            StepsDailyDisplay.StorageUnavailable.formatTodaySteps(context) { error("No count") })
        assertFalse(missing.showTodaySteps(false))
        assertTrue(missing.showTodaySteps(true))
    }

    private fun display(value: StepsDailyDisplayValue) = StepsDailyDisplay.Snapshot(1L,
        listOf(StepsDailyDisplayDay(1L, value)))
}
