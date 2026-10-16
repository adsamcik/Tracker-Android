package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsamcik.tracker.statistics.ui.measuredDate
import com.adsamcik.tracker.statistics.ui.rememberMeasuredHistoryViewZone
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/** Actual route-used lifecycle helper and measured-date calculation; system zone changes are fixtures. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredHistoryViewZoneComposeTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalZone: TimeZone

    @Before fun saveZone() { originalZone = TimeZone.getDefault() }
    @After fun restoreZone() { TimeZone.setDefault(originalZone) }

    @Test fun `resuming History uses the phone new calendar day without changing the saved timestamp`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
        val owner = TestOwner()
        val row = locationRow()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(measuredDate(row, rememberMeasuredHistoryViewZone()).toString())
            }
        }
        rule.runOnIdle {
            owner.move(Lifecycle.Event.ON_CREATE)
            owner.move(Lifecycle.Event.ON_START)
            owner.move(Lifecycle.Event.ON_RESUME)
        }
        rule.onNodeWithText("2026-01-01").assertIsDisplayed()
        rule.runOnIdle {
            owner.move(Lifecycle.Event.ON_PAUSE)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        }
        rule.onNodeWithText("2026-01-01").assertIsDisplayed()
        rule.runOnIdle { owner.move(Lifecycle.Event.ON_RESUME) }
        rule.onNodeWithText("2025-12-31").assertIsDisplayed()
        row.position.startTimeMs shouldBe Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
    }

    @Test fun `changing or disposing the History owner removes the old resume observer`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
        val first = TestOwner()
        val second = TestOwner()
        val owner = mutableStateOf(first)
        val shown = mutableStateOf(true)
        rule.setContent {
            if (shown.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                Text(rememberMeasuredHistoryViewZone().id)
            }
            }
        }
        rule.runOnIdle {
            first.move(Lifecycle.Event.ON_CREATE)
            first.move(Lifecycle.Event.ON_START)
            first.move(Lifecycle.Event.ON_RESUME)
            first.move(Lifecycle.Event.ON_PAUSE)
            second.move(Lifecycle.Event.ON_CREATE)
            second.move(Lifecycle.Event.ON_START)
            owner.value = second
        }
        rule.runOnIdle {
            first.registry.observerCount shouldBe 0
            second.registry.observerCount shouldBe 1
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            first.move(Lifecycle.Event.ON_RESUME)
        }
        rule.onNodeWithText("Europe/Prague").assertIsDisplayed()
        rule.runOnIdle { second.move(Lifecycle.Event.ON_RESUME) }
        rule.onNodeWithText("Asia/Tokyo").assertIsDisplayed()
        rule.runOnIdle { shown.value = false }
        rule.runOnIdle { second.registry.observerCount shouldBe 0 }
    }

    private fun locationRow(): UnifiedHistoryRow {
        val time = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        return UnifiedHistoryRow(HistoryPosition(time, "legacy:location", "saved-point"), null,
            setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(), measurement =
                HistoryMeasurement.Location(HistoryLocationPoint(time, 500_000_000, 140_000_000, null, null, 1L),
                    HistoryLocationCoverage.LEGACY_UNVERIFIABLE))
    }
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun move(event: Lifecycle.Event) { registry.handleLifecycleEvent(event) }
    }
}
