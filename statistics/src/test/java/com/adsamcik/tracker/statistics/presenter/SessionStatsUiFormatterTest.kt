package com.adsamcik.tracker.statistics.presenter

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SessionStatsUiFormatterTest {

	private val context: Context = mockk()
	private val resources: Resources = mockk()
	private val formatter = SessionStatsUiFormatter(context)
	private val snapshot = SessionStatsSnapshot(
		duration = DurationMs(12_000L),
		collections = 42L,
		totalDistance = DistanceM(1234.5f),
		steps = StepCount(678),
		tripCount = 9L,
		locationCount = 77L,
		wifiCount = 4L,
		cellCount = 5L,
	)

	init {
		every { context.resources } returns resources
		mockkObject(TrackerSettingsQuick)
		every { TrackerSettingsQuick.lengthSystem(context) } returns LengthSystem.Metric
		mockkStatic("com.adsamcik.tracker.shared.base.extension.StringExtensionsKt")
		every { 12_000L.formatAsDuration(context) } returns "12s"
		every { 42L.formatReadable() } returns "42"
		every { 678.formatReadable() } returns "678"
		every { 9L.formatReadable() } returns "9"
		every { 77L.formatReadable() } returns "77"
		every { 4L.formatReadable() } returns "4"
		every { 5L.formatReadable() } returns "5"
		mockkStatic("com.adsamcik.tracker.shared.utils.extension.StringExtensionsKt")
		every { resources.formatDistance(1234.5f, 1, LengthSystem.Metric) } returns "1.2 km"
		every { resources.formatDistance(0f, 1, LengthSystem.Metric) } returns "0 m"
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `formatSummary keeps legacy row order`() {
		val stats = formatter.formatSummary(snapshot)

		stats.map { it.nameRes } shouldContainExactly listOf(
			R.string.stats_time,
			R.string.stats_distance_total,
			R.string.stats_distance_on_foot,
			R.string.stats_distance_in_vehicle,
			R.string.stats_collections,
			R.string.stats_steps,
			R.string.stats_location_count,
			R.string.stats_wifi_count,
			R.string.stats_cell_count,
			R.string.stats_session_count,
		)
		stats[0].data shouldBe "12s"
		stats[1].data shouldBe "1.2 km"
		stats[4].data shouldBe "42"
		stats[9].data shouldBe "9"
	}

	@Test
	fun `formatWeekly keeps session count before radio counts`() {
		val stats = formatter.formatWeekly(snapshot)

		stats.map { it.nameRes } shouldContainExactly listOf(
			R.string.stats_time,
			R.string.stats_distance_total,
			R.string.stats_distance_on_foot,
			R.string.stats_distance_in_vehicle,
			R.string.stats_collections,
			R.string.stats_steps,
			R.string.stats_session_count,
			R.string.stats_location_count,
			R.string.stats_wifi_count,
			R.string.stats_cell_count,
		)
		stats[6].data shouldBe "9"
		stats[7].data shouldBe "77"
	}
}
