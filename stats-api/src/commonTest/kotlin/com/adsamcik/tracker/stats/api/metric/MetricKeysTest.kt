package com.adsamcik.tracker.stats.api.metric

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MetricKeysTest {

	@Test
	fun `all keys are non empty`() {
		allKeys.forEach { key ->
			key.isNotBlank() shouldBe true
		}
	}

	@Test
	fun `all keys are unique`() {
		allKeys.toSet().shouldHaveSize(allKeys.size)
	}

	private companion object {
		private val allKeys = listOf(
			MetricKeys.STEPS,
			MetricKeys.DISTANCE_M,
			MetricKeys.DISTANCE_ON_FOOT_M,
			MetricKeys.ACTIVE_MINUTES,
			MetricKeys.CELLS_DISCOVERED,
			MetricKeys.UNIQUE_AREAS,
			MetricKeys.TOTAL_DISTANCE_KM,
			MetricKeys.TOTAL_STEPS,
			MetricKeys.DAILY_STREAK,
			MetricKeys.WEEKLY_STREAK,
			MetricKeys.WALKING_TRIPS,
			MetricKeys.CYCLING_TRIPS,
			MetricKeys.BEST_DAILY_STEPS,
			MetricKeys.LONGEST_TRIP_KM,
			MetricKeys.TOTAL_TRIPS,
			MetricKeys.TRANSPORT_MODE_COUNT,
			MetricKeys.SEASONS_EXPLORED,
			MetricKeys.TOTAL_EXPORTS,
		)
	}
}
