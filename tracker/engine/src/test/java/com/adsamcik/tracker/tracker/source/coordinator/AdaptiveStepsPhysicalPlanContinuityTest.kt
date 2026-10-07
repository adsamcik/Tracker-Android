package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdaptiveStepsPhysicalPlanContinuityTest {
	private val factory = SemanticAcquisitionPlanFactory()
	private val environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, emptySet())

	@Test fun `all adaptive levels and live preferences keep one direct Steps physical request`() {
		val plans = (1..5).flatMap { level ->
			listOf(emptySet(), setOf(SourceKind.STEPS), setOf(SourceKind.LOCATION)).map { live ->
				val settings = adaptiveSettings(level)
				val desired = factory.create(settings, level.toLong(), 0, environment.copy(liveSources = live))
				val resolved = SourcePlanResolver().resolve(desired, emptyList(),
					PlanResolutionContext(emptyMap(), powerSaver = true, doze = true, severeThermalPressure = true))
				resolved.applicablePlans.getValue(SourceKind.STEPS) as StepsPlan
			}
		}
		plans.forEach { plan -> assertEquals(5_000L, plan.maximumReportLatencyMs) }
		assertEquals(1, plans.map { it.physicalConfigurationFingerprint() }.distinct().size)
	}

	@Test fun `legacy custom and unselected Steps retain their original behavior`() {
		val legacyLatencies = listOf(SourceCollectionFrequency.BATTERY_SAVER,
			SourceCollectionFrequency.BALANCED, SourceCollectionFrequency.RESPONSIVE).map { frequency ->
			val settings = TrackingParamsState(sourceCollectionSettings = SourceCollectionSettings(steps = frequency))
			(factory.create(settings, 1, 0, environment).plans.getValue(SourceKind.STEPS) as StepsPlan).maximumReportLatencyMs
		}
		assertEquals(listOf(300_000L, 60_000L, 5_000L), legacyLatencies)
		val disabled = factory.create(adaptiveSettings(5).copy(stepsEnabled = false, ambientStepsEnabled = false),
			1, 0, environment.copy(liveSources = setOf(SourceKind.STEPS)))
		assertFalse(disabled.plans.getValue(SourceKind.STEPS).enabled)
		val selected = factory.create(adaptiveSettings(3), 1, 0, environment)
		val permissionLost = SourcePlanResolver().resolve(selected, emptyList(),
			PlanResolutionContext(mapOf(SourceKind.STEPS to SourceConstraint(permissionGranted = false)), false, false, false))
		assertFalse(permissionLost.applicablePlans.getValue(SourceKind.STEPS).enabled)
	}

	private fun adaptiveSettings(level: Int) = TrackingParamsState(
		balanceProfileVersion = 1, overallBalanceLevel = level,
		locationProfileVersion = 1, locationBalanceLevel = level,
		locationEnabled = false, activityEnabled = false, stepsEnabled = true, barometerEnabled = false,
	)
}
