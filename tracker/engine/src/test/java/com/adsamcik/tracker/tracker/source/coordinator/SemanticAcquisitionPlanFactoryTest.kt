package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.ImpactDriver
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.kotest.matchers.shouldBe
import org.junit.Test

class SemanticAcquisitionPlanFactoryTest {
	private val subject = SemanticAcquisitionPlanFactory()
	private val environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, setOf(1))

	@Test
	fun `each component frequency controls only its own source plan`() {
		val settings = TrackingParamsState(
			sourcePolicyRevision = 42,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.RESPONSIVE,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.OFF,
				pressure = SourceCollectionFrequency.OFF,
				wifi = SourceCollectionFrequency.BATTERY_SAVER,
				cell = SourceCollectionFrequency.OFF,
			),
		)

		val plan = subject.create(settings, 3, 100, environment)

		plan.sourcePolicyRevision shouldBe 42
		(plan.plans[SourceKind.LOCATION] as LocationPlan).mode shouldBe LocationMode.HIGH_ACCURACY
		(plan.plans[SourceKind.WIFI] as WifiPlan).mode shouldBe WifiMode.CACHED_ONLY
		plan.plans.filterKeys { it !in setOf(SourceKind.LOCATION, SourceKind.WIFI) }
			.values.all { !it.enabled } shouldBe true
	}

	@Test
	fun `cost explanation exposes dominant expensive component without fake percent per hour`() {
		val settings = TrackingParamsState(
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.RESPONSIVE,
				wifi = SourceCollectionFrequency.RESPONSIVE,
			),
		)
		val estimate = QualitativeBatteryImpactEstimator().estimate(
			subject.create(settings, 3, 100, environment),
			comparisonBaselineId = "balanced",
		)

		estimate.level shouldBe ImpactLevel.HIGH
		estimate.dominantDrivers.contains(ImpactDriver.LOCATION) shouldBe true
		estimate.estimatedPercentPerHour shouldBe null
	}
}
