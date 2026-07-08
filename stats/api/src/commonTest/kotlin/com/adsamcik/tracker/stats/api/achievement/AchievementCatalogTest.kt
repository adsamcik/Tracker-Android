package com.adsamcik.tracker.stats.api.achievement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementCatalogTest {
	@Test fun catalogHasLongTailAchievements() { assertTrue(AchievementCatalog.definitions.size in 150..400) }

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
