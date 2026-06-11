package com.adsamcik.tracker.statistics.fragment

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Trip row accessibility labels")
class TripRowAccessibilityLabelTest {

    private val mockResources: Resources = mockk {
        every {
            getQuantityString(
                com.adsamcik.tracker.statistics.R.plurals.stats_trip_row_steps_content_description,
                120,
                "120"
            )
        } returns "120 steps"
    }

    private val context: Context = mockk {
        every { resources } returns mockResources
        every { getString(com.adsamcik.tracker.shared.base.R.string.activity_running) } returns "Running"
    }

    @Test
    fun `content description includes all rendered trip details`() {
        val result = buildTripRowContentDescription(
            timeText = "08:22",
            activityTypeText = "Walking",
            durationText = "2 m 1 s",
            distanceText = "74.28 m",
            stepsText = context.resources.getQuantityString(
                com.adsamcik.tracker.statistics.R.plurals.stats_trip_row_steps_content_description,
                120,
                "120"
            ),
        )

        result shouldBe "08:22, Walking, 2 m 1 s, 74.28 m, 120 steps"
    }

    @Test
    fun `content description skips missing optional values`() {
        val result = buildTripRowContentDescription(
            timeText = "08:22",
            activityTypeText = "session",
            durationText = null,
            distanceText = null,
            stepsText = null,
        )

        result shouldBe "08:22, session"
    }

    @Test
    fun `activity label matches inferred presentation when activity type missing`() {
        val trip = Trip(
            id = 1L,
            startTimeMs = 0L,
            endTimeMs = 0L,
            distanceM = 1500f,
            steps = 400,
            primaryActivity = null,
            activityConfidence = null,
            sampleCount = 0,
            source = SegmentSource.USER_CREATED,
            createdAt = 0L,
        )

        getTripActivityLabel(context, trip) shouldBe "Running"
    }
}
