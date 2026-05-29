package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementCatalogTest {
	@Test fun catalogHasLongTailAchievements() { assertTrue(AchievementCatalog.definitions.size in 150..200) }

	@Test fun thresholdsAreStrictlyAscendingPerMetric() {
		AchievementCatalog.byMetric.forEach { (metric, definitions) ->
			val thresholds = definitions.map { it.threshold }
			assertEquals(thresholds.distinct().size, thresholds.size, metric.name)
			thresholds.zipWithNext().forEach { (a, b) -> assertTrue(a < b, metric.name) }
		}
	}

	@Test fun resourceNamesAreStable() {
		AchievementCatalog.definitions.forEach { definition ->
			assertTrue(definition.nameRes.startsWith("achievement_"))
			assertTrue(definition.descriptionRes.endsWith("_desc"))
		}
	}
}

class AchievementEvaluatorTest {
	@Test fun evaluatesChangedMetricAndStopsAtFirstUnmet() {
		val events = AchievementEvaluator().evaluate(
			changedMetrics = setOf(MetricKey.DISTANCE_TOTAL_M),
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 10_000),
			lastUnlockedTierByMetric = emptyMap(),
		)
		assertEquals(3, events.size)
		assertEquals(MetricKey.DISTANCE_TOTAL_M, events.last().definition.metric)
	}

	@Test fun returnsEmptyListForNoChanges() {
		assertTrue(AchievementEvaluator().evaluate(emptySet(), MetricSnapshot.Empty, emptyMap()).isEmpty())
	}
}
