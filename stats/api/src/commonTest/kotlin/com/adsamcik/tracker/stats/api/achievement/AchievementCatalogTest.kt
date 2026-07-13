package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.metric.MetricKey
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

	@Test fun pacingDaysAreNonDecreasingAndDependencyAware() {
		AchievementCatalog.byMetric.forEach { (metric, definitions) ->
			definitions.zipWithNext().forEach { (a, b) ->
				assertTrue(a.minimumActiveDays <= b.minimumActiveDays, metric.name)
			}
			definitions.filter { it.minimumActiveDays > 1 }.forEach { definition ->
				assertTrue(definition.pacingMetric in definition.dependsOn, definition.id)
			}
		}
	}

	@Test fun transportationFamiliesUseTheirOwnDayCounters() {
		val expectations = mapOf(
			MetricKey.DISTANCE_ON_FOOT_M to MetricKey.ON_FOOT_ACTIVE_DAYS,
			MetricKey.CYCLING_DISTANCE_M to MetricKey.CYCLING_ACTIVE_DAYS,
			MetricKey.MAX_CYCLE_SESSION_M to MetricKey.CYCLING_ACTIVE_DAYS,
			MetricKey.VEHICLE_DISTANCE_M to MetricKey.VEHICLE_ACTIVE_DAYS,
		)
		expectations.forEach { (metric, pacingMetric) ->
			assertTrue(AchievementCatalog.byMetric(metric).all { it.pacingMetric == pacingMetric }, metric.name)
		}
	}

	@Test fun oneMotorizedDayCannotSweepDistanceTiers() {
		val unlockedVehicleTiers = AchievementCatalog.byMetric(MetricKey.VEHICLE_DISTANCE_M)
			.count { it.threshold <= 500_000.0 && it.isEligible(activeDays = 1) }
		val unlockedTravelTiers = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M)
			.count { it.threshold <= 500_000.0 && it.isEligible(activeDays = 1) }
		assertEquals(1, unlockedVehicleTiers)
		assertEquals(1, unlockedTravelTiers)
	}
}
