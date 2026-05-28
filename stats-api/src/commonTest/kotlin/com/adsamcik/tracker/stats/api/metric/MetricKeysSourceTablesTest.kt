package com.adsamcik.tracker.stats.api.metric

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MetricKeysSourceTablesTest {

	@Test
	fun `every metric maps to at least one table or returns empty for unknown`() {
		// All known metrics map to non-empty tables
		val mapped = MetricKeys.all()
		mapped.shouldNotBeEmpty()
		for (metric in mapped) {
			val tables = MetricKeys.sourceTables(metric)
			tables.shouldNotBeEmpty()
		}
	}

	@Test
	fun `unknown metric returns empty set (caller treats as always-dirty)`() {
		MetricKeys.sourceTables("unknown_metric_xyz").shouldBeEmpty()
	}

	@Test
	fun `isKnown discriminates known from unknown metrics`() {
		MetricKeys.isKnown(MetricKeys.STEPS) shouldBe true
		MetricKeys.isKnown(MetricKeys.CELLS_DISCOVERED) shouldBe true
		MetricKeys.isKnown("unknown_metric_xyz") shouldBe false
		MetricKeys.isKnown("") shouldBe false
	}

	@Test
	fun `every metric maps to at least one of the canonical TABLE constants`() {
		val canonical = setOf(
			MetricKeys.TABLE_DAILY_SUMMARY,
			MetricKeys.TABLE_SESSION_SEGMENT,
			MetricKeys.TABLE_EXPLORATION_CELL,
			MetricKeys.TABLE_EXPLORATION_STREAK,
			MetricKeys.TABLE_EXPORT_LOG,
			MetricKeys.TABLE_AGGREGATOR_STATE,
		)
		MetricKeys.all().forEach { metric ->
			val tables = MetricKeys.sourceTables(metric)
			tables.forEach { t ->
				(t in canonical) shouldBe true
			}
		}
	}

	@Test
	fun `daily_summary metrics include STEPS and ACTIVE_DAYS`() {
		// STEPS is dual-sourced after p6-7-fix: daily_summary (for catch-up evaluation
		// after a session ends) AND aggregator_state (for live in-session evaluation).
		// ACTIVE_DAYS is daily_summary-only because the aggregator doesn't track distinct
		// active days — that concept only exists in the daily_summary row.
		MetricKeys.sourceTables(MetricKeys.STEPS) shouldBe setOf(
			MetricKeys.TABLE_DAILY_SUMMARY, MetricKeys.TABLE_AGGREGATOR_STATE,
		)
		MetricKeys.sourceTables(MetricKeys.ACTIVE_DAYS) shouldBe setOf(MetricKeys.TABLE_DAILY_SUMMARY)
	}

	@Test
	fun `session_segment metrics include WALKING_TRIPS and DISTANCE_ON_FOOT_M`() {
		MetricKeys.sourceTables(MetricKeys.WALKING_TRIPS) shouldContain MetricKeys.TABLE_SESSION_SEGMENT
		MetricKeys.sourceTables(MetricKeys.DISTANCE_ON_FOOT_M) shouldContain MetricKeys.TABLE_SESSION_SEGMENT
	}

	@Test
	fun `exploration_cell metrics include CELLS_DISCOVERED`() {
		MetricKeys.sourceTables(MetricKeys.CELLS_DISCOVERED) shouldContain MetricKeys.TABLE_EXPLORATION_CELL
	}

	@Test
	fun `exploration_streak metrics include DAILY_STREAK and WEEKLY_STREAK`() {
		MetricKeys.sourceTables(MetricKeys.DAILY_STREAK) shouldContain MetricKeys.TABLE_EXPLORATION_STREAK
		MetricKeys.sourceTables(MetricKeys.WEEKLY_STREAK) shouldContain MetricKeys.TABLE_EXPLORATION_STREAK
	}
}
